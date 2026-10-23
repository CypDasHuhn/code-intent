# Correctness review — ticket 059 (AI restriction proposals)

## Round 1

Verdict: The core design is implemented in the right place and in the right order.
Confirmed groups are persisted separately from user annotations under the shared
`.cint/user-annotations.json.lock` protocol; analysis resolves them by path and
anchor hash, removes source contributions after user annotations and before
composition, adds `ai-confirmed` replacements, and lets composition recompute
derivative and scaffold annotations from the filtered source set. The normal
remove+add path works end to end, including restart persistence and inspector
removal. Four state/anchor defects remain, however: anchor resolution can rebind
a confirmed restriction to a different identical expression, removals outside the
proposal's anchor unit are accepted but lose their relative offsets on reload,
merged user/`ai-confirmed` provenance is not rejected by session validation, and
a decision republish drops every other pending proposal from the pass. The
`cint/suggest` restriction count also counts proposals that were not published.
Not ready to close.

### Findings

1. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:307-337` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:206-224` — a confirmed restriction can rebind to a different expression with identical anchor text, including when an identical line is inserted above the target.**

   `AiRestrictionResolver.anchor()` first tries the stored span and accepts it
   when `contentHash(selected) == record.contentHash`. If an identical call is
   inserted above the target, the stored span now selects the *new* call; its text
   has the same hash, so the resolver returns the new call and the relative
   offsets point into it. `LiveServerDecisions.symbolAt()` stores
   `record.symbol`, and the record also stores `unitFingerprint`, but the
   resolver never consults either: the fingerprint is set to the same content
   hash (`LiveServerDecisions.kt:218`) and `record.symbol` is not read. The
   fallback path (`units + named` filtered by hash, then `minByOrNull` by line
   distance) has the same ambiguity: after the target is deleted, any surviving
   identical expression is chosen instead of going dormant. Reproduction: two
   identical `log.warn(message)` calls in different functions, both annotated
   `LOGGING`; confirm a restriction on the second; insert an identical call
   immediately above the target (so the stored span now addresses the new call),
   or delete the target (so the fallback finds the other call). Re-analysis
   applies the removal to the wrong call, or removes nothing while the inspector
   reports `active`. This
   contradicts the design's path/symbol/content-hash anchoring
   (`docs/ai-annotation-session.md:244-246`) and the "target disappears →
   dormant" contract.

   Suggested fix: disambiguate anchors. Require a unique content-hash match, or
   match the stored `symbol` and ordinal/position within the enclosing
   declaration, and go dormant when the match is ambiguous. Populate
   `unitFingerprint` with a real unit key (enclosing symbol/kind plus occurrence)
   rather than the content hash, and use it in both the stored-span and fallback
   paths. Add tests with two identical calls where one is inserted above, moved,
   and deleted.

2. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:289-315` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:231-245` and `analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:131-147` — a removal that overlaps the verdict unit but starts before it is accepted, then loses its operation on reload.**

   `validateRemove` only requires overlap with the verdict unit
   (`remove.startByte >= unit.endByte || remove.endByte <= unit.startByte`),
   unlike `validateAdd`, which requires containment. `sessionDocument` exposes
   every overlapping annotation on every unit
   (`LiveServerDiscovery.kt:43`), so an inner unit (for example the `message`
   argument of `log.warn(message)`) shows the outer call's exact `LOGGING` span
   and the model can legitimately copy it into that unit's verdict. The proposal
   is anchored on the inner unit, and `relativeOperations` computes
   `operation.startByte - proposal.startByte`, producing a negative
   `startOffset`. `AiRestrictionsJson.operations` drops any operation with
   `start < 0`. Reproduction: confirm a group whose remove targets the enclosing
   call but whose verdict unit is the argument. The persisted record keeps only
   the add; because `AiRestrictionApplication.apply` skips the whole group when
   `removals == 0` (`AiRestrictionApplication.kt:21`), the replacement is never
   applied either. The inspector reports the record as `active` (anchor and add
   resolve), and the proposal id is treated as confirmed and suppressed. A
   remove-only group becomes unparseable and can be re-proposed on the next pass,
   so the user can confirm the same no-op repeatedly.

   Suggested fix: require removal spans to be contained in the verdict unit (the
   narrowing case still works when the verdict unit is the outer expression, as
   `validateAdd` already requires the replacement to be inside that same unit),
   or anchor the record on the removal's own annotation span instead of the
   unit. Reject or drop any persisted record with negative or out-of-anchor
   offsets rather than silently producing a partial record, and apply add-only
   records if they are meant to be valid. Add a reload test for a removal on an
   enclosing annotation submitted from a narrower unit.

3. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:298-306` — merged provenance is not checked, so a user-owned or `ai-confirmed` intent at a mixed-source span can be proposed and confirmed for removal.**

   `validateRemove` rejects `annotation.source == USER_SOURCE` or
   `AI_CONFIRMED_SOURCE`, but `AnnotationStore.annotations()` sets `source` to
   `"mixed"` whenever the provenance sources differ
   (`AnnotationStore.kt:76`). A span with both a pack `LOGGING` contribution and
   a user `LOGGING` contribution therefore has `source = "mixed"`, passes the
   check, and is accepted as a removal target. `SessionAnnotation.provenance`
   already carries `"<rule>:<source>"`, so the information is available but
   unused. Reproduction: user annotate `log.warn(message)` with `LOGGING` while
   the logging pack also establishes `LOGGING` at the same exact span (or confirm
   a candidate at that span). The AI proposes `− LOGGING + SEARCH`; validation
   accepts it; confirm writes a record; analysis protects the user contribution,
   removes only the pack contribution, and still applies the `SEARCH` add. The
   group advertises a removal that never happens, and the same span keeps
   `LOGGING` plus the replacement. A mixed span that contains an `ai-confirmed`
   contribution has the same problem, although application protects it by rule
   prefix.

   Suggested fix: reject when any provenance entry is the user contributor
   (`core:user-annotations:user`) or an `ai-confirmed` contributor, not only
   when the merged `source` is exactly that value. Add a core test where a
   `SessionAnnotation` with `source = "mixed"` and a user provenance entry is
   targeted, and a server test where the user annotation and pack annotation
   share the exact span.

4. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:135` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:113-134` — acting on one proposal drops every other pending proposal from the same AI pass.**

   `publish()` calls `publishCandidates(...)` without proposals, and
   `publishCandidates` always replaces `publishedProposals[key]` with the list it
   was given. Proposal confirm and reject both call `republishUri(uri)`, and
   candidate confirm does too, so after any of those decisions every remaining
   proposal group disappears from the next `publishCandidates` payload and from
   `publishedProposals`. A subsequent `cint/candidates/decide` with the second
   group's `proposalId` finds neither a proposal nor a candidate and returns
   `{accepted:false, missing:true}`; the client cannot review the rest of the pass
   without running a new `cint/suggest` (and the provider may produce a different
   set). Skip decisions (proposal and candidate) preserve the list because they
   republish through `publishCandidates` with the current proposals.
   Reproduction: a provider returns two units with remove+add verdicts; one
   `cint/suggest` publishes two `proposalId` groups; confirm the first; the
   second decide returns `missing` and the republished group list is empty.

   Suggested fix: carry the unconfirmed proposals for the current document
   version alongside the cached analysis and republish them (filtered by the
   decision/skip/restriction sets) after re-analysis, or retain them in
   `publishedProposals` until the document version/content changes. If decisions
   are intentionally one-group-per-pass, document that and return a specific
   reason rather than `missing`. Add a two-proposal test that confirms the first
   and still decides the second.

5. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:218,319-329` — `cint/suggest` reports the unfiltered proposal count.**

   `suggestResponse` sets `restrictions` to `outcome.proposals.size`, while
   `publishCandidates` filters proposals by restriction ids, denials, and session
   skips before publishing. Reproduction: deny the only proposal in the existing
   fixture, then run another `cint/suggest`; the response has `restrictions = 1`
   while `count = 0` and the published `groups` contain no proposal. The same
   happens after a session skip. A client badge or count driven by that field
   will show a restriction that cannot be reviewed. Suggested fix: return the
   published proposal count (or a small result object) from `publishCandidates`
   and use that number in `suggestResponse`; add an assertion to the deny and
   skip tests.

6. **`analysis-engine/core/suggest/src/test/kotlin/dev/cint/suggest/session/AnnotationSessionTest.kt:297-550`, `analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/suggest/LiveRestrictionProposalTest.kt:39-177`, and `analysis-engine/tests/integration-tests/src/test/kotlin/dev/cint/integration/restriction/AiRestrictionResolutionTest.kt:34-203` — the tests pin the happy path but not the regressions above.**

   The core narrowing test uses `unit("u0", 0, text.length)`, so its removal is
   always inside the verdict unit and cannot expose finding 2; the server test
   removes exactly the unit span. The user-protection test uses
   `source = "user"` rather than the production merged `"mixed"` shape, so
   finding 3 is invisible. The resolution tests use unique source text, so
   finding 1's duplicate-expression case is untested. The server test has a single
   proposal and never decides a second group or exercises `skip`, so finding 4
   and the skip path are unexercised. There is no test for
   `restrictionsEnabled = false`, a remove-only group end to end, a
   `request` whose removes all fail leaving an add-only candidate (the existing
   core test covers the candidate path but not its persistence), or the
   `restrictions` count after suppression.

   Suggested fix: add the tests named in findings 1-5, plus a two-call fixture
   with one identical call deleted/inserted, a mixed user/pack span, a
   remove-only proposal confirmed and reloaded, and a `restrictionsEnabled=false`
   capability/proposal test. These are the cases that would fail on the current
   tree; the existing happy-path tests also pass with the defects above.

### Concurs / Dissents

- Concurs with the persistence split: one `.cint/ai-restrictions.json` record per
  confirmed group, written under the shared user-annotation lock, and no
  `.cint/user-annotations.json` write for grouped removes. The round-trip
  `AiRestrictionsFileTest` covers the file and the separate lock protocol is
  respected by `add`/`remove`.
- Concurs with the application order and provenance: restrictions are resolved
  after the scheduler and before `AnnotationStore`/`Composition`, user
  contributions are protected by rule and source, `PURE`/`READONLY` are
  rejected, unresolved operations are dropped, and `ai-confirmed` additions are
  recomputed on every analysis. `removingLoggingRecomputesDerivativeAnnotations`
  and `narrowingKeepsOuterRemovalAndAddsOnTheInnerSpan` are meaningful checks of
  that shape.
- Concurs with the group decision plumbing for the normal case: one record is
  written for a confirmed group, deny is persisted in `.cint/suggestions.json`
  and filtered on later passes, skip is session-only, and the inspector
  list/remove path re-analyzes open documents. Add-only decisions still write
  user annotations.
- Concurs with protocol gating: the restriction capability is advertised only
  for protocol-4 clients with a session provider and `restrictionsEnabled`, and
  the inspector methods reject older clients.
- Dissents: findings 1-6. The happy-path behavior is real; the failures are in
  anchor identity, removal containment, merged provenance, and proposal
  lifecycle, not in the ordinary remove+add case.

### Residuals

- **No API v6 bump — acceptable, not a finding.** Proposals are a suggest-layer
  and protocol payload model (`CandidateProposal`/`ProposalOperation`); the
  frozen `Annotation`, `Emit`, and `toContribution` surfaces are untouched, and
  no pack API needs to express removals. A v6 bump would be gratuitous and would
  break existing packs for no contract gain. The ticket's "preferred" v6 shape is
  a preference, not an acceptance criterion.
- **Anchor-text change makes a record dormant — acceptable for 059.** Content
  hashing is the specified anchor, and line moves without text changes already
  reanchor. Normalizing formatting changes belongs to 060's unit fingerprints and
  decision reuse. Note that the same behavior means a whitespace-only edit inside
  the anchored unit silently restores the original annotation; that is the
  documented cost of the content-hash anchor, not a defect in this slice. The
  duplicate-text rebinding in finding 1 is a different failure and is not covered
  by this residual.
- **Deny suppression keyed by anchor — acceptable per design, with a caveat.**
  The design says denying suppresses the proposal at that anchor, and the record
  stores the anchor id plus the denied proposal id. A *different* later proposal
  at the same unchanged anchor is also suppressed, and any anchor text/position
  change drops the suppression; 060's normalized decision fingerprints should key
  this by the operation signature. This is broader than the ticket text but
  consistent with the current design.
- **Split-region plumbing untested — acceptable for now, latent gap.** The
  region scoping in `HostPipeline.scopeRestrictionsToRegion` mirrors the user
  annotation path except that it keeps the host content hash; a record clipped by
  a region boundary would therefore never reanchor and would be silently dormant.
  With the current Vue frontend, expression units are enumerated from the
  whole-file tree and the script regions do not produce usable restriction
  anchors, so there is no reachable failing case in this ticket. Add coverage
  when a split language starts producing in-region units, and recompute the
  clipped hash as `scopeToRegion` does for user annotations.
- **Rationale text retained — acceptable for 059.** The restriction file is
  local engine-owned review state and the inspector shows the rationale; the
  "omit rationale from disk" rule in `ai-annotation-session.md` is written for
  the 060 verdict cache, not this file. If the same privacy posture is intended
  for all `.cint` AI state, strip rationale at persist time in 060.
- The record's `unitFingerprint` is currently the same value as `contentHash`
  (`LiveServerDecisions.kt:218`) and `symbol` is stored but unused by
  `AiRestrictionResolver`. This is the data behind finding 1 rather than a
  separate defect; populate a real fingerprint when fixing it.
- `AiRestrictionsFile.add`/`remove` use `loadTolerant`, so a parse or IO error
  replaces the entire restriction file with only the new record (the same policy
  as `UserAnnotationsFile.add`). The file is written atomically by the engine, so
  this is an external-corruption path rather than a normal write race; keep it on
  the 060 persistence cleanup if stronger recovery is wanted.
- The new restriction test files and this review directory are currently
  untracked; they must be staged with the implementation commit.
- Read-only review: no source, test, build, lint, or formatter command was run.

## Round 2

Verdict: Findings 2, 3, and 5 are resolved. Finding 4 is resolved for the
deciding writer, but the carry mechanism now sends that writer's proposals to
every other client and still does not preserve the session's AI add-only
candidates. Finding 1 is only partially resolved: the new unit fingerprint still
rebinds an adjacent inserted duplicate, and the new test asserts that rebind
rather than the original or dormancy. The round-2 change to the `ai-confirmed`
contributor also drops provider/model/prompt from the contribution. New findings
7-10 are open; not ready to close.

### Per-finding status

1. **Dissent — the duplicate-anchor failure is still reproducible for an adjacent insertion, and the new test pins the rebind.**

   `AiRestrictions.kt:319-358` builds the fingerprint from the node kind,
   normalized text, enclosing symbol, `nextSiblingText`, and the ordinal among
   units with that same text/symbol/sibling; `anchor()` (`:362-399`) accepts a
   record only when exactly one current unit has that fingerprint. In
   `AiRestrictionResolutionTest.kt:249-310` the source has one `log.warn(message)`
   in `fun a()` and one in `fun b()`, and the record is built for the last call
   (b's). The insertion case appends a second identical call *after* b's call and
   then asserts
   `inserted.lastIndexOf("log.warn(message)")` (line 283), which is the newly
   inserted call, not the recorded target. Mechanically, inserting after the
   target changes the target's `nextSiblingText` from empty to
   `"log.warn(message)"`, so the target no longer matches the stored fingerprint;
   the inserted call has the old next sibling and the old ordinal and matches. The
   restriction is therefore applied to the inserted call. Inserting an identical
   call immediately *above* the target changes the ordinal in the same way and
   also rebinds. This contradicts the new snapshot claim that duplicate text goes
   dormant instead of rebinding (`docs/ai-annotation-session.md:541-543`) and the
   original finding.

   Separately, the legacy branch
   `if (storedMatches && symbolFiltered.size == 1) return stored` (`:397`) never
   checks that `stored` is one of the symbol-filtered candidates. For a record
   whose `unitFingerprint` equals `contentHash` (records written before this
   round) or whose fingerprint could not be computed, a stored span that now
   holds a different identical call is returned whenever exactly one other call
   has the recorded symbol; the symbol guard is bypassed. Round-1 records in a
   local `.cint/ai-restrictions.json` are exactly this shape.

   Suggested fix: when the stored span, symbol, and fingerprint disagree, prefer
   a candidate that matches all of them and otherwise go dormant; include the
   previous sibling (or the enclosing statement) in the fingerprint so an
   adjacent insertion does not transfer the identity to the new call; never
   return `stored` unless it is in `symbolFiltered`. Update the duplicate test to
   assert the original target or dormancy, not `lastIndexOf`, and add insertion
   above as well as below.

2. **Concur — addressed.** `AiRestrictionsJson.operations` now permits negative
   `startOffset`, `parseEntry` requires at least one removal, `resolve` accepts
   removals that overlap the anchor and rejects disjoint ones, and server confirm
   validates removals against the anchor length. The child-unit negative-offset
   test (`AiRestrictionResolutionTest.kt:208-247`) and the file round-trip test
   (`AiRestrictionsFileTest.kt:75-108`) cover the reported failure. The server
   confirm path itself is still only exercised with unit-span removals; add a
   server-level child-unit proposal in the test pass.

3. **Concur — addressed.** `isProtectedFromRemoval`
   (`AnnotationSession.kt:327-337`) checks both the merged `source` and every
   provenance entry, including the `core:user-annotations:` and
   `core:ai-confirmed:` prefixes. `mixedSourceWithUserProvenanceCannotBeRemoved`
   and `mixedUserAndPackSpanCannotBeProposedForRemoval` reproduce the merged
   shape. The rule is conservative (any user provenance blocks a removal even
   when the user owns a different intent at the span), which is safe for the
   acceptance rather than a correctness failure.

4. **Concur for the reported reproduction; new findings 7 and 8.** The
   `carriedProposals`/`remainingProposals` path preserves unrelated proposal
   groups for the deciding writer, and `confirmingOneProposalPreservesTheOtherPendingGroup`
   and `candidateConfirmDoesNotClearPendingProposalGroups` cover it. The carry is
   not scoped to the deciding writer and does not include add candidates, which
   is filed separately.

5. **Concur — addressed.** `publishCandidates` returns
   `CandidatePublishResult(candidates, proposals)`,
   `suggestResponse` reports the published proposal count, and the field is
   omitted for protocol-3 clients. The deny, skip, and multi-proposal tests
   assert the post-filter counts.

6. **Dissent — partially addressed.** The new tests cover negative offsets,
   mixed provenance, two proposals, remove-only groups, disabled restrictions,
   and the post-filter count. However, the duplicate-anchor test asserts the
   inserted occurrence (finding 1), the provenance assertions in
   `LiveRestrictionProposalTest.kt:135-139` and
   `AiRestrictionResolutionTest.kt:152` now assert only
   `rule == "core:ai-confirmed"` (finding 9), and there is no test for the
   cross-writer carry (finding 7), the lost add candidates (finding 8), the stale
   carried group (finding 10), or a server-level child-unit confirmation. The
   `maxCandidates` test covers two proposals but not the documented add-first
   priority when both adds and proposals are present.

### New findings

7. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerConfig.kt:123-131` with `LiveServerDocument.kt:81-143` and `LiveServerDecisions.kt:101,143,204,235` — a decision republish pushes the deciding writer's proposals into every other client that has the URI open.**

   `republishUri(uri, proposals)` loops all writers and calls
   `publish(writer, doc, proposals)` for each; `publish` forwards the same
   `carriedProposals` list to `publishCandidates`, which then overwrites
   `publishedProposals[DocumentKey(writer, uri)]`. The proposals belong to the
   deciding writer's session. Reproduction: two clients open the same URI; each
   runs `cint/suggest` and receives one proposal; client A confirms it; the
   republish to B is called with A's remaining list (empty), so B's snapshot
   loses its own pending proposal and a subsequent decide with B's proposal id
   returns `missing`. If A had two groups, B receives A's second group, which B's
   session never generated. For a protocol-3 writer, the same path appends a
   proposal group to `cint/publishCandidates`; `includeOperations` is false but
   `proposalGroupPayload` still emits `proposalId`/`groupId`, so a protocol-3
   client sees a restriction group despite the documented gate. If B's document
   version differs from A's, `PublishedProposal.version` is set to B's version
   while the span and content hash remain A's, so the published group is
   internally inconsistent.

   Suggested fix: carry proposals only for the deciding `DocumentKey`, and
   preserve each other writer's existing proposals (or revalidate them against
   that writer's document version/content hash) instead of replacing them with
   the deciding writer's list. Omit proposal groups entirely for protocol < 4.
   Add a two-writer test and a protocol-3 writer test.

8. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:137-143` with `LiveServerSuggestions.kt` and `LiveServerDecisions.kt` — the session's AI add-only candidates are still discarded by a decision republish, despite the snapshot claiming both proposals and add candidates are preserved.**

   A suggestion pass publishes `outcome.candidates` (the cached deterministic
   candidates plus discovery `result.candidates`). A decision republish calls
   `publish`, which re-analyzes and passes only `output.candidates` from the new
   analysis plus the carried proposals; `CachedAnalysis.output` never contains
   the discovery add candidates, and `publishCandidates` replaces
   `publishedCandidates[key]` with the newly passed list. Reproduction: a
   discovery pass yields one AI add-only candidate at a span with no
   deterministic candidate and one proposal; confirm the proposal; the AI
   candidate's group is absent from the republished `cint/publishCandidates` and
   a decide with its id returns `missing`. `docs/ai-annotation-session.md:531-534`
   says the republish preserves "every unrelated pending proposal and add
   candidate"; the code preserves only proposals.

   Suggested fix: carry the session's add candidates per writer/version along
   with proposals (or merge them back into the cached candidate state), or amend
   the snapshot/docs to say only proposals are preserved. Add a test with one AI
   add candidate and one proposal, confirm the proposal, and assert the add
   candidate is still published.

9. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictionApplication.kt:34,83` — the `ai-confirmed` contribution no longer carries provider/model/prompt provenance.**

   The replacement contribution is now created with
   `rule = AI_CONFIRMED_CONTRIBUTOR_ID` (bare `core:ai-confirmed`), and
   `removable` compares the rule exactly. Round 1 encoded
   `core:ai-confirmed:<provider>:<model>:<prompt>` in the rule, which is the only
   provenance carrier on the frozen `Contribution`/`Annotation` shape. The
   restriction record still stores provider/model/prompt, but the published
   contribution's provenance exposes only `core:ai-confirmed`; the tests were
   changed to assert that weaker fact (`AiRestrictionsFileTest.kt:148-150`,
   `LiveRestrictionProposalTest.kt:135-139`,
   `AiRestrictionResolutionTest.kt:150-152`). The ticket scope explicitly asks
   for "an `ai-confirmed` source contribution with provider/model/prompt
   provenance" (`docs/tasks/059-ai-restriction-proposals.md:33-34`); the amended
   design now says the record carries them instead
   (`docs/ai-annotation-session.md:206-210`).

   Suggested fix: restore the provider/model/prompt-bearing rule (and a
   `startsWith(AI_CONFIRMED_CONTRIBUTOR_ID)` protection check), or add a
   provenance carrier to the contribution and keep the record fields for
   inspection. If the record is intentionally the only carrier, record that
   interpretation in the ticket/acceptance and keep a test that asserts the
   record's fields; do not leave the earlier provenance test weakened silently.

10. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:241-248` with `LiveServerCandidates.kt:114-135` and `AiRestrictionApplication.kt:21` — carried proposals are not revalidated after the decision re-analysis, so a second group can become a silent no-op.**

   `remainingProposals` filters only by id; `publishCandidates` then publishes
   the carried proposals without comparing their removal targets with the
   newly analyzed annotations. Reproduction: two units overlap the same
   established annotation (an outer call and its argument both expose the
   annotation); a provider emits `− LOGGING + SEARCH` for both, producing two
   proposals that target the same established span. Confirm one; the second is
   carried into the republished state. Confirm the second; its removal target is
   gone (or is now the `ai-confirmed` replacement), so
   `AiRestrictionApplication.apply` sees `removals == 0` and applies neither the
   removal nor the replacement, yet a second record is persisted and the decide
   response is accepted. The same happens when the first decision changes the
   target's source to `ai-confirmed` or user-owned.

   Suggested fix: revalidate carried proposals against the new
   `CachedAnalysis.output.annotations` and the restriction/decision state before
   republishing, dropping groups whose removals no longer match an established
   unprotected `SOURCE_INTENT`, or mark them stale in the payload. Add a
   two-overlapping-group test that confirms the first and then decides the
   second.

### Concurs / Dissents

- Concurs: finding 2's negative-offset persistence and child-unit removal;
  finding 3's provenance protection; finding 5's post-filter count and
  protocol-3 response gate; the same-writer proposal carry; the documented
  add-first `maxCandidates` behavior; the `restrictionsEnabled` inspector gate;
  the normal protocol-3 path (`operations` omitted, `restrictions` omitted).
- Concurs: `AiRestrictionsFile` still writes one record per confirmed group
  under the shared lock, rejects add-only records, and the remove-only and
  restart tests cover it.
- Dissents: finding 1 (adjacent duplicate insertion still rebinds and the test
  asserts it), finding 4 is resolved only for the deciding writer and the
  add-candidate half of the lifecycle is missing, finding 6 is partial, and new
  findings 7-10.

### Residuals

- The fingerprint's `nextSiblingText`/ordinal heuristic is inherently fragile
  around adjacent duplicates; even after finding 1 is fixed, 060's diff-based
  mapping is the durable answer. Until then, a disagreement between the stored
  span and the fingerprint should go dormant rather than choose another
  occurrence.
- Records written before round 2 have `unitFingerprint == contentHash`; they use
  the legacy symbol/stored branch, which is the path behind the second half of
  finding 1. Treat them as a migration case (or dormant when the stored span's
  symbol does not match) until they are re-confirmed.
- `republishOpenDocs` (config changes) and `cint/analyze` still call `publish`
  with no proposals and clear pending groups. Config changes legitimately
  invalidate the AI environment; a same-version `cint/analyze` is less clear and
  should be decided or documented.
- `scopeRestrictionsToRegion` still keeps the host content hash for clipped
  records; the Vue split path remains unexercised. Unchanged from round 1.
- `AiRestrictionsFile.add`/`remove` still use `loadTolerant`, so an external
  parse error can replace the file with only the new record; unchanged and
  mirrored from user annotations.
- `maxCandidates` now caps add candidates first and then proposal groups with the
  remaining slots. This is documented and tested; it is a deliberate priority,
  not a defect.
- Review remains read-only: no source, test, build, lint, or formatter command was
  run.

## Round 3

Verdict: Most Round 2 items are resolved. The new fingerprint uses both siblings
and the duplicate test now asserts dormancy for adjacent insertion; the decision
republish is scoped per writer and no longer leaks proposals to protocol-3
clients or mixes document versions; AI add-only candidates are carried through a
decision; `ai-confirmed` carries structured provider/model/prompt fields on the
contribution; carried proposals are revalidated at republish; restriction removal
republishes with carry; confirms are gated by `restrictionsEnabled`; and the
reader rejects unknown file versions. Three defects remain. Inspector status and
proposal revalidation use a merged-annotation protection predicate that does not
match the application's contribution-level removal, and the remove-only status
branch reports inert records as active. A future-version restriction file is
ignored by the reader but overwritten by `add`, losing its records. Proposal
confirms reject-time revalidation and state guarding are still missing, so a
concurrent decision can confirm a group whose target another decision just
removed. Legacy records without a real fingerprint can still follow a same-symbol
duplicate. Not ready to close.

### Per-item status

- **Duplicate-anchor identity and dormancy — concur for records with a real
  fingerprint; residual for legacy records.** `AiRestrictions.kt:329-359` now
  includes `previousSiblingText` and `nextSiblingText`, and `anchor()`
  (`:387-427`) requires one unique fingerprint match; the legacy branch returns
  `matchingStored.singleOrNull()`, so it cannot return a range whose symbol
  differs from `record.symbol`. The updated
  `duplicateAnchorTextDoesNotRebindAfterInsertionMovementOrDeletion`
  (`AiRestrictionResolutionTest.kt:249-326`) asserts `isEmpty()` for insertion
  after and after a comment, `isEmpty()` for deletion, and the target for a
  blank-line move; the reported `lastIndexOf` rebind is gone. Two residuals
  remain: the test's "insertedAbove" string actually inserts a duplicate after
  the target (with a comment) rather than before it, so literal insertion above
  is not covered; and a legacy record whose `unitFingerprint` equals
  `contentHash` (round-1 files) or is null uses the stored-span branch, so an
  identical call inserted immediately above the target in the same symbol
  leaves the stored span on the inserted call and it is returned. That does not
  return a mismatched symbol, but it does not go dormant either. The docs claim
  duplicate text is dormant (`docs/ai-annotation-session.md:544-546`); migrate
  or re-confirm old records, or apply the same uniqueness rule to the legacy
  branch.

- **Per-writer decision republish — concur, fixed.** `republishUri`
  (`LiveServerConfig.kt:126-152`) takes the deciding `DocumentKey`; the deciding
  writer gets its remaining proposals/candidates and every other writer gets its
  own published lists filtered by `version`. `publishCandidates`
  (`LiveServerCandidates.kt:114-137`) emits no proposal list for protocol < 4.
  `differentClientsKeepTheirOwnProposalsAndProtocolThreeSeesNoGroups` covers two
  protocol-4 writers and a protocol-3 writer.

- **AI add-only candidates survive a decision — concur, fixed.**
  `publish` merges `carriedCandidates` with the new deterministic candidates
  (`LiveServerDocument.kt:138-159`), and the decision paths pass the deciding
  writer's published candidates. `aiAddOnlyCandidateSurvivesProposalDecision`
  confirms the proposal and then confirms the surviving AI candidate.

- **Structured `ai-confirmed` provenance — concur, fixed.** `Contribution` has
  additive `provider`/`model`/`promptVersion` fields
  (`analysis-engine/core/api/.../Model.kt:57-59`), `AiRestrictionApplication`
  copies them from the resolved record (`:42-44`), and `AnnotationJson` emits
  them in provenance. Both the core application test and the server confirm test
  assert all three fields.

- **Carried proposals revalidated — concur for the sequential path; new
  finding 13 for the concurrent path.** `revalidatedProposals`
  (`LiveServerRestrictionValidation.kt:16-31`) checks the anchor hash, requires
  a removal, and requires every removal target to be an unprotected established
  annotation in the new analysis. `publish` applies it to carried proposals.
  `staleCarriedProposalIsDroppedAndCannotBeConfirmed` confirms the first of two
  overlapping groups and verifies the second is `missing` and no second record
  is written. There is no decide-time revalidation or state guard; see finding
  13.

- **Restriction-removal republish preserves pending review state — concur by
  inspection, needs a test.** `handleRestrictionsRemove` calls
  `republishOpenDocs(carry = true)` (`LiveServerRestrictions.kt:70-72`), which
  carries each writer's own proposals and candidates (`LiveServerConfig.kt:112-124`),
  and `publish` revalidates proposals against the annotations restored by the
  removal. No dedicated test exercises a pending proposal across a restriction
  removal; add one.

- **Add-only record status/application agreement — dissent.** Add-only records
  (empty `remove`) are rejected on parse, and `resolveRecord` requires a
  resolved removal, which is correct. But `LiveServerRestrictions.status`
  (`:93-113`) sets `removeOnly = single.add.isEmpty()` and marks the record
  active even when `removeApplies` is false. A remove-only record whose target is
  absent, user-owned, or `ai-confirmed` is inert in analysis
  (`AiRestrictionApplication.apply` skips when `removals == 0`) yet is listed
  `active`; `docs/ai-annotation-session.md:551-553` says it is dormant. See
  finding 11.

- **`restrictionsEnabled` gates confirms — concur.** `handleProposalDecision`
  checks the setting before any decision (`LiveServerDecisions.kt:169-171`);
  `disabledRestrictionsRejectAConfirmEvenWhenAProposalIsAlreadyCached` injects
  a cached proposal and gets `{accepted:false, disabled:true}` with no file
  written. The inspector methods already gate on the same setting.

- **File version handling — dissent.** `AiRestrictionsJson.parse`
  (`:65-80`) rejects unknown versions and the test covers parsing a version-2
  file as empty. `AiRestrictionsFile.add` (`:223-234`) calls `loadTolerant`,
  receives an empty list for that same file, and rewrites it as version 1 with
  only the newly confirmed record. See finding 12.

### New findings

11. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerRestrictionValidation.kt:42-68` with `LiveServerRestrictions.kt:93-113` — inspector status and proposal revalidation use merged-annotation protection, and remove-only records are reported active when application does nothing.**

    `isProtected()` returns true when the merged annotation has any user or
    `ai-confirmed` provenance or any `PURE`/`READONLY` intent. The application
    works per contribution (`AiRestrictionApplication.removable`,
    `:79-90`): at a span containing a pack `LOGGING` and an `ai-confirmed`
    `SEARCH`, it removes the pack `LOGGING`, but `removalsApply` sees the
    `ai-confirmed` provenance and returns false. A confirmed record that removes
    `LOGGING` there is therefore listed `dormant` while analysis actually removes
    it, and a pending proposal to remove it is dropped by
    `revalidatedProposals` even though it would apply. The same over-broad
    predicate makes a pack removal at a span with a user `PURE` look dormant.
    Separately, `status` computes `removeOnly = single.add.isEmpty()` and ORs it
    into the active result: a remove-only record whose target is gone, user-owned,
    or `ai-confirmed` is inert (`removals == 0` in the application) but reported
    `active`. `addPresent` checks `annotation.source == "ai-confirmed"` exactly;
    a replacement merged with a pack or user contribution has source `"mixed"`,
    so a record whose add was applied is reported `dormant`. Reproduction:
    confirm a restriction that adds `SEARCH` at a span where a user `PURE`
    annotation also exists (or where the user later annotates it); the add is
    applied, but `cint/restrictions/list` says dormant. Remove the add from a
    record whose `LOGGING` target does not exist; it says active and analysis
    does nothing.

    Suggested fix: use one predicate shared by the application, revalidation,
    and inspector status. It should test each provenance contribution (SOURCE,
    exact span/intent, not user, not ai-confirmed, no directive intents) rather
    than the merged `Annotation`; for `addPresent`, look for an ai-confirmed
    contribution in provenance. Report `active` only when a removal or
    replacement actually applies, and only use `removeOnly` together with
    `removeApplies`. Add tests for a remove-only stale record, a merged
    ai-confirmed add, and a pack removal at a span with ai-confirmed/user
    provenance.

12. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:223-234,65-80` — an unsupported-version restriction file is read as empty and then overwritten by the next confirm.**

    `AiRestrictionsJson.parse` returns an empty list when the file version is not
    1, which is the documented reader behavior. `AiRestrictionsFile.add` cannot
    distinguish that from a missing file: it calls `loadTolerant`, gets an empty
    list, and `writeLocked` writes a version-1 file containing only the new
    record. Reproduction: write a version-2 `.cint/ai-restrictions.json` with
    records, confirm a proposal; the file becomes version 1 with the new record
    and every prior record is gone. The same downgrade loses the restrictions
    from the analysis and makes previously confirmed proposals eligible for
    republication. `remove` happens to return false without writing, so it is
    safer than `add`.

    Suggested fix: return an explicit `Absent`/`Version(1)`/`Unsupported`
    result from the file loader. `add` should refuse to write when an unsupported
    file exists (or preserve it and use a separate file), and the caller should
    surface an error rather than silently clobbering it. Add a test that
    `AiRestrictionsFile.add` against a version-2 file does not replace its
    records.

13. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:225-259` with `LiveServerDocument.kt:138-143` — proposal confirm/reject still has no decide-time revalidation or state guard, so a concurrent decision can confirm a stale group.**

    `revalidatedProposals` runs only inside `publish` after a decision has already
    written its record and republished. A second client's decision can read its
    `publishedProposals` entry before the first client's republish updates it;
    `handleProposalDecision` then checks only the anchor text hash and writes a
    second record. If both groups target the same established annotation, the
    second record's removal no longer applies; the application sees
    `removals == 0` and does nothing, but the decide response is accepted and the
    record is persisted. `baseStateRevision` is captured but used only by the
    skip path; confirm and reject do not compare it, and they do not take
    `candidateLocks`/a decision lock. `staleCarriedProposalIsDroppedAndCannotBeConfirmed`
    waits for the first republish, so it does not cover this race.

    Suggested fix: serialize proposal decisions on the same per-document lock as
    candidate publication, and revalidate the removal targets with
    `removalsApply` against `analyses[key].output.annotations` plus the current
    state/analysis revision before writing the record. Add a two-client or
    barrier test where both decisions read the pre-decision proposal list.

### Concurs / Dissents

- Concurs: the new unit fingerprint and duplicate test for records with real
  fingerprints; per-writer decision republish and protocol-3 proposal gating;
  carried AI add candidates; structured provider/model/prompt contribution
  fields; sequential carried-proposal revalidation; restriction-removal carry;
  `restrictionsEnabled` confirm gating; unknown-version reader rejection;
  negative-offset persistence; one-record-per-group persistence; `maxCandidates`
  add-first cap; and the normal active/inspector path.
- Dissents: finding 11 (status and revalidation use the merged-annotation
  predicate; remove-only status is always active), finding 12 (unknown-version
  file is overwritten by `add`), and finding 13 (no decide-time revalidation or
  state guard). The legacy same-symbol stored-span rebind is a residual rather
  than an open finding because the stated requirement was a mismatched symbol
  and the new test covers records with a real fingerprint.

### Residuals

- Legacy records with `unitFingerprint == contentHash` or null still use the
  stored-span branch; an identical call inserted immediately above the target in
  the same symbol can be selected because its symbol matches. Re-confirm old
  records or apply a uniqueness check to the legacy branch. The new duplicate
  test writes a real fingerprint via `recordFor`, so it does not pin this path.
- `republishOpenDocs(carry = true)` (`LiveServerConfig.kt:112-124`) carries
  `publishedProposals`/`publishedCandidates` without a version filter, unlike
  `republishUri`; normal didChange/didOpen publishes make the versions agree, but
  a defensive filter would keep the state exact.
- `mergeCandidates` (`LiveServerDocument.kt:147-159`) does not revalidate
  carried AI add candidates against the new annotations. After another decision
  establishes or protects the same intent/span, the carried candidate can be
  republished even though it would be a duplicate; add a candidate analogue of
  `revalidatedProposals` if review state must stay exact.
- `Contribution` gained `provider`/`model`/`promptVersion` without an API version
  bump. The `Annotation` shape is unchanged and normal pack contributions leave
  the fields null, so existing rule packs are source-compatible; direct
  constructors in downstream code would see a constructor change. Keep the
  additive-fields note in the API docs.
- `cint/analyze` still calls `publish` without carry and clears pending proposals
  and AI add candidates on the same document version. Config changes legitimately
  invalidate AI state; a same-version manual analyze is a policy decision and
  should be documented.
- Unchanged residuals: `scopeRestrictionsToRegion` keeps the host content hash
  for clipped records; `AiRestrictionsFile` uses `loadTolerant` for its
  read-modify-write; rationale is retained in the restriction file; the split
  frontend path remains untested.
- Test gaps for the open items: no remove-only dormant status test (finding 11),
  no `AiRestrictionsFile.add` against a version-2 file (finding 12), no
  concurrent proposal-decision test (finding 13), no legacy same-symbol
  duplicate test, no restriction-removal-with-pending-proposal test, and no
  carried-candidate revalidation test. The existing tests remain non-vacuous for
  the paths they cover.
- Review remains read-only: no source, test, build, lint, or formatter command was
  run.

## Round 4

Verdict: All three Round 3 findings are resolved and the four recorded residuals
are addressed. Inspector status and proposal revalidation now use the same
per-contribution removal predicate as `AiRestrictionApplication`, `removeOnly` is
gone, and merged `ai-confirmed`/pack plus remove-only inert cases are tested. An
unsupported future file version now makes `add`/`remove` fail without rewriting
the file, and the test pins the original bytes. Proposal decisions are serialized
by `proposalDecisionLock`, re-check the proposal under the lock, compare the
candidate state revision, and revalidate removals at confirm; the concurrency
test gets exactly one accepted decision and one record. The legacy stored-span
ambiguity, `republishOpenDocs(carry = true)` version filtering, carried-candidate
revalidation, and the additive `Contribution` note are also fixed. Zero open
correctness findings; the remaining items are residuals.

### Per-item status

- **Finding 11 — resolved.** `LiveServerRestrictionValidation.kt:85-107` now
  checks every SOURCE provenance contribution separately (`role`, exact
  span/intent, not user, not `ai-confirmed`, no directive intents), and
  `revalidatedProposals` uses that predicate. `addsApply` (`:63-78`) checks the
  ai-confirmed contribution in provenance, so a merged replacement is recognized.
  `LiveServerRestrictions.status` (`:93-98`) uses
  `restrictionApplies = removalsApply || addsApply` and no longer has a
  `removeOnly` shortcut. `LiveServerRestrictionValidationTest` covers a pack
  removal beside `ai-confirmed` and user `PURE`, the removal-only inert case, and
  per-contribution proposal revalidation; the server
  `restrictionWithNoApplicableRemovalIsListedDormant` covers the endpoint.
  Verified: a remove-only record whose removal does not apply now has
  `removalsApply == false` and `addsApply == false`, so it is dormant. A merged
  ai-confirmed add is found in provenance, not through the merged `source`.

- **Finding 12 — resolved.** `AiRestrictionsFile.add` and `remove` now use
  `loadForWrite` (`AiRestrictions.kt:258-267`), which throws
  `IllegalStateException` for any present non-1 version before writing.
  `addRefusesAnUnsupportedFutureVersionFileWithoutRewritingIt` asserts the
  exception and that the original file bytes are unchanged;
  `AiRestrictionsJson.parse` still reads unknown versions as empty for the
  analysis reader. `remove` shares `loadForWrite`, so it also refuses to write.
  The server does not yet correlate the resulting error to the JSON-RPC request
  id (see residuals), but the persistence contract holds.

- **Finding 13 — resolved.** `LiveServer.kt:82` adds `proposalDecisionLock`;
  `handleCandidateDecision` (`LiveServerDecisions.kt:47-62`) re-looks up the
  proposal under the lock. `handleProposalDecision` checks
  `baseStateRevision == candidateStateRevisions[key]` (`:188-190`) and, for
  confirm, revalidates every removal with `removalsApply` against
  `analyses[key].output.annotations` (`:191-206`) before writing.
  `concurrentDecisionsConfirmAtMostOnce` starts two clients against the same
  proposal, asserts exactly one accepted decision, the other missing/stale, and
  one restriction record. The lock is global rather than per-document, which is a
  throughput choice, not a correctness issue; no lock-order inversion was found
  between `proposalDecisionLock`, `candidateLocks`, `anchorLocks`, the annotation
  file lock, and `synchronized(this)`.

- **Legacy same-symbol residual — resolved.** `anchor()` now returns a stored
  span only when it is the sole symbol-filtered candidate (`:441-444`), so two
  identical calls in the same symbol make the legacy record dormant, and the
  fingerprint branch returns null when the stored span still content-matches a
  different unique fingerprint range (`:422-431`).
  `legacyRecordWithoutFingerprintGoesDormantWhenAnIdenticalSiblingIsInserted`
  covers `unitFingerprint = null`; the new duplicate test covers real
  fingerprints. Residual: the new test's `insertedAbove` string is still an
  insertion after the target with a comment, not literally before it; the
  fingerprint logic is symmetric and the legacy branch is covered, but the
  literal-above case is not pinned.

- **`republishOpenDocs(carry = true)` version filter — resolved.**
  `LiveServerConfig.kt:112-127` filters both proposals and candidates by
  `it.version == doc.version`, matching `republishUri`.

- **Carried-candidate revalidation — resolved.**
  `revalidatedCandidates` (`LiveServerRestrictionValidation.kt:35-52`) drops a
  carried or deterministic candidate whose intent is already established at the
  exact span, and `publish` applies it to
  `mergeCandidates(output, carriedCandidates)`
  (`LiveServerDocument.kt:138-142`). `carriedCandidatesAreDroppedWhenTheIntentIsAlreadyEstablished`
  covers the duplicate and a still-valid narrower candidate.

- **Additive `Contribution` note — resolved.** `Contribution` retains its
  defaulted `provider`/`model`/`promptVersion` fields,
  `ContractModelTest.contributionProvenanceFieldsAreAdditiveDefaults` pins the
  defaults and `CINT_API_VERSION == 5`, and the design/architecture docs state
  that `Annotation`, `Emit`, and `toContribution` are unchanged and only direct
  positional constructors in downstream code need the new arguments when set.

### New findings

None. No open correctness findings from this round.

### Concurs / Dissents

- Concurs: the per-contribution status/revalidation model, the remove-only inert
  behavior, the future-version write guard, the decision lock/state revision and
  confirm-time removal revalidation, the legacy same-symbol dormancy, the
  version-filtered republish, carried-candidate filtering, and the additive
  `Contribution` documentation.
- Dissents: none.

### Residuals

- The future-version guard throws; `handleMessage` catches the exception and
  sends an error with `id: null`, so a `cint/candidates/decide` or
  `cint/restrictions/remove` request sees no correlated error for its request id.
  The file is preserved. If client correlation matters, catch
  `IllegalStateException` in those handlers and respond with the request id.
- `revalidatedCandidates` now applies to deterministic output candidates as well
  as carried AI candidates. Rule-pack and current AI candidates are single-intent
  (`RuleEngine.outcomeOf` requires the rule's owned intent), so dropping a
  candidate when its intent is established at the exact span is correct; if
  multi-intent candidates ever appear, filter the established intents and keep
  the remainder instead of dropping the whole candidate.
- `restrictionApplies` is `removalsApply || addsApply`. A record whose removal no
  longer applies but whose add is present from another record is reported active.
  This matches the documented "add is present" rule, but a dry run against
  contributions would distinguish this record's own effect from another record's.
- `proposalDecisionLock` is global, so unrelated proposal decisions serialize
  across documents; the state-revision guard also makes a second unrelated
  proposal decision return stale after the first's republish. Both are
  conservative; a per-document decision lock would raise throughput and allow
  independent decisions to proceed after their own revalidation.
- Candidate decisions still use `anchorLocks` rather than
  `proposalDecisionLock`; a candidate confirm racing a proposal confirm at the
  same anchor can both succeed, with the user annotation winning on the next
  analysis. The same-proposal concurrency item is fixed; cross-kind decisions
  remain independently serialized.
- `AiRestrictionsFile.loadForWrite` still parses a version 1 file tolerantly: a
  malformed record is dropped and the next `add` rewrites the file; a
  non-numeric version string is treated as legacy. External corruption only.
- `cint/analyze` still clears same-version review state because it calls
  `publish` without carry; config changes and restriction removal use the
  appropriate carry. Unchanged.
- Unchanged residuals: `scopeRestrictionsToRegion` keeps the host content hash
  for clipped records; the split frontend path remains untested; rationale is
  retained in the restriction file.
- Test notes: the future-version test covers `add`; `remove` shares the guard but
  is not separately tested. The concurrent test covers the same proposal; two
  different proposals contending for the lock are only covered by inspection.
  The duplicate-anchor test does not literally insert above the target.
- Review remains read-only: no source, test, build, lint, or formatter command was
  run.

## Round 5

Verdict: The five Round 5 delta items are addressed and no new correctness defect
was found. Candidate and proposal decisions now share
`synchronized(proposalDecisionLock)` inside `handleCandidateDecision`, including
the proposal re-lookup, state-revision check, removal revalidation, and
`AiRestrictionsFile.add`; the same-proposal concurrency test still gets exactly
one accepted decision and one record, and the lock order has no inversion.
Carried ADD operations are validated at publication by `revalidatedProposals`
through `additionsAvailable`, and at confirm by the same helper; an established
replacement drops the proposal as stale. `AiRestrictionsFile.add` and
`AiRestrictionsJson.encode` both reject add-only records, so the reader and
writer agree. `AnnotationStore.contributionOrder` includes
`provider`/`model`/`promptVersion`, with a deterministic-order test. The
future-version guard now reports an error with the request id and leaves the file
untouched. Zero open correctness findings; the remaining items are residuals.

### Per-item status

- **Shared decision lock — concur.** `LiveServerDecisions.kt:50` wraps the whole
  proposal/candidate decision in `synchronized(proposalDecisionLock)`;
  `handleCandidateDecision` looks up the proposal and candidate under that lock,
  and `handleProposalDecision` checks the anchor hash, base state revision, and
  `removalsApply`/`additionsAvailable` before writing
  (`LiveServerDecisions.kt:174-214,261-302`). Candidate confirm additionally
  takes `anchorLocks` while already inside the global decision lock; no path takes
  `anchorLocks`, `candidateLocks`, the annotation-file monitor, the suggestion
  store monitor, the writer monitor, or `synchronized(this)` before taking
  `proposalDecisionLock`, so there is no lock-order inversion.
  `concurrentDecisionsConfirmAtMostOnce` still observes one accepted decision,
  one missing/stale, and one record. A candidate and a proposal decision are now
  serialized by the same lock; the only cost is that the lock is global rather
  than per document (residual).

- **Carried ADD validation — concur.** `revalidatedProposals`
  (`LiveServerRestrictionValidation.kt:18-46`) now requires
  `additionsAvailable` for every proposal add, and
  `handleProposalDecision` confirm builds `ResolvedRestrictionOperation`s for the
  adds and rejects the decision as `stale` when
  `additionsAvailable(cached.output.annotations, additions)` is false
  (`LiveServerDecisions.kt:200-213`). `proposalWithAnEstablishedReplacementAddIsDropped`
  and `additionsAvailable` pin the publication case. Publication and decide use
  the same helper, so a replacement that becomes established between them cannot
  be confirmed as a skipped add. Residual: `additionsAvailable` treats *any*
  provenance contribution at the exact span/intent as established, whereas
  `AiRestrictionApplication` skips only `Role.SOURCE_INTENT` duplicates; a
  derivative/scaffold at the same span can therefore drop a replacement the
  application would add if "established" is meant to mean SOURCE only. The
  session's `validateAdd` also treats any annotation as a duplicate, so the
  current behavior is defensible but not pinned by a derivative test.
- `additionsAvailable` also accepts a `PURE`/`READONLY` replacement because no
  such contribution exists. The session's `validateAdd` accepts these user
  intents, while `AiRestrictionApplication` deliberately skips directive adds
  (`DIRECTIVE_INTENTS`), so a proposal that adds one can confirm with an
  unapplied add. This predates the round-5 delta; rejecting directive intents in
  the ADD validation (or in `validateAdd`) would close the group-atomicity gap.
- `additionsAvailable` does not check the current known vocabulary. A config
  change clears pending proposals, so the normal path cannot shrink the
  vocabulary under a carried proposal; a proposal that survived a config
  reload/race could still confirm an intent no pack owns. The session's known
  vocabulary check is the only guard.

- **Add-only write/encode guard — concur.** `AiRestrictionsFile.add` requires a
  non-empty `remove` before acquiring the file lock, and
  `AiRestrictionsJson.encode` requires every record to have a removal. The
  `addRejectsAddOnlyRecordsBeforeWriting` test confirms no file is created, and
  the reader still rejects add-only records. Reader and writer now agree.

- **`AnnotationStore.contributionOrder` — concur.** The comparator includes
  `provider`, `model`, and `promptVersion` (`AnnotationStore.kt:13-30`), and
  `orderingIncludesAiProvenanceFields` pins the deterministic order
  `null`, `alpha`, `beta`. This closes the api-fidelity contribution-ordering
  residual.

- **Future-version error correlation — concur.** `handleProposalDecision`
  wraps `AiRestrictionsFile.add` in `runCatching` and sends the error with the
  request id (`LiveServerDecisions.kt:272-295`); `handleRestrictionsRemove` does
  the same (`LiveServerRestrictions.kt:70-75`).
  `futureVersionFileErrorIsCorrelatedToTheRequestId` writes a version-2 file,
  sends `cint/restrictions/remove` with id 9, asserts the error message and id 9,
  and asserts the file still contains `"version":2`. The add/confirm path uses
  the same guard and error response; only the remove path is covered by a
  server test.

- **api-fidelity R4-1/R4-2/R4-3 — concur.** The decision lock is now implemented
  (R4-1), carried ADD operations are revalidated at publication and decide (R4-2),
  and add-only records are rejected by both `add` and `encode` (R4-3). No
  remaining correctness issue from those findings.

### New findings

None. No open correctness findings from this round.

### Concurs / Dissents

- Concurs: shared decision lock and lock order; publication/decide ADD
  validation; add-only write/encode guard; `contributionOrder`; future-version
  error correlation and file preservation; the api-fidelity R4 fixes.
- Dissents: none.

### Residuals

- `additionsAvailable` uses any provenance contribution rather than
  `Role.SOURCE_INTENT` only. If the intended contract is the application's
  source-only duplicate rule, a derivative/scaffold at the same span/intent will
  drop a replacement the application would add, and a derivative test should pin
  the intended behavior; if the intended contract is the session's "any
  annotation at span+intent", document that and keep the current helper.
- `restrictionApplies` remains `removalsApply || addsApply`. A record whose
  removal no longer applies but whose add is present from another record is
  reported active; this matches the documented "add is present" rule but is not
  a dry run of this record's own effect.
- `proposalDecisionLock` is a single global lock and is held across analysis and
  republish to every client (`republishUri`); unrelated proposal/candidate
  decisions serialize across documents. Correctness is fine; per-document locks
  would improve throughput and allow independent decisions after their own
  revalidation.
- Candidate confirm/reject remain inside the global lock but do not compare
  `baseStateRevision` themselves; they rely on the under-lock lookup and the
  current `publishedCandidates`. A candidate confirmed after an unrelated
  decision is therefore accepted if it still exists; this is conservative for
  review state but not state-revision guarded like proposals.
- `AiRestrictionsFile.loadForWrite` still treats a non-numeric version string as
  legacy and a malformed version-1 record as absent on rewrite; external
  corruption only. `AiRestrictionsJson.encode` has no direct add-only test, but
  its `require` is exercised indirectly by `add`.
- `cint/analyze` still clears same-version review state because it calls
  `publish` without carry; config changes and restriction removal use the
  appropriate carry. Unchanged.
- Unchanged residuals: `scopeRestrictionsToRegion` keeps the host content hash
  for clipped records; the split frontend path remains untested; rationale is
  retained in the restriction file.
- Test notes: the decide-time ADD rejection has a unit test but no server
  `cint/candidates/decide` test; the future-version server test covers `remove`
  but not confirm-add; the concurrent test covers the same proposal, not a
  candidate/proposal pair. These are coverage gaps, not observed failures.
- Review remains read-only: no source, test, build, lint, or formatter command was
  run.
