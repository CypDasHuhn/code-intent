set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

default: list

# List available recipes.
list:
    @just --list

# Build engine modules without running tests.
build:
    ./gradlew --no-daemon --max-workers=4 assemble

# Run the deterministic engine tests and graph guard.
test-engine:
    ./gradlew --no-daemon --max-workers=4 check

# Run the shared-LSP oracle test. Set CINT_LSP_ROOT to a project with a running kotlin-lsp.
test-lsp:
    ./gradlew --no-daemon :lsp:test

# Run engine tests.
test: test-engine

# Check Kotlin formatting with ktlint.
lint:
    ktlint --relative '{contracts,analysis,sources,orchestration,apps,tests}/**/src/**/*.kt'

# Auto-format Kotlin sources with ktlint.
lint-fix:
    ktlint --format --relative '{contracts,analysis,sources,orchestration,apps,tests}/**/src/**/*.kt'

# Regenerate the published renderer protocol JSON Schema.
protocol-schema:
    ./gradlew --no-daemon :ipc:generateProtocolSchema

# Apply the first Kotlin LSP code action for each diagnostic across Kotlin files.
lsp-fix root="." max="25":
    LSP_FIX_ROOT={{quote(root)}} LSP_FIX_MAX={{quote(max)}} nvim --headless -u NONE -c 'luafile {{quote(justfile_directory() + "/scripts/kotlin-lsp-fixall.lua")}}'

# Classify Kotlin files as SUSPICIOUS or EXTREME by line count.
size root=".":
    ./scripts/classify-kotlin-size.nu {{quote(root)}}

# Report directories with one file or more than five files.
dir-size root=".":
    ./scripts/classify-directory-size.nu {{quote(root)}}

# Build the CLI, live server, and MCP distributions.
install:
    ./gradlew --no-daemon :cli:installDist :server:installDist :mcp:installDist

# Package installed distributions plus the protocol schema as a versioned release tarball.
release version="0.1.0": install
    CINT_ENGINE_VERSION={{quote(version)}} ./scripts/package-release.sh

# Run the batch CLI. Extra arguments are forwarded.
cli *args:
    ./gradlew :cli:run {{ if args == "" { "" } else { "--args=" + quote(args) } }}

# Run the HTTP server. Extra arguments are forwarded.
server *args:
    ./gradlew :server:run {{ if args == "" { "" } else { "--args=" + quote(args) } }}

# Run the live Unix-socket server. The nvim client normally spawns this.
live socket root *args: install
    ./apps/server/build/install/cint-server/bin/cint-server --socket {{quote(socket)}} --root {{quote(root)}} {{args}}

# Generate a tags.json artifact for a project.
tags root *args: install
    ./apps/cli/build/install/cint/bin/cint {{quote(root)}} --out {{quote(root + "/tags.json")}} {{args}}

# Remove Gradle build outputs.
clean:
    ./gradlew --no-daemon clean

# Run the declared module graph guard.
graph-guard:
    ./gradlew --no-daemon --max-workers=4 graphGuard

# Smoke-test the built CLI, live server, and MCP distributions.
smoke: install
    ./scripts/smoke.sh
