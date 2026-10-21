# Architecture review — ticket 036 (AI intent candidates)

## Round 1

Verdict: The direction is sound and the deterministic hull is preserved. The
`RuleEmit` split is type-level, `settle` partitions established emits before
anything reaches `AnnotationStore`, composition, provenance, or the `reads`
graph, and `AnalysisOutput` keeps candidates on a separate channel. AI is
invoked only on a cached `AnalysisOutput` after analysis, its output is
candidate-only, and confirmation re-enters through the existing user-annotation
path. The dependency direction is also right: `:suggest` depends only on `:api`,
`server` depends on `:suggest`, and `:core` does not depend on suggestion code;
no provider SDK leaks into the engine. However, the candidate/service boundary
is only sketched. Most candidate context building, provider selection, merging,
policy, suppression, and persistence still live in `LiveServer`; the validator
cannot enforce the documented AI invariants; candidate identity is in the AI
module rather than the candidate model; confirm is both a user annotation and a
suppression; and a confirm-triggered republish is indistinguishable from an
unchanged publish on the client. The architecture docs were not updated. This is
not ready to close.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:92-99,450-458,546-611`
   with `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/FakeIntentSuggestionProvider.kt:9-24`
   — candidate orchestration is concentrated in the host while `:suggest` is an
   SPI stub, and the fake provider is wired into the production server as
   enabled.**

   The server is allowed to own transport orchestration, configuration, and
   credentials, but here it also implements context building, provider
   invocation, candidate merging, suppression filtering, and the "enabled"
   policy. `:suggest` only declares `CandidateContext`, the provider interface,
   a validator, and a fake provider. `suggestionCapability()` hardcodes
   `enabled = true` and `provider = fake`, so every live server advertises fake
   AI even though the architecture says AI is disabled by default and pack
   candidates must work without it. There is no provider registry, config file,
   or injection seam; adding a real local/remote provider means editing
   `LiveServer` rather than adding a provider module.

   Fix: introduce a suggestion service in `:suggest` that owns the context
   builder, provider selection/registry, merge, and validation policy; have the
   server resolve config/credentials and call that service. Make the provider
   injectable and fake only in tests or an explicitly enabled dev mode, and
   derive `initialize.suggestions.enabled` from configuration.

2. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/FakeIntentSuggestionProvider.kt:44-56`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:580-596`
   — the validator cannot enforce the documented span/max invariants and the
   server discards all but one AI result, so alternative intents cannot work.**

   `CandidateValidator.valid(candidate, knownIntents)` never receives the
   originating candidate or source, so it cannot check the documented
   "span inside the input candidate" rule, overlaps with established
   annotations, or a real output limit. The `maxCandidates` parameter is used
   only as a boolean guard. `handleSuggest` then calls
   `suggest(...).firstOrNull { valid } ?: candidate`, which replaces the
   deterministic candidate with one AI answer and drops every other result.
   Ticket 038 explicitly expects AI to propose the *other* intent as an
   alternative for the SEARCH/LOOKUP boundary; that path cannot be expressed or
   reviewed with this shape.

   Fix: make the validator take the input candidate (and the analysis's
   established intents) and enforce span containment, vocabulary, count, and
   confidence against it. Have the enrichment step merge AI results with the
   original candidate as a group of alternatives for one anchor instead of
   choosing the first result, and preserve the original deterministic candidate
   when AI returns nothing usable.

3. **`analysis-engine/core/api/src/main/kotlin/dev/cint/api/Contract.kt:205-217`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:517-535`
   — the frozen candidate shape and wire payload have no structured AI
   provenance or rationale.**

   `CandidateEmit` carries only `source` (a string such as `ai:fake`) and
   `confidence`; the payload adds span, reason, and `aiEligible`. The protocol
   design calls for provider, model, prompt version, rationale, and a stable
   anchor on candidates. A real provider must either stuff this into `source`
   or force another breaking `api`/protocol change. There is also no
   original-candidate identifier or group key, so the renderer cannot show
   "these are alternatives for this span."

   Fix: define candidate provenance/rationale as structured data now (provider,
   model, prompt version, and optionally the originating candidate id) in
   `api`, and project it through the live payload and artifact. Keep the
   stable candidate identity separate from display/provenance metadata.

4. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/CandidateIdentity.kt:6-25`
   with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/support/hashing/Hashing.kt:5-9`
   and `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:464-495`
   — candidate identity lives in the AI module, duplicates the core content-hash
   implementation, and is used as a lossy dedupe key.**

   Candidate identity is needed for deterministic pack candidates and for
   persistence even when AI is disabled, but it lives in `:suggest`, so any
   non-AI consumer must depend on the AI module to compute or suppress
   candidates. `CandidateIdentity.contentHash` duplicates the canonicalisation
   in `core.support.hashing.contentHash`; confirmation writes the suggest-side
   hash into a user annotation that `UserAnnotationResolver` verifies with the
   core-side hash. Any future change to one implementation silently breaks the
   confirmation round-trip. In `publishCandidates`, `distinctBy { candidateId }`
   also drops candidates whose path, selection hash, intents, and reason match
   but whose source/provider differs, rather than merging their provenance.

   Fix: move candidate identity and hashing into a shared candidate/core module
   (or `api` if it is intended to be a public contract) and use one hash
   implementation. Include source/provider in the merge/dedupe decision, or
   model one candidate with a provenance list, instead of silently discarding
   a second provider's result.

5. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/SuggestionStore.kt:54-58`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:666-704`
   and `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt`
   — confirm and reject are conflated as suppression, and persistence is split
   across two stores with duplicated path knowledge.**

   `suppressedIds()` treats both `confirm` and `reject` records as suppression.
   Confirmation already writes a user annotation, so the `confirm` record is a
   second source of truth: deleting or editing the user annotation does not
   un-suppress the candidate, and there is no reconciliation between the two
   files. The write side now lives in `core/core` (`UserAnnotationsFile`) while
   reads still hardcode `.cint/user-annotations.json` in
   `core/host/HostPipeline.kt:111,202`, duplicating the path and persistence
   rules. This makes the "confirmed candidate becomes an established user
   annotation" lifecycle drift from the stored decision history.

   Fix: suppress only rejected candidates (or reconcile confirm decisions
   against live user annotations), keep the confirm decision as audit data, and
   give user-annotation persistence a single owner. Have `HostPipeline` use the
   same file helper rather than re-deriving the path.

6. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:408-447,680-694`
   with `nvim-renderer/lua/cint/ops/live_ops.lua:259-300`
   — confirm republishes with the same version, hash, and revision, so the
   client can skip the established-annotation render.**

   Confirmation writes the user annotation and calls `publish`, but
   `analysisRevision` is the config revision, not an analysis revision. With
   unchanged text and config, the confirm publish has the same version, content
   hash, and revision as the previous publish. `live_on_tags` updates
   `state.live_tags` and then returns early when the hash and revision match and
   the buffer was already rendered. The server-side test sees the established
   annotation in the wire payload, but the editor does not repaint it; the
   accepted candidate channel has not completed its confirmation round-trip.

   Fix: give each analysis publish a monotonic analysis revision (or compare the
   annotation payload), use it for tags and candidates, and add a headless test
   that confirms a candidate and asserts the established extmark appears with
   unchanged text.

7. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:92-98`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:464-495,546-611,629-704`
   — analysis and candidate state are keyed by URI while documents are keyed by
   connection.**

   `docs` is per writer, but `analyses`, `publishedCandidates`,
   `suggestionEpochs`, `cancelledSuggestions`, and `sessionSkipped` are global
   URI maps. With two clients on the same URI at different versions or unsaved
   text, the latest publish overwrites the candidate list and cached analysis
   for both, and a cancel/skip/decision on one connection affects the other.
   The reusable per-project daemon makes this a supported scenario rather than a
   theoretical one.

   Fix: key analysis and candidate state by document owner plus URI, or make the
   single-client contract explicit and reject concurrent ownership. Publish
   candidate lists to the owning writer.

8. **`docs/architecture.md:80-86,219-234` with `docs/engine.md:208-228` and
   `nvim-renderer/README.md:252-260` — the architecture and protocol docs still
   describe the pre-036 shape.**

   The ticket's docs scope is unmet: `architecture.md` still shows
   `Rule.run(): List<Emit>` and has no `:suggest` module in the module table;
   `engine.md` still says protocol 2 and documents only `publishTags` and config
   methods; the renderer README does the same; and no manual-test entry covers
   the live suggestion path. The candidate lifecycle, decision methods,
   candidate wire shape, and AI-disabled behavior are undocumented outside
   `ai-intents.md` and the ticket. Future packs 037-040 will read the canonical
   architecture, so the stale description will actively mislead them.

   Fix: document the v5 `RuleEmit` split, the candidate channel and lifecycle,
   protocol 3 (`initialize.suggestions`, `cint/publishCandidates`,
   `cint/suggest`, `cint/suggest/cancel`, `cint/candidates/decide`), the module
   boundary, the confirm/suppression semantics, and the live/manual check.

### Concurs / Dissents

- Concurs: the secondary-emit design preserves the hull. Candidates are
  partitioned in `settle`, never enter `AnnotationStore`, composition, fixpoint
  identity, or `reads`, and `toContribution` accepts only established `Emit`.
  The fixpoint rule keeps candidates from the final settled iteration, as
  required.
- Concurs: AI is post-analysis and non-authoritative in the current slice. It
  consumes cached `AnalysisOutput` candidates, publishes candidate-only output,
  and cannot create established annotations; confirmation goes through the
  existing user-annotation resolver on the next analysis.
- Concurs: the one-list `RuleEmit` split is the right base for 037-040.
  Deterministic packs can return candidates alongside established emits, and
  the engine's ownership check keeps a rule from claiming another rule's
  intent. The limitation is in the enrichment/merge path, not in the split
  itself.
- Concurs: `:suggest` is correctly dependency-isolated (`:suggest` → `:api`
  only, no server/editor/provider SDK), and `server` owning transport,
  configuration, and credentials is the right long-term placement. The problem
  is that the module's documented responsibilities are not yet implemented
  there.
- Concurs: placing `AnalysisOutput` in `core.runtime.scheduler` and shifting
  candidate spans through `SplitAnalyzer` is structurally reasonable; the
  frontend layer already shifts annotations and does not acquire detection
  logic.
- Concurs: candidate identity using path + selection/content hash + intents +
  reason matches the ticket notes, and confirmation writing an existing
  `UserAnnotation` keeps the frozen `Annotation` shape unchanged. The
  intent-model's stronger node-anchor requirement remains deferred.
- Dissents: findings 1-8.

### Residuals (not filed)

- Candidate identity is still content-hash based; the reserved node anchor is
  not used, so structural edits elsewhere and same-text occurrences are not
  distinguished beyond the current selection hash contract.
- `CandidateJson` (core artifact) and `candidatePayload` (server) are two
  hand-written candidate serializers, and the artifact form omits
  `candidateId`/selection hash, so batch and live candidate identity cannot be
  reproduced identically.
- `CandidateReason` is a closed enum and `aiEligible` defaults to true; 037-040
  can use `PACK_DOMAIN`, but candidate kinds/reasons and AI eligibility policy
  may need a richer shape later.
- `initialize.suggestions.knownIntents` is computed once per initialize and
  includes user/`UNTAGGED` vocabulary; it is not refreshed when annotations
  change.
- `SuggestionStore` rewrites the whole decisions file per decision and the
  user-annotation writer is not coordinated with it; concurrent decisions on
  one root can lose records.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.

## Round 2

Verdict: Round 1 findings 1-8 are substantively addressed. The suggestion
service, provider registry, settings loader, disabled-by-default gate, validator
with input containment, alternative grouping, structured provenance, shared
candidate identity/hash in `core`, reject-only suppression with confirm audit,
per-document `analysisRevision` repaint, and per-connection candidate state are
all present. The docs now describe the v5 contract, protocol 3, candidate
lifecycle, module boundaries, and the manual candidate check. The candidate
channel still preserves the deterministic hull and is a workable base for
037-040. Three new findings remain, the first of which is a revision-ordering
regression in the publish path. The loop cannot close until they are fixed or
explicitly deferred to named tickets.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:441-499`
   with `nvim-renderer/lua/cint/ops/live_ops.lua:286-310`
   — `analysisRevision` is allocated after the analysis lock is released, so an
   older-config analysis can receive a higher revision than a post-mutation
   analysis and win on the client.**

   `publish` samples `configRevision` and computes the analysis inside
   `synchronized(this)`, but calls `nextAnalysisRevision(key)` only after leaving
   the lock, then writes `analyses[key]` and the socket payloads. A
   `didChange` for one connection and a config-mutation republish for the same
   document can therefore interleave: the didChange analyzes the old config,
   is delayed before its revision allocation, and the config republish analyzes
   the new config and allocates a lower revision; the delayed old-config publish
   then allocates a higher revision. `live_on_tags` rejects only on a lower
   `analysisRevision` and does not use the config `revision` as a tiebreaker, so
   the client can accept stale annotations and candidates. The same overwrite
   affects `analyses[key]` for the next suggestion pass. The existing concurrent
   test checks the highest config revision, not the highest `analysisRevision`,
   so it does not pin this ordering.

   Fix: allocate the analysis revision under the same lock as the analysis and
   config snapshot, and update `analyses`/`publishedCandidates` in that same
   order (or compare the pair `(configRevision, analysisRevision)` on the
   client). Add a concurrent didChange/config test that asserts the
   highest-`analysisRevision` publish contains the post-mutation annotations and
   candidates.

2. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:113-117,653-760`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/SuggestionSettings.kt:7-14`
   — the suggestion worker executor is unbounded and never closed, and
   cancellation only discards a result after the provider returns.**

   The server owns the right executor boundary, but the new cached daemon pool
   has no shutdown path in `run()` or the `shutdown` handler, no timeout in
   `SuggestionSettings`, and no way to interrupt `IntentSuggestionProvider`. A
   blocking provider holds a worker thread and the document's in-flight slot
   until it returns; `cint/suggest/cancel` flips `SuggestPass.cancelled` but the
   provider keeps running, so later suggestions for that document report
   `busy` indefinitely. The test uses an explicit release latch, which exercises
   discard-after-return rather than real cancellation. The docs currently say
   the pass is cancellable.

   Fix: give the executor an owned lifecycle (close on server shutdown, bounded
   pool), add a per-pass timeout to the settings/options, and either pass a
   cancellation signal into the provider or run the call with a timed
   future/interrupt. If real provider timeouts are deferred, say so explicitly
   and avoid advertising in-flight cancellation as implemented.

3. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/SuggestionService.kt:83-86`
   with `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/CandidateGrouping.kt:18-50`
   and `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:515-637`
   — candidate grouping/anchor logic is duplicated, and the `:suggest` grouping
   API is unused.**

   `SuggestionService.group` and `CandidateGrouping.group` implement the same
   anchor hashing/grouping that `LiveServer.publishCandidates` reimplements with
   `groupBy(Triple(contentHash, startByte, endByte))`, `candidateGroupPayload`,
   and a second `anchorHash`. The server never calls the service grouping, so
   the new module boundary is still bypassed for the wire shape. Any change to
   identity, anchor hashing, or group ordering must now be made in both modules,
   which is exactly the drift the identity relocation was meant to remove.

   Fix: have the server call `suggestionService.group(document, candidates)` and
   map `CandidateGroup` to the payload, or move the payload mapping into
   `:suggest`; keep one `anchorHash`/group key implementation. Add a test that
   pins the service grouping and the served `groups` shape together.

### Concurs / Dissents

- Concurs with Round 1 finding 1: `SuggestionService` and
  `SuggestionProviderRegistry` now own context/merge/validation, the server
  configures them, `.cint/suggestions.yml` gates the pass, missing settings
  default to disabled/manual, and `initialize.suggestions.enabled` and
  `cint/suggest` honor the gate. The fake provider remains the registry default
  but is inert unless settings enable it, which is acceptable for this slice. A
  residual remains: `SuggestionOptions.model` is loaded and advertised but not
  passed to the provider or context.
- Concurs with Round 1 finding 2: `CandidateValidator.validate` takes the input
  candidate, document length, known intents, and max count, enforces containment,
  and `SuggestionService.enrich` merges proposals with the deterministic
  candidate instead of replacing it. Alternatives for one anchor are published
  in `groups`, and the server test covers an AI alternative with two intents.
- Concurs with Round 1 finding 3: `CandidateEmit` now carries
  rationale/promptVersion/provider/model, and both the live payload and artifact
  serialize them. The frozen v5 shape has the provenance needed for real
  providers without another immediate API break.
- Concurs with Round 1 finding 4: identity moved to
  `core.candidate.CandidateIdentity` and uses the canonical core hash;
  `:suggest` depends on `:core` for that shared helper, and alternatives are
  grouped by anchored span. The artifact's omission of `candidateId`/selection
  hash is now explicitly documented as presentation-only, which is defensible
  for 036. A residual remains for same-intent proposals from different
  providers: identity still excludes source/provider, so `distinctBy` collapses
  them rather than preserving provider provenance.
- Concurs with Round 1 finding 5: `suppressedIds()` now filters only `reject`;
  confirmation writes the user annotation and keeps a confirm record as audit,
  and removing the annotation re-proposes the candidate. `HostPipeline` uses
  `UserAnnotationsFile.path` for both reads, removing the duplicate path.
- Concurs with Round 1 finding 6: tags now carry a per-document monotonic
  `analysisRevision`, the client re-renders when it changes even with the same
  hash/version, and the headless test asserts the established extmark appears
  after confirm without a text change. Finding 1 above is the remaining
  ordering flaw in how that revision is allocated.
- Concurs with Round 1 finding 7: analyses, published candidates, analysis
  revisions, in-flight passes, and session skips are keyed by
  `DocumentKey(owner, uri)`, cleanup runs on close/disconnect, and the test with
  two clients on one URI shows independent skip/confirm state.
- Concurs with Round 1 finding 8: `docs/architecture.md`, `docs/engine.md`,
  `docs/ai-intents.md`, `docs/intent-model.md`, `docs/notes.md`,
  `docs/manual-test.md` (m012), and `nvim-renderer/README.md` now describe v5,
  protocol 3, candidate lifecycle, module boundaries, settings, and the manual
  live check.
- Dissents: new findings 1-3.

### Residuals (not filed)

- `SuggestionOptions.model` is unused; a configured model changes the
  advertised descriptor but not the provider/context, so the candidate's
  `model` can disagree with `initialize.suggestions.model`.
- `maxCandidates` is applied per input candidate, not per document/pass; several
  eligible inputs can therefore exceed the configured total.
- Candidate identity includes the byte span, so unrelated edits that shift bytes
  invalidate a rejection/confirmation. This is the documented deferred
  node-anchor tradeoff.
- `CandidateJson` and the server `candidatePayload` remain two serializers; the
  artifact intentionally omits decision identity, but the presentation fields
  can still drift.
- `SuggestionStore` and `UserAnnotationsFile.add` are not coordinated; two
  concurrent decisions can still lose a user-annotation or decision record.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.

## Round 3

Verdict: Round 2 findings 1-3 are addressed in the committed 036 state. The
suggestion and provider pools are bounded by `workerThreads`, provider calls
have a timeout and best-effort interrupt, both pools are closed by the run loop
and shutdown hook, grouping is now served by `SuggestionService.group` /
`CandidateGrouping`, and `analysisRevision` is allocated under the same lock as
the analysis and config snapshot, with a concurrent ordering test. Group
reconciliation persists `anchorId` and span data, resolves a confirmed anchor,
re-proposes after annotation removal, and keeps rejection/skip candidate-scoped.
Per-connection state and the v5 candidate channel remain intact, with no new
detection or persistence leak into `:core`. One new lifecycle finding remains:
the protocol `shutdown` request tears down the suggestion executors even though
the detached server keeps running, so a subsequent editor connection to the
same daemon cannot suggest. The loop cannot close until this is fixed or
explicitly deferred.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:308-311,179-185`
   with `nvim-renderer/lua/cint/ops/live_ops.lua:361-369`,
   `nvim-renderer/lua/cint/commands.lua:177-179,308-312`, and
   `nvim-renderer/lua/cint/transport/live.lua:313-317`
   — the protocol `shutdown` request permanently disables suggestions on a
   still-running reusable daemon.**

   `live_shutdown()` closes the client's documents and sends the JSON-RPC
   `shutdown` request on `:CintServerDisconnect` and `VimLeavePre`; the server's
   `shutdown` handler responds, calls `shutdownSuggestions()`, and continues its
   accept loop. The daemon is intentionally detached and reused across Neovim
   restarts (and `CintServerDisconnect` is only a client disconnect), so the
   next connection reaches a server whose `suggestionExecutor` and
   `providerExecutor` are already shut down. `cint/suggest` then throws
   `RejectedExecutionException` from `submit`, which surfaces as a generic error
   instead of working or reporting a deliberate disabled state. This also means
   one client's disconnect disables suggestions for every other connected
   client.

   Fix: keep executor teardown tied to process termination (the run loop exiting
   or the shutdown hook) and remove it from the protocol `shutdown` handler, or
   make `shutdown` actually stop the accept loop/server before tearing down the
   pools. Add a test that sends `shutdown`, reconnects to the same server, and
   either still suggests successfully or finds the server stopped.

### Concurs / Dissents

- Concurs with Round 2 finding 1 for boundedness, timeout, cancellation, and
  run/hook teardown: `suggestionExecutor` and `providerExecutor` are fixed pools
  sized by `workerThreads`, `providerFuture.get(timeoutMs)` interrupts on
  timeout, `cint/suggest/cancel` cancels the provider future, and the run loop
  and JVM hook call `shutdownSuggestions()`. Dissents on tying that teardown to
  the protocol `shutdown` request, filed as finding 1.
- Concurs with Round 2 finding 2: `LiveServer.publishCandidates` calls
  `suggestionService.group`, and `candidateGroupPayload` only maps the service's
  `CandidateGroup` to the wire shape. `SuggestionService.group` and
  `CandidateGrouping` are now live, and `suggestionService.anchorHash` is used
  for decision validation.
- Concurs with Round 2 finding 3: the analysis, config revision, and
  `analysisRevision` are sampled and assigned together under `synchronized(this)`;
  `analyses[key]` is updated there. The new barrier test pins config revision
  order against analysis revision during concurrent didOpen/config mutation.
- Concurs with group reconciliation and persistence: confirm/reject records now
  carry `anchorId` and span, `publishCandidates` resolves confirmed anchors,
  removes the whole group for a confirmed span, re-proposes after the user
  annotation is deleted, and leaves reject/skip candidate-scoped. Tests cover
  alternative confirmation, sibling disappearance, and persistence across a
  fresh server.
- Concurs with per-connection state: analyses, published candidates, analysis
  revisions, candidate locks/state revisions, in-flight passes/futures, and
  session skips are keyed by `DocumentKey(owner, uri)`; close/disconnect
  cleanup cancels in-flight work and removes that key's maps.
- Concurs with module boundaries and v5 shape: `:suggest` depends on `:api` and
  `:core`, `:core` does not depend on suggestions, and the server owns
  orchestration, settings, persistence, and the provider instance. Candidates
  still flow only through `AnalysisOutput`/`publishCandidates` and never enter
  `AnnotationStore`, composition, fixpoint identity, or `reads`. The
  `RuleEmit` split and anchor groups give 037-040 a usable way to emit
  deterministic candidates and AI alternatives without changing the hull.
- Dissents: finding 1.

### Residuals (not filed)

- `SuggestionOptions.model` is still unused: the configured model is advertised
  in `initialize.suggestions` but the provider/context and candidate provenance
  use the provider's own model.
- `maxCandidates` is applied per input candidate, not per document/pass, so
  candidate-heavy packs can exceed the configured total.
- Same-intent proposals from different providers still collapse because
  candidate identity/dedupe excludes source/provider; only different-intent
  alternatives survive as separate candidates.
- `CandidateContext` still lacks a source excerpt, language/imports, and an
  enclosing-function value; `neighboringIntents` is document-wide. The current
  fake provider does not need them, but a real provider will.
- Candidate identity construction is duplicated at
  `SuggestionService.candidateId` and `LiveServer.candidateId` (both delegate to
  `CandidateIdentity`, but the argument assembly can drift).
- `CandidateJson` and the server `candidatePayload` remain separate
  serializers; the artifact intentionally omits decision identity.
- `UserAnnotationsFile.add` and `SuggestionStore.record` are not coordinated;
  concurrent decisions can still lose one of the two records.
- The future maps are assigned after `submit`; a very fast completion can leave
  a completed future entry until the next suggestion or document cleanup. This
  is not currently user-visible.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.

## Round 4

Verdict: Round 3 finding 1 is fixed. The protocol `shutdown` handler no longer
tears down suggestion executors; teardown happens only when the run loop exits
or the JVM shutdown hook runs, and the new test confirms a later client can
still suggest against the same server. User-annotation writes now have both an
in-process lock and an OS file lock, with a concurrent-add test, and anchor
locks plus `anchorResolvedGlobally` prevent duplicate confirms and make a
second client's confirm of an already-resolved sibling fail. One new
cross-client consistency finding remains: confirm/reject republishes only the
requesting connection, so other connections with the same document keep showing
the resolved group until their next analysis. The loop cannot close until that
is fixed or explicitly deferred.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:966,1008`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:448-455`
   and `docs/engine.md:332-339`, `docs/ai-intents.md:189-190,209`
   — confirm/reject decisions are global but candidate-state republish is only
   sent to the requesting writer, so other clients keep stale resolved groups.**

   `handleCandidateDecision` writes the global user annotation/decision and then
   calls `publish(writer, doc)` for the connection that made the decision.
   `republishOpenDocs()` exists for config changes but is not used here. A second
   connection that has the same URI open still has its previous
   `publishedCandidates` list, so a confirmed group's sibling alternatives remain
   visible even though `anchorResolvedGlobally` will reject confirming them. The
   new cross-client test asserts only the `missing` response for the second
   client; it does not assert that the second client's candidate list clears.
   The docs say the siblings "are no longer published" and "disappear with the
   republished (possibly empty) candidate list," which is not true for the other
   connection. Reject has the same stale-list problem, since rejection is also
   global.

   Fix: after a global confirm/reject, republish candidate state to every writer
   with that URI open (keeping skip connection-local), or invalidate other
   clients' candidate state and force a candidate publish on their next request.
   Add a test that confirms in one connection and asserts the other connection's
   next/latest `cint/publishCandidates` no longer contains the group, and the
   same for reject.

### Concurs / Dissents

- Concurs with Round 3 finding 1: `shutdown` now only responds, while
  `shutdownSuggestions()` runs from the run loop exit and the JVM shutdown hook.
  `protocolShutdownDoesNotDisableSuggestionsForLaterClients` exercises a later
  client on the same server and gets `ai:fake`.
- Concurs with the anchor-lock hardening: `anchorLocks` serializes confirms per
  anchor, `anchorResolvedGlobally` checks persisted confirmations plus matching
  user annotations, and the second-client test shows a sibling confirm returns
  `missing`. Different-anchor concurrent confirms persist both annotations, and
  a stale skip no longer hides the candidate because `extraSkips` is applied
  before the session skip is recorded.
- Concurs with user-annotation locking: `UserAnnotationsFile.add` uses a
  per-root in-process lock and an OS file lock around read-modify-write, and the
  new `UserAnnotationsFileTest` verifies all concurrent additions survive. This
  closes the earlier lost-update residual.
- Concurs that there is no new module-boundary leak: `:suggest` remains the
  home of grouping/validation/merge, `:core` owns the shared candidate identity
  and candidate channel, the server owns orchestration/persistence, and the
  deterministic hull is unchanged (`AnalysisOutput`, no candidate entry into
  `AnnotationStore`, composition, fixpoint identity, or `reads`).
- Concurs that the v5 shape remains usable for 037-040: rules return established
  and candidate emits in one list, candidate groups carry alternatives, and the
  server can represent deterministic plus AI candidates with the same payload.
- Dissents: finding 1.

### Residuals (not filed)

- `docs/engine.md:232` is now stale: it still says both pools are shut down on
  the `shutdown` request, while the code deliberately leaves the server running.
  `docs/ai-intents.md` and `docs/notes.md` already say "with the server."
- `anchorLocks` is never evicted; a long-lived daemon retains one lock object per
  confirmed anchor even after the decision/annotation is removed.
- `SuggestionOptions.model` is still unused; the advertised model and the
  provider/candidate model can disagree (still only the fake provider exists).
- `maxCandidates` remains per input candidate rather than per document/pass.
- Same-intent proposals from different providers still collapse because
  candidate identity/dedupe excludes source/provider.
- `CandidateContext` still lacks a source excerpt, language/imports, and an
  enclosing-function value; `neighboringIntents` is document-wide.
- Candidate identity construction remains duplicated between
  `SuggestionService.candidateId` and `LiveServer.candidateId`.
- The futures maps are still populated after `submit`; a fast completion can
  leave a completed future entry until document cleanup.
- `SuggestionStore` decisions are not cross-process file-locked the way user
  annotations now are; multiple daemons on one root remain last-writer-wins.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.

## Round 5

Verdict: Round 4 finding 1 is fixed. `republishUri` now republishes candidate
state to every writer with the affected URI open after a confirm or reject,
while skip remains connection-local; the cross-client confirm and reject tests
assert the other writer's candidate list actually updates, not just that a
sibling decision is rejected. Skip is committed inside the candidate publish
under its expected-state guard, so a stale skip cannot hide a candidate. The
user-annotation lock was hardened into a shared directory lock used by both the
server and Neovim, with concurrent and lock-wait tests. I found no new
module-boundary or lifecycle leak in this commit, and the loop can close.

### Findings

- None.

### Concurs / Dissents

- Concurs with Round 4 finding 1: `republishUri(uri)` iterates every writer that
  has that URI open and calls `publish` for it, so confirm/reject clears the
  resolved group for the other client as well. `secondClientCannotConfirm...`
  now asserts the second client's `groups` is empty, and the new reject test
  asserts the second client's remaining candidate list. Skip still republishes
  only the requesting writer.
- Concurs with the skip atomicity change: `commitSkip` is applied inside
  `publishCandidates` after the analysis/state guards, and the successful-skip
  test interleaves a document change with the publish. A stale skip returns
  `stale` and does not enter `sessionSkipped`.
- Concurs with user-annotation locking: the server's `UserAnnotationsFile` and
  the Neovim `annotations.lua` now share the same
  `.cint/user-annotations.json.lock` directory protocol, with stale-lock
  cleanup, timeout, release in `finally`, and tests on both sides. The earlier
  lost-update path is closed.
- Concurs that no new module-boundary leak was introduced: `republishUri` stays
  in the server, `:suggest` still owns grouping/validation/merge, `:core` still
  owns the candidate identity/channel, and no candidate path enters the
  annotation store, composition, fixpoint, or `reads`. Executor teardown remains
  process/run-loop scoped, so client `shutdown` no longer disables the daemon.
- Concurs that the v5/candidate shape remains suitable for 037-040: pack
  candidates, AI alternatives, and cross-client decisions all use the same
  anchored candidate group.
- Dissents: none.

### Residuals (not filed)

- `docs/engine.md:232` still says the pools shut down on the protocol `shutdown`
  request; the code now intentionally shuts them down only with the server.
- `anchorLocks` still has no eviction; it retains one lock object per confirmed
  anchor for the daemon lifetime.
- The lock directory protocol is duplicated between Kotlin and Lua (path, stale
  age, timeout); it is consistent today but must be kept in sync manually.
- `SuggestionOptions.model` remains unused, `maxCandidates` remains per input
  candidate, same-intent multi-provider proposals still collapse, and the
  candidate context still lacks source/language/imports/enclosing-function data
  for a real provider.
- Candidate identity construction remains duplicated between
  `SuggestionService.candidateId` and `LiveServer.candidateId`.
- The futures maps are still populated after `submit`, so a fast completion can
  leave a completed entry until document cleanup.
- `SuggestionStore` decisions remain only in-process locked across daemons.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.
