set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

engine := "analysis-engine"
renderer := "nvim-renderer"

default: list

# List available recipes.
list:
    @just --list

# Build engine modules without running tests.
build:
    cd {{engine}} && ./gradlew :api:build :rulepacks:build :core:build :treesitter:build :lsp:build :frontend:build :cli:build :server:build

# Run the deterministic engine tests (no live LSP dependency).
test-engine:
    cd {{engine}} && ./gradlew :api:test :rulepacks:test :core:test :treesitter:test :frontend:test

# Run the shared-LSP oracle test (requires kotlin-lsp).
test-lsp:
    cd {{engine}} && ./gradlew :lsp:test

# Run the Neovim renderer tests headlessly.
test-nvim:
    ./{{renderer}}/tests/run.sh

# Run engine and renderer tests.
test: test-engine test-nvim

# Check Kotlin formatting with ktlint.
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
live socket root *args:
    ./{{engine}}/build/install/cint-server/bin/cint-server --socket "{{socket}}" --root "{{root}}" {{args}}

# Generate a tags.json artifact for a project.
tags root *args:
    ./{{engine}}/build/install/cint/bin/cint "{{root}}" --out "{{root}}/tags.json" {{args}}

# Remove Gradle build outputs.
clean:
    cd {{engine}} && ./gradlew clean
