# Correctness review — ticket 009 (validation rulepack)

## Round 1

Verdict: The guard detector itself is a faithful port of `ValidationBlock`:
direct-exit recognition, explicit comment/`BOOKKEEPING`/predicate/pure
precedence, constant-condition suppression, confidence/source selection, and the
emitted condition span/evidence/bytes all match the legacy block on the fixtures
and added guard cases. The rule is pure, emits only its owned `VALIDATION`, reads
only declared `BOOKKEEPING`, and the scheduler correctly places it after
`bookkeeping`. Return categorization parity with `ReturnBlock`, however, is not
preserved in either direction, because composition now derives validation
returns exclusively from this rule's guard annotations; and the five new `View`
members expand the frozen `api` without a recorded contract decision. Three
findings below.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/ValidationRulePack.kt:63-68` with `analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:145-158` — the direct-exit gate suppresses annotations that `ReturnBlock` used on the false edge and on nested returns.**
   `ValidationRule.run` only considers a guard when its *then* body contains a
   direct exit. `Composition.isValidationReturn` then requires a `VALIDATION`
   annotation on one of the return's guard conditions. Legacy `ReturnBlock`
   recomputed `isValidationGuard` from the guard itself and also tagged a
   rejection-sentinel return on the false edge, or a return nested below a
   qualifying guard. Concrete regression against the legacy engine:

   ```kotlin
   fun chooseNull(ok: Boolean): String? {
       if (!ok) {
           work()
       } else {
           return null
       }
   }
   fun work() {}
   ```

   The then body has no direct exit, so the new rule emits no annotation and the
   `return null` is `UNTAGGED`; legacy `ReturnBlock` sees the pure guard on the
   false edge and `isRejection("null")`, so it is `VALIDATION` (the behavior
   pinned by `CompositionTerminalTest.returnCategoryCoversRejectionSentinelsOnFalseEdgesAndFinalReturns`).
   A `// cint:validation` before that guard does not help either, because the
   rule still exits at the direct-exit check. The same loss occurs for a return
   nested under a qualifying outer guard whose direct then body is another `if`.
   This is an undocumented narrowing, not parity with `ValidationBlock`/`ReturnBlock`.
   Fix: decide the intended semantics and pin it. If `ReturnBlock` parity is
   required, make the validation source cover the guard shapes returns need (or
   keep the legacy guard predicate in return categorization and consume the source
   intent in addition), and add legacy-vs-new tests for an else-edge
   `return null`/bare `return` and a nested return under a pure guard. Otherwise
   record the narrowing as approved in the ticket/`intent-model.md` and test it.

2. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/ValidationRulePack.kt:68-85` with `Composition.kt:145-158` — a bookkeeping-only guard changes a return from legacy `UNTAGGED` to `VALIDATION`.**
   The rule tags a guard when its body has a `BOOKKEEPING` write even if the
   condition is effectful and is neither explicit, a predicate, nor
   side-effect-free. Legacy `ValidationBlock` did the same, but legacy
   `ReturnBlock.isValidationGuard` did not consult bookkeeping, so its return
   stayed `UNTAGGED`. With the new composition the source annotation wins:

   ```kotlin
   class C {
       @Bookkeeping var skipped = 0
       fun f(value: Int): Boolean {
           if (consume(value)) {
               skipped++
               return false
           }
           return true
       }
   }
   fun consume(value: Int): Boolean = value > 0
   ```

   `consume(value)` is a call expression, so it is not side-effect-free; only
   `hasBookkeeping` fires. The new `return false` is `VALIDATION`; legacy
   `ReturnBlock` reports `UNTAGGED`. This is a widening that the fixture parity
   test cannot see because its bookkeeping guards also have pure conditions
   (`value < 0`, `value == 0`). Fix: either document this as an approved
   divergence from `ReturnBlock` (and cover it), or restrict bookkeeping-triggered
   guard annotations to those that may also categorize returns, for example by
   distinguishing the guard certificate from return eligibility. Add the
   effectful-bookkeeping-guard case to the legacy comparison.

3. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Contract.kt:55-59` — five abstract members are added to the frozen `View` without a contract note or versioning decision.**
   `isConstant`, `isSideEffectFree`, `statements`, `isExit` and
   `closestCommentBefore` are now part of the plugin contract. This forces every
   non-core `View` implementation to add them (the test fake was updated), and a
   pack compiled against the new surface can be loaded by an older core that
   still reports the same `CINT_API_VERSION` (currently `1`), then fail at
   runtime with `AbstractMethodError`/`NoSuchMethodError` instead of being
   rejected by 011's compatibility gate. Unlike ticket 006's Contract note, the
   ticket, architecture §5/§9, and pack metadata record neither the extension nor
   its versioning consequence. Fix: record the deliberate contract extension and
   its versioning consequence in the ticket/architecture (and bump or otherwise
   gate `CINT_API_VERSION` if the additive surface is to be detectable), or keep
   these syntax helpers out of the frozen `View` and expose them through a
   narrower pack-facing facility. Add a contract test if they remain.

### Concurs / Dissents

- Concurs: `ValidationRule.run` mirrors `ValidationBlock` for guards with a
  direct then exit: `hasDirectExit`, explicit comment/`BOOKKEEPING`/predicate/pure
  signal order, the constant-and-not-explicit/bookkeeping suppression, 1.0/0.9/
  0.7/0.5 confidence, `comment` versus `treesitter` source, and the condition
  span/evidence/byte offsets.
- Concurs: `hasDirectExit` handles braced and single-statement then bodies the
  same way the legacy block did, including `return`, `continue` and `break`.
- Concurs: `hasBookkeeping` reads only the declared `BOOKKEEPING` intent through
  the capability-scoped view and restricts the check to annotations inside the
  then body; `isValidationPredicate` reproduces the legacy call-name/prefix
  matching, and the defaults match `intents.yaml`.
- Concurs: `hasValidationComment` reproduces the nearest-comment and
  one-line-gap rule; the function-body scope resolves to the same scope as the
  legacy `enclosingFunction`/`bodyOf` lookup for normal functions.
- Concurs: the rule is pure, performs no store mutation, owns only `VALIDATION`,
  and emits exactly that set; `reads = {BOOKKEEPING}` is honest.
- Concurs: the scheduler test pins `bookkeeping:bookkeeping` in stratum 0 and
  `validation:validation` in stratum 1, with `reads = {BOOKKEEPING}`.
- Concurs: the validation parity test compares role, intents, evidence, span,
  source tier, confidence and byte offsets against `ValidationBlock` over all
  four fixtures and the added guard cases; the dedicated tests cover pure
  predicate, explicit comment, bookkeeping, `return false`, bare `return`,
  plumbing-local derivative use, and no store mutation.
- Dissents: findings 1 and 2 (return categorization is not `ReturnBlock`-parity)
  and finding 3 (unrecorded frozen-API expansion).

### Residuals (not findings)

- The production `META-INF/services/dev.cint.api.RulePack` remains empty; pack
  registration/host wiring is not in ticket 009's scope and is left to 015.
- `ValidationRulePack` does not carry `@RulePackApi`; its explicit `apiVersion`
  override still passes 011's post-construction check, but the pre-construction
  annotation gate is not used.
- The tests compare the new pipeline against the legacy engine only over the
  listed fixtures/guard cases; else-edge rejection returns, nested returns, and
  effectful bookkeeping guards are not in the table, which is why findings 1 and
  2 are silent today.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 2

Verdict: F1 and F2 are addressed behaviorally. `Composition` now recomputes the
`ReturnBlock` guard predicate from comments, configured predicates, purity and
the constant-guard rule, so false-edge rejection returns, nested returns, and
effectful `BOOKKEEPING` guards match legacy; the new tests cover all three. F3 is
only partly addressed: `closestCommentBefore` was removed from `View` and a
contract test was added, but the remaining four abstract members are still an
unrecorded, unversioned frozen-API expansion. Two new correctness findings remain:
the pack's new comment recognition treats string content as comments, and return
categorization is now gated on the validation pack's presence rather than its
observable output.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/ValidationRulePack.kt:119-133` — `isComment` classifies string content as a comment and can fabricate a validation guard.**

   `hasValidationComment` walks every descendant of the function scope and
   accepts any node whose trimmed text starts with `//` or `/*`. Tree-sitter
   represents string bodies as `string_content` (Kotlin) / `string_fragment`
   (JavaScript/TypeScript) nodes, and those nodes carry the raw inner text. A
   string immediately before a guard can therefore be selected as the closest
   "comment":

   ```kotlin
   fun f(value: Int) {
       val label = "// cint:validation"
       if (consume(value)) {
           return
       }
   }
   fun consume(value: Int): Boolean = value > 0
   ```

   With no real comment, the string body is the latest descendant ending before
   the guard, its text matches `commentPattern`, and the guard is tagged
   `VALIDATION`; legacy `closestCommentBefore` filters by comment kinds and does
   not tag it. `"/* cint:validation */"` behaves the same. The effectful guard
   means predicate/pure detection cannot mask the false positive. Fix: restore a
   syntax-model-backed comment lookup (for example `closestCommentBefore` or a
   narrower `isComment`/comment-kinds `View` fact) instead of sniffing node text,
   and add a regression with a `// cint:validation` string before an effectful
   guard asserting no validation source.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:100,149-155` with `analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/ValidationRulePackTest.kt:351-384` — return categorization now depends on whether a `VALIDATION` owner is loaded, not on that rule's output.**

   `returnContributions` receives `VALIDATION_INTENT in terminal.sourceIntents`
   and `isValidationReturn` returns `false` when it is absent. With the validation
   pack loaded, `isValidationGuard` is computed entirely from syntax and
   `IntentConfig`; the pack's emitted annotation is never consulted. Removing the
   pack therefore flips the same return from `VALIDATION` to `UNTAGGED` even
   though the core predicate would classify it identically, which contradicts
   ticket 010's acceptance that composition is invariant to the set of loaded
   packs ("adding or removing a pack can never change it"). Conversely, a loaded
   owner that emits no validation annotation still enables core-detected
   validation returns. `returnsAreValidatedOnlyWhenTheValidationPackIsLoaded`
   codifies the pack-presence dependency rather than checking emitted intent.
   Fix: choose one source of truth. If return categorization is core-owned, drop
   the `validationLoaded` gate and derive it solely from the CFG/config so pack
   removal cannot change composition. If it must follow the pack, consume the
   rule's annotations (extending their guard coverage for false-edge/nested
   returns) instead of reimplementing the predicate in `Composition`. Whichever
   is chosen, update the 010 acceptance expectation and test both the pack-present
   and pack-absent results; document the dependency if it is intentional.

### Concurs / Dissents

- F1: concurs — `Composition.isValidationGuard`
  (`Composition.kt:161-171`) now mirrors legacy `ReturnBlock`: explicit comment,
  configured predicate, side-effect-free condition, with constant conditions
  rejected unless explicit. The false-edge `return null`, nested loop return, and
  effectful `BOOKKEEPING` cases added to `returnsMatchLegacyCategorization`
  close the round-1 reproductions.
- F2: concurs — bookkeeping is no longer consulted by return categorization, so
  the effectful bookkeeping guard's `return false` is `UNTAGGED` again, matching
  `ReturnBlock`, while the guard itself still composes `{VALIDATION,
  BOOKKEEPING}`.
- F3: dissents — the surface is reduced from five to four members and
  `viewExposesValidationSyntaxFacts` pins their presence, but
  `Contract.kt:55-58` still extends the frozen `View` with no ticket/architecture
  contract note, and `CINT_API_VERSION` remains `1`. A pack calling these methods
  can still load under an older core that reports the same version and fail at
  runtime. The round-1 fix request (record the extension and versioning
  consequence, or move the helpers behind a narrower facility) is not satisfied;
  the contract test only restates the added names.
- Concurs: the guard source and scaffold assertions are no longer conditional;
  the tests now require the `VALIDATION` source annotation on every guard, a
  guard-span scaffold, and the `{VALIDATION, BOOKKEEPING}` composition for a
  bookkeeping guard.
- Concurs: `hasDirectExit`, predicate matching, the `{BOOKKEEPING}` read,
  scheduler placement, purity/no-store-mutation, and the legacy shape fields
  remain as reviewed in round 1.
- Dissents: new findings 1 and 2.

### Residuals (not findings)

- `Composition` now duplicates validation detection in core (`isValidationGuard`,
  `isValidationPredicate`, `hasValidationComment`) and reads the legacy
  `IntentConfig` predicate/magic-comment values, while the rule reads pack-local
  `ValidationConfig`. Defaults agree, but a custom pack config (predicates or
  `magicComment`) is ignored by return categorization. This is a second source
  of truth until the typed-config migration (012/013) and the architecture
  reviewer's core/rulepack boundary call.
- The production `META-INF/services/dev.cint.api.RulePack` file remains empty;
  registration/host wiring remains out of ticket 009's scope.
- `ValidationRulePack` still has no `@RulePackApi` annotation, so only the
  post-construction `apiVersion` check applies.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 3

Verdict: The round-2 findings are addressed. Comment recognition now filters by
`ValidationConfig.commentKinds`, and the string-content cases are pinned; return
categorization is certified by emitted `VALIDATION` annotations rather than pack
presence, with a no-op validation pack test; and untagged rejection returns are
neutral in the scaffold union while return parity is retained. F3 remains
unaddressed (the four `View` additions are still unrecorded and unversioned). One
new correctness finding: the return-certification helper treats any descendant
`return` as belonging to the guard, including returns inside nested lambdas or
local functions.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/ValidationRulePack.kt:106-112` — `hasReturn` counts nested lambda/local-function returns and can certify a guard with no function-level return.**

   `hasReturn` scans every descendant of the guard node for any `isExit` whose
   text starts with `return`. It does not restrict the search to the guard's
   enclosing function or to returns represented in the CFG. A return inside a
   nested function or lambda therefore makes `returnGuard` true and emits a
   source `VALIDATION` annotation:

   ```kotlin
   fun f(values: List<String>) {
       if (values.isNotEmpty()) {
           fun positive() {
               return
           }
           positive()
       }
   }
   ```

   The `return` belongs to `positive`, and the outer then body has no direct
   exit, so legacy `ValidationBlock` emits no guard annotation and legacy
   `ReturnBlock` has no CFG return for that guard. The new rule emits `VALIDATION`
   on `values.isNotEmpty()` because the condition is a predicate; composition
   then adds an ambient validation scaffold (and can classify plumbing locals)
   for a guard that is not a return guard. A return in a lambda (including
   `return@label`) has the same effect. `matchesLegacyValidationOverFixturesAndGuardCases`
   does not include these return-only shapes, and `returnsMatchLegacyCategorization`
   compares only return categories, so the extra source/scaffold annotation is
   unobserved. Fix: base return certification on actual CFG return sites whose
   guard chain contains this guard (expose a `View` return-site fact or let
   composition decide certification from CFG), or at minimum exclude descendants
   whose enclosing function/lambda is not the guard's own function. Add
   lambda/local-function and newline/else-edge source/scaffold parity checks, not
   only return-category checks.

### Concurs / Dissents

- F4: concurs — `hasValidationComment` now filters descendants by
  `config.commentKinds` (`comment`, `line_comment`, `multiline_comment`), which
  covers the shipped profiles and no longer accepts string content. The Kotlin
  `//`/`/*` string cases and the JavaScript string case are pinned by
  `stringContentIsNotAValidationComment`.
- F5: concurs — `Composition.isValidationGuard` requires a certified
  `VALIDATION` annotation on the guard condition
  (`Composition.kt:163-165`) and no longer consults pack presence. The new
  `returnsUseEmittedValidationIntentsNotPackPresence` test distinguishes no
  pack, a no-op validation owner, and the real rule.
- F3: dissents — the four abstract `View` members remain unrecorded in the ticket
  and architecture, and `CINT_API_VERSION` is still `1`; the contract test only
  asserts the names exist. The versioned-contract concern from rounds 1-2 is
  unchanged.
- Concurs: rejection sentinel and bare `return` exits are neutral for the
  scaffold union (`Composition.kt:498`), and the bookkeeping-only guard test now
  pins `return false` as `UNTAGGED` while the scaffold is `{VALIDATION,
  BOOKKEEPING}`. Return-category parity cases (false edge, nested loop, effectful
  bookkeeping) remain covered.
- Dissents: new finding 1 (unscoped nested returns in return certification).

### Residuals (not findings)

- `Composition` still duplicates the legacy validation predicate/comment logic
  and reads `IntentConfig`, while the rule reads pack-local `ValidationConfig`;
  custom predicates or `magicComment` are ignored by return certification.
- The return-only guard emissions introduced for `ReturnBlock` parity broaden the
  validation source surface beyond `ValidationBlock` for else-edge/nested-return
  guards; that may be intended, but the source/scaffold parity table should
  include those shapes explicitly.
- The production `META-INF/services/dev.cint.api.RulePack` file remains empty;
  registration/host wiring remains out of ticket 009's scope.
- `ValidationRulePack` still has no `@RulePackApi` annotation, so only the
  post-construction `apiVersion` check applies.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 4

Verdict: The round-3 finding is addressed. Return-only certification and
`hasReturn` are gone, so nested lambda/local-function returns cannot fabricate a
validation guard; return categorization is again derived in `Composition` from
the CFG and legacy guard predicate, independent of pack presence or emissions;
the source/return parity table now includes the false-edge, nested, local-function
and lambda cases. Scaffold neutralization is correctly limited to bare `return`
and `return false`, and the contract note records the four additive `View`
members, so F3-F6 are concurred. One new correctness finding remains: return
categorization now ignores whether `VALIDATION` is defined in `IntentConfig`, so
it tags pure guards as validation in configs where legacy `ReturnBlock` was never
installed.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:148-178` — return categorization is unconditional on the `VALIDATION` intent definition, unlike legacy `ReturnBlock`.**

   `isValidationGuard` checks explicit comment / predicate / purity and
   `isValidationPredicate` simply returns false when
   `config.intents[VALIDATION_INTENT]` is absent, but the `explicit || predicate
   || pure` fallback still runs. Legacy `IntentBlocks.build` only appends
   `ReturnBlock` when `config.intents["VALIDATION"] != null`, so a config without
   a `VALIDATION` definition produced no validation returns at all. The new
   composition now tags pure guards anyway:

   ```kotlin
   val config = IntentConfig("kotlin", emptyMap(), emptyList())
   fun f(value: String?): Int {
       if (value != null) {
           return 1
       }
       return 0
   }
   ```

   Legacy leaves both returns `UNTAGGED`; the new pipeline tags `return 1`
   `VALIDATION` because `value != null` is side-effect-free. The existing
   empty-config regression (`CompositionTerminalTest.noSourceIntentsStillClassifiesReturnsWithoutScaffoldsOrDerivatives`)
   uses only a final return, so it cannot catch this. Fix: short-circuit
   `isValidationGuard`/`isValidationReturn` when the `VALIDATION` definition is
   absent (the same condition that installed the legacy `ReturnBlock`), or
   explicitly document that core return categorization is unconditional after
   the config migration. Add an empty/partial-config test with a pure guard and
   a return to pin the chosen behavior.

### Concurs / Dissents

- F3: concurs — `docs/tasks/009-validation-rulepack.md` now records the four
  additive `View` members, the external-implementation obligation, and the 011
  compatibility gate. The record is the requested contract note; the unchanged
  `CINT_API_VERSION = 1` value remains a versioning-policy residual for 011's
  owner, not a new 009 correctness defect.
- F4: concurs — comment lookup is limited to `config.commentKinds`, and the
  invalid nested-function/lambda return regressions confirm no return-only
  source is fabricated. String content is still excluded.
- F5: concurs — `Composition` no longer reads `terminal.sourceIntents` or emitted
  `VALIDATION`; `returnCategorizationDoesNotDependOnLoadedValidationPacks`
  produces the same return with the real pack, a no-op validation owner, and no
  validation pack, restoring 010's pack-independence for return identity.
- F6: concurs — `hasReturn`/`returnGuard` were removed, and
  `nestedFunctionAndLambdaReturnsDoNotCertifyGuards` proves local-function and
  lambda returns neither emit `VALIDATION` nor create a validation scaffold.
- Concurs: `exitIntents` still treats bare `return` as neutral and now scopes the
  rejection-sentinel special case to `"false"` only; `onlyFalseAndBareReturnsAreNeutralForScaffolds`
  pins `null`/`0` as still carrying `UNTAGGED` while `false` does not.
- Concurs: the validation parity table now includes the false-edge and nested
  control-flow shapes that previously produced extra source annotations, so
  `ValidationBlock` source parity is pinned for them.
- Dissents: new finding 1 (return categorization outside a configured
  `VALIDATION` intent).

### Residuals (not findings)

- `Composition` still duplicates the legacy validation predicate/comment logic
  and reads `IntentConfig`, while the rule reads pack-local `ValidationConfig`;
  custom predicates or `magicComment` are ignored by return categorization.
  This is a design/config-migration concern for 012/013 and the architecture
  boundary review.
- The `CINT_API_VERSION` value remains `1`; the contract note defers the
  additive-surface versioning policy to 011.
- The production `META-INF/services/dev.cint.api.RulePack` file remains empty;
  registration/host wiring remains out of ticket 009's scope.
- `ValidationRulePack` still has no `@RulePackApi` annotation, so only the
  post-construction `apiVersion` check applies.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 5

Verdict: The round-4 config fallback is addressed by requiring emitted
`VALIDATION` annotations, empty-intent split scaffolds are suppressed, and the
empty-config/no-op-pack and bare-return tests are useful. The certification path
does not, however, restore `ReturnBlock` parity for return-only guards: the
validation rule still emits only for guards with a direct then exit, so a
false-edge rejection return or a return nested under a guard without a
direct-exit descendant guard has no annotation to certify. The `guard.node`
containing-annotation check cannot supply it, because the emitted annotations are
on nested conditions and are contained by the guard, not containing it. The
round-4 `chooseNull` return case was dropped from `returnsMatchLegacyCategorization`,
and the nested-loop case now adds an inner direct-exit `if`, which hides the
remaining gap.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:158-166` with `analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/ValidationRulePack.kt:64-79` and `analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/ValidationRulePackTest.kt:286-365` — return-only guards still cannot be certified, so false-edge rejection and plain nested-loop returns lose legacy `VALIDATION`.**

   `isValidationGuard` now requires a `VALIDATION` annotation containing the
   guard condition or the whole guard node. `ValidationRule` only emits for
   `if`s whose then body has a direct `return`/`continue`/`break`. Consider:

   ```kotlin
   fun chooseNull(ok: Boolean): String? {
       if (!ok) {
           work()
       } else {
           return null
       }
   }

   fun nested(values: List<String>): Int {
       if (values.isNotEmpty()) {
           while (true) {
               return values.first().length
           }
       }
       return 0
   }
   ```

   Neither `if` has a direct exit in its then body, so the rule emits nothing.
   `containingAnnotations(guard.node)` does not help: the only annotations that
   could exist are on nested condition nodes and therefore lie inside the guard,
   not around it. Legacy `ReturnBlock` tags both `return null` (false edge,
   rejection sentinel) and the while return (predicate guard) as `VALIDATION`.
   The current return-parity table no longer contains the first shape, and the
   second was rewritten with an inner `if (values.isNotEmpty())` that emits the
   annotation the outer guard cannot get. This is an undocumented narrowing of
   the accepted `ReturnBlock` parity, not merely a test omission. Fix: certify
   guards from actual CFG return sites (a View/tree fact for returns whose guard
   chain contains the guard, excluding nested functions/lambdas), or let the rule
   emit a return-guard certificate for those sites without the direct-exit
   requirement. Restore the `chooseNull` and plain nested-`while` cases to
   `returnsMatchLegacyCategorization` and keep the nested-function/lambda
   exclusion.

### Concurs / Dissents

- F3: concurs — the ticket contract note records the four additive `View`
  members and the external-implementation obligation; `CINT_API_VERSION = 1` is
  left as the 011 versioning residual.
- F4: concurs — comment lookup uses `config.commentKinds`; string content and
  nested-function/lambda returns no longer fabricate validation sources.
- F5: concurs for the stated change — return categorization no longer depends on
  pack presence, a no-op validation owner, or `.config` fallback; it requires an
  emitted annotation. The new finding shows the annotation coverage is too
  narrow for return-only guards, not that the gate itself is wrong.
- F6: concurs — `hasReturn`/`returnGuard` remain removed and the local-function
  and lambda regressions pass.
- Concurs: the empty-config/no-op-pack assertions in
  `returnsRequireEmittedValidationAnnotations` and the strengthened bare-return
  scaffold assertions cover the round-4 config concern.
- Concurs: skipping empty-intent then/else split contributions avoids emitting a
  scaffold with an empty intent set, and the `onlyFalseAndBareReturnsAreNeutralForScaffolds`
  test now distinguishes `false` from `null`/`0` correctly.
- Dissents: new finding 1 (return-only guard certification and narrowed return
  parity tests).

### Residuals (not findings)

- `Composition.isValidationPredicate`/`hasValidationComment` still duplicate the
  rule's pack-local detection settings and read `IntentConfig`; custom
  `ValidationConfig` predicates or `magicComment` can disagree with return
  categorization. Deferred to the 012/013 config migration and architecture
  boundary review.
- The `containingAnnotations(guard.node)` clause currently has no reachable
  producer in the bundled pipeline (validation emits on conditions, never on a
  containing guard); if certified return guards are added, the direction and
  span should be specified rather than relying on this clause.
- The production `META-INF/services/dev.cint.api.RulePack` file remains empty;
  registration/host wiring remains out of ticket 009's scope.
- `ValidationRulePack` still has no `@RulePackApi` annotation, so only the
  post-construction `apiVersion` check applies.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.
