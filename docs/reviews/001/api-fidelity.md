# API-fidelity review — ticket 001 (api module and frozen contract)

## Round 1

Verdict: The core shapes are close: `View` matches architecture.md §5 member-for-member, `Rule`/`RulePack` match §3, `Intent`/`UNTAGGED` and `Relation` match the resolved decisions, `:api` has no in-tree dependency, and `:rulepacks` depends only on `:api`. The contract is not clean yet, however: the plugin-facing `Emit` can carry core-only roles and a free-form rule id, the frozen module exposes core-only fact types that §5 never exposes, `Annotation.rule` can silently be empty, and `core` still has a public second annotation shape. These are contract-level gaps in 001, not requests for 002–010 behavior.

### Findings

1. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Contract.kt:39-50` — `Emit` lets a plugin choose any role and any rule id.**
   A rule can return `Emit(role = Role.SCAFFOLD, ...)` or `Role.DERIVATIVE_USE` and can set `rule` to an id other than its own. Architecture.md §1 says plugins may only emit `SOURCE_INTENT`; §2 says a rule emits only the intent it owns. There is no constructor validation, and the scheduler/store contract contains no rejection hook, so the boundary is advisory rather than structural. This is the same guarantee ticket 010 must enforce, but the frozen type should not make it impossible to enforce cheaply.
   Fix: make the plugin-facing emit type source-only (remove `role` or fix it to `SOURCE_INTENT`), or expose a source-only factory and have the scheduler/store reject any non-`SOURCE_INTENT` emit and any `Emit.rule != rule.id`; add a test that a non-source emit is rejected.

2. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Facts.kt:72-76` and `analysis-engine/api/src/main/kotlin/dev/cint/api/Model.kt:13-16` — the frozen module exposes core-only types.**
   `LoopFact` is not exposed by the §5 `View` and is used only by core's `SyntaxModel`; `SourceFile` is not referenced by any §5/§6 contract member and is used only by core/frontend/treesitter. Putting them in `api` freezes core implementation shapes and widens the plugin surface beyond architecture.md §9. `LoopFact` in particular has no path to a rule.
   Fix: move `LoopFact` back to core's syntax model and `SourceFile` to core (or a non-frozen shared module), keeping `api` to the §5/§6 types plus genuinely required supporting types (`Span`, `Role`, `NodeAnchor`, `Contribution`, `Coverage`, `SyntaxNode`, and the facts `View` exposes). If `SourceFile` must stay, record that decision against ticket 004's note.

3. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Model.kt:49-52` — `Annotation.rule` can be empty despite §6 requiring the producing rule.**
   `provenance` defaults to `emptyList()`, and `rule` is `provenance.firstOrNull()?.rule.orEmpty()`. A legal `Annotation(...)` with no provenance therefore has `rule == ""`, while the artifact and server both publish `annotation.rule`. The engine happens to populate provenance today, but the frozen contract does not require it.
   Fix: remove the default and require non-empty provenance (or validate in `init`), or carry an explicit required `rule` field and keep provenance for contributors; add a test for the minimum legal annotation.

4. **`analysis-engine/core/src/main/kotlin/dev/cint/core/model/Model.kt:8-28` — `RawAnnotation` remains a competing annotation model in `core`.**
   It duplicates `Annotation`'s role/intents/evidence/span/source/confidence/byte fields, adds a separate `rule` string, and is public while the engine passes both it and `api.Annotation` around. The acceptance says no module outside `api` defines a competing copy, and this keeps the old rule-string representation reachable even though `+`-joining was removed from `merge`.
   Fix: make the legacy store record internal and reduce it to a contribution record keyed by rule, or have it wrap `api.Annotation`/`Contribution`; 002 can complete the replacement, but the duplicate public type should not remain at the contract boundary. Add a test that the engine's only public annotation shape is `api.Annotation`.

### Verified

- `View` in `api/.../Contract.kt` has the exact §5 members and signatures: `calls`, `properties`, `functions`, `ifs`, `tries`, `catches`, `imports`, `references`, `throws`, `coverage`, `enclosingFunction`, `enclosingDeclaration`, `writes`, `isAnnotatedPure`, `annotations`, `intentsAt`. Both gated methods are present; their enforcement is correctly left to 004.
- `Rule`/`RulePack` match §3: `id`, `owns` (single `Intent`), `reads: Set<Intent>`, `run(View): List<Emit>`; `RulePack` has `id`, `languages`, `rules()`.
- `Intent` is an open `typealias` to `String`; `UNTAGGED_INTENT` is `"UNTAGGED"` and no rule in the API test owns it.
- `Relation(reader, intent, owner)` is exactly the resolved intent→rule edge from README decision #2, and the API test pins its shape. Production resolution is correctly deferred to 003.
- `Annotation` and `Emit` carry role, intents, evidence, span (line/column), byte offsets, source, confidence and a nullable `NodeAnchor`; the anchor is never populated by the engine. Provenance is structured `Contribution` data, and `RawAnnotation.merge` no longer concatenates rule strings.
- `api/build.gradle.kts` has no in-tree or third-party implementation dependency; `rulepacks/build.gradle.kts` depends only on `:api`. `settings.gradle.kts` includes both and no `:dsl`; every other module declares `implementation(project(":api"))`. `justfile` builds/tests `:api` and builds `:rulepacks`.
- No remaining same-named duplicate `Role`, `Span`, `SourceFile`, `UNTAGGED_INTENT`, `Annotation`, `SyntaxNode`, `Coverage` or fact types outside `api`; the remaining second annotation shape is `RawAnnotation`, covered in finding 4.

### Residuals (not filed as findings)

- `Span` is line/column only; byte offsets are sibling fields on `Annotation`/`Emit`, and `Contribution` has no byte offsets. The annotation still carries both, matching the old shape, but any consumer treating `span` as the complete range (or provenance as containing byte offsets) must be corrected in 002/016.
- `Schedule` has no explicit terminal-composition member. §4's terminal node can be run after the strata without changing `Schedule`, but 003/010 should either model it or document that choice.
- `Phase` and `RuleBlock` in `core/engine/Engine.kt` remain a legacy role/rule shape alongside `Role`/`Rule`. That is intentional per the ticket's “legacy detection remains until 006–014” note, not a 001 finding.

## Round 2

Verdict: All four round-1 findings are addressed. `Emit` no longer exposes `role`/`rule`, and `Rule.toContribution` stamps `SOURCE_INTENT` with the running rule's id; `LoopFact` moved to `core.syntax` and `SourceFile` to `core.model`, so `:api` now holds only the §5/§6 types plus the scheduler/annotation support they require; `Annotation` requires a non-empty provenance list and derives `rule` from a guaranteed contributor; `RawAnnotation` is `internal` and reduced to a contribution record, with a reflection test asserting a public engine surface free of it. `View` still matches §5 exactly, and the module graph is unchanged from round 1 except the `SourceFile`/`LoopFact` relocations. No fresh findings are filed.

### Findings

- F1: concurs — `Emit` has no `role`/`rule` fields (`api/Contract.kt:52-61`), and `Rule.toContribution` (`api/Contract.kt:18-29`) stamps `Role.SOURCE_INTENT` plus `this.id`. `ContractTest.emitCannotChooseItsRoleOrRuleId` proves it by reflection and asserts the contribution's rule/role; `Rule.run` still returns `List<Emit>`, so a rule has no channel to emit a core-only role.
- F2: concurs — `LoopFact` is now core's own type (`core/syntax/SyntaxModel.kt:10-14`), `SourceFile` is core's (`core/model/Model.kt:8-11`), and `api` contains no `SourceFile`/`LoopFact` references. What remains is the §5 `View`/facts (`SyntaxNode`, `CallFact`, `PropertyFact`, `FunctionFact`, `IfFact`, `TryFact`, `Coverage`), the §6 annotation types (`Role`, `Span`, `NodeAnchor`, `Contribution`, `Annotation`), `Intent`/`UNTAGGED_INTENT`, and the `Rule`/`RulePack`/`Relation`/`Schedule`/`Scheduler` contract.
- F3: concurs — `Annotation.provenance` is a required constructor parameter and `init` rejects an empty list (`api/Model.kt:46-51`); `rule` reads `provenance.first().rule` (`:53`). `ContractTest.annotationRequiresAtLeastOneContributor` covers the rejection, and the engine builds `Annotation` from `RawAnnotation.provenance` (`core/engine/Engine.kt:205-215`).
- F4: concurs — `RawAnnotation` is `internal` and holds only span/bytes/provenance, deriving role/intents/evidence/source/confidence (`core/model/Model.kt:13-37`); `RuleBlock.apply` and `AnalysisContext.emit`/`emitAll` traffic in `api.Contribution` (`core/engine/Engine.kt:34`, `:162-181`), and `PublicEngineSurfaceTest` asserts no public engine signature mentions `RawAnnotation`. The physical store swap is explicitly left to 002, which the round-1 fix note allowed.

### Verified

- `View` is unchanged from round 1 and still matches architecture.md §5 member-for-member: `calls`, `properties`, `functions`, `ifs`, `tries`, `catches`, `imports`, `references`, `throws`, `coverage`, `enclosingFunction`, `enclosingDeclaration`, `writes`, `isAnnotatedPure`, `annotations`, `intentsAt` (`api/Contract.kt:31-50`).
- `Rule`, `RulePack`, `Intent`/`UNTAGGED_INTENT`, `Relation(reader, intent, owner)`, `Schedule`, and `Scheduler` are unchanged in shape; the API test now includes a local `resolve()` that exercises the relation edge and an unknown-read rejection, still correctly leaving production stratification to 003.
- Module graph is unchanged: `api/build.gradle.kts` has no in-tree dependency, `rulepacks/build.gradle.kts` depends only on `:api`, every other module declares `implementation(project(":api"))`, `settings.gradle.kts` is the same eight-module list, and `justfile` still builds/tests `:api` and builds `:rulepacks`.
- The legacy `Phase`/`RuleBlock` path and `core/rules/Blocks.kt` still build against `api.Contribution`; `RawAnnotation` no longer appears there.

### Residuals

- The round-1 residual about provenance byte offsets is effectively addressed: `Contribution` carries `startByte`/`endByte`, and `Artifact`/`AnnotationPayload` publish provenance including byte offsets. That work sits ahead of ticket 016; 016 should reconcile its renderer contract against what already landed rather than re-implement it.
- `Span` itself remains line/column only with byte offsets as sibling fields, and `Schedule` still has no terminal-composition member; both are unchanged from round 1 and belong to 002/003/010.
