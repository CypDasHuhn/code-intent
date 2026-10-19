# Correctness review — ticket 001 (api module and frozen contract)

## Round 1

Verdict: The module extraction is mechanically sound: the frozen types moved without field loss, `:api` has no in-tree dependencies, `:rulepacks` depends only on `:api`, no competing copies or stale imports remain, and `RawAnnotation.merge` no longer concatenates rule strings. Two correctness gaps remain at the edges: the derived `Annotation.rule` is the only provenance field the artifact and live serializers publish, so multi-contributor spans silently lose contributors (and a default-constructed `Annotation` has an empty rule); and the new provenance span shifting in `SplitAnalyzer` has no test. The `Relation` "pin" is a self-fulfilling shape test rather than a semantic one. I found no behavior change in the moved types themselves.

### Findings

1. **analysis-engine/api/src/main/kotlin/dev/cint/api/Model.kt:50-52 — `Annotation.rule` is lossy for merged spans, can be empty, and the emitted payloads still drop the provenance list.**
   `provenance` defaults to `emptyList()`, so any construction that omits it yields `rule == ""`, which breaks the contract claim that an annotation carries its rule. For a merged annotation, `rule` is only `provenance.first().rule`. `Artifact.render` (`analysis-engine/core/src/main/kotlin/dev/cint/core/artifact/Artifact.kt:35`) and `LiveServer.annotationJson` (`analysis-engine/server/src/main/kotlin/dev/cint/server/LiveServer.kt:267`) publish only `annotation.rule` and never the `provenance` list, so the rest of the chain is lost. Reproduction: `LoggingStateBlock` emits a `SOURCE_INTENT` annotation at each guard condition and `ValidationBlock` emits one at `guard.condition`; a guard such as `if (loggingEnabled) return` (with `loggingEnabled` in `guardNames`, a direct exit and a side-effect-free condition) produces two contributions at the same role/bytes. They merge to provenance `[logging-state, validation-guard]`, but the CLI artifact and live payload report only `"rule":"logging-state"`, whereas the previous `+`-join retained both. Code that identifies a contributor with `it.rule == ...` (several `BlockTest` assertions) likewise cannot see a contributor that is not first.
   Fix: make `provenance` non-default (or reject an empty provenance in an `init`) so `rule` is never empty, and either serialize `provenance` in `Artifact`/`LiveServer` now or keep the legacy composite until a named ticket (002/016) changes the emitted shape. Add a serializer test with two contributors on one span; if the emitted-shape change is deferred, say so explicitly.

2. **analysis-engine/frontend/src/main/kotlin/dev/cint/frontend/SplitAnalyzer.kt:54-60 — the new provenance span shift is untested, so contributor coordinates can silently regress.**
   The shift correctly applies to the annotation span, byte offsets and every `provenance[*].span`, but `VueSplitTest.scriptAnnotationsUseHostCoordinates` only asserts `print.span`. Removing `provenance = provenance.map { ... }` would leave contributor spans relative to the `<script>` region while the annotation is host-relative, and the whole suite would still pass because no test reads provenance.
   Fix: assert `print.provenance.single().span` equals the expected host coordinates (for a single contributor it should equal the shifted annotation span), and add a fixture with two contributors on one span so the list-mapping path is exercised. Also assert `startByte`/`endByte` are host-relative for the merged annotation.

3. **analysis-engine/api/src/test/kotlin/dev/cint/api/ContractTest.kt:45-57 — the `Relation` test is tautological.**
   The test builds `byIntent` and `relations` inside the test, then asserts those values equal a `Relation` constructed from the same lookup. It never invokes a resolver, so it cannot fail if intent→owner resolution is wrong; for example, `associateBy` silently accepts duplicate owners and `getValue` only rejects an unknown name. It pins the data-class constructor, not the "resolved intent→rule edge" semantics the ticket says is pinned here.
   Fix: assert the defining invariants directly from the fixture (`intent in reader.reads`, `owner.owns == intent`, owner is unique) and add an unknown-intent negative case through a small test-local or api-level resolver. Full duplicate-owner/cycle behavior belongs to 003, but the positive relation should be produced by something other than the assertion's own expectation.

### Verified

- `:api` contains no in-tree dependencies and `:rulepacks` depends only on `:api`; `settings.gradle.kts` and `justfile` include both modules.
- No competing definitions of `Annotation`, `SourceFile`, `Span`, `Coverage`, `SyntaxNode`, the fact types, `Rule`, `RulePack`, `View`, `Emit`, `Relation`, `Schedule`, or `Scheduler` remain under `core`; only `RawAnnotation` and `LineIndex` stay in `core.model`.
- No stale imports of the moved types remain (`dev.cint.core.model.Annotation/SourceFile/Span/Role/UNTAGGED_INTENT`, `dev.cint.core.syntax.SyntaxNode`/fact types); every module that names them declares `:api`.
- The moves are field-for-field: `Role`, `SourceFile`, `Span`, `SyntaxNode` (including default members), `Coverage`, and all fact types match their previous definitions.
- `Rule`/`RulePack` shapes match the contract: one `owns`, `reads: Set<Intent>`, namespaced-id convention, pack `languages`; `Intent` is an open string alias and `UNTAGGED_INTENT` is reserved.
- `RawAnnotation.merge` no longer splits/joins on `+`; it unions intents, keeps the longest evidence, widens source to `mixed`, takes max confidence, and appends structured contributions with exact-equality de-duplication. Contribution order follows the deterministic legacy block order.
- `SplitAnalyzer` shifts annotation spans, annotation byte offsets and provenance spans consistently; the byte offset is region-relative in the same way as the pre-existing annotation shift.
- `Emit.nodeAnchor` and `Annotation.nodeAnchor` exist and are unused (grep confirms no producer sets them); they default to null and the contract tests assert that.
- No emitted rule value can contain `+`: no `joinToString("+")` or equivalent remains, and all rule ids/names in `rules/Blocks.kt` are static strings.
- Legacy `rules/Blocks.kt`, `intents.yaml` and `Phase` remain, as the ticket requires for 014.

### Residuals (not filed as findings)

- `RawAnnotation` still carries a `rule` field that `merge` leaves as the receiver's rule while `provenance` holds all contributors. It is currently consistent with the "primary rule" meaning of `Annotation.rule` and no code reads it after a merge, but it is a second source of truth that 002 should collapse (or derive).
- `Contribution` has no byte offsets, so per-contributor provenance cannot express them even though annotation spans do; if contributor-level bytes are needed for the renderer, 016 will have to extend the shape.
- Rule-id `"<packId>:<localName>"` uniqueness and the `UNTAGGED` never-owned/never-read invariant are conventions here, not checks; the executable enforcement lands with pack loading (011) and scheduler load errors (003).
- `Schedule` has no explicit terminal-composition node; it lists rule strata and relations only. The terminal node can be implicit in the engine, but 003/010 should confirm the shape is sufficient.
- `Role` still exposes `SCAFFOLD` and `DERIVATIVE_USE` in the plugin-facing enum, although architecture §1 says those roles are core-only at the plugin boundary; ticket 016 restates this. The ticket requires a role field on `Emit`, so this may be intentional for now.
- `Artifact.render` and `LiveServer.annotationJson` also omit `nodeAnchor`, which is consistent with "reserved, unused" for 001.

## Round 2

Verdict: All three round-1 findings are fixed, and the wider refactor that carried the fixes is behavior-preserving for the legacy engine: `RawAnnotation` is now an internal contribution record whose derived fields (`intents` union, longest `evidence` with first-max tie-break, single-or-`mixed` `source`, max `confidence`, first `role`) reproduce the old `merge` results, and the annotation-level surface is unchanged for single-contributor spans. Provenance is now required, published additively with per-contributor byte offsets by both the CLI artifact and the live payload, the split shift moves contributor spans and bytes, and the `Relation` test exercises a resolver with positive and negative cases. No fresh correctness defects were found in the changed code.

### Findings

- F1: concurs — `Annotation.provenance` is a required constructor parameter and `init` rejects an empty list (`analysis-engine/api/src/main/kotlin/dev/cint/api/Model.kt:46,49-51`), so `rule` (`:53`) can no longer be empty; `Contribution` now carries `startByte`/`endByte` (`:33-34`). `Artifact.render` emits a `provenance` array with rule/role/intents/evidence/source/confidence/line-col/bytes per contributor plus annotation-level `startByte`/`endByte` (`analysis-engine/core/src/main/kotlin/dev/cint/core/artifact/Artifact.kt:41-44,56-66`), and `annotationJson` does the same (`analysis-engine/server/src/main/kotlin/dev/cint/server/AnnotationPayload.kt:19-38`). `ArtifactTest.renderPublishesEveryContributorInProvenance` and `AnnotationPayloadTest.publishesEveryContributorInProvenance` each build a two-contributor annotation and assert both entries and their bytes are present, so a regression back to first-contributor-only is now caught. The remaining primary-only `rule` is acceptable given the disposition (final renderer shape deferred to 016).
- F2: concurs — `SplitAnalyzer.shift` now maps `provenance` entries, shifting both `span` and each contributor's `startByte`/`endByte` (`analysis-engine/frontend/src/main/kotlin/dev/cint/frontend/SplitAnalyzer.kt:54-66`). `VueSplitTest` asserts the single-contributor case (contributor equal to the annotation span/bytes, `:67-72`) and the two-contributor case (`logging-state` + `validation-guard`, contributor spans equal to the merged annotation, host-derived expected byte range, `:75-108`); dropping or misdirecting the provenance shift would now fail.
- F3: concurs — the test-local `resolve` (`analysis-engine/api/src/test/kotlin/dev/cint/api/ContractTest.kt:26-37`) rejects duplicate owners via `putIfAbsent` and unknown reads via `IllegalArgumentException`, and `relationResolvesReadIntentToOwningRule` asserts `intent in reader.reads`, `owner.owns == intent`, exactly one owner for the intent, the expected `Relation(validation, "BOOKKEEPING", bookkeeping)`, plus `relationRejectsUnknownReadIntent` for the negative case (`:58-78`). The test now exercises resolution rather than re-deriving its own expectation.

### Verified

- `RawAnnotation` equivalence: derived `intents`/`evidence`/`source`/`confidence`/`role` match the previous `merge` semantics for the cases the old code handled (union; longest evidence with receiver winning ties because it appears first; `"mixed"` only when sources differ; `max`; all contributors share the keyed role), and `span`/`startByte`/`endByte` still come from the first contribution.
- `AnalysisContext.emit`/`emitAll` traffic in `api.Contribution` and key by role/bytes; `merged != existing` still compares the merged provenance, so re-emitting an identical contribution is a no-op and does not mark the context dirty.
- No production path constructs an `Annotation` without provenance: the only constructor call is `AnalysisContext.annotations()` (`core/engine/Engine.kt:205-215`), and `ContractTest.annotationRequiresAtLeastOneContributor` pins the rejection.
- Both emitted payloads contain all contributors: the artifact builder emits valid comma-separated JSON (no trailing separator) and the live payload maps the list for Jackson; the two new tests parse/assert both contributors and their byte offsets.
- `SplitAnalyzer` shifts annotation and contributor coordinates consistently at each region level, and the test derives the expected host line/col/byte from the source text rather than from the analyzer output.
- Module graph and imports are still correct after the `SourceFile`/`LoopFact` relocations: `:api` has no in-tree dependency, `:rulepacks` depends only on `:api`, all other modules declare `:api`, and no module imports a removed `dev.cint.api.SourceFile`/`LoopFact`.
- No competing copies of the moved types, no stale imports, and no rule string contains `+`; `RawAnnotation` is `internal` and `PublicEngineSurfaceTest` asserts the public engine surface names only `api.Annotation`/`api.Contribution`.
- `Emit` is now source-only and `Rule.toContribution` stamps the running rule's `id` with `Role.SOURCE_INTENT`; correctness-neutral today because no production code emits via `Emit` yet.

### Residuals

- `Emit` no longer exposes `role`/`rule` (the ticket's scope wording lists both on `Emit`) and `Rule.toContribution` hardcodes `SOURCE_INTENT` plus `this.id`. Correctness-neutral for 001, but 003/006 must key store replacement by rule id so two rules cannot share one contributor entry.
- `RawAnnotation.merge` still accumulates provenance instead of replacing a rule's previous contribution each iteration; a rule that changes a contribution at the same span can leave a stale entry. Unchanged from round 1 and owned by 002.
- `SourceFile` and `LoopFact` left `:api` for `core` in round 2. Verified that the resulting imports surface is consistent and that no `api` member references either; no correctness impact found.
