# Detection-fidelity review — ticket 009 (validation rulepack)

## Round 1

Verdict: The rule itself is parity-clean on the existing fixtures and on the
guard cases the new test exercises: direct `return`/`continue`/`break` detection,
predicate/default matching, the `BOOKKEEPING` read, constant-guard exclusion,
comment scope, and the span/evidence/source/confidence shapes all line up with
`ValidationBlock`. It is a pure rule with an honest `reads = {BOOKKEEPING}` and
the scheduler puts it in stratum 1. The return/scaffold side is not fully
parity-clean, however: the new pipeline's return categorization diverges from
legacy `ReturnBlock` in both directions, and the new scaffold assertion is
vacuous.

### Findings

1. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/ValidationRulePack.kt:70-85 with analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:145-158 — `BOOKKEEPING`-only guards now make their returns `VALIDATION`, unlike legacy `ReturnBlock`.**
   The new rule tags a guard condition `VALIDATION` when the then-branch merely
   contains a bookkeeping write (`ValidationRulePack.kt:71,74`), and return
   categorization treats any source `VALIDATION` on the guard condition as a
   validation guard (`Composition.kt:154-158`). Legacy `ReturnBlock.isValidationGuard`
   only checks explicit comment, predicate, and purity; it deliberately ignores
   bookkeeping (`Blocks.kt:385-394`). A shape such as:

   ```kotlin
   class C {
       @Bookkeeping var skipped = 0
       fun f(check: () -> Boolean): Boolean {
           if (check()) {
               skipped++
               return false
           }
           return true
       }
   }
   ```

   is tagged `VALIDATION` by both validation implementations, but legacy tags
   `return false` `return-untagged` while the new pipeline tags it `VALIDATION`
   (and the guard scaffold consequently differs too). `returnsMatchLegacyCategorization`
   (`ValidationRulePackTest.kt:206-247`) misses this because every case it uses
   has a pure or predicate condition; the bookkeeping case it does include
   (`value < 0`) is independently pure. This is a widening, not the documented
   narrowing the ticket permits.
   Suggested fix: add this exact case and decide explicitly. If returning from a
   bookkeeping-backed validation guard is intended to be `VALIDATION`, record the
   approved divergence in the ticket/acceptance text and assert the new behavior;
   otherwise keep return categorization aligned with `ReturnBlock` for
   bookkeeping-only guards.

2. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/ValidationRulePack.kt:65-68,90-96 with analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:145-158 — returns on guards without a direct exit are no longer seen by return categorization.**
   The validation rule intentionally requires a direct exit in the then-branch
   before tagging the guard (`hasDirectExit`), which matches legacy
   `ValidationBlock`. Legacy `ReturnBlock`, however, classifies returns from the
   CFG's guard chain using prior comment/predicate/purity checks without requiring
   that direct exit. Because the new return category only sees guards the
   validation rule annotated, a return reached through nested control flow loses
   its `VALIDATION` category:

   ```kotlin
   fun f(values: List<String>): Int {
       if (values.isNotEmpty()) {
           for (value in values) {
               return value.length
           }
       }
       return 0
   }
   ```

   Legacy `ReturnBlock` sees the predicate guard and tags the nested return
   `return-validation`; the new pipeline leaves it `UNTAGGED` because the guard's
   then-body's direct statement is a loop, not an exit. The same applies to a
   rejection return on the else edge of a predicate guard whose then branch has no
   direct exit. Again `returnsMatchLegacyCategorization` only uses direct-exit
   guards, so the claimed parity is not exercised.
   Suggested fix: add nested-exit and else-edge cases. Either extend guard
   recognition to nested exits (which would then diverge from `ValidationBlock`)
   or record this as an approved narrowing of the ticket's `ReturnBlock` parity
   requirement and pin the new expectation.

3. **analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/ValidationRulePackTest.kt:276-287 — the ambient validation scaffold assertion is conditional and can silently pass when no scaffold exists.**
   The loop only asserts scaffold intents inside `if (scaffold != null)`. A
   regression that removed the guard scaffold would leave the test green.
   `validationGuardClassifiesPlumbingLocals` (`:290-309`) does not restore the
   check: the derivative use it asserts can be classified from the condition and
   return annotations alone, without any `SCAFFOLD` over the guard. The legacy
   `BlockTest.validationGuardClassifiesPlumbingLocals` did assert the
   `intent-scaffold` intents, so this coverage was lost in the port.
   Suggested fix: assert that the guard-span scaffold exists (and has the
   expected intents, including `{VALIDATION, BOOKKEEPING}` for a bookkeeping
   guard) instead of conditionally checking it, and do the same in the plumbing
   local test.

### Concurs / Dissents

- Concurs: guard recognition matches `ValidationBlock` — only an `if` with a
  then-body containing a direct `return`/`continue`/`break` is considered, and
  the then-body statement fallback matches legacy.
- Concurs: predicate/default detection is not narrowed. `DEFAULT_PREDICATES`
  contains the same names as `intents.yaml`, the prefixes remain
  `is`/`has`/`Is`/`Has`, and calls are resolved inside the condition span the
  same way as `syntax.callsIn(condition)`.
- Concurs: the `BOOKKEEPING` read is honest and effective: it is the only
  declared read, it is read from the settled stratum-0 store, and the byte
  containment in the then-body matches legacy (writes only, not reads).
- Concurs: constant-guard suppression and the explicit-comment bypass match
  legacy, including the `source = "comment"` tier and line-distance rule.
- Concurs: condition spans/bytes, evidence truncation, `source` tiers, and the
  confidence ladder (`1.0` explicit, `0.9` bookkeeping, `0.7` predicate, `0.5`
  pure) match the legacy `ValidationBlock`.
- Concurs: `reads = {BOOKKEEPING}`, the rule owns only `VALIDATION`, the
  scheduler test places `bookkeeping:bookkeeping` in stratum 0 and
  `validation:validation` in stratum 1, and the rule emits without mutating the
  store.
- Concurs: on the tested direct-exit predicate/pure guards, `return false` and
  bare `return` are `VALIDATION` and do not make the scaffold `UNTAGGED`.
- Dissents: findings 1-3. Findings 1 and 2 mean `returnsMatchLegacyCategorization`
  overstates what is covered; finding 3 means the ambient scaffold is not pinned.

### Residuals (not findings)

- The pack is not registered in the production `META-INF/services` file; pack
  selection and host wiring remain ticket 015, consistent with the other bundled
  packs.
- Validation predicates are duplicated from `intents.yaml` until the typed-config
  migration (013); there are no language overrides today, so this does not change
  behavior yet.
- The rule was verified statically only; no source edit, build, test, or
  formatter command was run by this role, per workflow.md.

## Round 2

Verdict: Round-1 findings 1 and 2 are addressed. Return categorization now
re-derives the legacy `ReturnBlock` guard criteria (explicit comment, predicate,
purity) instead of treating every `VALIDATION` source annotation as a validation
guard, so the bookkeeping-only broadening and the nested-exit narrowing both
match legacy, and the added return cases pin them. Finding 3 is also addressed:
the source annotation and guard scaffold are now asserted with
`assertNotNull`, and the plumbing-local/bookkeeping scaffold cases are
non-vacuous. One detection-fidelity gap remains in the scaffold contract for the
bookkeeping-only guard the fix restored.

### Findings

1. **analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:145-158,309-329 with analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/ValidationRulePackTest.kt:268-281,428-466 — a `BOOKKEEPING`-only validation guard still produces a guard scaffold containing `UNTAGGED` for `return false`.**
   Restoring `ReturnBlock` parity means a return under a guard tagged only by
   `BOOKKEEPING` is now `RETURN_CATEGORY` `UNTAGGED` (for example the
   `if (consume(value)) { skipped++; return false }` case added at
   `ValidationRulePackTest.kt:268-281`). `ifScaffolds` unions the condition
   intents (`VALIDATION`) with the body intents (`BOOKKEEPING` plus the
   `UNTAGGED` return), so the ambient scaffold over that guard becomes
   `{VALIDATION, BOOKKEEPING, UNTAGGED}`. That violates the ticket's explicit
   "`return false` and bare `return` do not make a scaffold `UNTAGGED`" bullet.
   The new bookkeeping scaffold test (`:428-466`) avoids the case by using a
   pure condition (`value < 0`), where the return is categorized `VALIDATION`;
   the bookkeeping-only `consume(value)` case is only checked for return parity,
   never for the scaffold.
   Suggested fix: use the existing bookkeeping-only `consume(value)` shape in a
   scaffold test and reconcile the two requirements. Either treat an
   `UNTAGGED` return under a validation-tagged guard as neutral when composing
   that guard's scaffold (so `return false` does not add `UNTAGGED`), or record
   an approved divergence from the ticket bullet and pin the legacy-compatible
   `{VALIDATION, BOOKKEEPING, UNTAGGED}` result.

### Concurs / Dissents

- F1: concurs — return categorization now uses the legacy explicit/predicate/pure
  guard criteria (`Composition.kt:145-190`), so a bookkeeping-only guard no
  longer widens `return false` to `VALIDATION`. The added `consume(value)` case
  pins the `UNTAGGED` result.
- F2: concurs — the nested-`while` return and the else-edge rejection case are
  now in the parity table, and the new guard criteria see the nested return the
  same way `ReturnBlock` did. Return categorization no longer depends solely on
  guards with direct exits.
- F3: concurs — `validationReturnAndBareReturnAreNotUntagged` now asserts the
  validation source span and a guard scaffold with `assertNotNull`, and
  `validationGuardClassifiesPlumbingLocals` asserts both the scaffold and the
  derivative intents. The added pack-gating test pins the no-validation-pack
  behavior.
- Concurs: the extracted `isValidationGuard`/`isValidationPredicate`/
  `hasValidationComment` logic matches legacy `ReturnBlock` over the new cases,
  including constant-guard suppression and the comment distance rule.
- Concurs: the validation rule remains pure with honest `reads =
  {BOOKKEEPING}`, owns only `VALIDATION`, keeps the same condition
  spans/evidence/confidence, and the scheduler still places it in stratum 1.
- Dissents: finding 1 above. F1/F2/F3 from Round 1 are not re-reported; this is a
  remaining scaffold consequence of the chosen ReturnBlock-parity fix.

### Residuals (not findings)

- Return categorization now duplicates validation guard detection in core using
  the legacy `IntentConfig` (`config.intents[VALIDATION]` and the hardcoded
  `cint:validation` comment). Today this matches the pack defaults, but the
  typed-config migration (012/013) must keep the rule's config and this core
  copy in sync or return categories will drift. This is also an architecture
  boundary question outside this review's dimension.
- The `validationLoaded` gate keys off `VALIDATION` in `terminal.sourceIntents`,
  so a no-op pack that owns `VALIDATION` would enable the legacy return
  heuristics; the existing pack-independence test uses a `NOOP` intent and does
  not cover that shape.
- The pack is still not registered in the production `META-INF/services` file;
  host wiring remains ticket 015.
- Static review only; no source edit, build, or test command was run by this
  role, per workflow.md.

## Round 3

Verdict: The Round-2 finding is addressed: the bookkeeping-only guard no longer
puts `UNTAGGED` into its scaffold, the string-content comment false positive is
gone, and return-category parity is preserved. The no-op validation pack gate is
also handled. One new validation-fidelity regression remains: to keep return
parity, the rule now emits `VALIDATION` source spans for guards that legacy
`ValidationBlock` never tagged (nested and else-branch returns). The
scaffold-neutralization fix is also broader than the ticket wording.

### Findings

1. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/ValidationRulePack.kt:79-83,106-112 with analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:158-170 — `returnGuard` widens validation source annotations beyond the ticket's direct-exit scope, regressing `ValidationBlock` parity.**
   `hasReturn` scans every descendant of the guard, so a guard whose then-branch
   has no direct exit is still tagged when a return appears in a nested
   loop/function or in the `else` branch. For the new return case:

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

   legacy `ValidationBlock` emits no `validation-guard` span because the then
   body's direct statement is `work()`, not an exit. The new rule emits a
   `VALIDATION` source span on `!ok` because `returnGuard` sees the else return
   and the condition is side-effect-free. The same happens for the nested-loop
   return (`ValidationRulePackTest.kt:257-267`) and for any return inside a
   lambda/local function. Return categories still match `ReturnBlock`, but
   `ValidationBlock` source/scaffold/derivative behavior now differs: for the
   `chooseNull` shape the new then-branch scaffold gains `VALIDATION`, whereas
   legacy leaves it `UNTAGGED`. The ticket scopes guard recognition to a
   then-branch with a direct return/continue/break, and the validation parity
   table does not include these shapes.
   Suggested fix: keep source emission restricted to direct exits and decouple
   return certification from a per-guard source span (for example a rule/pack
   presence signal or a separate return fact), or explicitly approve this
   widening in the ticket. Add the `chooseNull` and nested-return shapes to the
   validation parity test either way.

2. **analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:497-498 — the scaffold neutralization covers every rejection sentinel, not just the ticket's `return false`/bare return.**
   The added `if (expression.text.trim() in rejectionSentinels) return emptySet()`
   makes `return null`, `return 0`, `return -1`, `return ""`, `return []`,
   `return {}` and `return undefined` neutral in every scaffold, including
   returns outside a validation guard. The ticket only requires `return false`
   and bare `return` not to make a scaffold `UNTAGGED`; legacy `IntentScaffoldBlock`
   still contributes `UNTAGGED` for returns carrying an untagged return
   category. A non-validation guard whose condition is covered by another
   source intent (for example a logging-state guard condition) now loses the
   `UNTAGGED` contribution that legacy would add. No test covers a non-validation
   guard with a sentinel other than `false`.
   Suggested fix: scope the neutralization to the ticket's cases or to returns
   under a validation-tagged guard; if the wider behavior is intended, record
   and test it as an approved divergence.

### Concurs / Dissents

- Concurs: the Round-2 scaffold finding is fixed. `bookkeepingOnlyGuardDoesNotMakeItsScaffoldUntagged`
  asserts the return stays `UNTAGGED` while the guard scaffold is
  `{VALIDATION, BOOKKEEPING}` with no `UNTAGGED`; the `consume(value)` shape
  pins the bookkeeping-only condition.
- Concurs: the string-content false positive is fixed. `hasValidationComment`
  now filters by `config.commentKinds` (`ValidationRulePack.kt:139-143`) rather
  than raw text, and the Kotlin/JavaScript string cases are no longer tagged.
- Concurs: return parity is preserved for the bookkeeping-only, nested-return,
  and else-edge rejection cases; `returnsMatchLegacyCategorization` now includes
  all three.
- Concurs: `returnsUseEmittedValidationIntentsNotPackPresence` pins the no-op
  validation-pack case, so return categories now follow actual emitted
  validation intents rather than mere ownership of `VALIDATION`.
- Concurs: the validation rule remains pure with honest `reads =
  {BOOKKEEPING}`, one owned intent, unchanged span/evidence/confidence shapes,
  and stratum-1 scheduling.
- Dissents: findings 1 and 2. Finding 1 is a new source-detection widening, not
  a re-report of the Round-2 return-parity finding; finding 2 is an over-broad
  scope of the scaffold fix.

### Residuals (not findings)

- `returnGuard`/`hasReturn` scans the whole guard, including nested
  functions/lambdas; even after restricting source emission, the distinction
  between a real return site and a lambda-local return may deserve its own
  fact.
- Return categorization still duplicates validation detection in core using the
  legacy `IntentConfig` and hardcoded comment regex; the typed-config migration
  (012/013) must keep this in sync with the pack.
- The pack is still not registered in the production `META-INF/services` file;
  host wiring remains ticket 015.
- Static review only; no source edit, build, or test command was run by this
  role, per workflow.md.

## Round 4

Verdict: The Round-3 findings are addressed. `returnGuard`/`hasReturn` source
fabrication is gone, validation source emission is direct-exit only again, the
new parity cases cover else/nested/nested-function/lambda returns, return
categorization is decoupled from the validation pack and pinned against
`ReturnBlock`, and scaffold neutralization is scoped to bare return/`false` with
`null`/`0` retaining `UNTAGGED`. One new scaffold artifact remains: the neutral
`false` return leaves an empty-intent scaffold over the else range.

### Findings

1. **analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:350-368 with analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/ValidationRulePackTest.kt:615-671 — a neutral `false` return still emits a split else scaffold with an empty intent set.**
   In `falseCase`, `thenIntents` is `{LOGGING}` but `elseIntents` is empty
   because `exitIntents` now returns `emptySet()` for `return false`. The
   split branch emits both `tagRange` calls unconditionally, so the else range
   becomes a `SCAFFOLD` contribution with `intents = emptySet()`. The renderer
   resolves an empty-intent spec as its default tag rather than ignoring it, so
   the neutral branch can still be painted as a default scaffold even though the
   ticket asks for it not to contribute `UNTAGGED`. The new test only asserts
   that `UNTAGGED` is absent; it does not assert that the else range has no
   scaffold, and despite its name it does not exercise a bare return.
   Suggested fix: skip the split range when its intent set is empty (or skip the
   whole split when the neutral side has no intents), and extend
   `onlyFalseAndBareReturnsAreNeutralForScaffolds` to assert no empty-intent
   scaffold covers `return false` and to include an actual bare `return`.

### Concurs / Dissents

- Round-3 finding 1: concurs — `ValidationRule` is back to direct-exit-only
  guard tagging (`ValidationRulePack.kt:75-83`); the `chooseNull`, nested-loop,
  nested-function and lambda cases are in the validation parity table and match
  `ValidationBlock`. No return-only source spans are fabricated.
- Round-3 finding 2: concurs — `exitIntents` now neutralizes only the literal
  `false` expression (`Composition.kt:495`); `null` and `0` still contribute
  `UNTAGGED`, pinned by `onlyFalseAndBareReturnsAreNeutralForScaffolds`.
- Concurs: return categorization is decoupled from source annotations and pack
  presence (`Composition.kt:148-170`) and still matches `ReturnBlock` on
  bookkeeping-only, nested, and else-edge rejection cases;
  `returnCategorizationDoesNotDependOnLoadedValidationPacks` pins the no-pack and
  no-op-pack behavior.
- Concurs: string-content comment false positives remain fixed by the
  `commentKinds` filter, and the validation rule still has honest
  `reads = {BOOKKEEPING}`, one owned intent, unchanged spans/evidence/confidence,
  and stratum-1 placement.
- Dissents: finding 1 only; it is a scaffold-shape consequence of the scoped
  neutralization, not a re-report of the Round-3 findings.

### Residuals (not findings)

- Return categorization still re-implements validation guard detection in core
  from `IntentConfig` plus a hardcoded `cint:validation` regex; the typed-config
  migration (012/013) must keep this in sync with the pack's `ValidationConfig`.
- The pack is still not registered in the production `META-INF/services` file;
  host wiring remains ticket 015.
- Static review only; no source edit, build, or test command was run by this
  role, per workflow.md.

## Round 5

Verdict: The Round-4 scaffold finding is addressed: empty-intent split ranges
are skipped, and the test now covers bare return/`return false` and asserts no
empty-intent scaffold. A new return-categorization regression accompanies this
change, though: composition again requires a `VALIDATION` source annotation to
certify a guard (`certified`), while the validation rule is direct-exit only. As
a result, returns under pure/predicate guards whose then-branch has no direct
exit are `UNTAGGED` again instead of matching legacy `ReturnBlock`.

### Findings

1. **analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:158-166 — the reintroduced `certified` gate breaks `ReturnBlock` parity for guards without a direct exit.**
   `isValidationGuard` now returns false unless a `VALIDATION` annotation
   contains the condition or guard node. The validation rule emits only for
   direct exits, so the previously fixed shapes regress:

   ```kotlin
   fun nested(values: List<String>): Int {
       if (values.isNotEmpty()) {
           while (true) {
               return values.first().length
           }
       }
       return 0
   }
   ```

   and

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

   In both, legacy `ReturnBlock` classifies the nested/else return as
   `VALIDATION` from the pure/predicate guard; the new pipeline leaves it
   `UNTAGGED` because no validation source annotation exists on the outer
   guard. `returnsMatchLegacyCategorization` no longer contains these plain
   shapes: the nested case now has an inner direct-exit `if` to certify, and the
   else case was replaced by a nested inner guard
   (`ValidationRulePackTest.kt:310-335`). `returnsRequireEmittedValidationAnnotations`
   (`:420-459`) now pins the regression: without a loaded validation pack the
   return is `UNTAGGED`. That contradicts the Round-4 decoupled behavior and the
   ticket's `ReturnBlock` parity acceptance.
   Suggested fix: remove the `certified` gate / restore the Round-4 return
   criteria, or certify returns through a separate signal that does not require
   widening validation source spans. Restore the plain nested-loop and
   `chooseNull` parity cases and the pack-independence test.

### Concurs / Dissents

- Round-4 finding: concurs — `ifScaffolds` now emits only non-empty split
  ranges (`Composition.kt:354-377`); `onlyFalseAndBareReturnsAreNeutralForScaffolds`
  covers `null`, `0`, `false`, and bare return and asserts no empty-intent
  scaffold.
- Concurs: validation source emission remains direct-exit only and matches
  `ValidationBlock`; the string-content comment fix and the
  `chooseNull`/nested/nested-function/lambda validation parity cases remain.
- Concurs: the rule is still pure with honest `reads = {BOOKKEEPING}`, one owned
  intent, unchanged span/evidence/confidence, and stratum-1 scheduling.
- Dissents: finding 1. This is a Round-5 regression, not a re-report of an
  earlier addressed finding.

### Residuals (not findings)

- Return categorization still duplicates validation detection in core using
  `IntentConfig` and a hardcoded `cint:validation` regex; 012/013 must keep this
  in sync with the pack's `ValidationConfig`.
- The pack is still not registered in the production `META-INF/services` file;
  host wiring remains ticket 015.
- Static review only; no source edit, build, or test command was run by this
  role, per workflow.md.
