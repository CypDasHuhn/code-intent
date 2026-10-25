# Correctness Review — 068 V2 Knowledge Store

Verdict: FINDINGS

1. **Decoded records bypass knowledge validation** — `analysis-engine-v2/knowledge/src/main/kotlin/dev/cint/knowledge/KnowledgeStoreJson.kt:82-97`. Only the `KnowledgeSnapshot` version/timestamp invariants run on decode; `validateFunctionAnnotation`, `validateSpanOverride`, `validatePatternHypothesis`, and `validatePackDefault` are never applied. A hand-edited or corrupted file with unknown intents, empty intents, or `createdAt > updatedAt` loads successfully, so invalid data enters the store without a `ValidationError`. Fix: validate every decoded record (and the pack-origin/selector pairing) before constructing the store.

2. **Caller-owned mutable collections alias store state** — `analysis-engine-v2/knowledge/src/main/kotlin/dev/cint/knowledge/InMemoryKnowledgeStore.kt:81,121,158` (and snapshots at `:54-58`). `intents`, `evidence`, `examples`, and `counterexamples` are `Set`/`List` interfaces; a caller passing a `MutableSet`/`MutableList` can mutate stored knowledge (or a returned snapshot) after the write, changing state without `touch()` and without a store revision. Fix: defensively copy (`toSet()`/`toList()`) on write and on snapshot, or use immutable collection types.

3. **Pack origins are not part of a snapshot** — `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/knowledge/KnowledgeSnapshot.kt:11-21` and `InMemoryKnowledgeStore.kt:375-388`. `snapshot()` omits `packOrigins`; `fromSnapshot` defaults them to empty. JSON round-trip preserves origins only because `encode` reads the sidecar directly (`KnowledgeStoreJson.kt:42`), so a snapshot-based clone silently loses origin tracking. Fix: include origins in the snapshot contract or expose a compound snapshot that callers must pass through.

4. **Stale writes can regress durable timestamps** — `InMemoryKnowledgeStore.kt:81,121`. `set*` replaces the whole record, discarding the stored `createdAt` and accepting an older `updatedAt` than the current entry. A stale caller can overwrite newer knowledge and move `updatedAt` backwards. Fix: preserve `createdAt`, advance `updatedAt` in the store, and reject updates older than the stored revision.

5. **Pack origin survives an explicit user override** — `InMemoryKnowledgeStore.kt:81-83` versus removal at `:96`. `setFunctionAnnotation` never reconciles `packOrigins`; a pack-seeded selector later written by a user still reports the pack origin, while `removeFunctionAnnotation` clears it. Fix: define and enforce the intended semantics (clear on non-pack write, or document history retention) and test both paths.

6. **`materializeDefaults` silently accepts invalid input when a store exists** — `InMemoryKnowledgeStore.kt:185-190`. The early return precedes validation, so unknown intents and blank pack ids are reported as success on any existing store. Fix: validate before the no-op return, or return an explicit no-op/already-exists result.

7. **Vacuous and mislabelled tests** — `analysis-engine-v2/knowledge/src/test/kotlin/dev/cint/knowledge/InMemoryKnowledgeStoreTest.kt:321` asserts `assertNotNull` on a non-null `Long` timestamp (always true); `:115-133` claims removal idempotence but removes a missing function annotation and never calls `removePatternHypothesis`; `:187-210` claims cancellation covers every mutation but omits removals, `proposeChange`, and `pruneStale`. Fix: assert `timestamp > 0`, exercise the actual removals/idempotence, and cover every mutator.

Selector identity normalization, duplicate replacement, deterministic ordering, no-overwrite materialization when `exists=false`, pack-origin recording on seeded entries, and `proposeChange` rollback all behave correctly. The main risks are validation on load, aliasing, incomplete snapshot semantics, and timestamp/update handling.

## Round 2

1. CONCUR — `KnowledgeStoreJson.kt:62` now calls `validateSnapshot` (`KnowledgeValidation.kt:40-95`), covering records, duplicate identities, and origin pairing; tests `KnowledgeStoreJsonTest.kt:96-151`.
2. CONCUR — `KnowledgeCopies.kt:9-21`, applied at `InMemoryKnowledgeStore.kt:42-48,64-70,106-116`; test `:154-175`.
3. CONCUR — `KnowledgeSnapshot.kt:14`; populated at `InMemoryKnowledgeStore.kt:71-73` and consumed at `:380-397`; test `:344-380`.
4. CONCUR — stale rejection and createdAt preservation at `InMemoryKnowledgeStore.kt:102-109`; test `:133-151`.
5. CONCUR — non-pack writes clear origin `:111-113`; test `:383-396`.
6. CONCUR — validation precedes the no-op `:251-257`; test `:335-341`.
7. CONCUR — `InMemoryKnowledgeStoreTest.kt:425` asserts timestamp > 0; pattern removal `:197-210`; full cancellation `:250-278`.

New checks: `knowledge-persistence` is a clean adapter (`KnowledgeStoreFiles.kt:15-77`), wired in `settings.gradle.kts:12,35` and `graph-guard.json:7-8`; no knowledge→IO edge. Contract changes (required `createdAt`, `PatternSelector`, `Batch`, snapshot `timestamp`/`packOrigins`) are updated at all call sites and covered by tests. No new correctness regression found. The on-disk document shape changed from round 1 while `formatVersion` remains 1; no persisted v2 store exists, so this is not a live regression, but a future schema change should bump it.

Verdict: PASS.
