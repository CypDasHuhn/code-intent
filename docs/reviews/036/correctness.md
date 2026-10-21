# Correctness review — ticket 036 (AI intent candidates)

## Round 1

Verdict: The core channel is in place and mostly sound. `RuleEmit` splits at the
type level, `RuleEngine` partitions output without putting candidates into the
store/reads/composition, fixpoint rules keep the final iteration's candidates,
and `AnalysisOutput` is threaded through host, split, CLI, HTTP and the live
server. The live suggest/publish/confirm/reject path works at the protocol level,
and the nvim renderer is non-destructive. It is not ready for closure: a
confirmed intent is not repainted by the client, candidate identity collapses
distinct occurrences with identical text, provider output is not validated
against its input, suggestions are handled synchronously with no real in-flight
cancellation, and the new suggest module's tests are outside the CI test recipe.

### Findings

1. **`nvim-renderer/lua/cint/ops/live_ops.lua:284-302` with
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:402-448,680-703`
   — confirming a candidate publishes changed annotations with the same content
   hash and config revision, so the client discards the render and the confirmed
   intent stays invisible.**

   After `cint/candidates/decide` with `confirm`, the server writes the user
   annotation and immediately re-analyzes via `publish`. The file text did not
   change, so `cint/publishTags` carries the same `hash`, and `configRevision`
   did not change, so it carries the same `revision`. `live_on_tags` stores the
   new tags but returns before `render_spans` when
   `previous.hash == params.hash && previous.revision == revision &&
   state.rendered[buf] ~= nil`. The following `publishCandidates` then clears the
   candidate marks because the candidate is now suppressed. The user sees
   neither the old candidate nor the new established annotation until a manual
   render or another text/config change. `LiveSuggestionsTest` only inspects the
   server JSON, so it does not catch this.

   Fix: carry a monotonically increasing per-document analysis/publish revision
   (or a tags payload revision) that changes on every publish, including
   decisions, and use it in `live_on_tags`; alternatively compare the annotation
   payload rather than only source hash + config revision. Add a headless spec
   that confirms a candidate without changing the buffer and asserts the new
   established extmark appears and the candidate mark disappears.

2. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/CandidateIdentity.kt:6-20`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:472-495,537-544`
   — the candidate identity has no positional anchor, so two identical code
   fragments in one file receive the same id; dedupe then drops one and
   suppression hides both.**

   The id is `path + contentHash(selected range) + intents + reason`. Two
   identical guards/calls with the same text, intent and reason (a common case,
   e.g. two `if (true)` gaps under distant validation comments) produce the same
   `candidateId`. `publishCandidates(...).distinctBy { it.candidateId }` keeps
   only the first, so the second is never reviewable. Because
   `SuggestionStore.suppressedIds()` is keyed by the same id, confirming or
   rejecting one occurrence also hides the other. `anchorHash` hashes only the
   selected text, so no occurrence position survives into the key.

   Fix: include a stable occurrence anchor in the identity (for example the
   enclosing symbol plus byte span, or the source span with a content-hash
   fallback), and key suppression to that occurrence. Add a test with two
   identical spans and assert two candidate ids, two independent decisions, and
   independent suppression.

3. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/FakeIntentSuggestionProvider.kt:44-56`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:577-596`
   — the validator does not validate provider output against the input candidate
   or document, and `maxCandidates` is never applied to a list.**

   `CandidateValidator.valid` only checks that intents are non-empty and in the
   known vocabulary, confidence is in range, and `startByte >= 0 &&
   endByte >= startByte`. It has no input-candidate or document-length argument,
   so the documented "span inside the input candidate" rule is unenforced and an
   out-of-bounds span anywhere in the file would pass. `maxCandidates <= 0` is
   checked but the value is otherwise unused, so a provider returning too many
   candidates is not rejected. The server also keeps only
   `.firstOrNull { valid }`, silently discarding alternative candidates. The
   tests only exercise the input candidate, not a fake provider returning
   malformed output.

   Fix: make the validator take the input candidate (and document bounds) and
   validate a returned `List<CandidateEmit>`, enforcing containment, allowed
   intents, confidence, and the maximum count; decide explicitly whether
   alternatives are kept or dropped. Add tests with a stub provider returning an
   out-of-bounds span, unknown intent, too many candidates, and a valid
   alternative.

4. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/FakeIntentSuggestionProvider.kt`
   tests are not run by `justfile:17-18` / `.github/workflows/ci.yml:44`.**

   The new `:suggest` module is included in `settings.gradle.kts` and its tests
   exist, but `test-engine` lists every test task except `:suggest:test`. CI runs
   `just test-engine`, so `FakeIntentSuggestionProviderTest` (identity stability,
   enrichment and basic validation) never runs in the gate the ticket claims is
   green.

   Fix: add `:suggest:test` to the `test-engine` recipe (or switch the recipe to
   a Gradle test selector that includes new modules), and confirm CI executes it.

5. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:498-505,517-535`
   with `nvim-renderer/lua/cint/ops/suggestion_ops.lua:145-171`
   — candidates are published as a flat list, so there is no grouping of
   alternatives and the review flow can confirm multiple competing intents for
   one span.**

   The protocol document describes candidate groups and the renderer UX says the
   user chooses among alternative intents for one span. The server sends one flat
   `candidates` array, each candidate has its own id, and `review` confirms one
   candidate at a time with no awareness of sibling candidates on the same
   anchor. If two packs/AI propose different intents for the same span, both can
   be confirmed, producing one established annotation with both intents. The
   `FakeIntentSuggestionProvider` can already return a list, but the server keeps
   only the first valid item (see finding 3).

   Fix: group candidates by stable anchor/span, keep alternatives inside the
   group, and make the renderer/decide protocol select one alternative per span
   (or explicitly define multi-intent confirmation). Add a server test with two
   candidates on one span and a renderer test that picks one.

6. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:135-153,546-627`
   — suggestion cancellation cannot interrupt an in-flight pass, and there is no
   per-document one-in-flight guard.**

   `handleClient` calls `handleMessage` synchronously on the connection's read
   loop, and `handleSuggest` runs the provider and publish inline. A
   `cint/suggest/cancel` arriving while a suggest is executing cannot be read
   until that suggest has finished, so the epoch/request-id checks only handle a
   cancel that arrives before the request (which is what the test does). There is
   also no `inFlight`/debounce state per document; two clients can run
   simultaneous suggestions for the same URI, and the later write wins by the
   global `suggestionRevision`. This does not match the "async, cancellable,
   debounced, one in-flight request per document" contract.

   Fix: dispatch suggestion work outside the read loop, record the in-flight
   request id/epoch per document, refuse or supersede a second request, and
   re-check cancellation before publishing. Add a test with a blocking provider
   that sends a cancel during the pass and asserts no candidate publish.

7. **`analysis-engine/core/core/src/test/kotlin/dev/cint/core/runtime/engine/RuleEngineCandidateTest.kt`,
   `analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnalyzer.kt`,
   `analysis-engine/core/host/src/test/kotlin/dev/cint/host/HostPipelineTest.kt`,
   `analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/LiveSuggestionsTest.kt`,
   `nvim-renderer/tests/candidates_spec.lua` — the tests do not pin several of
   the contracts this ticket adds.**

   Missing coverage: candidates are not checked for absence from a downstream
   rule's `reads`/`view.annotations`; no test asserts split-region candidate
   spans/bytes are shifted (only annotations are covered by
   `VuePipelineTest`/`SplitUserAnnotationsTest`); no host-pipeline test asserts
   candidates are returned alongside annotations; `LiveSuggestionsTest` does not
   exercise `skip`, a same-version/different-hash decision, or suppression from a
   fresh `SuggestionStore`; `candidates_spec.lua` does not test protocol < 3
   gating, reject/skip actions, the status count, or the post-confirm tags render
   from finding 1; and the duplicate-text identity from finding 2 is untested.

   Fix: add the focused cases above. In particular, a two-identical-span
   candidate test, a split candidate span test and a headless confirm/render test
   would have caught findings 1, 2 and the untested split path.

### Concurs / Dissents

No prior round exists for this ticket. I concur that the v5 API split is
type-level and complete (`Rule.run`/`Matcher.match` return `List<RuleEmit>`,
`Emit`/`CandidateEmit` builders exist, `toContribution` still accepts only
`Emit`, and the version is bumped to 5); that `RuleEngine` partitions
established emits from candidates, validates ownership for both, keeps
candidates out of `AnnotationStore`, composition and the reads graph, and
returns the final settled iteration's candidates for fixpoint rules; that empty
emits still remove a rule from the settled map and do not disturb monotonicity;
that `AnalysisOutput` is carried through `HostPipeline`, `SplitAnalyzer`, CLI,
HTTP and the live server and that candidate spans use the same region shift as
annotations; that confirm writes a real user annotation which is re-analyzed as
established and reject writes a persisted suppression that hides the same id on
the next publish; and that candidate rendering is non-destructive and gated on
protocol 3. I dissent from readiness on findings 1-7, especially the broken
confirm repaint and the identity collision.

### Residual observations (not filed)

- Skip is session-wide (`sessionSkipped`) rather than per suggestion pass, and a
  `confirm` decision remains in `suppressedIds` after the user removes the
  corresponding user annotation, so an occurrence cannot be re-proposed by
  unannotating.
- `.cint/suggestions.json` suppression is consulted only by `LiveServer`; CLI
  and HTTP `/analyze` still publish rejected candidates. Confirm works there via
  the user-annotation file.
- `suggestionCapability()` hardcodes `enabled = true`; there is no
  `.cint/suggestions.yml`/disable switch, so the "AI disabled" path is only the
  normal analysis path, not an explicit mode.
- The renderer exposes `candidate_count`/`status()` but no statusline string
  (e.g. `CodeIntent: 3 unsure`); `:CintLiveTags`/`CintServerStatus` only show the
  number on demand.
- `cancelledSuggestions` grows without bound, and `analyses`/`publishedCandidates`
  are not cleared on `didClose`.
- `docs/` and `docs/manual-test.md` are not updated for the new commands, the
  protocol 3 suggestions surface or a ticket-036 live review gate (the workflow
  expects a manual-test row for the live socket path).
- The worktree was reviewed as an unstaged/untracked handover; no build, test,
  lint or formatter command was run by this role, per `docs/workflow.md`.

## Round 2

Verdict: Round 1 findings 1-4 and 6 are addressed, and finding 7 is addressed
except for the same-version/different-hash decision case. The implementation now
has per-occurrence identity, grouped alternatives with provenance, a real
validator, a config gate, an async suggestion executor with per-document
in-flight cancellation, and tests for reads isolation, split candidates, host
candidates, skip, protocol gating, identical spans and post-confirm repaint.
Finding 5 is only partly resolved: the client can choose one member of a group,
but the server does not reconcile the group after a decision. The round-2 fixes
also introduce two revision-ordering races and one error-path regression, so the
loop cannot close yet.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:457-496`
   with `nvim-renderer/lua/cint/ops/live_ops.lua:286-310` — `analysisRevision` is
   assigned after the analysis/config lock, and the client now compares only
   `analysisRevision`, so an older-config analysis can be labelled with a newer
   revision and overwrite the correct publish.**

   The analysis output and `configRevision` are captured under
   `synchronized(this)`, but `nextAnalysisRevision(key)` runs after the lock is
   released and the tags write happens later still. Interleaving: client A's
   `didChange` analyzes config R0 and releases the lock; client B's config
   mutation reloads R1 and `republishOpenDocs` analyzes the same document with R1,
   taking analysis revision 1 and publishing it; A resumes, takes analysis
   revision 2 and publishes the R0 annotations. `live_on_tags` only rejects a
   payload when `analysis_revision` is lower (line 294); it no longer compares
   `params.revision`, so the R0 annotations with revision 2 are accepted and
   rendered over the R1 result. The same misordering can affect candidates. The
   new `staleAnalysisCannotPublishAfterConfigMutation` test covers the suggestion
   path, not a concurrent tags publish.

   Fix: assign the per-document analysis revision while still holding the lock
   that captures the analysis and `configRevision` (or otherwise make revision
   order match analysis order), and/or have `live_on_tags` reject a payload whose
   `revision` (config revision) is lower than the previous one as well as a lower
   `analysisRevision`. Add a deterministic concurrent didChange/config-mutation
   test that asserts the last rendered tags come from the new config.

2. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:515-566,728-741,806-817`
   — `publishCandidates` has no atomic stale-revision guard, so an async suggest
   or decision can overwrite newer candidate state after its check.**

   `handleSuggest` checks `latest?.analysisRevision != cached.analysisRevision`
   before calling `publishCandidates`, but this is a check-then-act window. A
   confirm/reject publish or a config republish can land between the check and
   `publishedCandidates[key] = published`; the async pass then writes its older
   candidate list and increments `suggestionRevision`. The client may discard the
   stale message because its `analysisRevision` is lower, but the server state is
   now stale. The `skip` branch is worse: it reads `publishedCandidates[key]` and
   republishes it with `analyses[key]?.analysisRevision`, so a stale list can be
   re-sent with a newer analysis revision and be accepted. The same window exists
   for a decision racing a text change/analysis.

   Fix: make candidate publication atomic per document key: take a per-key
   lock (or CAS) that checks the incoming `analysisRevision` against the current
   published/analysis revision before updating `publishedCandidates` and writing,
   and drop the stale pass. Do not let `skip` relabel an old candidate list with a
   newer revision. Add a test that blocks a suggestion, performs a
   confirm/didChange for the same document, releases the suggestion, and asserts
   neither server state nor the published message regresses.

3. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:515-566,781-860`
   with `nvim-renderer/lua/cint/ops/suggestion_ops.lua:182-223` — confirming one
   member of an alternative group does not resolve the group, so the remaining
   alternative can be confirmed later and the span ends up with both intents.**

   The renderer now asks the user to pick one alternative for a group, which
   addresses the UI half of Round 1 finding 5. Server-side, a confirm still only
   records the chosen `candidateId`; `publishCandidates` filters only that id (and
   only while its user annotation exists) and republishes the sibling candidate.
   The user can then confirm the sibling, and `UserAnnotationsFile.add` keeps both
   annotations because they have different intents, producing one established
   annotation with both intents. Repairing the first alternative by removing its
   annotation would re-propose it as well.

   Fix: make a confirm resolve the anchor/group (for example, persist an
   anchor-level decision and suppress the sibling candidates, or explicitly define
   multi-select semantics and remove the "alternative" claim). Add a server test
   that confirms one of two alternatives and asserts the sibling is no longer
   published or confirmable.

4. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:530`
   with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:13-17`
   — candidate publication now reads the user-annotation file without error
   handling, so a malformed file throws after tags are written and stops the
   candidate channel.**

   `publishCandidates` calls `UserAnnotationsFile.load(root)` unconditionally for
   confirm reconciliation. `load` does not catch `Files.readString` or the YAML
   parse failure; the exception escapes `publishCandidates` after `publish` has
   already written `cint/publishTags`. `handleMessage` then reports an error with
   no request id, and every later analysis repeats the failure. `HostPipeline` is
   deliberately tolerant of the same file, so this is a regression on the
   existing user-annotation path. The new re-propose test deletes a valid file,
   so it does not cover corruption.

   Fix: load user annotations through a tolerant helper (log and treat as empty,
   as `HostPipeline.userContributionsFor` does), and avoid the read entirely when
   there are no confirm records. Add a test with malformed
   `.cint/user-annotations.json` that asserts tags/candidates still publish.

5. **`analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/LiveSuggestionsTest.kt`
   — the same-version/different-hash decision case from Round 1 finding 7 is
   still not tested.**

   `skipIsSessionOnlyAndStaleVersionsAreRejected` covers a stale version, and the
   server has a `currentHash != candidate.contentHash` check, but no test sends a
   decision with the current document version and a changed/hash-stale candidate
   span. Add that case (and a test for the revision-ordering races in findings
   1-2) so the stale-decision contract is pinned.

### Concurs / Dissents

- **Finding 1 (confirm repaint):** concurs. Tags now carry a per-document
  `analysisRevision`, `live_on_tags` accepts it, and `candidates_spec.lua` asserts
  the confirmed user annotation is painted without a text change. The remaining
  problem is the revision-assignment race (finding 1 above), not the repaint path
  itself.
- **Finding 2 (per-occurrence identity):** concurs. Identity now includes
  `startByte`/`endByte`, the two-identical-gap server test asserts distinct ids,
  independent rejection and independent suppression, and the nvim grouping test
  uses a stable group anchor.
- **Finding 3 (validator):** concurs. `CandidateValidator.validate` now enforces
  input containment, document bounds, vocabulary, confidence, dedupe and a real
  per-provider-call `maxCandidates`; tests cover out-of-bounds, outside-input,
  unknown intent, low confidence, max count and kept alternatives. The service
  merges AI alternatives instead of keeping only the first.
- **Finding 4 (`:suggest` test gating):** concurs. `justfile` now includes
  `:suggest:test` in `test-engine`, which is what CI runs.
- **Finding 5 (grouping/alternatives):** concurs on the payload (`groups` with
  `anchorId`), provenance fields and renderer alternative picker; dissents on
  server-side group reconciliation, as finding 3 above.
- **Finding 6 (async cancellation/one-in-flight):** concurs. Suggest work runs on
  a per-document executor with an in-flight map, cancel is processed off the read
  loop, the pre-publish check revalidates the document and analysis revision,
  cancellation is pruned, and the blocking-provider and config-race tests cover
  the intended behavior. I found no remaining cancellation-cleanup defect, though
  the executor can recreate an empty per-writer `docs` entry after disconnect.
- **Finding 7 (missing tests):** concurs for reads isolation, split candidate
  spans/bytes, host-pipeline candidates, skip/session-only behavior, fresh-server
  suppression, protocol < 3 gating, status text, confirm repaint and identical
  spans. Dissents only on the same-version/different-hash decision test (finding 5
  above).

**Loop status:** the loop cannot close yet. Findings 1-4 are implementation
defects/regressions and finding 5 is a remaining pin; another round should verify
the revision ordering and group reconciliation fixes.

### Residual observations (not filed)

- The suggestion executor may resurrect a `docs` entry for a disconnected writer
  after `clearDocument` removed it; the write then fails in the worker. Cleaning
  up an owner's keys and marking cancelled before removing the writer, or having
  the worker bail when the writer is no longer present, would avoid the leak.
- Confirm/reject records remain audit history, but group-level decisions are not
  persisted; see finding 3.
- The config gate is read once at `LiveServer` construction, so editing
  `.cint/suggestions.yml` requires a restart (consistent with the manual-test
  row, but not live-reloaded).

## Round 3

Verdict: Round 2 findings 1-5 are addressed. Analysis revisions are now assigned
under the same lock as the analysis/config snapshot, tag and candidate writes
are version-gated, candidate publication uses a per-document compare-and-set,
confirm resolves the candidate group (including a test that the sibling cannot
later be confirmed), malformed user annotations are tolerated, the
same-version/different-hash decision is tested, and the executor/provider pools
are bounded with a timeout. Two correctness issues remain: a stale `skip` still
mutates session suppression even though the response says stale, and concurrent
confirms do an unsynchronized read-modify-write of the user-annotation file.
The loop cannot close until those are fixed.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:926-940`
   — a stale `skip` is applied to session suppression even though the decision
   is reported as stale and no candidate list is published.**

   The skip branch adds `candidateId` to `sessionSkipped` before calling
   `publishCandidates`. If the compare-and-set fails because the candidate state
   changed (a newer suggest/decision publish landed after
   `baseStateRevision` was captured), `publishCandidates` returns `null` and the
   handler responds `accepted=false, stale=true`. The candidate is nevertheless
   now in `sessionSkipped`, so every later candidate publication hides it even
   though the user was told the skip did not apply. The skip is effectively
   committed on a stale decision, and no test exercises this path.

   Fix: apply the session skip only after `publishCandidates` succeeds, or roll
   the `sessionSkipped` entry back when it returns `null` (and on the stale
   version/hash exits). Add a test that forces the expected-state mismatch
   between the skip capture and publish and asserts the response is stale and
   the candidate remains visible afterwards.

2. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:28-41`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:963-990`
   — concurrent confirms perform an unsynchronized load-modify-save of
   `.cint/user-annotations.json`, so one confirmation can overwrite another.**

   `handleCandidateDecision` runs on each connection's thread, and the live
   server explicitly supports independent per-connection candidate state. Two
   clients (or one client and the nvim annotation writer) can confirm different
   candidates at the same time. Each `UserAnnotationsFile.add` loads the file,
   appends its record, and writes the whole list; the later writer overwrites the
   earlier one. The server records both decisions in `suggestions.json`, so the
   lost annotation then makes the confirmed candidate re-propose or leaves the
   group unresolved. `SuggestionStore.record` is synchronized, but the
   annotation write is not, and `add`/`save` are separate read-modify-write
   operations.

   Fix: serialize annotation writes (a per-root/server lock around `add`, or a
   file lock for cross-process writers) and, ideally, write from the freshly
   loaded list under that lock. Add a two-client concurrent-confirm test (or a
   direct concurrent `add` test) asserting both annotations survive and both
   groups resolve.

### Concurs / Dissents

- **Round 2 finding 1 (revision ordering):** concurs. `publish` now computes the
  analysis, snapshots `configRevision`, and takes `nextAnalysisRevision` inside
  the same `synchronized(this)` block, so revision order matches analysis order.
  The barrier test and the concurrent didChange/config test both pin the
  post-mutation result at the highest revision; the client's analysis-revision
  gate now discards reordered older writes.
- **Round 2 finding 2 (candidate compare-and-set):** concurs on the CAS and the
  async overwrite race. `publishCandidates` locks per document and rejects lower
  analysis revisions, old published revisions and unexpected state revisions;
  the async test asserts a released suggestion cannot overwrite the newer
  analysis. Dissents only on the stale-skip rollback in finding 1 above.
- **Round 2 finding 3 (group resolution):** concurs. Confirm records carry the
  anchor and span, `publishCandidates` resolves the anchor only while the user
  annotation still exists, the alternative test confirms one member and asserts
  the sibling is missing, and removing the annotation makes the group
  re-proposable. The implementation path is correct; the multi-candidate
  re-proposal case is not separately tested.
- **Round 2 finding 4 (tolerant malformed user annotations):** concurs.
  `loadTolerant` is used for candidate publication and `add`, and the malformed
  file test verifies publication and confirmation continue. Residual: `add` now
  silently overwrites the malformed file with only the new record rather than
  preserving a backup.
- **Round 2 finding 5 (same-version/different-hash decision):** concurs.
  `sameVersionDifferentHashDecisionIsStale` rewrites the document text without a
  version change and asserts the confirm is refused and no annotation is written.
- **Bounded executor/shutdown/interruption:** concurs on the implementation.
  Both pools are fixed-size `workerThreads` pools, provider work is submitted
  separately with a bounded `timeoutMs`, cancellation interrupts the provider,
  and `shutdownSuggestions` marks passes cancelled, cancels futures and calls
  `shutdownNow` from the shutdown hook, run loop and protocol shutdown. The
  timeout and cancel-during-work tests pass the intended behavior; shutdown and
  the busy response are not directly tested.
- **Single grouping implementation:** concurs. `LiveServer` calls
  `SuggestionService.group`/`CandidateGrouping`, and the server no longer
  duplicates the group key logic; anchor ids, payload groups and decision
  records use the same grouping values.

**Loop status:** the loop cannot close yet. Finding 1 (stale skip committing
session state) and finding 2 (concurrent confirmation lost updates) are remaining
correctness work; after those, the ticket has no open correctness findings from
this reviewer.

### Residual observations (not filed)

- `inFlightFutures.remove(key)` in the suggestion `finally` is unconditional; if
  a new pass for the same document starts after `inFlightSuggestions.remove` but
  before that line, the old pass can remove the new pass's future from the
  cancellation map. `providerFutures` and `inFlightSuggestions` still cancel the
  new pass, so the practical impact is limited, but a value-checked removal or a
  per-pass token would close the window.
- The `shutdown` request shuts down the global suggestion pools but does not stop
  the server itself, and the new busy/shutdown paths have no direct tests.
- `SuggestionStore.suppressedIds()` is now unused after the decision-based
  suppression rewrite.
- The walkthrough remains read-only; no build, test, lint or formatter command
  was run by this role, per `docs/workflow.md`.

## Round 4

Verdict: Round 3 findings are addressed on the paths the implementor targeted.
A stale skip no longer adds to `sessionSkipped`, `UserAnnotationsFile.add` is
serialized with an in-process lock plus a lock file, cross-client confirms check
persisted decisions/annotations under an anchor lock, and the protocol
`shutdown` request no longer tears down the reusable suggestion executors. The
new tests cover all four cases. One race remains in the skip fix: `sessionSkipped`
is updated after `publishCandidates` has released its per-document lock, so a
concurrent publish can republish the just-skipped candidate after the accepted
skip. The nvim annotation writer also does not participate in the new lock file,
so cross-writer annotation loss is still possible. The loop cannot close until
the skip commit is atomic; the cross-writer lock gap should be fixed or
explicitly deferred.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:930-946`
   — the skip is committed to `sessionSkipped` after `publishCandidates` releases
   the candidate lock, so a concurrent publish can make an accepted skip
   reappear.**

   `publishCandidates` filters the skip through `extraSkips` and writes the
   candidate list under `synchronized(candidateLocks[key])`, then returns. The
   handler adds `candidateId` to `sessionSkipped` only afterwards (line 946).
   Between the lock release and that update, an async suggest, config republish
   or decision can acquire the candidate lock, see no session skip, and publish
   the candidate again. The skip's response is still `accepted=true`, but the
   last candidate message the client receives can contain the skipped candidate,
   which then stays visible until another publish. The new
   `staleSkipDoesNotHideTheCandidate` test only covers the failed-CAS path and
   does not exercise this successful-skip window.

   Fix: commit the skip inside the same per-document critical section as the
   publish (for example have `publishCandidates` take a `commitSkip` argument and
   add it to `sessionSkipped` before releasing the lock, or have the skip branch
   hold the candidate lock around both the state update and publish). Add a test
   that blocks after the successful publish but before the state update, performs
   another publish, and asserts the candidate is not re-exposed after the skip
   response.

2. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:963-990`
   with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:28-58`
   and `nvim-renderer/lua/cint/ops/annotations.lua:89-107`
   — the lock file only serializes writers that call `UserAnnotationsFile`; the
   nvim annotation writer still does an uncoordinated load-modify-save of the
   same file.**

   The server-side confirms now serialize correctly, including concurrent
   confirms from separate server threads. However, `:CintAnnotate`/`:CintUnannotate`
   in nvim still reads the JSON, modifies it, and renames a temp file without
   taking `user-annotations.json.lock`. A confirm in the live server and an
   annotation command in nvim can therefore still lose one write, which is the
   cross-writer half of the Round 3 finding. The new `UserAnnotationsFileTest`
   only exercises concurrent Kotlin callers in one JVM and does not exercise the
   cross-process nvim path.

   Fix: have the nvim writer take the same lock file (or route nvim annotation
   writes through the live server), or explicitly defer cross-process
   coordination to a named follow-up with the data-loss window documented. Add
   a cross-process or nvim-plus-server test if the lock is extended.

### Concurs / Dissents

- **Round 3 finding 1 (stale skip):** partially concurs. The failed-CAS path no
  longer hides the candidate, `extraSkips` applies the skip only to the publish
  that succeeds, and the new barrier test pins the stale response plus the
  surviving candidate. Dissents on the success path: the session state is
  committed after the candidate lock is released (finding 1 above).
- **Round 3 finding 2 (concurrent annotations):** concurs for
  `UserAnnotationsFile.add`. The in-process lock and exclusive lock file
  serialize Kotlin writers, `add`/`save` use the same order, reads see the
  atomic rename, and `UserAnnotationsFileTest.concurrentAddsPreserveEveryAnnotation`
  covers concurrent in-process adds. Dissents only on the nvim writer, which does
  not take the lock (finding 2 above).
- **Cross-client group resolution:** concurs. Confirms check
  `anchorResolvedGlobally` under a per-anchor lock before writing the annotation
  and decision; the second client's test gets `missing` for an already-resolved
  sibling and only one annotation/decision survives. Persisted state is checked
  on every publish through `resolvedAnchors`, so a removed annotation
  re-proposes the group. The anchor lock is in-memory per `LiveServer`, which is
  correct for the one-daemon-per-root model; multiple server instances on one
  root would still race between the check and write.
- **Protocol shutdown:** concurs. The `shutdown` branch now only responds and the
  pools are torn down by the run-loop exit/shutdown hook instead; the new test
  opens a later client and successfully performs an AI suggestion after
  `shutdown`. The docs still need a correction because `docs/engine.md:230-233`
  says the `shutdown` request shuts the pools down, which is no longer true.
- **Lock ordering/deadlocks:** concur that the new ordering is safe. Annotation
  writers take the in-process root lock then the file lock; anchors take the
  anchor lock then the annotation/store locks; candidate publication takes the
  per-document candidate lock then the store/annotation read path. No path takes
  those in the reverse order, and the per-writer socket lock is only held for the
  write itself. No deadlock was found in the reviewed paths.
- **Test adequacy:** the four new server tests and the concurrent-add unit test
  cover the requested Round 3 fixes. Missing: a successful-skip/post-publish race
  test (finding 1), a cross-process/nvim annotation writer test (finding 2), and
  a multi-candidate group re-proposal-after-annotation-removal test (the current
  re-proposal test is single-candidate only).

**Loop status:** the loop cannot close yet. Finding 1 is a remaining correctness
race in the newly added skip commit, and finding 2 is the unresolved cross-writer
half of the persistence finding; both need a fix or an explicit named deferral.

### Residual observations (not filed)

- `anchorLocks` grows without cleanup for each seen anchor id, and
  `inFlightFutures.remove(key)` can still remove a newer pass's future in the
  narrow window after the in-flight entry is removed; neither was observed to
  break a decision in the reviewed tests.
- `UserAnnotationsFile.add` still treats a malformed file as empty and overwrites
  it on confirm without a backup; this is now a deliberate tolerant path and is
  pinned by `malformedUserAnnotationsDoNotBreakCandidatePublicationOrConfirm`.
- `SuggestionStore.suppressedIds()` remains unused after the decision-based
  suppression rewrite.
- The docs correction noted in the concurs section (`docs/engine.md:230-233`)
  is a scribe change, not a source edit.
