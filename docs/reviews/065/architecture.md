## Round 1

**Verdict: FINDINGS**

1. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/knowledge/PatternHypothesis.kt:28` — `materialize()` embeds knowledge policy in a DTO: it treats the hypothesis `id` as a `FunctionSelector.qualifiedName`, splits `proposedIntents` into one annotation per intent, hard-codes `ACCEPTED`/`DERIVED`, and discards the hypothesis provenance. `knowledge-model.md:110` requires policy acceptance before materialization. Move this behavior to `knowledge` behind a port, preserve real selectors/provenance, and let policy choose status.

2. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/knowledge/FunctionAnnotation.kt:21` — `packId`/`packVersion` are not part of the FunctionAnnotation shape in `knowledge-model.md:33-42` and make every annotation pack-aware. Keep pack identity on `PackDefault` only; materialized records should be ordinary FunctionAnnotations with `PACK_DEFAULT` provenance.

3. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/knowledge/KnowledgeSnapshot.kt:13` — the persisted snapshot carries `packDefaults` beside `functionAnnotations`, but `knowledge-model.md:147-149` says materialized defaults are FunctionAnnotations with pack-default provenance. This creates two competing sources. Remove `packDefaults` from the materialized snapshot or expose seeds as a separate read-only projection.

4. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/knowledge/KnowledgeChange.kt:12` — `SetFunctionAnnotation` (and `SetSpanOverride` at :25) carries a second `provenance` while the payload already has provenance. A writer can submit conflicting values, undermining the single mutation/provenance path. Use one authoritative field or define and validate precedence.

5. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/ports/Index.kt:32` — `factStore()` exposes the internal `FactStore` (:35) as a cross-module contract. This exceeds the narrow-port boundary and lets consumers bypass index change detection; `module-graph.md:328-330` permits it only if the pipeline needs direct queries. Keep FactStore internal and add targeted query methods to Index.

6. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/ipc/McpMessages.kt:1` — MCP DTOs live in `dev.cint.contracts.ipc`, conflating the renderer wire protocol with the agent boundary; `module-graph.md:374` states MCP is not renderer IPC. Move them to `dev.cint.contracts.mcp` (or `.agent`) and update `ports/Transports.kt` imports.

Concurrent/dissent: none.

Contracts otherwise remain data plus narrow ports. No `java.*`, `Path`, socket, process, classloader, parser, or LSP types appear in `commonMain`/`nativeMain`; the only expect surface is `sha256` and `currentTimeMillis`, with actuals confined to `jvmMain`/`nativeMain`. The new `IpcTransport`/`McpTransport` are interfaces over DTOs and import no transport implementation. The module graph still holds: settings and module build files match `graph-guard.json`, and contracts adds no project dependency. Knowledge DTOs cover FunctionAnnotation, SpanOverride, PackDefault, PatternHypothesis, provenance/status/confidence without a per-pack config schema; the findings are model drift, duplicated provenance, and two over-broad surfaces.

## Round 2

**Verdict: CONCUR — all six round-1 findings addressed; deferrals are legitimate.**

1. CONCUR — `PatternHypothesis.materialize()` removed (`analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/knowledge/PatternHypothesis.kt:24-28`). 068 covers function annotations, span overrides, pack defaults, pattern hypotheses, provenance, mutation, and materialization; 076 covers propagation. Contracts no longer choose status/provenance.
2. CONCUR — `packId`/`packVersion` removed from `FunctionAnnotation` (`.../knowledge/FunctionAnnotation.kt:11-21`) and remain on `PackDefault` (`.../knowledge/PackDefault.kt:15-16`), matching knowledge-model.md:33-42 and 76-91. 068 must track pack origin separately for update diffs (knowledge-model.md:166).
3. CONCUR — `packDefaults` removed from `KnowledgeSnapshot` (`.../knowledge/KnowledgeSnapshot.kt:11-20`); seeds stay inputs via `KnowledgeChange.MaterializeDefaults` and `KnowledgeStore.materializeDefaults`, matching knowledge-model.md:147-149.
4. CONCUR — `SetFunctionAnnotation`/`SetSpanOverride` now carry only their payloads (`.../knowledge/KnowledgeChange.kt:10-24`), leaving provenance authoritative in the record.
5. CONCUR — `FactStore` and `factStore()` removed from `Index` (`analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/ports/Index.kt:8-29`); 071 owns fact caching, and `IndexedSource` still carries facts, so the port stays narrow.
6. CONCUR — MCP DTOs moved to `dev.cint.contracts.mcp` (`analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/mcp/McpMessages.kt:1`); `Transports.kt:4-6` and tests use it, and no `contracts.ipc.Mcp*` references remain.

Residual: `FunctionAnnotation.rationale` (`.../knowledge/FunctionAnnotation.kt:21`) is still an additive field absent from the model's FunctionAnnotation; non-blocking, but 068 should decide whether rationale is durable function knowledge. Module graph, platform actuals, and transport boundaries remain unchanged.
