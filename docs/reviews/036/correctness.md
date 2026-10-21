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
