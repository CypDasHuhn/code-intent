# Architecture

CodeIntent is a fixed analysis hull with loadable detection and persisted
knowledge. Core owns scheduling and composition; default rulepacks own detection data;
knowledge owns project-specific function contracts.

```text
source
  -> languages facade
       -> tree-sitter syntax facts
       -> semantic provider (LSP) types
  -> index (hashes, cache, incremental facts)
  -> intent-pipeline (default rulepacks + knowledge + core + optional fallback AI)
  -> runtime
       -> CLI artifact / live protocol / MCP tools
```

## Modules

The repository root is the engine root. Modules are physically grouped, but
their canonical Gradle project names and dependency edges are unchanged.
[`docs/rewrite/module-graph.md`](../rewrite/module-graph.md#project-tree) is the
canonical module tree; this summary does not restate it.

| module | role |
|---|---|
| `contracts` | Data model and narrow ports: intents, facts, annotations, candidates, knowledge DTOs, manifests, IPC/MCP. |
| `core` | Scheduler, strata, fixpoint, capability view, annotation store, composition. |
| `default-rulepacks` | Language-neutral pack manifests, playbooks, rule data, and seed defaults. |
| `index` | File hashing, incremental fact cache, dependency tracking. |
| `knowledge` | Function annotations, span overrides, hypotheses, pack defaults, mutation. |
| `knowledge-persistence` | JSON snapshot and init-checkpoint adapters. |
| `languages` | Descriptor catalog and the only binding to source adapters. |
| `syntax-treesitter` | Tree-sitter parse, incremental tree, hashes, batch facts. |
| `lsp` | Managed, attached, and editor-proxy semantic providers. |
| `intent-pipeline` | Pack selection, rule plans, knowledge resolution, result shaping. |
| `ai` | Fallback AI proposals for dirty or uninitialized code. |
| `runtime` | Orchestration and the public `analyze` surface. |
| `ipc` | NDJSON framing and renderer wire DTOs. |
| `apps/cli`, `apps/server`, `apps/mcp` | Batch CLI, live daemon, agent server. |
| `tests/fixtures`, `tests/integration` (`:test-fixtures`, `:integration-tests`) | Shared fakes and integration tests. |

Dependency direction: `contracts` <- `core` <- `intent-pipeline` <- `runtime` <-
apps. `languages` is the only module that depends on `syntax-treesitter` and
`lsp`. The graph guard enforces this.

The canonical grouped tree and the 063 supersession note live in
[`docs/rewrite/module-graph.md`](../rewrite/module-graph.md#project-tree).

## Rule contract and scheduler

- Intent names are open. `UNTAGGED` is reserved and never rule-owned.
- Every rule owns exactly one intent; ownership is globally unique.
- `reads` declares required intent dependencies; `optionalReads` adds an edge
  only when the owner is present.
- The scheduler resolves intents to owning rules, rejects missing owners and
  cycles, and topologically sorts strata. A stratum runs once against its
  earlier output; a self-reading rule may run an internal monotone fixpoint.
- Core composition is the terminal node. It produces scaffold and
  derivative-use annotations and consumes all source intents.
- Rules return established annotations and review-only candidates. Candidates
  never enter the annotation store, knowledge, or composition.

## Knowledge

- Function annotations, span overrides, pattern hypotheses, and materialized
  pack defaults are persisted project data.
- Users, MCP agents, and fallback AI all write through one mutation API.
- Pack defaults are materialized once; they do not reapply on every analysis.
- Function contracts propagate deterministically to call sites and chains.
- Selectors include qualified name plus optional import, receiver, declaring
  type, signature, and structural pattern.

## Languages and providers

- Kotlin, C, JavaScript, TypeScript, Vue, and C# are registered in `languages`.
- `syntax-treesitter` performs no file IO; callers pass bytes.
- The semantic provider is mandatory. Modes are managed, attached, and
  editor-proxy. There is no syntax-only production path.
- Vue split regions are analyzed by their region language and shifted back to
  host coordinates.

## Hosts and protocol

- `cint` is the batch CLI and emits JSON or NDJSON.
- `cint-server` is the NDJSON JSON-RPC Unix-socket daemon. Current protocol is 5;
  protocol-4 clients receive legacy-shaped payloads where supported.
- `cint-mcp` exposes repository discovery (`repo.files`, `analysis.symbols`,
  `knowledge.resolveSelector`), `repo.languages`, `analysis.run`, `repo.status`,
  `repo.index`, knowledge snapshot/default/materialization tools, and
  function/span/pattern tools. The tested agent sequence is in
  [`docs/mcp-agent-workflow.md`](../mcp-agent-workflow.md).
- The renderer consumes published data and never runs detection.

See `orchestration/ipc/PROTOCOL.md` for the wire contract and
`docs/rewrite/module-graph.md` for the full dependency matrix.
