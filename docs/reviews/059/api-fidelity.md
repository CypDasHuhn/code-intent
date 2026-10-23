# API-fidelity review — ticket 059 (AI restriction proposals)

## Round 1

Verdict: The frozen `api` decision is sound: the module still reports
`CINT_API_VERSION = 5`, `toContribution` still accepts only `Emit`, and the
`Annotation` shape is untouched. The operation model is a `suggest`/protocol
model with explicit `ADD`/`REMOVE`, which is a compliant reading of the ticket's
"preferred API v6" wording because restrictions are AI-only review data, not
rulepack emits. The wire and file contract around that model is not ready to
close, however: a confirmed group can lose a removal through the relative-offset
format, a decision on one proposal clears every other proposal, two documented
anchoring fields are inert, and several protocol/config/count claims do not match
the implementation.

### Findings

1. **A removal that starts before its expression unit is accepted but is dropped by the persistence format.**

   Location:
   `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:289-306`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:231-245`,
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:131-138`.

   `validateRemove` only requires the removal span to *overlap* the unit
   (`remove.startByte >= unit.endByte || remove.endByte <= unit.startByte`).
   A verdict for a child unit can therefore target an enclosing annotation whose
   start is before the unit start. `relativeOperations` writes the offset as
   `operation.startByte - proposal.startByte`, producing a negative
   `startOffset`. `AiRestrictionsJson.operations` rejects every operation with
   `start < 0`, so the record is written and immediately re-read without its
   removal. Because `AiRestrictionApplication.apply` only adds replacements when
   at least one removal applied, the whole group then has no effect. Confirming
   still returns `accepted: true`. This is the narrowing shape the design calls
   out (outer removal, inner add) when the agent grades a child unit.

   Suggested fix: require removal targets to be contained in the unit, as
   `validateAdd` does for adds (the agent can grade the parent unit to remove the
   enclosing intent), or allow negative relative offsets in the file and
   resolver and resolve them against the anchor. Add a test where a child unit
   proposes removing an enclosing annotation.

2. **Confirming or rejecting one proposal clears every other proposal in the document.**

   Location:
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:203,225`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:135`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:133-134`.

   The proposal decision handlers republish through `republishUri(uri)`. That
   calls `publish(...)`, which calls
   `publishCandidates(writer, doc, output.candidates, analysisRevision = ...)`
   with the default `proposals = emptyList()`. `publishCandidates` then assigns
   `publishedProposals[key] = publishedProposalList`, i.e. an empty list. A
   document with two proposal groups loses both after one decision; the docs say
   a decision republish has empty `candidates`/`groups` only "when nothing
   remains" (`docs/engine.md:675-680`). The same path also drops add-only
   discovery candidates after a candidate decision, so the problem is not
   limited to the new group shape.

   Suggested fix: when republishing a decision, pass
   `publishedProposals[key]` minus the decided `proposalId` (and likewise keep
   the unaffected candidate state), or re-run the pass and republish its
   surviving groups. Add a two-proposal test that decides one and asserts the
   other is still published.

3. **The documented `path`/`symbol`/`content-hash` anchor is not implemented: `symbol` and `unitFingerprint` are stored but ignored.**

   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:307-336`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:218`,
   `docs/ai-annotation-session.md:243-245`,
   `docs/tasks/059-ai-restriction-proposals.md:28-29`.

   The file stores `symbol` and `unitFingerprint`, but `AiRestrictionResolver`
   never reads either. `unitFingerprint` is populated with the same value as
   `contentHash`. Resolution is path + content hash + nearest line to the old
   span. Two identical expression texts (or the same text in another split region
   or language) can therefore resolve to the wrong unit after a move, and the
   record cannot use the symbol the design says it resolves through. The design
   says the record resolves through the same path/symbol/content-hash machinery
   as user annotations; user annotations do consult `symbol`.

   Suggested fix: use `symbol` and a real unit fingerprint (language, node kind,
   enclosing symbol, normalized text) as filters or fallbacks before the
   nearest-line tie-break, or remove both fields from the file/docs and document
   content-hash-plus-nearest-line as the actual anchor. Add a duplicated-unit
   test.

4. **`ai-confirmed` provenance is encoded as a concatenated rule id, which the architecture explicitly rules out.**

   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictionApplication.kt:32-42,89-95`,
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/artifact/AnnotationJson.kt:26-39`,
   `docs/architecture.md:217-225`,
   `docs/ai-annotation-session.md:206-207`.

   `aiConfirmedRule` produces
   `core:ai-confirmed:<provider>:<model>:<prompt>` and puts it in
   `Contribution.rule`. `docs/architecture.md` says provenance is structured
   data, "not a concatenated rule string", and defines `rule` as the producing
   rule id. The published annotation provenance exposes only
   `source = ai-confirmed` plus that synthetic id; `provider`, `model`, and
   `promptVersion` are not fields in the tag payload. A renderer cannot consume
   the promised provider/model/prompt provenance from tags without parsing an
   undocumented colons-in-rule convention.

   Suggested fix: use a stable `core:ai-confirmed` rule id and expose
   provider/model/prompt as structured artifact fields, or document explicitly
   that tag provenance carries no structured AI provenance and that clients
   must read `cint/restrictions/list` for it. Whichever is chosen, document the
   wire shape in the annotation contract.

5. **`cint/suggest.restrictions` counts proposals the server filtered out, unlike `count`.**

   Location:
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:218,319-328`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:110-120`,
   `docs/engine.md:646-655`.

   The response uses `outcome.proposals.size`, while `publishCandidates` filters
   proposals that are already persisted, rejected, or skipped and returns the
   actual published total in `count`. A rejected proposal re-emitted by the next
   pass is filtered from `groups` but still reported as `restrictions: 1`. A
   skipped proposal behaves the same way. A client using the field for the
   completion count will claim a restriction exists when none was published.

   Suggested fix: return the published proposal count from `publishCandidates`
   (a small result object or out parameter) and use it for `restrictions`; if
   the raw session count is also wanted, expose it under a separate name. Add a
   reject-then-suggest assertion on the response field.

6. **`restrictionsEnabled` gates the capability descriptor and proposal generation, but not the inspector the docs say it gates; the descriptor also silently depends on provider support.**

   Location:
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:38`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:21,55`,
   `docs/architecture.md:288-289`,
   `docs/summary/annotations-and-ai.md:108-109`,
   `docs/engine.md:589-590`,
   `docs/ai-annotation-session.md:534-535`.

   The docs say `restrictionsEnabled` (default true) "gates proposals and the
   inspector". The proposal path honours it, but
   `cint/restrictions/list` and `cint/restrictions/remove` only check
   `protocol >= 4`; with `restrictionsEnabled: false` they still list and remove
   records. Conversely, `initialize.suggestions.restrictions` is true only when
   `restrictionsEnabled && supportsDiscovery && protocol >= 4`, while the docs
   describe only `restrictionsEnabled` and protocol. A protocol-4 client with a
   provider that does not implement the session surface sees `restrictions:
   false` even though the inspector is available.

   Suggested fix: define one contract. If the flag really gates the inspector,
   enforce it in both handlers and document the descriptor condition. If the
   inspector must remain available to clean up existing records when proposals
   are disabled, say so and advertise that accordingly. Cover both with a
   settings test.

7. **Protocol-3 clients receive the new `operations` group field (and the `cint/suggest.restrictions` field) even though the docs promise the existing candidate shapes.**

   Location:
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:187-203`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:328`,
   `docs/engine.md:597-599`.

   `candidateGroupPayload` always adds `operations`, with no client-protocol
   check, and `suggestResponse` always adds `restrictions`. The docs state that
   protocol-3 clients keep the legacy enrichment path and "the existing candidate
   shapes". Additive JSON is normally tolerated, but the stated contract is
   otherwise. Strict schema validators or older renderers that treat unknown
   group fields as a shape change will not see the documented behavior.

   Suggested fix: gate the new fields on the requesting client's protocol (the
   server already records `clientProtocols`), or amend the protocol-3 section to
   state explicitly that `operations` and `restrictions` are additive for all
   clients. Add a protocol-3 shape assertion.

8. **`maxCandidates` is documented as capping validated proposals, but proposal groups bypass it.**

   Location:
   `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:420-434`,
   `docs/engine.md:462-463`.

   `maxCandidates` is applied only inside `buildCandidates`, which caps add-only
   `CandidateEmit`s. `acceptedProposals` is not limited. A positive
   `maxCandidates` can therefore still publish an unbounded number of proposal
   groups, contradicting "positive values cap validated proposals".

   Suggested fix: apply the cap to add-only candidates and proposal groups
   together (and document the ordering), or narrow the doc to say the cap
   applies only to add-only candidates. Add a test with a positive cap and
   multiple proposals.

9. **The docs do not specify the implemented `cint/restrictions` payloads, and the file examples show a `contentHash` form the writer does not emit.**

   Location:
   `docs/engine.md:681-685`,
   `docs/ai-annotation-session.md:532-535`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:85-115`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:230-244`,
   `docs/ai-annotation-session.md:224`,
   `docs/summary/annotations-and-ai.md:140-141`.

   The docs say the list method "returns stored restriction records with their
   operations and a status", but not the `{ "restrictions": [...] }` envelope,
   the operation object's field names (`kind`, byte and line/column fields,
   `confidence`, optional `rationale`/`source`), or the remove response
   `{accepted, removed}`. In addition, the examples show `sha256:...` and
   `sha256-hex`, while `AiRestrictionsJson.encode` writes a bare lowercase
   SHA-256 hex string; the parser tolerates the prefix, so the examples are
   misleading but not obviously wrong. The `unitFingerprint` example also
   suggests a distinct value while the server currently writes the content
   hash.

   Suggested fix: document the exact JSON for list/remove and both operation
   shapes, and align the example `contentHash`/`unitFingerprint` values with
   what the writer emits (or emit the documented prefixed form).

### Concurs / dissents

- Concurs: keeping the frozen API at v5 is compliant with the ticket's
  "preferred" wording. The operation model lives in `suggest`/protocol, `api` is
  untouched, `toContribution` still accepts only `Emit`, and `Annotation` is
  unchanged. `docs/ai-intents.md:62-64` records the suggest-layer choice.
- Concurs: protocol-4 gating for discovery and proposals is in place, the
  protocol-3 legacy path remains, `cint/candidates/decide` still accepts
  `candidateId`, and add-only confirms still write
  `.cint/user-annotations.json`.
- Concurs: the proposal group shape (`proposalId`/`groupId`, explicit
  `operations`, empty independently-confirmable `candidates`) and the
  `cint/publishCandidates` envelope match the design.
- Concurs: `.cint/ai-restrictions.json` lives under the shared
  `.cint/user-annotations.json.lock`, is written atomically, and carries path,
  symbol, anchor span, content hash, fingerprint, relative operations, and
  provider/model/prompt fields. The list/remove inspector and the protocol-4
  gate exist.
- Concurs: the application order is user contributions, then restrictions, then
  composition; user annotations and `PURE`/`READONLY` survive at apply time;
  derivative/scaffold output is recomputed from the filtered source set;
  dormant records do not crash the pass; user wins.
- Concurs: `restrictionsEnabled` is parsed with the documented default and is in
  the initialize descriptor; the new settings key is documented in
  `docs/engine.md`.
- Concurs: the current nvim renderer ignores proposal groups with empty
  `candidates` (it flattens `group.candidates`), so the new group type is inert
  rather than crashing older clients. The full review UX remains 061.
- Dissents: findings 1-9.

### Residuals (not filed)

- `validateRemove` uses the aggregate annotation `source`. A user or
  `ai-confirmed` contributor that shares the exact span with a pack contributor
  produces `source = "mixed"`, which passes the check. Apply-time `removable`
  still protects the user/AI contribution, so no user intent is lost, but the
  proposal and persisted record can be misleading. Check
  `SessionAnnotation.provenance` if stricter proposal validation is wanted.
- Rejection identity includes `startByte`/`endByte`, so a pure line move changes
  the proposal `anchorId` and can resurrect a denied group. Confirmed
  restrictions re-anchor by content hash and survive moves. Decision re-keying
  by a normalized anchor fingerprint is 060 scope.
- `HostPipeline.scopeRestrictionsToRegion` filters split-host records against
  the stored host span before content-hash re-anchoring. A line move in a
  fenced/markdown host can therefore drop a restriction that a plain-file move
  would re-anchor. The integration test covers the plain-file case only.
- `AiRestrictionsJson.parse` ignores the file's `version` field (like the user
  annotation parser), so a future file version is accepted as v1.
- The parser accepts add-only restriction records, but
  `AiRestrictionApplication.apply` only applies replacements when at least one
  removal matched. Such a record is inert while `cint/restrictions/list` can
  report it active.
- The design doc still calls the v6 proposal model "preferred" without recording
  the v5/suggest-layer decision; `docs/ai-intents.md` is the only place that
  states the chosen shape.

## Round 2

Verdict: Six of the nine Round 1 findings are addressed in substance: negative
relative removal offsets now round-trip and resolve; `symbol` and
`unitFingerprint` are real and consulted; `ai-confirmed` uses the stable
`core:ai-confirmed` contributor id with provenance explicitly moved to the
restriction record/list; the `restrictions` response count is post-filter;
`restrictionsEnabled` gates both inspector handlers and the descriptor no longer
depends on provider support; `maxCandidates` caps proposals after adds; and the
`cint/restrictions` list/file shape and v5 decision are documented. Three
findings remain open in part. The proposal-preservation fix does not preserve
unrelated add-only AI candidates, the new unit fingerprint can rebind a record to
a later-inserted duplicate, and decision republish broadcasts one client's
proposal set to every client, overwriting other clients' groups and leaking
proposal groups to protocol-3 clients.

### Findings

1. **R2-1 (from Round 1 finding 3): the new fingerprint can rebind to a duplicate inserted after the anchor.**

   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:319-358,380-388`,
   `analysis-engine/tests/integration-tests/src/test/kotlin/dev/cint/integration/restriction/AiRestrictionResolutionTest.kt:272-283`,
   `docs/engine.md:333-335`,
   `docs/ai-annotation-session.md:541-543`.

   `fingerprint` includes `nextSiblingText(target.node)` and an occurrence
   ordinal computed only among units whose next sibling matches. When a new
   identical call is inserted *after* the anchored call, the anchored call's next
   sibling changes, so it no longer matches its old fingerprint; the inserted
   call has an empty next sibling and becomes occurrence 1, so it matches the old
   fingerprint exactly. `anchor()` then returns the inserted duplicate. The new
   integration test asserts `inserted.lastIndexOf("log.warn(message)")`, i.e. it
   pins the rebind, while the docs say duplicate text goes dormant instead of
   rebinding. This makes a restriction move to a newly inserted sibling, which is
   the wrong anchor.

   Suggested fix: make the fingerprint independent of the following sibling (or
   use a stable occurrence identity that insertion after the target cannot
   transfer), prefer the stored span when its content hash still matches, and
   return dormant when the identity is ambiguous. Change the test to assert the
   original anchor or dormancy, not the inserted duplicate.

2. **R2-2 (from Round 1 finding 2): decision republish still drops unrelated add-only AI candidates, and the new docs claim it preserves them.**

   Location:
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:101,143`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:137-143`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:114-135`,
   `docs/ai-annotation-session.md:531-533`.

   The proposal side of Round 1 finding 2 is fixed: `remainingProposals` excludes
   the decided group and `republishUri` carries the rest forward. The add-only
   candidate side is not: `publish(...)` always calls `publishCandidates` with
   `output.candidates` from the fresh analysis, and the decision handlers pass
   only `publishedProposals`, never the other entries in
   `publishedCandidates[key]`. A discovery pass that produced both an add-only
   candidate and a proposal loses the candidate when the proposal is confirmed or
   rejected (and vice versa). `docs/ai-annotation-session.md` now says a decision
   republish preserves "every unrelated pending proposal and add candidate",
   which the implementation does not do.

   Suggested fix: carry the surviving `publishedCandidates[key]` entries through
   the decision republish as well (filtering the decided/confirmed/rejected
   identity), or re-run the pass and publish its surviving state. Add a test that
   decides one item in a document containing both an add-only candidate and a
   proposal and asserts the other remains. Align the docs with whichever contract
   is chosen.

3. **R2-3 (from Round 1 finding 7): one client's carried proposals are published to every client, overwriting other clients' pending groups and appearing for protocol-3 clients.**

   Location:
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerConfig.kt:123-130`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:114-135`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:101,143,204`,
   `docs/engine.md:608-611,690-692`,
   `docs/ai-annotation-session.md:531-533`.

   The direct protocol-3 gating is correct: `includeOperations` suppresses the
   group `operations` field and `suggestResponse` omits `restrictions` for
   protocol 3. The new decision carry path bypasses both rules. `republishUri`
   loops over every writer for the URI and passes the deciding writer's
   `remainingProposals` into `publish`; `publishCandidates` publishes a proposal
   group for every writer regardless of that writer's protocol, and assigns
   `publishedProposals[key] = publishedProposalList`, replacing any proposals
   that writer already had. A protocol-3 client can therefore receive a
   `proposalId` group with no `operations` (and can call
   `cint/candidates/decide` on it), while another protocol-4 client loses its own
   pending groups. The carried proposals also have no check against the receiving
   writer's document version/content hash.

   Suggested fix: carry proposals per writer and merge them by `proposalId` with
   that writer's existing set rather than replacing it; publish proposal groups
   only to protocol-4 clients; and verify the proposal's document version or
   content hash before publishing. Add a two-client, mixed-protocol decision
   test.

### Concurs / dissents

- Finding 1 — concurs: negative relative removal offsets are accepted by the
  file parser, validated against the anchor, resolved with the overlap rule, and
  covered by the file and child-unit integration tests.
- Finding 2 — concurs on the proposal-group half only; dissents on the add-only
  candidate half (R2-2).
- Finding 3 — concurs that `symbol` and `unitFingerprint` are now populated and
  consulted, and that duplicate/missing identity generally returns dormant;
  dissents on the fingerprint's following-sibling instability (R2-1).
- Finding 4 — concurs: the contributor id is the stable `core:ai-confirmed`, the
  tag provenance is no longer a concatenated rule string, and the docs now state
  that provider/model/prompt provenance lives on the restriction record and
  `cint/restrictions/list`.
- Finding 5 — concurs: `CandidatePublishResult` reports published counts and
  `cint/suggest.restrictions` uses the post-filter proposal count, with a skip
  regression test.
- Finding 6 — concurs: both inspector handlers check `restrictionsEnabled`, the
  descriptor is `restrictionsEnabled && protocol >= 4`, and the docs describe the
  inspector/provider-support split.
- Finding 7 — concurs on the direct candidate-group and response gating; dissents
  on the decision carry path (R2-3).
- Finding 8 — concurs: `maxCandidates` caps add candidates first and proposal
  groups with the remaining slots, with a regression test and matching docs.
- Finding 9 — concurs on the `cint/restrictions` list envelope, operation and
  remove payloads, bare content hash, real `unitFingerprint`, and the recorded
  v5/suggest-layer decision.
- Dissents: findings 2, 3, and 7, for the reasons in R2-1 through R2-3.

### Residuals (not filed)

- `cint/restrictions/remove` and config republish still call
  `republishOpenDocs()` with no carried proposals, so pending proposal groups are
  cleared on those paths. The docs do not promise preservation there, but a
  client can lose review state after an unrelated inspector removal.
- Carried proposal snapshots are not revalidated against the post-confirm
  analysis. A proposal at an overlapping or enclosing unit whose removal target
  was consumed by the confirmed group can survive as a no-op record; only the
  decided id is excluded.
- `cint/candidates/decide` proposal confirmation does not check
  `restrictionsEnabled`; a stale carried proposal can still write a restriction
  after the feature is disabled.
- `cint/restrictions/list` reports a record with a resolvable add but no
  resolvable removal as `active`, while `AiRestrictionApplication` skips the add
  because no removal applied. Status and application still disagree in that
  edge.
- `docs/ai-intents.md` still lists group `operations` in the protocol-3 section
  without the protocol qualifier used by `docs/engine.md`; the engine doc is the
  accurate one.
- `AiRestrictionsJson.parse` still ignores the file `version` field; add-only
  records are now rejected, so that older residual is narrowed but not closed.

## Round 3

Verdict: The three Round 2 findings are addressed in substance. The duplicate
fingerprint now includes previous and following sibling context, the legacy
`storedMatches` path only returns the stored range when it is in the symbol
filter, and the test asserts dormancy for adjacent insertion above and below.
Decision republish is scoped per writer, carries that writer's add candidates
and proposals, revalidates proposals against the new analysis, and suppresses
proposal groups for protocol-3 writers. Restriction removal carries pending
review state, file versions are checked, add-only records are rejected, and the
`restrictionsEnabled` confirmation path returns a disabled result. Two contract
gaps remain: the new optional `provider`/`model`/`promptVersion` fields on the
`Contribution` API model are not covered by an explicit API-version/additive
decision and contradict the still-current tag-provenance sentence in the design
doc, and the new `disabled` decision response is not documented.

### Findings

1. **R3-1 (frozen API model): `Contribution` gained optional fields while `CINT_API_VERSION` remains 5 and the contract documentation is inconsistent.**

   Location:
   `analysis-engine/core/api/src/main/kotlin/dev/cint/api/Model.kt:47-60`,
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/artifact/AnnotationJson.kt:26-43`,
   `docs/ai-annotation-session.md:206-210,431-436`,
   `docs/architecture.md:370-384`.

   `Contribution` is a type in the frozen `api` module, not a suggest-layer
   model. `provider`, `model`, and `promptVersion` are optional/defaulted, so the
   JSON change is additive and old wire consumers can ignore the new keys, and
   `AnnotationJson` only emits them when present. The Kotlin data-class surface
   still changes: its primary constructor, `copy`, and `componentN` signatures
   all gain parameters, while `CINT_API_VERSION` stays 5. `docs/architecture.md`
   says `api` is the frozen public surface and changes only deliberately with
   versioning; there is no contract note covering this additive model change and
   no `ContractModelTest` pin for the new fields. `docs/ai-annotation-session.md`
   still says "the frozen tag provenance carries only the stable rule id and
   `source = ai-confirmed`" (`:206-210`), which is now false because provenance
   entries carry the three new fields, and the same document says the v5
   compatible shape leaves `Emit`, `Annotation`, and `toContribution` unchanged
   without mentioning `Contribution` (`:431-436`).

   Suggested fix: record an explicit additive-API decision (the `certifiesReturn`
   precedent) stating that these defaulted `Contribution` fields are
   source-compatible and whether `CINT_API_VERSION` stays 5, pin them in the API
   contract test, and update the tag-provenance paragraph. If binary compatibility
   for packs that construct `Contribution` is required, bump `CINT_API_VERSION`
   and update the loader/docs instead.

2. **R3-2 (decision status): proposal confirmation's `disabled` result is undocumented.**

   Location:
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:169-170`,
   `docs/engine.md:682-686`,
   `docs/ai-intents.md:264-273`.

   With `restrictionsEnabled: false`, a proposal decision returns
   `{accepted: false, disabled: true}` before the anchor/version checks. The
   documented result shapes are only `{accepted: true, stale: false, decision}`,
   `{accepted: false, stale: true}`, and `{accepted: false, missing: true}`. A
   client cannot distinguish the feature gate from a stale or missing proposal
   from the docs, and the new status also applies to `skip` and `reject`, not
   only confirm.

   Suggested fix: document `disabled` as a decision result (and whether it is
   proposal-only), or return one of the documented error/status shapes; add a
   decision test that covers the disabled rejection.

### Concurs / dissents

- R2-1 — concurs: the fingerprint now includes previous and following siblings,
  `anchor()` no longer returns a stored range outside the symbol filter, and the
  updated duplicate test asserts dormancy for insertion above and below.
- R2-2 — concurs: `publish` carries `carriedCandidates`, `mergeCandidates`
  preserves unrelated add-only candidates, and `aiAddOnlyCandidateSurvivesProposalDecision`
  covers the mixed candidate/proposal case.
- R2-3 — concurs: `republishUri` selects proposals and candidates per
  `DocumentKey`, `publishCandidates` emits no proposal groups when
  `includeOperations` is false, and the mixed-client test covers both the second
  protocol-4 writer's group and a protocol-3 writer.
- Residual fix — restriction-removal republish: concurs via
  `republishOpenDocs(carry = true)`.
- Residual fix — carried-proposal validation: concurs via
  `revalidatedProposals` and the stale-carried-proposal test.
- Residual fix — `restrictionsEnabled` on confirm: concurs that confirmation is
  gated; dissents only on the undocumented `disabled` payload (R3-2).
- Residual fix — add-only record status: concurs; `AiRestrictionsJson.parse`
  rejects records with no removal and the status logic handles the removal/add
  cases.
- Residual fix — protocol-3 wording: concurs; `docs/engine.md` and
  `docs/ai-intents.md` now state that protocol-3 clients receive no `operations`
  field or proposal groups.
- Residual fix — file version: concurs; the reader checks version 1 and ignores
  unknown versions.
- Dissents: R3-1 and R3-2.

### Residuals (not filed)

- `AnnotationStore.contributionOrder` does not include the new `provider`,
  `model`, or `promptVersion` fields. A provenance list whose entries differ only
  by those fields can retain input order rather than a deterministic sorted
  order.
- `AiRestrictionsFile.add` still accepts an add-only `AiRestrictionRecord` and
  `AiRestrictionsJson.encode` writes it, while `AiRestrictionsJson.parse` rejects
  it. Production proposal confirmation never creates that shape, but a direct
  core caller can persist a record that disappears on reload.
- `cint/restrictions/list` reports a remove-only record as `active` when the
  removal resolves but is protected, and `addPresent` only recognizes an
  exact `source = ai-confirmed` annotation, so a mixed-source replacement can
  make an applied group look dormant.
- `republishOpenDocs(carry = true)` reuses each writer's stored proposals and
  candidates without a document-version filter, and carried candidates are not
  revalidated against the current text/content hash; a stale review row can
  survive an inspector removal. (`republishUri` does filter by version for the
  non-deciding clients.)
- `revalidatedProposals` validates the removal targets but not the add
  operations; a carried add that now duplicates an established or user intent
  can remain published.
- The artifact schema example in `docs/engine.md` still shows provenance
  entries without the optional provider/model/prompt keys; they are additive, so
  this is documentation-only.

## Round 4

Verdict: R3-1 and R3-2 are closed. The `Contribution` fields now have an
explicit additive/v5 decision in `docs/architecture.md` and
`docs/ai-annotation-session.md`, the artifact note documents the optional
provider/model/prompt keys, the tag-provenance sentence is corrected, and
`ContractModelTest` pins the defaults, `copy`, and `CINT_API_VERSION = 5`. The
`{accepted:false, disabled:true}` proposal-decision result is documented in
`docs/engine.md` and `docs/ai-intents.md`. The status, stale-carried-candidate,
and artifact-example residuals are fixed. Three contract defects remain: the
decision path claims a "decision lock" that is not implemented, carried
proposals do not validate their ADD operations, and `AiRestrictionsFile.add`
still writes add-only records that the reader rejects. Contribution ordering
remains a residual.

### Findings

1. **R4-1 (decision path): the documentation claims a decision lock for proposal decisions that the server does not take.**

   Location:
   `docs/engine.md:690-692`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:188-207`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:24-38`.

   The docs say proposal decisions "revalidate the group against the current
   analysis under a decision lock and return `stale` when another decision
   already consumed its removal target." The implementation checks
   `baseStateRevision` and `removalsApply(cached.output.annotations, removals)`
   with no lock; `publishCandidates` uses `candidateLocks[key]` only after the
   decision. Two concurrent confirms can both observe the old analysis and
   revision, both pass the check, and both write restriction records before
   either republish updates the analysis and revision. The per-file
   `AiRestrictionsFile.add` lock serializes the writes but cannot make the
   validate-and-write decision atomic.

   Suggested fix: take a per-document decision lock (or `candidateLocks[key]`)
   around the current-hash/base-revision/`removalsApply` checks and the
   restriction write, or remove the "decision lock" claim and document the
   best-effort race. Add a two-client concurrent-confirm test.

2. **R4-2 (carried proposals): ADD operations are not revalidated, so a carried group can contain a replacement that is already established and will be skipped.**

   Location:
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerRestrictionValidation.kt:18-33`,
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:191-207`,
   `docs/engine.md:702-704`.

   `revalidatedProposals` keeps a proposal if its span hash matches and every
   REMOVE target is still a removable established intent. It does not inspect
   `proposal.adds`. `handleProposalDecision` confirms after the same
   removal-only check. If another decision or a user annotation establishes the
   replacement intent at the same span while the proposal remains pending,
   `publishCandidates` republishes the group and confirm writes a restriction;
   `AiRestrictionApplication` then skips the duplicate ADD. The group's
   operations are therefore not all applied, contrary to the atomic-group
   contract, and the review UI advertises a replacement that will not happen.

   Suggested fix: validate each proposal ADD against the current annotations and
   the known vocabulary, dropping the proposal or the operation when it is
   already established or protected; mirror the check in
   `handleProposalDecision`. Add a carried-proposal test with an add that became
   established.

3. **R4-3 (file contract): `AiRestrictionsFile.add` can persist an add-only record that the reader rejects.**

   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:228-239,70-78`,
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:90-98`.

   `AiRestrictionsJson.parse` requires at least one REMOVE and returns null for
   add-only records, while `AiRestrictionsFile.add` accepts any
   `AiRestrictionRecord` and `AiRestrictionsJson.encode` writes it. A direct
   core caller can add an add-only record, receive it in the returned list, and
   have it disappear on the next load. The server's proposal path always has a
   removal, but the core file API has no matching write-side guard.

   Suggested fix: reject add-only records in `add`/`encode` (or support them
   consistently in `parse` and application) and add a round-trip test. This is
   the add-only write/read asymmetry tracked as a residual since Round 2.

### Concurs / dissents

- R3-1 — concurs: the additive `Contribution` fields are recorded as
  source-compatible under v5 in `docs/architecture.md` and
  `docs/ai-annotation-session.md`; the tag-provenance claim is corrected; the
  artifact note lists the optional keys; and `ContractModelTest` asserts the
  null defaults, `copy` values, and `CINT_API_VERSION == 5`.
- R3-2 — concurs: the `disabled` proposal-decision result is documented for
  confirm/reject/skip in `docs/engine.md` and `docs/ai-intents.md`.
- Residual — status edge cases: concurs; `LiveServerRestrictions.status` now
  uses `restrictionApplies`/`addsApply` and per-contribution protection.
- Residual — stale carried candidates: concurs; carried candidates are
  version-filtered and revalidated against established intents.
- Residual — artifact-example docs: concurs; the artifact note documents the
  optional provider/model/prompt provenance keys.
- Residual — contribution ordering: dissents/remains; `contributionOrder` still
  omits the new fields, so contributions differing only in provider/model/prompt
  retain input order.
- Residual — add-only write/read asymmetry: dissents; filed as R4-3.
- Residual — unvalidated ADD operations: dissents; filed as R4-2.
- Dissents: R4-1, R4-2, and R4-3.

### Residuals (not filed)

- `AnnotationStore.contributionOrder` does not include `provider`, `model`, or
  `promptVersion`; deterministic provenance ordering for contributions that
  differ only by those fields is not pinned.
- `revalidatedCandidates` still has no stored content hash to compare for a
  carried candidate; the per-writer document-version filter is the current
  safeguard against text drift.
- `republishOpenDocs(carry = true)` filters stored state by document version,
  but candidate revalidation remains existence/intent based rather than a full
  span-text comparison.

## Round 5

Verdict: No open findings. The decision lock is implemented for both candidate
and proposal decisions (`proposalDecisionLock` wraps the shared decision path)
and the docs describe it accurately. Carried proposals now validate both
removals and replacement adds before publication and confirmation, and a carried
group with an established replacement is dropped; a server-level test covers
that case. Add-only records are rejected by `AiRestrictionsFile.add` and
`AiRestrictionsJson.encode`, matching the reader, with a reload test. The
contribution comparator includes `provider`/`model`/`promptVersion` and is pinned
by `AnnotationStoreTest`. Future-version files are not rewritten: add/remove
throw a correlated JSON-RPC error and preserve the original file, with core and
server tests. The R3/R4 API-version, provenance, `disabled` result, status,
stale-candidate, and artifact-example items remain closed.

### Findings

None.

### Concurs / dissents

- R4-1 — concurs: candidate and proposal decisions share
  `proposalDecisionLock`; proposal confirmation revalidates removals and adds
  under that lock, and the protocol docs now match.
- R4-2 — concurs: `revalidatedProposals` checks `additionsAvailable`, proposal
  confirmation checks it again, and `proposalWithAnEstablishedReplacementAddIsDropped`
  covers the stale-add case.
- R4-3 — concurs: `AiRestrictionsFile.add` and `AiRestrictionsJson.encode`
  require at least one removal; `addRejectsAddOnlyRecordsBeforeWriting` and the
  reload tests pin the symmetric contract.
- Contribution ordering — concurs: `AnnotationStore.contributionOrder` includes
  the new provenance fields and `orderingIncludesAiProvenanceFields` pins the
  order.
- Future-version guard — concurs: `loadForWrite` refuses an unknown version,
  add/remove surface a correlated error, and the test asserts the version-2 file
  is still intact.
- Residual checks — concurs with the improvements to status determination,
  version-filtered carried review state, and the artifact/documentation shape.
- No dissents.

### Residuals (not filed)

- `revalidatedCandidates` remains existence/intent based rather than comparing a
  stored content hash; the per-writer `version` filter is the current safeguard
  against text drift.
- `docs/engine.md` describes carried proposals as dropped when their removal
  target is gone, while `docs/summary/annotations-and-ai.md` also mentions the
  replacement-add case; the behavior is correct but the engine sentence is less
  complete.
- The additive `Contribution` decision documents source compatibility under v5
  but does not assert binary compatibility for external code that constructs
  `Contribution`; the project's additive-default precedent is the stated policy.
