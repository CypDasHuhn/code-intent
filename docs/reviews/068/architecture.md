# Architecture Review — 068 V2 Knowledge Store

Verdict: FINDINGS

1. **Port is narrower than the implementation** — `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/ports/KnowledgeStore.kt:12-60` omits `effectiveIntents`, `pruneStale`, `packOrigin`, and `isMaterialized`, which exist only on `InMemoryKnowledgeStore` (`InMemoryKnowledgeStore.kt:46,239-274`). Consumers must therefore import the concrete store, and `effectiveIntents` places derivation precedence inside persistence. Fix: put lifecycle operations on a port and move override-vs-annotation precedence to `intent-pipeline`.

2. **Adapters are coupled to the concrete in-memory store and to the JVM** — `KnowledgeStoreJson.kt:26` accepts `InMemoryKnowledgeStore`; `KnowledgeStoreFiles.kt:17,46` load/save it, and `KnowledgeStoreFiles.kt:7-11` puts `java.nio.file.Path` in the same module as the core model. This blocks codec reuse by another store and makes knowledge JVM-bound. Fix: codec should operate on `KnowledgeSnapshot`/a document value; keep filesystem `Path` behind a separate adapter module.

3. **Pack origins are outside the snapshot contract** — `KnowledgeSnapshot.kt:11-21` has no origin field; `KnowledgeStoreJson.kt:42` reads `store.packOriginEntries()` internally, while `fromSnapshot` defaults origins to empty (`InMemoryKnowledgeStore.kt:375-388`). A snapshot round-trip through the port silently loses pack provenance, and `KnowledgeDocument` (`KnowledgeStoreJson.kt:108-124`) duplicates snapshot fields plus sidecar data. Fix: expose origins in a compound snapshot/port method or make them an explicit sidecar value passed to the codec.

4. **Contracts contain clock behavior** — `FunctionAnnotation.kt:22-48`, `SpanOverride.kt:23-47`, `PatternHypothesis.kt:25-51`, and `KnowledgeSnapshot.kt:20` call `Clock.System.now()`/`currentTimeMillis()`. Contracts are meant to be data plus pure helpers; supplying time here conflicts with the store's injectable clock and makes DTO behavior nondeterministic. Fix: require callers to pass timestamps and keep contracts clock-free.

5. **One mutation API is not achieved** — `KnowledgeChange.kt:6-44` has no pattern-hypothesis set/remove cases, so pattern writes cannot go through `proposeChange`, while the nested `ProposeChange` variant duplicates the top-level `proposeChange` (`KnowledgeStore.kt:56-59`). Fix: make `KnowledgeChange` exhaustive for every durable record type and keep one transactional entry point.

6. **Pattern identity splits the selector model** — `PatternSelector` exists (`Selector.kt:79-92`) but `PatternHypothesis` uses a raw `String id` (`PatternHypothesis.kt:13`) and removal keys on `id` (`KnowledgeStore.kt:46-49`). This diverges from the model and forces agents to invent opaque ids. Fix: give hypotheses a `PatternSelector` or document the id as a separate stable identity.

The boundaries are otherwise sound: knowledge imports only contracts, with no analysis, AI, MCP, parser, or LSP leakage; pack defaults are seed records, not per-pack user config; and analysis, AI, MCP, and SQLite remain out of scope. The risk is that the contract is thinner than the implementation, so the next modules will either import `InMemoryKnowledgeStore` or reimplement precedence, snapshot, and pattern-mutation behavior. Fix the port and adapter boundaries before `intent-pipeline` and `runtime` wire against them.

## Round 2

1. CONCUR — `KnowledgeStore.kt:17,57-71` exposes `isMaterialized`, `packOrigin`, and `pruneStale`; `KnowledgeLookup.kt:8-26` reduces precedence to a snapshot query. Deferring full composition to 075 is legitimate if 075 consumes this helper and does not grow rule/default precedence inside `knowledge`.
2. CONCUR — `KnowledgeStoreJson.kt:20,36` encodes/decodes `KnowledgeSnapshot`; `knowledge-persistence/.../KnowledgeStoreFiles.kt:15-92` owns `Path` and IO. The new module is justified, but the graph omits `runtime -> knowledge-persistence` (`graph-guard.json:21-27`), so no production module can yet load or save the store.
3. CONCUR — `KnowledgeSnapshot.kt:14,27-29` carries origins; `KnowledgeValidation.kt:81-94` validates them; `InMemoryKnowledgeStore.kt:380-397` passes them through. The duplicate document is gone.
4. CONCUR — `FunctionAnnotation.kt:21-49`, `SpanOverride.kt:21-46`, `PatternHypothesis.kt:24-52`, and `KnowledgeSnapshot.kt:20` require caller timestamps; knowledge DTOs are clock-free.
5. CONCUR — `KnowledgeChange.kt:32-55` adds pattern cases and `Batch`; `KnowledgeChangeApplier.kt:28-42` applies them through the port.
6. CONCUR — `PatternHypothesis.kt:12` uses `PatternSelector`; `KnowledgeStore.kt:52-55` removes by selector, matching the selector model.

The new module and contract changes are directionally correct. Add the runtime persistence edge before ticket 078, and keep 075 responsible for full precedence composition.
