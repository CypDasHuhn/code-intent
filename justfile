set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

engine := "analysis-engine"
renderer := "nvim-renderer"

default: list

# List available recipes.
list:
    @just --list

# Build engine modules without running tests.
build:
    cd {{engine}} && ./gradlew assemble

# Run the deterministic engine tests (no live LSP dependency).
test-engine:
    cd {{engine}} && ./gradlew :api:test :rulepacks:test :core:test :suggest:test :host:test :treesitter:test :frontend:test :cli:test :server:test :rulepacks-tests:test :test-fixtures:test :integration-tests:test

# Run the shared-LSP oracle test. Set CINT_LSP_ROOT to a project with a running kotlin-lsp.
test-lsp:
    cd {{engine}} && ./gradlew :lsp:test

# Run the Neovim renderer tests headlessly.
test-nvim:
    ./{{renderer}}/tests/run.sh

# Run the cross-writer test on its own; test-engine and CI already run it.
test-cross-writer:
    cd {{engine}} && ./gradlew :core:test --tests 'dev.cint.core.user.CrossWriterAnnotationLockTest'

# Run engine and renderer tests.
test: test-engine test-nvim

# Check Kotlin formatting with ktlint. Lua is not format-linted in this repo.
lint:
    ktlint --relative '{{engine}}/**/src/**/*.kt'

# Auto-format Kotlin sources with ktlint.
lint-fix:
    ktlint --format --relative '{{engine}}/**/src/**/*.kt'

# Apply the first Kotlin LSP code action for each diagnostic across Kotlin files.
lsp-fix root=engine max="25":
    LSP_FIX_ROOT={{quote(root)}} LSP_FIX_MAX={{quote(max)}} nvim --headless -u NONE -c 'luafile {{quote(justfile_directory() + "/scripts/kotlin-lsp-fixall.lua")}}'

# Classify Kotlin files as SUSPICIOUS or EXTREME by line count.
size root=engine:
    ./scripts/classify-kotlin-size.nu {{quote(root)}}

# Report directories with one file or more than five files.
dir-size root=engine:
    ./scripts/classify-directory-size.nu {{quote(root)}}

# Build the CLI and live server distributions.
install:
    cd {{engine}} && ./gradlew :cli:installDist :server:installDist

# Run the batch CLI. Extra arguments are forwarded.
cli *args:
    cd {{engine}} && ./gradlew :cli:run {{ if args == "" { "" } else { "--args=" + quote(args) } }}

# Run the HTTP server. Extra arguments are forwarded.
server *args:
    cd {{engine}} && ./gradlew :server:run {{ if args == "" { "" } else { "--args=" + quote(args) } }}

# Run the live Unix-socket server. The nvim client normally spawns this.
live socket root *args: install
    ./{{engine}}/build/install/cint-server/bin/cint-server --socket {{quote(socket)}} --root {{quote(root)}} {{args}}

# Generate a tags.json artifact for a project.
tags root *args: install
    ./{{engine}}/build/install/cint/bin/cint {{quote(root)}} --out {{quote(root + "/tags.json")}} {{args}}

# Remove Gradle build outputs.
clean:
    cd {{engine}} && ./gradlew clean

# V2 recipes (analysis-engine-v2)
engine-v2 := "analysis-engine-v2"

# Build V2 skeleton without running tests.
v2-build:
    cd {{engine-v2}} && ./gradlew --no-daemon --max-workers=4 assemble

# Run V2 tests including graph guard.
v2-test:
    cd {{engine-v2}} && ./gradlew --no-daemon --max-workers=4 check

# Regenerate the V1 Kotlin syntax snapshot from the pinned V1 commit and compare it to the checked-in files.
v2-syntax-provenance:
    ./analysis-engine-v2/scripts/verify-v1-syntax-snapshot.sh

# Run the frozen CLI and IPC parity suites and merge the machine-readable report.
v2-parity:
    cd {{engine-v2}} && ./gradlew --no-daemon --max-workers=4 :integration-tests:test :server:test
    python3 {{engine-v2}}/scripts/merge-parity-report.py {{engine-v2}}/parity/v2-parity-report.json
    git diff --exit-code -- {{engine-v2}}/parity/v2-parity-report.json {{engine-v2}}/parity/defect-baseline.json {{engine-v2}}/parity/ipc-defect-baseline.json

# Smoke-test the built CLI, live server, and MCP distributions.
v2-smoke: v2-install
    ./{{engine-v2}}/scripts/v2-smoke.sh

# Run V2 graph guard against the declared graph and its self-test cases.
v2-graph-guard:
    cd {{engine-v2}} && ./gradlew --no-daemon --max-workers=4 graphGuard graphGuardSelfTest

# Build V2 CLI distribution.
v2-cli:
    cd {{engine-v2}} && ./gradlew --no-daemon :cli:installDist

# Build V2 server distribution.
v2-server:
    cd {{engine-v2}} && ./gradlew --no-daemon :server:installDist

# Build V2 MCP distribution.
v2-mcp:
    cd {{engine-v2}} && ./gradlew --no-daemon :mcp:installDist

# Build all V2 app distributions.
v2-install: v2-cli v2-server v2-mcp

# Clean V2 build outputs.
v2-clean:
    cd {{engine-v2}} && ./gradlew --no-daemon clean
