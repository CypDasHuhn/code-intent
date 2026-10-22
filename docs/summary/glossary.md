# Glossary

| term | meaning |
|---|---|
| **annotation** | A published span with role, intents, rule/source/confidence, span/bytes, and provenance. |
| **intent** | Open string label attached to a span, e.g. `LOGGING`, `SEARCH`, `VALIDATION`. |
| **role** | One of `SOURCE_INTENT`, `DERIVATIVE_USE`, `SCAFFOLD`. Core owns the last two. |
| **source tier** | Where evidence came from: `treesitter`, `lsp`, `comment`, `cfg`, `annotation`, `user`, `heuristic`, `ai:<provider>`. |
| **provenance** | Per-contributor origin of an annotation (rule, role, intents, source, confidence, span). |
| **contribution** | Internal store unit; source contributions have rule + role + intents + span. |
| **rule** | Unit owning exactly one intent and declaring reads/optionalReads. |
| **rulepack** | Distribution unit bundling rules plus its typed config schema. |
| **reads / optionalReads** | Required / additive intent dependencies, resolved to owning rules. |
| **stratum** | Scheduler layer of rules evaluated once against frozen earlier output. |
| **fixpoint** | Internal iteration for a rule that reads its own output; monotone over a finite lattice. |
| **scaffold** | Composition annotation covering an enclosing region (if/try/function/return) with its intents. |
| **derivative use** | Composition annotation marking a value/assignment that carries another span's intent through read-only flow. |
| **candidate** | Review-only `CandidateEmit`; never enters composition or the annotation store. |
| **AI candidate** | Candidate from `ai:<provider>`, structurally candidate-only, confirmable by the user. |
| **AI-eligible** | Candidate flagged by a pack as suitable for AI enrichment. |
| **anchor** | Candidate identity span/hash; alternatives at one anchor form a group. |
| **confirm / reject / skip** | Candidate decisions: write user annotation + reanalyze / persist suppression / hide for current client session. |
| **user directive** | `PURE` or `READONLY` user intent with built-in composition semantics. |
| **UNTAGGED** | Reserved neutral marker for unclassified spans; never rule-owned. |
| **span** | 0-based start/end line+column plus byte offsets. |
| **analysisRevision** | Monotonic per-document live publish ordering for analysis/tags. |
| **suggestionRevision** | Monotonic ordering for candidate publishes. |
| **oracle** | Type-information provider; `LspOracle` for Kotlin, `NullOracle` otherwise. |
| **oracle budget/session** | Per-analysis memoization plus timeout/failure/elapsed circuit breaker. |
| **live daemon** | `cint-server`, NDJSON JSON-RPC over a per-user Unix socket, protocol 3. |
| **split region** | Vue `<script>` region analyzed as JS/TS then shifted back to host coordinates. |
| **meaning** | Client-side visual policy (`SHOW`, `HIGHLIGHT`, `IGNORE`, `CONCEAL`) resolved from intents/tags. |
| **handler** | Client rendering primitive selected by a meaning (`color`, `highlight`, `ignore`, `conceal`). |
| **profile / shard** | Client config overlay file / named config patch stacked on a profile. |
| **hover** | Non-focusable cursor-relative intent inspector showing the innermost span's intents. |
