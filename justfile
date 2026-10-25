set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

engine := "analysis-engine"

default: list

# List available recipes.
list:
    @just --list

# Build engine modules without running tests.
build:
    cd {{engine}} && ./gradlew --no-daemon --max-workers=4 assemble

# Run the deterministic engine tests and graph guard.
test-engine:
    cd {{engine}} && ./gradlew --no-daemon --max-workers=4 check

# Run the shared-LSP oracle test. Set CINT_LSP_ROOT to a project with a running kotlin-lsp.
test-lsp:
    cd {{engine}} && ./gradlew --no-daemon :lsp:test

# Run engine tests.
test: test-engine

# Check Kotlin formatting with ktlint.
lint:
    ktlint --relative '{{engine}}/**/src/**/*.kt'

# Auto-format Kotlin sources with ktlint.
lint-fix:
    ktlint --format --relative '{{engine}}/**/src/**/*.kt'

# Regenerate the published renderer protocol JSON Schema.
protocol-schema:
    cd {{engine}} && ./gradlew --no-daemon :ipc:generateProtocolSchema

# Apply the first Kotlin LSP code action for each diagnostic across Kotlin files.
lsp-fix root=engine max="25":
    LSP_FIX_ROOT={{quote(root)}} LSP_FIX_MAX={{quote(max)}} nvim --headless -u NONE -c 'luafile {{quote(justfile_directory() + "/scripts/kotlin-lsp-fixall.lua")}}'

# Classify Kotlin files as SUSPICIOUS or EXTREME by line count.
size root=engine:
    ./scripts/classify-kotlin-size.nu {{quote(root)}}

# Report directories with one file or more than five files.
dir-size root=engine:
    ./scripts/classify-directory-size.nu {{quote(root)}}

# Build the CLI, live server, and MCP distributions.
install:
    cd {{engine}} && ./gradlew --no-daemon :cli:installDist :server:installDist :mcp:installDist

# Package installed distributions plus the protocol schema as a versioned release tarball.
release version="0.1.0": install
    CINT_ENGINE_VERSION={{quote(version)}} ./scripts/package-release.sh

# Run the batch CLI. Extra arguments are forwarded.
cli *args:
    cd {{engine}} && ./gradlew :cli:run {{ if args == "" { "" } else { "--args=" + quote(args) } }}

# Run the HTTP server. Extra arguments are forwarded.
server *args:
    cd {{engine}} && ./gradlew :server:run {{ if args == "" { "" } else { "--args=" + quote(args) } }}

# Run the live Unix-socket server. The nvim client normally spawns this.
live socket root *args: install
    ./{{engine}}/apps/server/build/install/cint-server/bin/cint-server --socket {{quote(socket)}} --root {{quote(root)}} {{args}}

# Generate a tags.json artifact for a project.
tags root *args: install
    ./{{engine}}/apps/cli/build/install/cint/bin/cint {{quote(root)}} --out {{quote(root + "/tags.json")}} {{args}}

# Remove Gradle build outputs.
clean:
    cd {{engine}} && ./gradlew --no-daemon clean

# Run the declared module graph guard and its self-test.
graph-guard:
    cd {{engine}} && ./gradlew --no-daemon --max-workers=4 graphGuard graphGuardSelfTest

# Run the frozen CLI and IPC parity suites and verify the committed report.
parity:
    cd {{engine}} && ./gradlew --no-daemon --max-workers=4 :integration-tests:test :server:test
    python3 {{engine}}/scripts/merge-parity-report.py {{engine}}/parity/v2-parity-report.json
    git diff --exit-code -- {{engine}}/parity/v2-parity-report.json {{engine}}/parity/defect-baseline.json {{engine}}/parity/ipc-defect-baseline.json

# Smoke-test the built CLI, live server, and MCP distributions.
smoke: install
    ./{{engine}}/scripts/smoke.sh

# Verify the checked-in V1 Kotlin syntax snapshot against the pinned V1 commit.
syntax-provenance:
    ./{{engine}}/scripts/verify-v1-syntax-snapshot.sh
