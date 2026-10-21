---
name: analysis oracle caching and budgeting
status: todo
depends-on: [040]
reviewers: [correctness, detection-fidelity]
---

## Goal

Restore live-analysis latency after the new intent packs. The packs now query
receiver types frequently, and `LspOracle.typeAt` performs an uncached LSP hover
with the 750 ms live timeout, so unresolved receivers can multiply into
multi-second analyses.

## Scope

- Memoize `AnalysisContext.receiverType` per analysis, keyed by call/receiver
  offset, so packs asking about the same call reuse one result.
- Add a per-document-version oracle cache in `LspOracle` (positive and negative),
  invalidated on `didOpen`/`didChange`, with a bounded size.
- Gate type queries behind cheap evidence in the new packs, especially
  `DataAccessRule.lookup`/`isEnumReceiver`: check callee/qualifier/known-map/config
  first and only consult the oracle for plausible calls.
- Add a per-analysis oracle budget/circuit breaker: after a configured number of
  timeouts/failures or elapsed budget, stop querying for that analysis and let
  text/config evidence degrade normally.
- Record counters/diagnostics (queries, cache hits, timeouts) behind the existing
  server diagnostics path; no prompt/key logging.
- Tests: duplicate-query memoization, negative-result cache, cache invalidation
  on text/changedtick, gating negative cases, timeout budget degradation, and a
  latency comparison on a fixture with many unresolved receivers.
- Docs: engine/server performance notes and a manual latency check if needed.

## Acceptance

- One analysis never issues duplicate hovers for the same receiver/offset.
- Repeated analyses of an unchanged document hit the oracle cache; edits
  invalidate only the affected document/version.
- A fixture with many unresolved receivers no longer scales linearly with the
  hover timeout (bounded by the budget).
- Detection output is unchanged for covered fixtures; any intentional gating
  change is pinned by tests.
- `just build`, `just test-engine`, `just lint`, and `just test-nvim` pass.

## Out of scope

- Replacing the LSP oracle or adding a second semantic backend.
- Async/background oracle prefetching.
- Changing live hover timeout defaults.

## Notes

- Evidence: the live log showed `TimeoutException` in
  `DataAccessRule.isEstablishedLookup`; `AnalysisContext.receiverType` calls
  `LspOracle.typeAt` per invocation and `LspOracle` caches nothing.
- Prefer a small `TypeOracle` caching decorator over threading caches through
  every rule.
- Keep the cache scoped to the analysis/file version so freshness is obvious.
