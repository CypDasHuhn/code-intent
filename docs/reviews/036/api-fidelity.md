# API-fidelity review — ticket 036 (AI intent candidates)

## Round 1

Verdict: The core v5 split is sound. `RuleEmit` is a sealed interface with the
common fields, `Emit` remains the established type, `CandidateEmit` is the
review-only type, and `toContribution` still accepts only `Emit`. `Rule.run` and
`Matcher.match` return `List<RuleEmit>`, `CINT_API_VERSION` is 5, v4 packs are
rejected, and the scheduler partitions established emits from candidates without
putting candidates into the annotation store, composition, convergence, or the
`reads` graph. The artifact gains a separate `candidates` array and the live
protocol is bumped to 3 with the requested methods, server-owned persistence, and
non-destructive renderer support. The remaining gaps are in the candidate
provenance contract, revision/cancellation semantics, and contract recording:
the documented rationale/prompt version never reaches the wire, candidate
builders default to an established-looking `treesitter` source, `analysisRevision`
is actually the config revision and can be used to accept stale cached
candidates, `cint/suggest/cancel` cannot cancel the synchronous request it is
meant to cancel, the docs still describe protocol 2 and omit the new surface,
and the protocol-3 capability/backward-compatibility and reads-boundary cases are
not pinned by tests.

### Findings

1. **`analysis-engine/core/api/src/main/kotlin/dev/cint/api/Contract.kt:208-217`
   with `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/FakeIntentSuggestionProvider.kt:7,10-17`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:518-534`,
   and `analysis-engine/core/core/src/main/kotlin/dev/cint/core/artifact/CandidateJson.kt:8-19`
   — the candidate contract does not carry rationale or prompt version.**

   `docs/ai-intents.md:160-161` says candidates carry `source`, confidence,
   rationale, prompt version, reason, and a stable anchor, and
   `docs/ai-intents.md:169-170` expects review to show evidence and rationale.
   `CandidateContext.promptVersion` and `FAKE_PROMPT_VERSION` exist but are never
   used, `CandidateEmit` has no rationale field, and neither the live candidate
   payload nor `CandidateJson` serializes either datum. The renderer therefore
   cannot show why a candidate exists or which prompt/provider version produced
   it, and the documented candidate shape is only partially implemented.

   Suggested fix: add optional `rationale` and `promptVersion` (and, if intended,
   provider/model) to `CandidateEmit` with defaults, populate them in the fake
   provider from `CandidateContext`, and serialize them consistently in
   `candidatePayload` and `CandidateJson`. Add a wire/artifact test that pins the
   full candidate shape.

2. **`analysis-engine/core/api/src/main/kotlin/dev/cint/api/Contract.kt:219-292`
   with `docs/ai-intents.md:160-161` — candidate builders default `source` to
   `"treesitter"`, an established-provenance token.**

   Every `CandidateEmit` builder defaults to `Emit.DEFAULT_SOURCE` (`"treesitter"`).
   A pack that uses `CandidateEmit.of`/`range`/`declaration`/`call` without an
   explicit source publishes a candidate whose source is indistinguishable from
   established tree-sitter contributions. The documented candidate sources are
   `rule:<id>` and `ai:<provider>`; today only the validation pack happens to set
   the correct value. This undermines provenance display and any consumer that
   classifies candidates by source prefix.

   Suggested fix: require an explicit candidate source, or make the default a
   rule/candidate-specific value rather than `Emit.DEFAULT_SOURCE`. Add a builder
   test that asserts candidate sources are not `treesitter` unless deliberately
   supplied.

3. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:564-611`
   with `LiveServer.kt:402-447,464-512` — `analysisRevision` is the config
   revision, and `cint/suggest` can republish a cached analysis under a newer
   revision.**

   `publishCandidates` receives `configRevision`, never a revision tied to the
   `AnalysisOutput` it publishes. `handleSuggest` reads `analyses[uri]` without
   recording or checking the revision/version that produced it. If another
   client mutates config while a suggest pass is enriching, the mutation's
   republish can land first, then the older suggest result can be published with
   the newer `configRevision` and a higher `suggestionRevision`; the client accepts
   it because the suggestion revision is greater. The field named
   `analysisRevision` also does not increment per analysis, so it cannot
   distinguish analyses of the same document/config revision.

   Suggested fix: store the observed revision alongside each `AnalysisOutput`,
   re-check that the cached output is still current before publishing enriched
   candidates, and either introduce a real analysis revision or rename/document
   the field as the config revision. Add a concurrent config-mutation/suggest
   test that proves an older analysis cannot win with a newer revision.

4. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:163-210,546-627`
   — `cint/suggest/cancel` cannot cancel the request it is paired with.**

   The per-connection reader calls `handleSuggest` synchronously, so a cancel
   line from the same client is not read until the suggest pass has already
   published and responded. The epoch check only helps when another connection
   cancels. Cancelled request ids are added to `cancelledSuggestions` and never
   removed, so the set grows for the daemon lifetime. The method therefore
   advertises a cancellation contract the normal client path cannot exercise.

   Suggested fix: make suggestion passes asynchronous or hand them to a worker
   that checks a cancellation token before publish, or state explicitly that
   cancellation is best-effort/cross-client only. Prune cancelled ids after
   their request completes. Add a same-connection cancel test.

5. **`docs/engine.md:208-236`, `docs/notes.md:411-415`,
   `docs/architecture.md:221-270`, `nvim-renderer/README.md:77-98,255-270`, and
   `docs/manual-test.md` — the contract documentation still describes protocol 2
   and omits the candidate surface.**

   The engine docs still say protocol `2` and list only `cint/publishTags`,
   `cint/analyze`, and `cint/config/*`; they do not document protocol 3,
   `initialize.suggestions`, `cint/publishCandidates`, `cint/suggest`,
   `cint/suggest/cancel`, `cint/candidates/decide`, `.cint/suggestions.json`, or
   the artifact `candidates` field. Notes still list protocol 2. The architecture
   module table omits `suggest`, still describes `api` as the old `Emit` surface,
   and still lists occurrence persistence as deferred even though candidate
   decisions now persist. The renderer README has no `:CintSuggest`/`:CintReview`
   entries and no candidate protocol description. `manual-test.md` has no ticket
   036 live candidate entry. The ticket explicitly requires docs for
   architecture, protocol, renderer commands, and manual live testing.

   Suggested fix: update those sections with the exact protocol-3 request/response
   and error shapes, capability descriptor, revision fields, persistence files,
   candidate source/reason contract, v5 API/module changes, renderer commands, and
   a manual-test entry for live publish/confirm/reject.

6. **`analysis-engine/core/api/src/test/kotlin/dev/cint/api/ContractTest.kt:368-393`,
   `analysis-engine/core/core/src/test/kotlin/dev/cint/core/runtime/engine/RuleEngineCandidateTest.kt`,
   `analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/LiveSuggestionsTest.kt`,
   and `nvim-renderer/tests/live_spec.lua,candidates_spec.lua` — the new boundary
   and backward-compatibility cases are not pinned.**

   The v5 tests check that a mixed list can be constructed and that v4 packs are
   rejected, but there is no test that a rule with `reads` cannot observe a
   candidate through `view.annotations`, nor a direct assertion that
   `toContribution` accepts only established emits. On the protocol side, no test
   asserts the `initialize.suggestions` descriptor shape is secret-free, and no
   client test covers the protocol-2 fallback where suggestions must stay
   disabled and no `cint/suggest` request should be sent. The existing live spec
   was updated from 2 to 3 without adding the old-server case. Artifact and live
   candidate shapes are also not compared by a shared test.

   Suggested fix: add a reader-rule/fixpoint test proving candidates are absent
   from `view.annotations`; assert the `initialize.suggestions` key set and that no
   credential-like fields appear; add a protocol-2 daemon test asserting
   `suggestions_supported()` is false and no suggest call is issued; and pin the
   shared candidate fields plus the artifact/live differences explicitly.

### Concurs / Dissents

- Concurs: the v5 type split is coherent and correctly versioned. `RuleEmit` is
  sealed, `Emit`/`CandidateEmit` carry the common fields, `Emit` remains the
  established type, `CandidateEmit` adds `reason`/`aiEligible`, and
  `Rule.run`/`Matcher.match` return `List<RuleEmit>`. `toContribution` still takes
  `Emit`, so candidates cannot be converted into contributions. `CINT_API_VERSION`
  is 5, the v5 constants are updated, and the loader test pins that v4 packs are
  rejected. The change is deliberately source/binary incompatible at the rule
  contract boundary, which is why the version bump is required; the config
  descriptor additions remain the earlier additive v4 surface.
- Concurs: scheduler ownership is correct. `settle` validates that each emit,
  including a candidate, uses exactly `rule.owns`, partitions established and
  candidate emits, and keeps only the final settled iteration's candidates for
  fixpoint rules. Candidates never enter `AnnotationStore`, composition
  inputs, the fixpoint identity set, or the `reads` graph. The candidate
  partition and foreign-candidate rejection are covered.
- Concurs: candidates do not change the frozen `Annotation` shape, roles, or
  provenance. Confirmed candidates enter through the existing user-annotation
  record on the next analysis, and the live confirm test observes them as
  `source: "user"` provenance.
- Concurs: the artifact `candidates` field is additive and uses the same
  presentation fields (`intents`, `source`, `confidence`, span/byte positions,
  `reason`, `aiEligible`) as the live payload. The live payload additionally
  carries `candidateId`, `path`, and `contentHash`, which is appropriate for
  decisions; the artifact is not a decision surface. `FileEntry.candidates`
  defaults to an empty list, so existing artifact producers remain source
  compatible.
- Concurs: protocol 3 is a coherent additive step. `initialize` keeps the
  existing result fields and adds a secret-free `suggestions` descriptor
  (`enabled`, `trigger`, fake `provider`/`model`, `knownIntents`).
  `cint/publishCandidates` carries `uri`, `version`, `analysisRevision`,
  `suggestionRevision`, and the candidate list. `cint/candidates/decide` accepts
  confirm/reject/skip, validates document version and anchor hash, persists
  reject suppression and confirm user annotations, and the client gates feature
  use on a returned protocol of at least 3. Old clients ignore the new
  notification and an old protocol-2 daemon leaves suggestions disabled.
- Concurs: candidate identity and persistence are consistent in the normal path.
  Identity is path + content hash of the anchored text + sorted intents + reason;
  the suggest module's hash canonicalization matches `core.contentHash`; decisions
  are written to `.cint/suggestions.json` and confirm writes the existing
  `.cint/user-annotations.json` record shape. Reject suppresses the same
  occurrence and the next analysis shows it gone; skip is session-only.
- Concurs: the module boundary is clean. `:suggest` is a new module depending only
  on `:api`; `server` owns orchestration, persistence, and the fake provider
  instance; no AI types or provider SDKs leak into `core` or `api`; the fake
  provider is deterministic and has no network path.
- Dissents: findings 1-6.

### Residuals (not filed)

- `CandidateValidator` checks known intents, confidence range, and non-negative
  byte bounds, but not that an AI-proposed span stays inside the input candidate,
  and `maxCandidates` is only a zero check rather than a per-pass limit. The fake
  provider copies the input exactly, so this is latent until a real provider.
- `initialize.suggestions.enabled` is hardcoded true and there is no
  `.cint/suggestions.yml` gate yet; pack candidates still publish without AI, but
  an AI-disabled state is not representable. This is acceptable for the fake
  slice only if documented as a real-provider follow-up.
- `analyses`, `publishedCandidates`, `sessionSkipped`, and `cancelledSuggestions`
  are keyed/populated server-wide and are not pruned on `didClose`, so a long-lived
  daemon grows and two clients editing the same URI can share candidate state.
- Confirm permanently suppresses the candidate id even if the user later removes
  the corresponding user annotation; the decision file has no cleanup path.
- The artifact candidate shape omits `candidateId`/`contentHash`, so an artifact
  consumer cannot correlate a candidate with a live decision id unless the file
  hash and identity algorithm are applied externally. This is fine if the
  artifact remains presentation-only, but it should be stated.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.

## Round 2

Verdict: Round-1 findings 1-6 are addressed. Candidate provenance is now complete
through the API, fake provider, live payload, and artifact; the default candidate
source is `candidate`; config and analysis revisions are separate; `cint/suggest`
is asynchronous and cancellable; the docs describe protocol 3, v5, candidate
groups, settings, commands, and the live manual check; and the reads boundary,
capability descriptor, protocol-2 gate, and artifact/live candidate fields are
tested. The new flat-candidates-plus-groups wire shape is coherent and remains
backward compatible with clients that only understand flat candidates (and with
old clients that ignore the notification). One new finding remains in the
revision-ordering path: the analysis revision is assigned after the analysis lock
is released, so an older analysis can receive a higher revision than a newer
config-driven analysis and be accepted by the client. The loop cannot close until
that ordering is made monotonic with the analysis snapshot.

### Findings

7. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:457-496`
   with `LiveServer.kt:408-415,516-585` — `analysisRevision` is assigned after
   the synchronized analysis, so a stale config analysis can win with a higher
   revision.**

   `publish` analyzes under `synchronized(this)` and captures the config
   revision there, but calls `nextAnalysisRevision(key)` only after leaving the
   lock and before storing the `CachedAnalysis`. A concurrent
   `cint/config/append|remove` can finish its reload and its republish of the new
   config while the older publish is between the lock exit and revision
   assignment. If the new publish assigns revision N first and the older publish
   then assigns N+1, the client accepts the older tags and candidates: it checks
   document version (unchanged by a config mutation) and rejects only a lower
   `analysisRevision`/`suggestionRevision`. `CachedAnalysis.configRevision` is
   stored but never used for ordering, so it does not defend this case. This is
   narrower than the round-1 suggest race, but it defeats the same stale-analysis
   guarantee for the automatic publish path.

   Suggested fix: assign the analysis revision inside the same synchronized block
   that produced the analysis (or otherwise tie the sequence number to the
   analysis snapshot/config revision), and use the config revision as a second
   ordering guard in the client if that is the intended contract. Add a
   latch-driven test with an analysis started before a config mutation and a
   republish completing first; assert the older analysis never becomes the last
   accepted tags/candidates.

### Concurs / Dissents

- Concurs finding 1: `CandidateEmit` now carries optional
  `rationale`/`promptVersion`/`provider`/`model`; the fake provider fills them
  from its context, and both `CandidateJson` and `candidatePayload` serialize
  them. `ArtifactTest` and `LiveSuggestionsTest` pin the artifact and live
  shapes, and `:CintReview` displays rationale.
- Concurs finding 2: candidate builders now default to source `candidate`, and
  the contract test explicitly distinguishes that from `Emit.DEFAULT_SOURCE`.
  Deterministic candidates use `rule:<id>` and AI proposals use `ai:<provider>`.
- Concurs finding 3 for the round-1 scope: `publishTags` carries both the config
  `revision` and a per-document `analysisRevision`; `cint/publishCandidates`
  carries `analysisRevision`, and `cint/suggest` checks the cached analysis
  revision before publishing. `staleAnalysisCannotPublishAfterConfigMutation`
  covers the blocking-provider case. Finding 7 records the remaining ordering
  race in `publish` itself.
- Concurs finding 4: `cint/suggest` runs on a suggestion executor with one
  in-flight pass per owner/document, a per-pass cancellation flag, a cancel-token
  set pruned after 60 seconds, and disabled/stale/busy/cancelled responses.
  `blockingProviderCanBeCancelledBeforePublish` exercises same-connection
  cancellation while the provider is blocked.
- Concurs finding 5: `docs/engine.md`, `docs/architecture.md`, `docs/notes.md`,
  `docs/ai-intents.md`, `docs/intent-model.md`, `nvim-renderer/README.md`, and
  `docs/manual-test.md` now describe protocol 3, the v5 rule split, `suggest` as
  a module, candidate settings/persistence, the new commands, flat candidates
  plus groups, and the live manual check (m012).
- Concurs finding 6: `RuleEngineCandidateTest` now proves candidates are
  invisible to downstream `reads` (including `intentsAt`), the contract test
  pins the established/candidate source boundary, `LiveSuggestionsTest` pins the
  secret-free initialize descriptor and config gate plus disabled suggest,
  `candidates_spec.lua` pins protocol-2 gating and status text, and artifact/live
  candidate fields are tested separately. The remaining artifact/live
  differences are documented deliberately rather than silently divergent.
- Concurs: the new wire shape is consistent. `cint/publishCandidates` sends a
  flat `candidates` array and `groups`; each group carries `anchorId`, path,
  content hash, span/bytes, and the full candidate payloads, so a group is a
  projection of the same candidates rather than a second candidate model. The
  client prefers flat candidates and reconstructs groups when they are absent,
  which is backward compatible with the round-1 shape. Optional
  rationale/prompt/provider/model fields are additive.
- Concurs: candidate identity now includes the byte span and the document-scoped
  owner key, so two identical anchors at different occurrences have distinct ids
  and independent suppression; the docs state the byte-span part explicitly.
  Confirm re-proposes after the user annotation is removed because confirm
  records are no longer treated as permanent suppression, while reject remains
  persistent.
- Concurs: protocol 3, the protocol-2 gating, and the capability gate remain
  backward compatible. Old clients ignore `cint/publishCandidates`; a new client
  against a protocol-2 daemon falls back to config-revision ordering and sends no
  suggest request. Per-owner document keys prevent two clients on the same URI
  from overwriting each other's candidate state.
- Concurs: the module boundary remains acceptable after moving candidate identity
  into `core`: `suggest` depends on `api` and `core` only, the server owns
  orchestration/settings/persistence, and no AI or provider SDK leaks into
  `core` or `api`.
- Dissents: finding 7.

### Residuals (not filed)

- Confirm/reject persist globally but republish only to the acting writer; another
  client looking at the same URI keeps its old candidate state until its next
  analysis. Skip is per-owner even though the docs say "server session"; decide
  whether decisions should broadcast to all writers and state the skip scope
  explicitly.
- The nvim suggest callback handles `stale` and `cancelled`, but a `disabled` or
  `busy` result falls through to a "0 unsure candidate(s)" notice. This is a
  presentation nit because the descriptor normally prevents the disabled call.
- Grouping is implemented twice (suggest `CandidateGrouping` and server
  `publishCandidates`), and `SuggestionService.group` is unused by the server;
  the two must stay in lockstep on anchor hashing and span grouping.
- `SuggestionOptions.maxCandidates` is applied per input candidate, not as a
  total per pass, so a document with many candidates can exceed the configured
  total.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.
