# CodeIntent

> Code to Intent.

Paints code in the editor by _intent_ (Logging, Error Handling, Validation,
Business Logic, ...) instead of by syntax.

## Human Note

Unsurprisingly most of this is vibecoded. Me, Cyp, has brainstormed this idea
and architectured the UX and basic blocks of the analysis engine, but the code
was completely generated.

This Analysis engine will be reviewed, and probably re-written, after i actually
understand what this large project actually needs. Until then, i sadly don't
have the time to do that, so i chose this route.

As for the lua client, maybe, but it's less important then the backend anyway.

This note here is the last Human-written lines in this repository.

## Components

| dir | what |
| --- | --- |
| [`contracts/`](contracts) | Data model, ports, manifests, and wire DTOs. |
| [`analysis/`](analysis) | Deterministic core, packs, intent pipeline, and persisted knowledge. |
| [`sources/`](sources) | Index, language facade, tree-sitter adapter with vendored native grammars, and LSP providers. |
| [`orchestration/`](orchestration) | Runtime composition, fallback AI, and renderer IPC. |
| [`apps/`](apps) | Batch CLI, live Unix-socket daemon, and MCP server. |
| [`tests/`](tests) | Shared fakes and integration tests. |
| [`scripts/`](scripts) | Build, smoke, and release scripts. |

The Neovim client lives in
[`code-intent.nvim`](https://github.com/CypDasHuhn/code-intent.nvim) (sibling
checkout at `../code-intent.nvim`). This repository publishes the versioned renderer
protocol in [`docs/ipc/PROTOCOL.md`](docs/ipc/PROTOCOL.md) and
[`orchestration/ipc/protocol.schema.json`](orchestration/ipc/protocol.schema.json).
It contains no Neovim code and runs no renderer tests.

## Flow

```
source / buffer
      │
      ├─ tree-sitter ──► syntax facts, nesting, control flow
      └─ semantic provider (LSP) ──► types, symbols
                │
                ▼
     index + intent pipeline
       ├─ manifest packs + playbooks
       ├─ persisted function knowledge
       └─ deterministic core scheduler + composition
                │
        ┌───────┴────────┐
        ▼                ▼
     tags.json       live protocol / MCP
        │                │
        └──────► code-intent.nvim (external)
```

The engine is a fixed analysis hull. Detection ships as manifest packs and seed
defaults; semantic knowledge is persisted project data. The index owns change
detection, `runtime` orchestrates, and `languages` is the only module that binds
source adapters.

## Quick start

```sh
just install
./apps/cli/build/install/cint/bin/cint /path/to/project --out tags.json
# or run the live daemon directly
./apps/server/build/install/cint-server/bin/cint-server --socket /tmp/cint.sock --root /path/to/project
```

See [`docs/engine.md`](docs/engine.md) for the module
layout, knowledge model, config, and LSP sharing. The full documentation index is
[`docs/README.md`](docs/README.md).

## Tasks

Common workflows are wrapped in the [`justfile`](justfile):

```sh
just --list
just build
just test-engine
just lint
just graph-guard
just install
just smoke
just tags /path/to/project
```

Renderer development and tests live in the external
[`code-intent.nvim`](https://github.com/CypDasHuhn/code-intent.nvim) repository;
see [`docs/ipc/PROTOCOL.md`](docs/ipc/PROTOCOL.md) for the pinned protocol
contract.
