# Correctness Review — 078 V2 Runtime

Verdict: FINDINGS

1. **Language cache ignores root/options** — `DefaultRuntime.kt:165-172` keys `supports` only by `descriptor.id`, while `languageOptions()` (`:175-181`) permits a per-request root override. A later `analyze` of the same language at a different `rootPath` resolves a new support, immediately shuts it down (`:168`), and reuses the first root's provider. Fix: key the cache by descriptor id plus normalized root/provider config (or skip reuse when options differ), and report/dispose displaced supports.

2. **`forceFullAnalysis` is ignored** — `DefaultRuntime.kt:61-68` always calls `index.ensure` with an unmodified `SourceInput`; the request flag is never consulted. Cached content hashes therefore serve "forced" analyses without reparse. Fix: invalidate the target paths (or use `fullRescan`) before `ensure` when the flag is set.

3. **Invalid paths escape as exceptions** — `DefaultRuntime.kt:351-355` calls `Path.of` on `knowledgePath`/`rootPath` without guarding, so `InvalidPathException` escapes `RuntimeFactory.create` instead of returning a `ContractError`. Fix: parse in `runCatching` and return `InvalidInput`; also resolve a relative `knowledgePath` against `rootPath`.

4. **Shutdown loses save failures** — `DefaultRuntime.kt:141-149` sets `closed = true` before returning a failed save. A second `shutdown()` short-circuits to success and never retries the write. Fix: keep shutdown retryable until save succeeds, or retain the error for later calls.

5. **Loaded stores ignore the materialized flag** — `DefaultRuntime.kt:271-278` passes `exists = true` for any existing snapshot, so `InMemoryKnowledgeStore` reports `materialized=true` even when `KnowledgeSnapshot.materialized` is false. Defaults are never seeded and fallback policy sees an initialized store. Fix: pass `exists = snapshot.materialized` (and seed when false while preserving stored entries).

6. **Reload drops support-shutdown errors** — `DefaultRuntime.kt:105` ignores `shutdownSupports()` failures (likewise `:168` for duplicate supports). A provider that fails to stop is unreported while state is rebuilt. Fix: aggregate and return the shutdown result before committing reload.

7. **Multi-file results lose file identity** — `AnalysisMerge.kt:27-41,86-105` prefixes fact/node IDs but flattens `annotations`/`candidates` unchanged, including unqualified `nodeAnchor` IDs. Their spans and anchors are file-relative and indistinguishable across files. Fix: preserve per-path results or qualify annotation/candidate identity and anchors.

8. **Test gaps** — `DefaultRuntimeTest.kt:90,165,216,248,286` only analyzes one path, so `AnalysisMerge`, `publishOnAnalyze`, `forceFullAnalysis`, reload failure, save failure, and root-switch caching are untested. Add end-to-end cases for these.

The core single-file flow is correct: construction builds packs, knowledge, index, resolver, and pipeline in order; defaults materialize only when the store is absent; corrupt-store, missing-oracle, and missing-grammar errors propagate; reload reloads disk knowledge and invalidates tracked paths; publish/status/shutdown are wired. The risks are stale language caches, ignored force semantics, lifecycle errors being masked, lost multi-file identity, and thin coverage of the failure and multi-target paths.

## Round 2

Verdict: FINDINGS

1. CONCUR — `DefaultRuntime.kt:215-239` keys supports by language, root, provider fields, timeouts, and channel identities; `:191-198` reuses/disposes; test `DefaultRuntimeTest.kt:588-626`.
2. CONCUR — `DefaultRuntime.kt:62-65` invalidates forced targets; test `:505-531`.
3. CONCUR — repository port `contracts/.../KnowledgeRepository.kt:14-25`; `FileKnowledgeRepository.kt:28-81` returns `InvalidInput` and resolves relative paths; tests `:256-273,383-393`.
4. CONCUR — `DefaultRuntime.kt:167-174` retries save errors; test `:423-442`.
5. CONCUR — `:357-382` uses `exists = snapshot.materialized` and seeds without dropping entries; test `:276-306`.
6. CONCUR — `:116-117` propagates shutdown failures before reload mutation; test `:231-253`.
7. DISSENT — merge moved to `contracts/.../AnalysisResultMerge.kt:28-61`, but only existing anchors are qualified. Core annotations carry no anchor (`core/.../AnnotationStore.kt:78-90`), so production multi-file annotations still lack file identity; the test synthesizes one (`DefaultRuntimeTest.kt:750-777`).
8. CONCUR — added tests cover force, publishOnAnalyze, multi-file, root caching, save/reload failures, and path handling (`DefaultRuntimeTest.kt:230-626`).

New: `openRepository` treats only null root/path as unset (`DefaultRuntime.kt:390`), while `FileKnowledgeRepository.kt:31-52` rejects blank, so `RuntimeConfig(rootPath = "")` now fails although other runtime paths treat blank as absent. Also, without a configured root, support keys use the source's parent directory (`:220-225`), creating one provider per directory.

Items 1-6 and 8 are fixed, but annotation identity in real multi-file output remains ambiguous and blank-path behavior regressed, so the ticket still needs changes.

## Round 3

Verdict: PASS

1. CONCUR — root/provider-aware keys retained (`DefaultRuntime.kt:215-232`).
2. CONCUR — forced invalidation retained (`:62-65`).
3. CONCUR — repository parsing retained (`FileKnowledgeRepository.kt:28-81`).
4. CONCUR — retryable shutdown retained (`DefaultRuntime.kt:168-174`).
5. CONCUR — `exists = snapshot.materialized` retained (`:355`).
6. CONCUR — reload shutdown propagation retained (`:116-117`).
7. CONCUR — `AnalysisResultMerge.kt:58-71` now assigns a path-qualified anchor to every annotation, synthesizing a span anchor when absent; `DefaultRuntimeTest.kt:559-588` uses anchorless production-shaped input (`:794`) and asserts `path|span:...`.
8. CONCUR — blank root is unset (`DefaultRuntime.kt:384-388`; test `:619-630`), and rootless reuse is covered (`:591-616`).

Residual: a rootless key is empty, so one runtime analyzing files from different discovered project roots would reuse the first provider; that is acceptable only under the one-project-per-runtime assumption.
