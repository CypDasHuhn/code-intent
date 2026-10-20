# API-fidelity review — ticket 030 (client-authored annotations)

## Round 1

Verdict: The feature is wired end to end and the frozen `api` module is
untouched. Neovim persists a stable record, the host loads and resolves it,
`RuleEngine` injects it as a source contribution, composition consumes it for
purity, the payload publishes it through the existing `provenance` shape, and the
renderer adds a user marker. The contract problems are at the new contributor
boundary: the user layer is an unregistered pseudo-rule id that can collide with
a real pack, `PURE` is smuggled through the open intent set even though it is a
directive rather than an intent, the renderer identifies user authorship from a
hard-coded rule-id prefix rather than the published `source` tier, and the
durable anchor is a parallel format that never bridges to the reserved
`nodeAnchor`. These should be resolved before the experiment is merged.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/user/UserAnnotations.kt:6` with `analysis-engine/core/src/main/kotlin/dev/cint/core/runtime/scheduler/RuleEngine.kt:74-90` and `analysis-engine/core/src/main/kotlin/dev/cint/core/runtime/scheduler/RuleScheduler.kt:104-110` — the pseudo-contributor id is in the rule namespace but is not reserved.**

   `USER_CONTRIBUTOR_ID = "user:annotations"` matches the documented
   `<packId>:<localName>` rule-id format, so it is indistinguishable from a
   loaded rule. The scheduler reserves only `CoreContributors.PREFIX` (`"core:"`)
   in `rulesOf`; a third-party pack with id `user` and rule id `user:annotations`
   passes validation. `RuleEngine` pre-seeds `settled[USER_CONTRIBUTOR_ID]` and
   then overwrites that same key when the real rule runs, so the user facts are
   silently dropped. Even without a collision, `Contribution.rule` is documented
   as the producing rule id, but this contributor is not produced by a rule and
   does not appear in `ExecutionPlan.relations` or `terminal.sourceIntents`.

   Fix: give the user layer an explicit contributor kind, or reserve a
   namespace (for example reject any rule or pack id that claims `user:`) and
   document a `UserContributors` constant alongside `CoreContributors`. Add a
   loader/scheduler test that a pack cannot register `user:annotations` and a
   host test that a colliding rule cannot remove user facts.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/user/UserAnnotations.kt:8` with `UserAnnotationResolver.kt:26-27` and `analysis-engine/core/src/main/kotlin/dev/cint/core/facts/view/CapabilityView.kt:88-89` — `PURE` is published as an open intent although it is a purity directive.**

   `USER_PURE_INTENT = "PURE"` is placed in the same `intents` set as detection
   intents, so it appears in `Annotation.intents`, in per-contributor
   `provenance[].intents`, and in `intentsAt()`. `isUserPure` then recognizes the
   directive by looking for the same string. Nothing reserves `PURE`: a rulepack
   may own it, and user records may also use the reserved `UNTAGGED` name. On the
   client a PURE-only span resolves through the tag/default policy as a visible
   `PURE` tag, and when it merges with a pack contribution it changes
   `chosen_tag`/meaning selection even though purity is supposed to be an input
   to composition, not a rendered class.

   Fix: model purity as a directive (a reserved contributor kind/flag) separate
   from published intents, or at minimum reserve `PURE` like `UNTAGGED`, define
   its rendering semantics, and validate user intents against reserved names.
   Add a PURE-only render test and a PURE-merged test showing the intended tag
   and meaning.

3. **`nvim-renderer/lua/cint/render.lua:67-75` — user authorship is inferred from a hard-coded `"user:"` rule prefix instead of the payload's `source` tier.**

   The live payload already publishes `source = "user"` on the user contribution
   (`UserAnnotationResolver.kt:81`); the annotation-level `source` becomes
   `"mixed"` after merging, which is why the renderer walks provenance. But it
   tests `rule:sub(1, 5) == "user:"`, coupling the renderer to core's internal
   contributor id and mislabelling any future pack whose id begins with `user`
   (including the collision in finding 1). It also means the `U` marker is not a
   contract-level distinction the server can evolve. The marker itself
   (`render.lua:138-140`) overwrites any configured `sign_text`/icon and is only
   applied when some other handler produced options.

   Fix: key the marker off `contribution.source == "user"` (or add a documented
   contributor kind/flag to the payload), and add tests for a merged annotation
   and for a non-user contributor whose rule id merely starts with `user:`.
   Document that the marker overrides the configured icon when both are present.

4. **`analysis-engine/core/src/main/kotlin/dev/cint/core/user/UserAnnotations.kt:10-18` with `UserAnnotationResolver.kt:75-85` and `analysis-engine/server/src/main/kotlin/dev/cint/server/AnnotationPayload.kt:20` — the durable anchor is a parallel format and never populates the reserved `nodeAnchor`.**

   The ticket asks for a decided stable-anchor format and says the design must
   stay compatible with the reserved `nodeAnchor`; this implementation persists
   `path` + span + `symbol` + `contentHash` and emits a plain `Contribution`, so
   every user annotation is published with `nodeAnchor = null`. There is no
   mapping from the persisted identity to `NodeAnchor`, and no recorded decision
   that the anchor remains unused. The resolver also prefers `symbol`
   unconditionally, so `contentHash` does not veto a same-named symbol after an
   edit or rename; the annotation can bind to a different declaration at the
   nearest line.

   Fix: either derive a stable `NodeAnchor` id from the persisted record (and
   carry it through the contribution path) or explicitly record that user
   annotations do not participate in occurrence persistence and why. Make the
   symbol/content-hash precedence explicit and add stale/rename/duplicate-symbol
   tests so the anchor contract is pinned rather than accidental.

### Concurs / Dissents

- Concurs: no `api` source or `CINT_API_VERSION` change was needed. `Emit`
  remains source-only, `Role` stays core-only, and user records are rejected
  from emitting scaffold/derivative roles because they enter as
  `Role.SOURCE_INTENT` (`UserAnnotationResolver.kt:77`).
- Concurs: the published shape is additive and faithful to 016 — structured
  per-contributor `provenance`, open intent names, and an unchanged nullable
  `nodeAnchor` field. `HostUserAnnotationsTest` pins the merged annotation,
  unioned intents and both contributor ids, and verifies deletion removes the
  user provenance without disturbing the pack contribution.
- Concurs: unknown user intent names round-trip through the Kotlin model and
  survive the host path (`UserAnnotationsTest.persistenceRoundTripKeepsUnknownIntentsAndMetadata`),
  and the renderer's existing `resolve.default` policy handles names with no
  client tag entry.
- Concurs: purity reaches composition through the existing `isAnnotatedPure`
  capability (`CapabilityView.kt:88-89`) and derivative call handling
  (`DerivativeComposition.kt:52-55,98-110`); `UserAnnotationPureTest` pins the
  promotion of a user-PURE function through a logging chain.
- Concurs: explicit reload is a valid choice under the ticket. The client sends
  a fresh `didChange`/analysis request and the live server re-analyzes and
  re-publishes unconditionally (`LiveServer.kt:146-155,176-205`), so no file
  watcher is required.
- Dissents: findings 1-4.

### Verified

- No production module outside `core`, `host` and the renderer changed; the
  nvim commands expose annotate/unannotate/list/reload and the persistence file
  lives at `<root>/.cint/user-annotations.json`.
- User contributions are added to `settled` as fixed source facts before the
  first stratum, so they are monotone and cannot be retracted by a rule.
- `Composition.compose` excludes user contributions from the normal
  `sourceIntents` filter and adds them through the explicit `contextSources`
  parameter, which keeps unowned open intents from being rejected by the pack
  graph.
- `HostPipeline.analyze` reads and parses the file per analysis and passes the
  resolved contributions to the engine; parse failures degrade to no user
  annotations rather than failing analysis.
- The live payload serializes per-contributor `source`, so the renderer already
  has a contractual field for the user tier even though it does not use it.

### Residuals (not filed as findings)

- Split sources bypass the feature: `LiveServer.kt:233` and `SplitAnalyzer.kt:52-59`
  call `SplitAnalyzer`/`RuleEngine` directly, never `HostPipeline.analyze`, so
  Vue/split regions receive no user contributions (and would need region
  shifting if wired). If split files are in scope, thread the contributions
  through the region leaf and shift them with `Annotation.shift`.
- The Lua writer (`vim.json.encode`) and Kotlin reader/writer (`SnakeYAML`,
  manual JSON encoding) have no shared schema or cross-language fixture. The
  nvim spec never feeds its emitted JSON to `UserAnnotationsJson`, and the
  Kotlin tests never use a client-produced file.
- `HostPipeline` reports annotation-file read/parse failures only to stderr and
  continues with an empty user layer; the payload has no diagnostic for a
  corrupt `.cint/user-annotations.json`.
- The symbol resolver matches by name and line distance only. There is no
  structural node path, so renames lose the annotation and duplicate names can
  bind to the wrong declaration; `contentHash` is not used to validate a symbol
  match.
- `annotate` replaces all records for the same symbol/path, so independent
  intents on one symbol must be supplied together; `annotations()` and
  `CintAnnotations` are buffer-scoped rather than project-scoped.
- `HostPipeline.root` defaults to `null`, so library/test callers that do not
  pass a root silently opt out of user annotations; the CLI and server do pass
  it.
- Reviewer performed read/reason only; no build, test or formatter command was
  run, and the green handover is assumed per `docs/workflow.md`.
