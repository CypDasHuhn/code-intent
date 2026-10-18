# Semantic Code Painter

> Code to Intent.

Paints code in the editor by *intent* (Logging, Error Handling, Validation,
Business Logic, ...) instead of by syntax.

## Components

| dir | what |
|-----|------|
| [`analysis-engine/`](analysis-engine) | Kotlin engine. Tree-sitter for structure, LSP for types, composable rule blocks, batch CLI, and HTTP host. Built-in Kotlin, JavaScript, and TypeScript syntax profiles. |
| `nvim-renderer/` | Neovim plugin that consumes tags and paints spans (background tint, gutter icon, conceal). |

## Flow

```
source / buffer
      │
      ├─ tree-sitter ──► spans, nesting, control flow
      └─ LSP ─────────► types, symbols, definitions
                │
                ▼
        core rule blocks
                │
        ┌───────┴────────┐
        ▼                ▼
     tags.json       HTTP /analyze
        │                │
        └──────► nvim-renderer
```

Tree-sitter owns statement-level structure (tier 0). LSP only annotates those
nodes with semantic facts (tier 1). The engine stays editor- and
language-agnostic; language specifics are data in a syntax profile, and a small
frontend binding selects the parser, profile, and oracle.

## Quick start

```sh
cd analysis-engine
./gradlew :cli:run --args="/path/to/project --out tags.json"
./gradlew :cli:installDist
./gradlew :server:run --args="--port 8765"
```

See [`analysis-engine/README.md`](analysis-engine/README.md) for the module layout,
config, blocks, and LSP sharing.

## Tasks

Common workflows are wrapped in the [`justfile`](justfile):

```sh
just --list
just test
just install
just tags /path/to/project --no-lsp
```
