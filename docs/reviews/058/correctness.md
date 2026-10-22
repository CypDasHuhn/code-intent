# Correctness review — ticket 058 (AI expression discovery session)

## Round 1

Verdict: The core loop is directionally correct — units are enumerated in core,
the tool surface is read-only and document-scoped, verdicts are validated before
becoming `CandidateEmit`s, and the server publishes only through the existing
candidate path. Coverage and stale/pass lifecycle handling are not yet sound,
however: a verdict with no explicit decision is counted as graded, the enumerated
"expression" worklist contains non-expression nodes and misses at least one
expression kind, discovery can start against a stale cached analysis, a stale
session keeps running and blocks the new version, and the units/token budgets do
not bound tool results. Not ready to close.

### Findings

1. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/SessionTools.kt:148-173` and `AnnotationSession.kt:195-199` — a verdict entry with no explicit `none`/add decision is counted as graded.**

   `parseVerdicts` accepts any object with a non-blank `unitId`; `none` is only
   true for the literal `none`, and unknown `verdict` values, a missing
   `verdict`, or `{"unitId":"u1","add":[]}` all produce an empty verdict.
   `accept` then adds the unit to `gradedUnits` before checking whether any
   operation exists. This makes "graded 1/1" possible for a unit the model never
   decided, contradicting "silence is not a verdict" and making coverage
   untrustworthy. The tool schema also marks only `unitId` as required, so a
   schema-valid call can trigger this.

   Suggested fix: parse a verdict only when `verdict == "none"` (case-insensitive)
   or at least one add/remove operation was parsed; treat unknown/missing
   `verdict` values and conflicting `none`+add as malformed. In `accept`, only add
   to `gradedUnits` for an explicit verdict, and leave malformed entries
   uncovered. Add a test for `{"unitId":"u0"}` and `{"unitId":"u0","verdict":
   "bogus"}`.

2. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/syntax/ExpressionUnits.kt:9-29,48-69` with `analysis-engine/languages/treesitter/src/main/resources/syntax/*.yaml` — the fallback "expression" enumeration includes non-expressions and excludes some real expressions.**

   No profile defines `expression`/`expressionExcluded`, so every language uses
   `node.kind !in excluded`. The traversal still descends into excluded
   declarations, and the exclusion list contains neither `function_body`,
   `statements`, control bodies, `parameter`, binding/type nodes, nor declaration
   names. As a result function bodies, blocks, parameters, declaration names, and
   type nodes become units the agent may annotate. Conversely `classKinds`
   includes `object_literal`, which is a Kotlin expression, so real expressions
   are omitted. This contradicts the design's "every expression node" worklist
   and lets adds land on non-expression spans.

   Suggested fix: either populate explicit `expression` groups per profile and
   filter to them, or tighten the fallback to skip declaration names, parameters,
   type structure, function bodies, and statement/control containers while
   keeping expression kinds such as `object_literal`. Add negative assertions
   (function body/parameter/declaration name absent) and an `object_literal`
   positive assertion. This is the content-level counterpart of architecture
   finding 5, which only notes that the profile groups are empty.

3. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:97-107,149-160,222-229` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:96-108` — discovery can start with a new document and a stale cached analysis.**

   `handleSuggest` reads `current` from `docs` and `cached` from `analyses`
   independently. `didChange` updates `docs` first and only later stores the new
   `CachedAnalysis` inside `publish`. A suggestion task scheduled in that window
   sees the new text/version but the old `cached.output.annotations`; the
   worklist is built from the new text while duplicate checks and existing
   intent metadata come from the old revision. The post-pass revision check can
   still pass if the new analysis has not been stored yet, publishing candidates
   against the new version with an old `analysisRevision`.

   Suggested fix: require `cached.version == current.version` and
   `cached.textHash == contentHash(current.text)` before starting discovery (or
   capture the document and analysis under the same lock), otherwise return
   `stale`. Add a regression test using `analysisRevisionBarrierForTest` to hold
   `publish` between the `docs` update and the `analyses` update.

4. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:254-260` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:232-236` — a version change does not cancel the in-flight session.**

   `isCancelled` for the session only checks `pass.cancelled` and thread
   interruption; `didChange` does not cancel passes for the key (only
   `clearDocument` on close does). After an edit the old pass keeps issuing
   provider/tool calls up to the full session timeout, emits no progress (the
   callback filters it), is discarded at publish time, and meanwhile keeps
   `inFlightSuggestions` occupied so a `cint/suggest` for the new version
   responds `busy`. That is discard, but not cancellation, and it violates the
   one-pass-per-document-version lifecycle.

   Suggested fix: in `isCancelled` compare the current `docs` version/text with
   the pass snapshot, or cancel/replace the pass on `didChange`. Add a server test
   that edits mid-session and asserts the old pass stops and the new request is
   not blocked.

5. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/SessionTools.kt:39-58,124-133` with `AnnotationSession.kt:48` — the units/token budgets do not bound tool results.**

   `maxUnitsPerRequest` is only used to chunk the initial prompt; `SessionTools`
   holds the whole document and `list_expression_units` defaults to
   `Int.MAX_VALUE` with no batch restriction. `get_annotations` likewise defaults
   to the whole document. One tool call can inject every unit and annotation into
   the next provider request, while `maxTokensPerRequest` only caps the provider's
   output (`max_tokens`), not result/tool-token volume. The locality batching and
   token budget described in the ticket are therefore bypassable.

   Suggested fix: give `SessionTools` the active batch and default
   `list_expression_units` to it (or cap `limit` to `maxUnitsPerRequest`), bound
   `get_annotations`, and account for serialized tool-result bytes/tokens against
   a session budget.

6. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/SuggestionService.kt:116-126` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:288-308` — a skipped discovery reports coverage with no uncovered units.**

   When `allowGlobs` rejects the document, `discover` returns `total = units.size`,
   `graded = 0`, `skipped = true`, and the default empty `uncovered`. The server
   attaches total/graded/uncovered to the `cint/suggest` response (and does not
   forward `skipped`), so the client sees `graded 0/N` with zero uncovered units —
   exactly the silent gap the coverage contract is meant to prevent.

   Suggested fix: either set `uncovered` to all unit ids for a skipped result, or
   make the server surface `skipped: true` and omit the coverage block (or omit
   the session result) so zero-graded never masquerades as full coverage.

7. **`analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/suggest/LiveSuggestionDiscoveryTest.kt:29-81` and `analysis-engine/core/suggest/src/test/kotlin/dev/cint/suggest/session/AnnotationSessionTest.kt:18-296` — the server discovery test covers only the happy path, and the core tests miss the coverage/lifecycle negatives.**

   The server test never exercises stale versions, mid-session cancellation,
   budget exhaustion with partial publication, `allowGlobs` skip, or a provider
   that calls tools without submitting; the core tests never exercise the
   no-decision verdict from finding 1 or the confidence ceiling. The existing
   tests therefore do not catch the main correctness risks above.

   Suggested fix: add server tests for stale discard (no publish, no progress for
   the new version), cancellation (no partial publish), budget exhaustion
   (graded candidates publish plus uncovered), and allowGlobs skip; add core tests
   for a verdict with only `unitId`, an unknown verdict value, and a
   `maxConfidence` cap smaller than the proposed confidence.

### Concurs / dissents

- Concurs with architecture findings 1 (system prompt is dropped), 3 (privacy
  gaps on `lookup_type`/session events), 4 (no protocol-3 gating), 5 (profile
  expression groups unpopulated), 6 (`TempKindsTest` is a debug dump), and 7
  (speculative remove parsing); these are not re-filed here.
- Concurs that AI-disabled behavior is unchanged: discovery is gated by
  `suggestions.enabled`, the legacy enrichment path is intact when
  `discoveryEnabled` is false, and the deterministic pipeline, scheduler,
  composition, and annotation store are untouched. Candidate-only output is
  structurally preserved (`aiEligible = false`, `source = ai:<provider>`).
- Dissents: findings 1-7 above; the stale-analysis race (finding 3) and the
  unbounded tool results (finding 5) go beyond the architecture report's
  protocol/privacy concerns.

## Round 2

Verdict: Most Round 1 behavior is now correct and covered. Findings 1, 3, 6,
and 7 are addressed; finding 4 is addressed for the reported symptoms but the
same fix introduces a new cancellation/cleanup race; findings 2 and 5 are only
partially addressed. Not ready to close.

### Per-finding status

1. **Concur — addressed.** `SessionTools.kt:165-188` now accepts only an
   explicit `verdict == "none"` or at least one parsed add, rejects unknown
   verdict values and `none`+add conflicts, and `AnnotationSession.kt:196-213`
   only marks units from parsed verdicts. `AnnotationSessionTest.kt:212-295`
   covers missing, unknown, conflicting, empty, and explicit verdicts.

2. **Dissent — partially addressed.** The explicit per-language `expression`
   groups, `object_literal`, and non-Kotlin tests are present
   (`ExpressionUnitsTest.kt:32-119`). However `ExpressionUnits.kt:23-31,103`
   skips the entire `parameter`/`parameterList` subtree, so default-value
   expressions are no longer units at all (for example a default
   `Regex("x")` argument). Separately, `ExpressionUnits.kt:33` with
   `Syntax.kt:27-32` still exposes `SyntaxModel.expressions` as a direct
   `isExpression` filter that bypasses `skipExpressionSubtree`, so that API
   still returns declaration names. Fix: skip only the parameter name/type and
   continue into default-value children, and either route `expressions` through
   the same visit or remove the parallel API; add a default-parameter fixture.

3. **Concur — addressed.** `LiveServerSuggestions.kt:102-109` now rejects a
   suggestion whose `cached.version`/`textHash` does not match the current
   document, and `LiveSuggestionDiscoveryTest.kt:130-165` exercises the barrier
   race with no provider call and no AI publish.

4. **Concur — addressed for the reported behavior, with a new regression.**
   `LiveServerSuggestions.kt:266-273` cancels on version/text change and
   `:324-333` stops the in-flight pass on `didChange`;
   `LiveSuggestionDiscoveryTest.kt:167-195` confirms the old request is
   cancelled and the new version is not `busy`. The cleanup race is new finding
   8.

5. **Dissent — partially addressed.** Tool results are now bounded by the
   active batch, result count, and byte size (`SessionTools.kt:44-66,132-150,219,253-254`;
   `AnnotationSession.kt:39,138,340-352`;
   `AnnotationSessionTest.kt:356-424`). The initial batch prompt is still
   unbounded: `AnnotationSession.kt:288-329` embeds each full `sanitize(unit.text)`
   and every existing annotation with no byte/token cap, so one large string
   literal or lambda unit can still bypass the per-request token budget before
   any tool result exists. Fix: bound/summarize unit text and annotation lists in
   `batchPrompt` (the same cap used for tool text), and count prompt bytes
   against the session budget; add a large-unit prompt test.

6. **Concur — addressed.** `SuggestionService.kt:113-124` now populates
   `uncovered` with every unit for a skipped result, `LiveServerSuggestions.kt:319`
   surfaces `skipped`, and `LiveSuggestionDiscoveryTest.kt` asserts `graded = 0`
   plus `uncovered.size == total` for the `allowGlobs` case.

7. **Concur — addressed.** The new tests cover protocol-3 legacy behavior,
   stale-analysis rejection, mid-session edit cancellation, partial publication
   on budget exhaustion, `allowGlobs` skip, confidence capping, sanitized
   `lookup_type`, system-prompt delivery, and absence of provider prose in the
   session log (`AnnotationSessionTest.kt:297-565`,
   `LiveSuggestionDiscoveryTest.kt:72-295`). The only missing lifecycle case is
   the queued-before-start cancellation in new finding 8.

### New findings

8. **`LiveServerSuggestions.kt:324-333` with `:212-216` — cancelling the outer
   suggestion future on `didChange` can drop the request response and can evict
   a newer pass's future.**

   `cancelInFlightSuggestion` removes the pass and then calls
   `inFlightFutures.remove(key)?.cancel(true)`. If the submitted task is still
   queued on the bounded suggestion executor, cancellation prevents its `call`
   from running at all, so the `cint/suggest` request never receives the
   `cancelled` response (that response is only written from inside the task).
   If the task is running, cancellation frees `inFlightSuggestions` immediately,
   so a new version's pass can register a new future before the old task's
   `finally` runs; the old task then calls `inFlightFutures.remove(key)` without
   an identity check (`:214`) and removes the new pass's future. A later edit or
   close can then no longer interrupt that pass through `inFlightFutures`
   (provider-future cancellation may still stop it, but the outer task can stay
   blocked until its session timeout). Cancelling the outer task also risks
   interrupting it while it is writing to the shared socket writer.

   Suggested fix: do not cancel the outer future from `didChange`; set
   `pass.cancelled`, cancel only the provider future, and let the task observe
   `isCancelled` and always write its cancelled response (including an entry
   check before any work). If the outer future must be tracked, remove it with
   `inFlightFutures.remove(key, future)` so an old task cannot evict a new one.
   Add tests for `didChange` while the suggestion task is still queued, and for
   two rapid edits while a replacement pass is running.

## Round 3

Verdict: Finding 8 is resolved and the round-3 delta introduces no new
correctness defect on the server lifecycle, response, pass-identity,
provider-future, executor, or stale/close paths. The `inFlightFutures` map is
gone, cancellation now only marks the pass and cancels the provider future, and a
queued task always gets to write its own cancelled response. Zero open
correctness findings. One test caveat is recorded under Verified: the new
two-edit test would also pass on the prior tree, so the queued-close test is the
one that actually reproduces the old failure.

### Per-finding status

8. **Concur — addressed.** `LiveServer.kt:81-84,135-140` no longer owns or
   cancels outer suggestion futures. `cancelInFlightSuggestion`
   (`LiveServerSuggestions.kt:338-347`) sets `pass.cancelled`, removes the pass
   with `remove(key, pass)`, and cancels only the provider future;
   `clearDocument` (`LiveServerDocument.kt:53-69`) does the same instead of
   cancelling the queued `FutureTask`. The task keeps its entry check
   (`LiveServerSuggestions.kt:101-104`), so a task that never started still
   writes `cancelled`; a task past the entry check is stopped by the cancelled
   provider future and also writes `cancelled` (catch at `:220-225`). Pass
   identity is value-checked on both the task `finally` (`:227`) and the cancel
   path (`:345`), and `providerFutures.compute` (`:160-168`) refuses to register
   an old pass's future, so an old task cannot evict or cancel a newer pass's
   future. Not cancelling the outer task also removes the risk of interrupting a
   response write on the shared writer.

   Tests: `closeWhileSuggestionIsQueuedStillWritesACancelledResponse`
   (`LiveSuggestionDiscoveryTest.kt:232-261`) queues a second document's
   suggestion on a one-thread executor and closes it; on HEAD `clearDocument`
   cancelled the queued `FutureTask`, its `call` never ran, and response 3 was
   never written, so the test times out. It now asserts response 2 accepted and
   response 3 cancelled. `editCancelsAQueuedSuggestionWithoutDroppingItsResponse`
   (`:196-229`, already present) covers the `didChange` queue.
   `twoRapidEditsWhileReplacementPassRunsKeepTheNewPassResponsive`
   (`:263-299`) exercises the two-edit/replacement path and the final pass
   staying responsive, but it would pass on HEAD: HEAD's task `finally` already
   used the two-arg identity removal (`git show HEAD:...:229`) and `didChange`
   did not cancel outer futures, so the eviction half of finding 8 was not
   reproducible in the prior tree. The production conclusion is unaffected
   because provider-future cancellation still stops the pass.

### New findings

None.

### Concurs / dissents

- Concurs with finding 8. The queued-response defect was real in `clearDocument`
  and is fixed; the map removal also makes the eviction concern structurally
  impossible.
- Concurs with the accompanying `ExpressionUnits` delta:
  `annotationKinds`/`modifierKinds`/`typeReferenceKinds` are no longer skipped as
  whole subtrees, type exclusion is now profile-driven
  (`ExpressionUnits.kt:23-26`), and annotation names/types are excluded via
  `isDeclarationName` (`:49-54`) while annotation arguments are enumerated.
- Dissents: none.

### Verified

- Lifecycle/close: a queued task for a closed document still runs its entry
  check and writes `cancelled`; a running task is stopped by
  `providerFutures`/`pass.cancelled`, and no publish happens after cancellation
  (`LiveServerSuggestions.kt:194-216`). `shutdownSuggestions` still marks passes
  and interrupts both executors (`LiveServer.kt:135-140`), so removing the outer
  future map does not leave shutdown unable to stop work.
- Response writing: the only `respond` for a cancelled pass now comes from the
  task itself (entry check, provider-cancel catch, or cancelled-at-publish), so
  responses are no longer dropped by cancelling a queued `FutureTask` and no
  outer interruption can hit `write` mid-message.
- Pass identity: `inFlightSuggestions.remove(key, pass)` and
  `providerFutures.remove(key, providerFuture)` are value-checked; a replacement
  pass cannot be removed or its provider future cancelled by an old task.
- Expression delta: `kotlinAnnotationArgumentsAreUnitsWithoutAnnotationNames`
  fails on HEAD (the annotation subtree was skipped) and passes now;
  `profilesRouteTypeNodesThroughExpressionExcluded` and the two default-parameter
  tests pin the profile-driven type exclusion. The type-group loop is vacuous for
  empty `typeReference` groups, but the full `expressionExcluded` lists keep the
  behavior correct.
- Residual, not filed: named annotation-argument labels
  (`@Deprecated(message = "x")`) are `simple_identifier` children of
  `value_argument`, not of `annotation`, so the new annotation traversal exposes
  `message` as a unit. The same pattern already exists for named call arguments
  and is a pre-existing heuristic limitation rather than a round-3 regression; if
  the worklist is meant to be expression positions only, handle the name side of
  `value_argument` in `isDeclarationName` and add a named-argument fixture.
