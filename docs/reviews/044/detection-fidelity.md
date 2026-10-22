# Detection fidelity review — 044: analysis oracle caching and budgeting

## Verdict

Dissents. The NullOracle fixture snapshots stay byte-identical and the
memoization/circuit-breaker plumbing is sound, but the healthy-oracle path has
real detection regressions and the requested cache/budget behavior is not
actually pinned by tests.

## Findings

### 1. `isTypeQueryable` gates out real detections on call/index/chained receivers

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:118-132, 141-162, 184-211`
(and `CallFacts.receiverOffset` at
`analysis-engine/core/core/src/main/kotlin/dev/cint/core/syntax/CallFacts.kt:47`).

Problem: the old `isEstablishedSearch`/`isEstablishedLookup`/`isEnumReceiver`
consulted `view.receiverType` for any call that the callee/config evidence had
already made plausible. The new textual `isTypeQueryable` accepts only
identifiers and dotted names, so receivers that are call or index expressions
are skipped even when the oracle is healthy. `receiverOffset` deliberately
points into a nested call expression (`endByte - 1`) precisely so those
receivers can be typed; this gating removes that class of detection:

- `supplier().findAll()` (callee configured) no longer establishes SEARCH and
  falls back to a candidate.
- `source.load().getValue(id)` no longer establishes LOOKUP from
  `lookupTypes`; it is downgraded to a candidate.
- `getStatusType().valueOf(code)` loses LOOKUP *entirely*: `isEstablishedLookup`
  fails on the type gate and `isEnumCandidate` also fails because
  `isEnumStyleReceiver` rejects the lowercase call-result name, so not even a
  candidate is emitted.

That is not "only implausible queries"; the callee is already cheap-evidence
positive, and the receiver is exactly the shape the offset model was built for.

Suggested fix: gate on AST shape rather than flat receiver text. Keep literals,
binary/lambda expressions, etc. out, but allow call/index/navigation receivers
(possibly nested simple chains), or drop `isTypeQueryable` from the established
paths and use it only to suppress genuinely questionable hovers. Add typed
regression tests with chained receivers covering SEARCH, LOOKUP and
enum-LOOKUP.

### 2. Failed/timeout lookups are not memoized, so duplicate hovers still occur

Location:
`analysis-engine/core/core/src/main/kotlin/dev/cint/core/facts/oracle/Oracle.kt:83-115`;
call sites at
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:438-442, 615`;
`analysis-engine/languages/lsp/src/main/kotlin/dev/cint/lsp/KotlinLspOracle.kt:79-99`.

Problem: only successful results (positive or `null` hover) are stored in
`OracleSession.cache`. The `catch` paths return `null` without recording
anything. With the default `maxTimeouts = 2`, the same offset can therefore be
hovered twice in one analysis. `ServiceRulePack`'s
`receiverType(call) ?: typeAt(receiver)` retries the same offset after a
timeout: `AnalysisContext` memoizes the `null`, but the session has no entry, so
the second lookup issues another hover. `LspOracle` likewise does not record
timeouts/failures for the document version, so every new analysis (new session)
re-pays the same timeout until the per-analysis budget trips.

This violates the acceptance criterion "One analysis never issues duplicate
hovers for the same receiver/offset" and weakens "repeated analyses of an
unchanged document hit the oracle cache". It also lets one pathological offset
consume the whole budget before later, resolvable receivers are reached,
changing their classifications for reasons unrelated to their own evidence.

Suggested fix: memoize failure outcomes per key in `OracleSession` (e.g. a
tri-state/sentinel so blocked, failed and genuinely unresolved are distinct),
and record timeout/failure negatives in `DocumentTypeCache` for the version (or
keep a failed-offset set there). Add a test that times out on one offset, calls
it again, and asserts a single delegate call, plus a second analysis that hits
the cached failure.

### 3. Cold negative hovers are cached for the whole document version

Location:
`analysis-engine/languages/lsp/src/main/kotlin/dev/cint/lsp/KotlinLspOracle.kt:73-99, 118-128`;
`analysis-engine/languages/lsp/src/main/kotlin/dev/cint/lsp/DocumentTypeCache.kt`.

Problem: `open()` sends `didOpen` and `typeAt` immediately hovers, with no
readiness barrier. If the server answers with an empty hover while still
indexing, `cache.put(key, null)` pins that transient negative to the document
version. Every later analysis of the unchanged document then reuses `null`,
whereas the pre-044 code re-queried and could recover once indexing completed.
This makes "detection output unchanged when the oracle is healthy" fragile on
the live path: a cold first analysis can permanently downgrade type-established
SEARCH/LOOKUP until the user edits the file.

Suggested fix: do not cache a negative from a document version until at least
one non-null hover has been observed for it (or until a readiness/diagnostics
signal), or revalidate cold negatives on the next analysis. Pin this with an
LSP-level test (fake server) that returns null while cold and a type once ready.

### 4. LSP cache wiring/invalidation and cross-analysis reuse are unpinned

Location:
`analysis-engine/languages/lsp/src/test/kotlin/dev/cint/lsp/DocumentTypeCacheTest.kt:12-67`;
`analysis-engine/languages/lsp/src/test/kotlin/dev/cint/lsp/LspOracleTest.kt:7-40`.

Problem: the tests exercise `DocumentTypeCache` and `OracleSession`
independently. Nothing tests `LspOracle.open()`'s version bump,
`didClose`/`didOpen` invalidation, or that a second analysis of unchanged text
actually reuses the LSP cache. The golden `pipeline-current.json` and all
fixture snapshots run with `NullOracle`, so they cannot distinguish healthy
cache behavior from no caching at all. The ticket explicitly asked for
"cache invalidation on text/changedtick" and "repeated analyses of an unchanged
document hit the oracle cache"; `m016` is live/manual and cannot substitute for
these deterministic checks.

Suggested fix: add an internal seam or fake `LanguageServer` test for `open()`:
unchanged text does not re-open and the second analysis gets cache hits; changed
text bumps the version, drops the old URI entries and re-hovers; another
document's entries survive.

### 5. Budget/latency coverage is a call-count proxy, not the requested latency comparison, and misses established-evidence preservation

Location:
`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/dataaccess/DataAccessOracleBudgetTest.kt:107-161`.

Problem: `timeoutBudgetDegradesToCandidatesWithoutQueryingEveryCall` only
asserts two delegate calls, and `elapsedBudgetBoundsManyUnresolvedReceivers`
uses a fake clock. Neither measures or compares latency, and neither uses a
fixture with many unresolved receivers (the code is generated inline). More
importantly, neither pins the fidelity property that matters: after the circuit
trips, text/config-established classifications (known maps, qualified
repositories, enum-name suffixes) must still be established. The timeout test
only checks that some candidate survives, so a regression that let the budget
suppress known-map or qualifier evidence would still pass.

Suggested fix: add a fixture with many unresolved receivers and assert the
delegate query count is bounded *and* that the annotations for pre-established
calls (e.g. `statusById.getValue`, `repository.findAll`, `Status.valueOf`) are
unchanged when the session is already tripped. Either add a real wall-clock
bound with a delaying oracle or explicitly document call-count as the
deterministic proxy and leave wall-clock to `m016`.

## Summary

The core idea — per-rule `AnalysisContext` memoization plus a per-analysis
`OracleSession`/LSP document-version cache and a circuit breaker — is
reasonable, and the golden snapshots and NullOracle pack tests remain unchanged
because none of them exercise the healthy-oracle path. However, the new
`isTypeQueryable` text filter is broader than "implausible queries": it drops
type-evidenced SEARCH/LOOKUP and even whole enum-LOOKUP detections for
call/index receivers that `receiverOffset` was designed to type. The session
also fails to memoize timeout/failure outcomes, so duplicate hovers and repeated
timeout spend survive the change, and cold negative hovers can become sticky for
a document version. Finally, the LSP open/version wiring and the
latency/bounded-scaling claim are not actually pinned by deterministic tests,
and budget degradation never asserts preservation of text/config-established
classifications.

## Concurs / dissents

Dissents — the findings above are work for the implementor.

## Round 2

### Verdict

Dissents — findings 1, 2, 4 and 5 are resolved; finding 3 is addressed for the
reported premature-negative case, but the warm-up heuristic leaves one
unresolved cache gap (new finding below).

### Status of round-1 findings

1. **Resolved.** `ReceiverShape.isTypeQueryableReceiver` now gates on AST kind
   fragments, rejecting literals/binary/lambda while allowing call, index,
   navigation and member receivers. `DataAccessTypeTest.chainedCallReceiversKeepTypedSearchLookupAndEnumLookup`
   pins SEARCH, LOOKUP and enum LOOKUP for chained receivers.
2. **Resolved.** `OracleSession` stores `Outcome.Failed` per key and
   `LspOracle` stores `DocumentTypeEntry.Failure` per version;
   `duplicateTimeoutsAreMemoizedPerOffset`, `aSecondAnalysisReusesTheCachedFailure`
   and `ServiceBudgetTest` pin the duplicate-hover and repeated-timeout paths.
   The redundant `typeAt(receiver)` retry was removed from the service pack.
3. **Addressed for the reported case; residual see new finding.** A cold empty
   hover no longer pins a durable negative until the version has warmed, and
   `LspOracleCacheWiringTest.coldEmptyHoverIsNotPinnedForTheVersion` covers it.
4. **Resolved.** `LspOracleCacheWiringTest` exercises `open()` versioning,
   unchanged-text reuse, per-URI invalidation, cached failure and concurrent
   text churn through a fake server; the LSP constructor is now internal for
   that seam.
5. **Resolved.** The budget tests now use the `many-unresolved.kt.txt` fixture,
   assert bounded delegate calls, and assert known-map/name-suffix/qualifier
   classifications survive a tripped budget. Wall-clock remains the manual
   `m016` check and the deterministic call-count proxy is documented in
   `manual-test.md`/`engine.md`.

### New finding

**Negative results are never cached for versions whose hovers all return empty**

Location:
`analysis-engine/languages/lsp/src/main/kotlin/dev/cint/lsp/KotlinLspOracle.kt:99-105`
(the `warmed[uri] = version` / `warmed[uri] == version` condition) and the reset
at `KotlinLspOracle.kt:148-153`.

Problem: `warmed` is set only when a hover returns a non-null type. The ticket's
own target fixture (`analysis-engine/tests/rulepacks-tests/src/test/resources/fixtures/many-unresolved.kt.txt`)
queries only undeclared receivers (`unknownN.get`); its text/config-established
calls short-circuit before the oracle. If a real server returns empty hovers for
those offsets (rather than timing out), no hover ever warms the version, so
`DocumentTypeEntry.Value(null)` is never stored. Every analysis of that
unchanged document therefore re-issues the same empty hovers (bounded per
analysis by the budget, but never served from the document cache), so the
acceptance "Repeated analyses of an unchanged document hit the oracle cache" is
still unmet for exactly the many-unresolved case this ticket targets. The
inverse also holds: a single early positive is not a reliable readiness signal;
it can warm the version while other symbols are still unresolved and pin their
cold nulls, which is a narrower form of the original finding 3.

Suggested fix: base negative-cache readiness on an actual server signal (for
example the first `publishDiagnostics`/index-complete notification or a
`didOpen` analysis barrier) rather than the first positive hover, or allow
negatives to be cached but revalidate them on the next analysis until a
documented warm condition (with a bounded revalidation count). Add a fake-server
test where every hover is empty and assert the second analysis hits the cache
(or follows the documented revalidation), plus a partial-warm case that must
not pin an unresolved offset prematurely.

### Summary

Round 2 resolves the substantive detection-fidelity regressions: the AST-shape
gate restores typed SEARCH/LOOKUP/enum detection for call/index/navigation
receivers, failure/timeout outcomes are memoized within the session and the LSP
document cache, the service fallback no longer re-queries on timeout, and the
budget tests now use a many-receiver fixture and prove that text/config-
established classifications survive a tripped circuit. The remaining issue is
narrow but directly on the ticket's acceptance surface: using the first positive
hover as the readiness signal means an unchanged document with only empty
hovers never caches negatives and still re-hovers on every analysis, while a
partially warm document can still pin cold nulls. That should be fixed with an
explicit readiness or bounded-revalidation rule and pinned by a fake-server
test.

### Concurs / dissents

Dissents — the new finding above is work for the implementor.

## Round 3

### Verdict

Concurs. The Round 2 residual is resolved and no new detection-fidelity finding
remains.

### Status of the Round 2 residual

**Resolved.** Negative (empty-hover) entries are now cached per document version
with a bounded TTL instead of requiring a first positive hover to "warm" the
version (`KotlinLspOracle.kt:89-99, 109`; `DocumentTypeCache.kt`). All-null
documents therefore serve repeated queries from the cache immediately and
re-hover only after the TTL, so the `many-unresolved` case no longer re-queries
on every analysis; a partially indexed document can no longer pin a cold null
for the whole version because every negative expires. The policy is pinned by
`coldEmptyHoverIsReusedWithinTheNegativeTtlAndRevalidatedAfterIt` and
`manyUnresolvedNegativesStayBoundedWithinTheTtlAndRevalidateAfterIt`, and the
positive-durable/negative-TTL failure-retry split is stated in `engine.md`,
`intent-model.md` and `m016`. The narrower `this`/`super`/parenthesized/cast/as
receiver gap was also closed in `ReceiverShape.kt` and pinned by
`thisSuperParenthesizedAndCastReceiversKeepTypedDetection`.

### New / unresolved findings

None. `DocumentTypeCache` now distinguishes only value entries, the TTL path
removes stale negatives before re-hovering, positives remain version-durable,
and failures stay session-scoped as documented. The receiver allowlist restores
the previously gated shapes without loosening literal/binary/lambda filtering.
No classification regression was found in the healthy-oracle path.

### Summary

Round 3 closes the Round 2 residual: the warm-on-first-positive heuristic has
been replaced by a bounded per-version negative TTL, so unchanged all-null
documents now use the cache on immediate repeat analyses and revalidate after
the TTL, while positives remain durable and transient failures are retried
rather than pinned. The TTL behavior and the many-receiver case are covered by
deterministic fake-server tests, the receiver allowlist now includes
`this`/`super`/parenthesized/cast/as shapes backed by typed tests, and the
policy is documented. No new detection-fidelity issue was found; the remaining
wall-clock latency check stays with manual `m016`.

### Concurs / dissents

Concurs — zero findings remain for this review dimension.
