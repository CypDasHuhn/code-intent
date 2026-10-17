set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

engine := "analysis-engine"
renderer := "nvim-renderer"

default: list

# List available recipes.
list:
    @just --list

# Build engine modules without running tests.
build:
    cd {{engine}} && ./gradlew :core:build :treesitter:build :cli:build :server:build

# Run the deterministic engine tests (no live LSP dependency).
test-engine:
    cd {{engine}} && ./gradlew :core:test :treesitter:test

# Run the shared-LSP oracle test (requires kotlin-lsp).
test-lsp:
    cd {{engine}} && ./gradlew :lsp:test

# Run the Neovim renderer tests headlessly.
test-nvim:
    ./{{renderer}}/tests/run.sh

# Run engine and renderer tests.
test: test-engine test-nvim

# Build the CLI and live server distributions.
install:
    cd {{engine}} && ./gradlew :cli:installDist :server:installDist

# Run the batch CLI. Extra arguments are forwarded.
cli *args:
    cd {{engine}} && ./gradlew :cli:run --args="{{args}}"

# Run the HTTP server. Extra arguments are forwarded.
server *args:
    cd {{engine}} && ./gradlew :server:run --args="{{args}}"

# Run the live Unix-socket server. The nvim client normally spawns this.
live socket root *args:
    ./{{engine}}/build/install/semantic-code-painter-server/bin/semantic-code-painter-server --socket "{{socket}}" --root "{{root}}" {{args}}

# Generate a tags.json artifact for a project.
tags root *args:
    ./{{engine}}/build/install/semantic-code-painter/bin/semantic-code-painter "{{root}}" --out "{{root}}/tags.json" {{args}}

# Remove Gradle build outputs.
clean:
    cd {{engine}} && ./gradlew clean
