# CodeIntent — Architecture

CodeIntent is a fixed analysis hull with loadable detection and persisted
project knowledge. The current engine is the V2 engine under `analysis-engine/`;
the module graph is enforced by `graphGuard` and documented in
[`rewrite/module-graph.md`](rewrite/module-graph.md).

## Goal

Separate four concerns that V1 mixed together:

- **Source facts** come from tree-sitter and a semantic provider.
- **Detection** comes from manifest packs, not classpath code.
- **Project knowledge** is persisted and edited through one mutation API.
- **Orchestration** lives in `runtime`; apps only configure and call it.

```
source / buffer
  -> languages facade
       -> syntax-treesitter (tree, changed ranges, node hashes, facts)
       -> lsp (managed, attached, or editor-proxy semantic facts)
  -> index (hashes, cache, incremental invalidation)
  -> intent-pipeline
       -> packs (manifests, playbooks, seed defaults)
       -> knowledge (function annotations, overrides, hypotheses)
       -> core (strata, fixpoint, composition, candidates)
       -> ai (fallback proposals only)
  -> runtime
       -> cli artifact / server protocol / mcp tools
```

## Module boundaries

| module | owns | must not depend on |
|---|---|---|
| `contracts` | DTOs, ports, manifests, wire messages | adapters, runtime, apps |
| `core` | deterministic kernel, scheduler, composition | parser, LSP, filesystem, network |
| `packs` | manifest packs, playbooks, defaults | core, knowledge, adapters |
| `index` | change detection, fact cache | core, packs, languages, adapters |
| `knowledge` | materialized knowledge and mutation | core, index, adapters, apps |
| `knowledge-persistence` | JSON/checkpoint adapters | core, runtime, apps |
| `languages` | language descriptor to adapter binding | core, index, runtime |
| `syntax-treesitter` | tree-sitter parsing and facts | core, packs, languages |
| `lsp` | semantic provider implementations | core, packs, languages |
| `intent-pipeline` | pack selection, plans, knowledge resolution | runtime, index, adapters, IPC |
| `ai` | fallback proposals | core, packs, index, knowledge |
| `ipc` | NDJSON/wire DTOs | runtime, adapters, apps |
| `runtime` | orchestration, lifecycle, `analyze` | parser/LSP adapters, IPC, apps |
| `cli`, `server`, `mcp` | app process and transport | core internals, adapters |

The full allowed-edge set and the native-spike exception are in
`analysis-engine/graph-guard.json` and `docs/rewrite/module-graph.md`.

## Deterministic kernel

- Rule nodes are identified by intents. Each rule owns exactly one intent.
- `reads` declares required dependencies; `optionalReads` adds an edge only when
  the owner is present. Missing required owners and cycles are load errors.
- The scheduler topologically sorts rules into strata. Each stratum runs once
  against frozen earlier output; a self-reading rule may run an internal
  monotone fixpoint.
- Core composition is the terminal node. It consumes source intents and emits
  `DERIVATIVE_USE` and `SCAFFOLD` annotations without naming a pack or rule.
- Rules emit established annotations and review-only candidates. Candidates
  never enter composition, the knowledge store, or the reads graph.

## Knowledge model

Function annotations, span overrides, pattern hypotheses, and materialized pack
defaults are versioned project data. Selectors carry qualified name plus
optional import, receiver, declaring type, signature, and structural pattern.
Provenance records the writer and source.

Users, MCP agents, and fallback AI share the same mutation API. Pack defaults
seed the store once; they do not re-apply on every analysis. Function contracts
propagate deterministically to call sites and chains, so AI is not consulted per
call site.

## Language support

`languages` owns the descriptor catalog and binds a descriptor to a
`SyntaxEngine` and `SemanticProvider`. The index receives `LanguageSupport`; it
never resolves a language or constructs an adapter. `syntax-treesitter` has no
file IO and returns contract fact batches. `lsp` supports managed, attached, and
editor-proxy modes behind the same port; there is no syntax-only production
mode.

## Runtime and apps

`runtime` owns index, knowledge, pack, and pipeline lifecycles. It is the only
analysis module apps need to import.

- `cint` is the batch host and emits JSON or NDJSON.
- `cint-server` is the live per-project daemon. It owns the renderer IPC
  boundary, speaks NDJSON JSON-RPC, and negotiates the current protocol plus the
  legacy protocol-4 shape.
- `cint-mcp` exposes analysis and knowledge tools to external agents. MCP is the
  primary AI workflow; in-house fallback AI is optional.

## Renderer boundary

The editor client is external: the Neovim renderer lives in the sibling
`code-intent-nvim` repository. It consumes published annotations, candidates,
and review state, owns visual meanings, highlights, profiles/shards, hover, and
review UX, and never runs detection. The engine publishes the versioned
contract in [`docs/ipc/PROTOCOL.md`](ipc/PROTOCOL.md).

## Native platform

`syntax-treesitter` and `contracts` are multiplatform. The native spike proves
tree-sitter, JSON, Unix sockets, and child processes on Kotlin/Native. HTTP/TLS
remains JVM-only; details and reproduction commands are in
[`analysis-engine/docs/rewrite/native-spike.md`](../analysis-engine/docs/rewrite/native-spike.md).
