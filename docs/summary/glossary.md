# Glossary

| term | meaning |
|---|---|
| **annotation** | A published span with role, intents, rule/source/confidence, span/bytes, and provenance. |
| **intent** | Open string label attached to a span, e.g. `LOGGING`, `SEARCH`, `VALIDATION`. |
| **role** | One of `SOURCE_INTENT`, `DERIVATIVE_USE`, `SCAFFOLD`. Core owns the last two. |
| **source tier** | Where evidence came from: `treesitter`, `lsp`, `comment`, `cfg`, `annotation`, `user`, `heuristic`, `ai:<provider>`. |
| **provenance** | Per-contributor origin of an annotation or knowledge record. |
| **rule** | Unit owning exactly one intent and declaring reads/optionalReads. |
| **rulepack** | Manifest content bundling playbook rules plus seed defaults. |
| **playbook** | Pack rule plan consumed by `intent-pipeline` and evaluated by `core`. |
| **reads / optionalReads** | Required / additive intent dependencies resolved to owning rules. |
| **stratum** | Scheduler layer of rules evaluated once against the stratum's earlier output. |
| **fixpoint** | Internal iteration for a rule that reads its own output; monotone over a finite lattice. |
| **scaffold** | Composition annotation covering an enclosing region with its intents. |
| **derivative use** | Composition annotation marking a value/assignment that carries another span's intent through read-only flow. |
| **candidate** | Review-only output; never enters composition or the knowledge store. |
| **function annotation** | Persisted knowledge record for a function selector: intents, scope, status, provenance. |
| **span override** | Persisted knowledge record that replaces intents at a span. |
| **pattern hypothesis** | Proposed intent set for a structural pattern, with evidence and status. |
| **pack default** | Seed knowledge materialized from a pack; it becomes project-owned once materialized. |
| **knowledge store** | The materialized semantic data at `<root>/.cint/knowledge.json`. |
| **selector** | Function identity: qualified name plus optional import/receiver/declaring type/signature/pattern. |
| **confirm / reject / skip** | Candidate decisions: promote knowledge / persist suppression / hide for the current client session. |
| **UNTAGGED** | Reserved neutral marker for unclassified spans; never rule-owned. |
| **span** | 0-based start/end line+column plus byte offsets. |
| **analysisRevision** | Monotonic per-document live publish ordering for analysis/tags. |
| **suggestionRevision** | Monotonic ordering for candidate publishes. |
| **semantic provider** | Type/symbol provider behind the `SemanticProvider` port; managed, attached, or editor proxy. |
| **live daemon** | `cint-server`, NDJSON JSON-RPC over a per-user Unix socket. |
| **split region** | Vue `<script>` region analyzed as JS/TS then shifted back to host coordinates. |
| **meaning** | Client-side visual policy (`SHOW`, `HIGHLIGHT`, `IGNORE`, `CONCEAL`) resolved from intents/tags. |
| **handler** | Client rendering primitive selected by a meaning (`color`, `highlight`, `ignore`, `conceal`). |
| **profile / shard** | Client config overlay file / named config patch stacked on a profile. |
| **hover** | Non-focusable cursor-relative intent inspector showing the innermost span's intents. |
| **MCP** | Agent-facing tool boundary (`cint-mcp`); distinct from the renderer IPC protocol. |
