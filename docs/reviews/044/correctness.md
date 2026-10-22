# Correctness review — ticket 044 (analysis oracle caching and budgeting)

## Round 1

Verdict: Not ready. The core mechanism is mostly sound — per-analysis receiver
memoization, negative memoization, the bounded per-document-version LSP cache,
the tripping budget, and the stats plumbing all exist and behave sensibly in the
normal single-analysis flow. The remaining correctness work is in the scoping
and accounting of the new cache, thread safety of the LSP wrapper, budget
consumption by the still-ungated Service receiver path (plus per-region split
budgets), and tests that do not exercise session reuse, delegate-cache
accounting, or LSP-level versioning. Read-only review of the uncommitted working
tree on branch `ticket/044-oracle-caching`; no build, tests, formatter, or source
edit was run. I also read `docs/reviews/044/detection-fidelity.md`; where its
findings overlap I concur below rather than re-report them as new findings.

### Findings

1. **`OracleSession`'s cache key omits the document version, and the engine
   reuses caller-supplied sessions.**

   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/facts/oracle/Oracle.kt:69-74,88`
   keys entries by `(file.path, byteOffset)` only, and
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/scheduler/RuleEngine.kt:79`
   reuses an incoming `OracleSession` instead of wrapping it in a fresh one.

   Today the production hosts pass a raw `LspOracle`, so the engine creates a
   fresh session per `analyze` and the key is safe. But `OracleSession` is
   public and `RuleEngine.analyze` explicitly accepts one, so a host that reuses
   a session for successive versions of the same path (or for split regions that
   share `host.path`) will receive the first version's positive *and negative*
   types for the new text. The session's counters and budget also carry over,
   which contradicts the ticket's "scoped to the analysis/file version" premise.
   The same hazard applies to `AnalysisContext.receiverTypes` if a context were
   ever reused, though contexts are currently per rule.

   Fix: include a file content hash or per-analysis generation in the session
   key, or make `RuleEngine` always create a fresh session and expose metrics
   separately. Add a test that reuses one session for two different texts at the
   same path/offset and asserts a fresh query/updated result.

2. **Per-analysis stats mix session and delegate scopes, so the counters do not
   add up.**

   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/facts/oracle/Oracle.kt:97-129`
   increments `queries` for every session cache miss, then
   `oracleStats()` adds the delegate's `cacheHits` delta, while
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:1108-1115`
   prints the result as `queries`/`cacheHits`.

   A session miss that is served by `LspOracle`'s own cache counts as both a
   `query` and a `cacheHit`; a session hit counts only as a `cacheHit`. With N
   lookups of an unchanged document that LSP already has cached, the log reports
   `queries=N cacheHits=N` for zero actual hovers, and a mixed run can report a
   hit rate above 100%. Delegate `queries`/`timeouts`/`failures` are not folded
   in (only `cacheHits` is), so the reported numbers are neither hover counts nor
   a coherent cache hit rate. Additionally, because `NullOracle` is also wrapped
   in an `OracleSession`, heuristic/no-LSP analyses emit `cint oracle:` lines
   with `queries > 0`, which misrepresents oracle activity.

   Fix: define one accounting model (for example `queries` = actual delegate
   hover calls, `cacheHits` = lookups served from either cache, with a separate
   `sessionHits`/`delegateHits` breakdown), take deltas for all delegate
   counters, and skip/suppress the diagnostic when the delegate is `NullOracle`.
   Add a metrics test whose delegate reports its own cache hit and assert the
   buckets do not double-count one lookup.

3. **`LspOracle`'s cache is synchronized but its open/version/cache-hit path is
   not.**

   `analysis-engine/languages/lsp/src/main/kotlin/dev/cint/lsp/KotlinLspOracle.kt:66-82,113-131`
   keeps `opened` and `versions` in plain `mutableMapOf`s, and `typeAt`/`open`
   are unsynchronized; the new cache's `contains` and `get`
   (`analysis-engine/languages/lsp/src/main/kotlin/dev/cint/lsp/DocumentTypeCache.kt:23-27`)
   are individually synchronized but the pair is not atomic.

   `LspOracle` is a shared per-language object in `LiveServer`. The current live
   publish path serializes analysis under `synchronized(this)`, so this is latent
   today, but `analyzeForTest`, other hosts, or a future concurrent analysis path
   can call it from multiple threads. Two concurrent `open` calls can compute
   different versions for the same URI and lose `didOpen` state, and a thread can
   build a key with another thread's version while hovering the text it opened,
   storing the wrong type under that version. A concurrent `invalidate` between
   `contains` and `get` returns `null`, which `OracleSession` then memoizes as a
   genuine negative for the rest of the analysis.

   Fix: synchronize the whole open/version/cache-decision sequence (or use
   `ConcurrentHashMap` plus a per-URI lock/atomic version), and add an atomic
   lookup that distinguishes "absent" from "stored null". Add a concurrency test
   with two texts for the same URI.

4. **The budget can still be consumed by ungated Service receivers, and a split
   document gets one budget per region.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:599-617`
   still reaches `view.receiverType(call) ?: view.typeAt(receiver)` for every
   call that does not match the cheap callee/path/qualifier/service-name/suffix
   evidence. This includes the index/call/safe-call receivers that DataAccess now
   skips, and `ServiceDelegationRule` visits every function, so a first function
   with many unrelated unresolved receivers can trip the shared session budget
   before a later service call that genuinely needs typing. That later call then
   degrades for reasons unrelated to its own evidence. Separately,
   `analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnalyzer.kt:54-55,85-103`
   creates a new `RuleEngine()` per region, so a split document's analysis gets a
   fresh 1500 ms budget per region instead of one per document. There is no live
   LSP cost today because Vue regions are JavaScript/TypeScript with
   `NullOracle`, but the per-analysis budget semantics break as soon as an
   LSP-backed split language is added.

   Fix: apply the same cheap shape/evidence gate before querying in
   `delegationEvidence` (or give Service its own budget), and thread one
   budget/session through split regions, or explicitly document per-region
   budgets. Add a test with many unrelated receivers followed by a typed service
   call to prove the budget does not starve the latter.

5. **Tests do not pin session lifecycle/stats, and one version test is
   tautological.**

   `analysis-engine/languages/lsp/src/test/kotlin/dev/cint/lsp/DocumentTypeCacheTest.kt:40-50`
   constructs the version-2 key itself and asserts it is absent; it would pass
   even if no production code ever bumped a version.
   `analysis-engine/core/core/src/test/kotlin/dev/cint/core/runtime/engine/RuleEngineOracleSessionTest.kt:35-76`
   only exercises a fresh session within one analysis, not a reused session
   across versions or files. No test runs `oracleStats()` with a delegate that
   serves from its own cache, so finding 2 cannot fail. `docs/manual-test.md:23`
   claims positive/negative LSP caching and document-version invalidation are
   automated, but only the standalone cache class is tested; `docs/engine.md:440-441`
   also says entries are dropped on `didOpen`/`didChange`, while the
   implementation drops them lazily when a later `typeAt` sees changed text and
   uses `didClose`+`didOpen`.

   Fix: add a session-reuse test, a delegate-metrics test, and (as
   detection-fidelity finding 4 requests) an `LspOracle.open`/version test or an
   extracted testable coordinator. Correct the manual-test and engine-doc
   wording to match what is actually deterministic.

### Concurrence with the detection-fidelity report

- Concur with detection-fidelity finding 1: `isTypeQueryable` is a text-shape
  gate, not a cheap callee/config gate, and it drops type-evidenced SEARCH,
  LOOKUP, and enum-LOOKUP for call/index/chained receivers that
  `receiverOffset` was designed to type. Detection is changed even with a healthy
  oracle and an untripped budget; the new tests only pin the index-receiver
  downgrade.
- Concur with detection-fidelity finding 2: timeout/failure outcomes are not
  memoized by `OracleSession`, and `ServiceRulePack`'s
  `receiverType(call) ?: typeAt(receiver)` retries the same offset, so duplicate
  hovers still occur within an analysis. This also amplifies finding 4 above.
- Concur with detection-fidelity finding 3: a cold/partially indexed LSP can
  return an empty hover that is then cached as a version-scoped negative, so a
  transient miss becomes sticky for an unchanged document.
- Concur with detection-fidelity finding 5: the budget tests use a fake clock
  and call counts and do not assert that already text/config-established
  classifications survive after the circuit trips.
- No independent finding on cache-key collisions within a single analysis:
  `path`+`offset` identifies one receiver per analyzed file, and the multi-file
  case is safe because each analysis gets its own session. Collisions require
  session reuse or split regions sharing a path, which finding 1 covers.

## Summary

The core design is mostly correct: `AnalysisContext` and `OracleSession` dedupe
within an analysis, the LRU key includes `uri`/`version`/`offset`, text-change
invalidation is right for the normal per-analysis flow, the budget returns
`null` rather than throwing, the DataAccess/Service short-circuits preserve the
original boolean outcomes, and stats flow through `AnalysisOutput` and the live
stderr path. What remains is correctness work around the session's lifecycle
and accounting: the cache key trusts per-analysis scoping that the public
`RuleEngine` API does not enforce, the counters conflate session and LSP scopes
(and log `NullOracle` lookups), the shared `LspOracle` open/version path is not
thread-safe, the Service receiver path can still spend the shared budget on
unrelated calls (with one budget per split region), and the tests do not pin
session reuse, delegate-cache stats, or LSP-level versioning. The
detection-fidelity concerns about healthy-oracle gating, unmemoized failures,
sticky cold negatives, and budget-fidelity coverage are also unresolved; I
concur with them.

## Concurs / Dissents

- Concurs: negative memoization, the bounded LRU, per-document-version keys, and
  text-change invalidation are implemented coherently for the normal
  per-analysis flow; `OracleSession` correctly distinguishes a cached negative
  from a miss and does not cache thrown timeouts/failures.
- Concurs: the budget defaults (2 timeouts / 3 failures / 1500 ms), the
  pre-query trip check, serving existing cache entries after a trip, and
  restoring the interrupt flag on `InterruptedException` all behave as intended.
- Concurs: DataAccess's `knownReceiver`/`enumReceiver` reordering preserves the
  original boolean result while skipping unnecessary hover queries; Service's
  `initCallEvidence` early return is semantically equivalent.
- Concurs: stats aggregate across split regions and reach the live server's
  stderr diagnostics without logging prompts or keys.
- Dissents: findings 1–5 are correctness, accounting, concurrency, and coverage
  work; the ticket should not close until they are fixed or explicitly deferred,
  in addition to the overlapping detection-fidelity findings.

## Round 2

Verdict: Not ready, but the round-1 findings are resolved. The session key and
per-analysis reset, the explicit stats model with delegate deltas, the
synchronized LSP cache path, the Service/DataAccess shape gate, the split-budget
documentation, and the new tests all address the previous work. Two new issues
remain: a transient cold LSP failure is cached for the whole document version
(bypassing the new cold-negative gating), and the new AST receiver allowlist
drops `this`/`super` (and parenthesized/cast) receivers that the previous text
gate accepted. Read-only review of the uncommitted working tree on branch
`ticket/044-oracle-caching`; no build, tests, formatter, or source edit was run.

### Finding status

1. **Session key/version reuse — resolved.** `OracleSession.Key` is now
   `(path, content hash, offset)`, `beginAnalysis` resets cache/counters/budget
   for a new identity, and `typeAt` also resets when the file text changes.
   `OracleSessionTest.reusingASessionWithNewTextResetsTheAnalysisAndRefreshesTheType`
   pins the refresh. `RuleEngine` calls `beginAnalysis(file)` for every analysis,
   so the public reuse path is now safe for sequential analyses.
2. **Stats accounting — resolved.** `OracleStats` now exposes `requests`,
   `hoverCalls`, `sessionHits`, `delegateHits`, `timeouts`, `failures`, `blocked`
   and elapsed nanos; `OracleSession` folds delegate deltas and the live server
   logs the explicit fields. `RuleEngine` skips wrapping `NullOracle`, so
   heuristic analyses emit no oracle diagnostic. The delegate-folding test pins
   that one logical lookup is not counted in both the query and hit buckets.
3. **LspOracle concurrency — resolved.** The whole `typeAt`/`open` path is
   `@Synchronized`, the cache lookup is atomic and distinguishes absence from a
   stored negative/failure, and `DocumentTypeCache` no longer splits
   `contains`/`get`. `LspOracleCacheWiringTest.concurrentTextsForTheSameUriRemainConsistent`
   exercises concurrent texts for one URI.
4. **Service budget/split budgets — resolved as scoped.** Service delegation and
   init now use the AST receiver gate before type queries, and
   `ServiceBudgetTest` pins that non-typeable receivers do not consume the
   budget before a later typed service call. Split budgets are documented as
   per-region. The shape gate itself has a new regression below.
5. **Test coverage/docs — resolved.** Session reuse, delegate metrics, fake-server
   cache/version/failure wiring, concurrency, cold-negative handling, budget
   degradation and preservation of known-map/qualifier evidence now have
   deterministic tests; `m016` and the engine/intent docs were updated. The
   standalone version unit test is still somewhat tautological, but the
   `LspOracle` wiring test now pins the actual version bump.

### New findings

1. **A transient cold failure is cached for the whole document version, unlike a
   cold empty hover.**

   `analysis-engine/languages/lsp/src/main/kotlin/dev/cint/lsp/KotlinLspOracle.kt:100-111`.

   The `type != null` path warms the URI, and the `type == null` path is cached
   only when `warmed[uri] == version`; however, the `catch` block unconditionally
   stores `DocumentTypeEntry.Failure` for any exception, including a timeout on
   the first hover of a cold/partially indexed project. Every later analysis of
   the unchanged document then receives the stored failure as a normal `null`
   (counted as a delegate cache hit, not as a timeout/failure). A one-off
   cold-start timeout therefore suppresses type evidence for that offset until
   the user edits the file, even after the LSP has recovered, and the per-analysis
   budget reset no longer gives the next analysis a retry. This is the same
   sticky-transient problem the `warmed` check solves for empty hovers, applied
   to the failure path in reverse.

   Fix: apply the cold check to failures too (cache a failure only after the
   version is warmed, or retry cold failures in the next analysis), or give
   failure entries a short TTL/generation. Keep timeout/failure entries
   distinguishable so cached failures are not reported as ordinary successful
   cache hits. The existing `aSecondAnalysisReusesTheCachedFailure` test should be
   extended with a recovery case (fail cold, then succeed and assert a re-hover
   and updated type) rather than pinning only the sticky behavior.

2. **The new AST receiver allowlist regresses `this`/`super` receivers and still
   drops parenthesized/cast receivers.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/ReceiverShape.kt:5-56`,
   used from
   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:132,158,181,199`
   and
   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:442,616`.

   The Kotlin parser exposes `this_expression` and `super_expression` (and
   `parenthesized_expression`/`as_expression`), none of which contain a
   `TYPEABLE_RECEIVER_FRAGMENTS` substring. The round-1 text gate accepted
   `this`/`super` because their text is a plain identifier, so this is a new
   regression: `this.findAll()` or `super.find()` no longer consults the oracle
   for SEARCH/LOOKUP/enum or SERVICE_DELEGATION, even with a healthy oracle and
   an untripped budget. Parenthesized and cast receivers (`(repo).findAll()`,
   `(repo as Repository).findAll()`) remain unsupported as well. The new
   chained-call test only covers call/index/navigation receivers.

   Fix: add `this`/`super` (and preferably parenthesized/cast) shapes to the
   allowlist, or switch to a denylist of clearly non-typeable expressions so
   unknown/compound receivers are still eligible. Add healthy-oracle regression
   tests for bare `this`, `super`, and a cast/parenthesized receiver across
   SEARCH, LOOKUP and SERVICE_DELEGATION, or explicitly pin the downgrade if it
   is deliberate.

### Concurs / Dissents

- Concurs: the round-1 findings are substantively addressed, and the new stats,
  session-reset, LSP synchronization and budget-preservation tests are
  meaningful rather than vacuous.
- Concurs: the `warmed` cold-negative path, failure-entry type separation,
  atomic cache lookup, delegate-delta stats and NullOracle suppression behave as
  described for the normal flow.
- Dissents: the two new findings are real correctness/behavior gaps — a cold
  timeout can permanently poison an offset for an unchanged document, and the
  round-2 shape gate drops `this`/`super` receivers that the round-1 gate
  accepted. The ticket should not close until these are fixed or explicitly
  deferred.

### Summary

Round 2 resolves all five round-1 correctness findings: session reuse is
version-aware and reset per analysis, stats are coherently separated and no
longer report NullOracle activity, the shared LSP oracle serializes its
open/version/lookup/hover path with atomic cache access, Service uses the shape
gate before type queries, split budgets are documented, and the added tests
cover the previously unpinned behavior. Two new issues remain. The LSP failure
cache is not cold-gated, so a transient first-hover timeout becomes a durable
version-scoped negative and is reported as a cache hit instead of a failure;
and the new AST allowlist rejects `this_expression`/`super_expression` (plus
parenthesized/cast receivers), regressing receivers the previous text gate
accepted and losing healthy-oracle typed detection. Both need a small code and
test follow-up before this review dimension can close.

## Round 3

Verdict: Concur. Both Round 2 findings are resolved, the TTL/failure/stat
behavior is internally consistent, and I found no new correctness issue.
Read-only review of the uncommitted working tree on branch
`ticket/044-oracle-caching`; no build, tests, formatter, or source edit was run.

### Round 2 finding status

1. **Cold failure caching — resolved.** `LspOracle.typeAt` no longer stores
   exceptions in `DocumentTypeCache`; only positive and empty-hover entries are
   stored, and an empty hover carries a `cachedAtNanos` timestamp. Positives are
   always durable (`entry.fact != null`), while negatives are reused only within
   `negativeTtlNanos` and removed/re-hovered after the TTL. Timeouts/failures
   increment the LSP counters and are rethrown, so `OracleSession` memoizes a
   failure only within one analysis and a later analysis retries it.
   `LspOracleCacheWiringTest.aTimeoutIsMemoizedWithinAnAnalysisAndRetriedInALaterAnalysis`
   pins the within-analysis memoization and cross-analysis retry, and
   `coldEmptyHoverIsReusedWithinTheNegativeTtlAndRevalidatedAfterIt` plus
   `manyUnresolvedNegativesStayBoundedWithinTheTtlAndRevalidateAfterIt` pin the
   TTL revalidation and bounded negative caching. Revalidated negatives and
   retried failures increment `hoverCalls`, not `delegateHits`, as documented;
   `OracleSession`'s delegate-delta accounting still reports the correct
   per-analysis hover/timeout/failure counters.
2. **AST receiver allowlist — resolved.** `ReceiverShape` now includes
   `this_expression`, `super_expression`, `parenthesized`/`parenthesised`,
   `cast` and `as_expression`. The new
   `DataAccessTypeTest.thisSuperParenthesizedAndCastReceiversKeepTypedDetection`
   exercises SEARCH and LOOKUP for bare `this`, `super`, parenthesized and cast
   receivers, and the shared predicate is the same one Service uses for its type
   fallback. No new detection regression was found in the reviewed paths. A
   Service-specific assertion for those shapes would be additional belt-and-
   braces coverage, but it is not a correctness gap: the code path and predicate
   are shared.

### New findings

None.

### Concurs / Dissents

- Concurs: positive types are durable per document version, nulls are bounded by
  the negative TTL, and version changes still invalidate the whole URI. The TTL
  check removes expired entries before re-hovering, so expired negatives cannot
  be reported as cache hits.
- Concurs: timeout/failure outcomes are not document-version cached; the
  session-only memoization preserves the "no duplicate hovers in one analysis"
  guarantee while allowing later analyses to retry.
- Concurs: the stats model remains coherent with the new behavior — fresh
  positives/negatives count as delegate hits, TTL revalidations and retries count
  as hover calls, and the `requests = hoverCalls + sessionHits + delegateHits +
  blocked` invariant holds for the tested delegates.
- Concurs: the AST allowlist additions match the Kotlin parser node kinds
  (`this_expression`, `super_expression`, `parenthesized_expression`,
  `as_expression`) and the receiver-shape regression is covered by a real
  healthy-oracle test.
- Dissents: none. No further correctness work is required for this review
  dimension; the ticket can close once the remaining review dimensions concur.

### Summary

Round 3 resolves both Round 2 findings. The LSP cache now keeps positive types
durable per document version, caches empty hovers behind a bounded and
constructor-configurable negative TTL with removal and re-hover on expiry, and
leaves timeout/failure outcomes out of the document cache so `OracleSession` can
dedupe them within one analysis while later analyses retry. Those revalidations
and retries are accounted as hover calls rather than delegate hits, and the
positive/negative/failure statistics remain consistent. The receiver-shape
allowlist now accepts `this`/`super`/parenthesized/cast/as receivers, with a
healthy-oracle DataAccess regression test and the same shared predicate used by
Service. I found no new correctness issue and no reason to keep this dimension
open.
