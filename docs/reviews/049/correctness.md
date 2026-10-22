# Correctness review — ticket 049 (user readonly directive)

## Round 1

Verdict: Not ready. The production wiring is coherent: `READONLY` stays a
generic user intent through resolution, merge and removal; `CapabilityView`
treats calls to `READONLY` functions as side-effect-free; derivative
read-only confidence includes them; `isAnnotatedPure` and the function-level
derivative path remain PURE-only. The gaps are in what the tests actually pin
and in one contract detail. The new derivative test passes without any
`READONLY` handling, no test exercises the validation-composition effect, the
new view predicate also changes PURE behavior despite the ticket saying PURE is
unchanged, and the detailed derivative-use documentation still lists only
user-pure calls. Read-only review of the working tree; no build, test, formatter,
or source edit was run.

### Findings

1. **`analysis-engine/languages/treesitter/src/test/kotlin/dev/cint/treesitter/language/UserAnnotationPureTest.kt:146-149` — the READONLY derivative test does not distinguish READONLY from an ignored intent.**

   `val message` derives from an identifier initializer (`source`) at `0.7`,
   and the `LOGGING` annotation on `log.warn(message)` then classifies the
   `source` read, so `val source` is emitted on the next fixpoint iteration.
   `readOnlyConfidence(make())` returns the unknown read-only value (`0.55`)
   even with no directive because `hasKnownWrite` is false, so all three
   assertions (presence of `val source`/`val message`, absence of `fun make`,
   and the `LOGGING` intents) still pass if `ctx.isUserReadOnlyCall` is deleted.
   READONLY only changes the confidence from `0.55` to `0.7`, and the test never
   inspects confidence, so the ticket's "promotes derivative use" criterion is
   not pinned.

   Fix: assert the confidence on the derivative that carries `val source` is
   `0.7`, and run the same fixture without the annotation as a control expecting
   `0.55` (or otherwise assert the annotated run is `0.7` where the control is
   `0.55`). Keep the `fun make` absence assertion to pin the non-pure
   distinction.

2. **`analysis-engine/tests/integration-tests/src/test/kotlin/dev/cint/integration/view/CapabilityViewTest.kt:208-214` with `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:338-352` — validation composition is not pinned, and one assertion is vacuous.**

   No test drives `ValidationRulePack` with a `READONLY` call. At the view level
   the only meaningful assertion is `isSideEffectFree(call.node)`;
   `assertTrue(view.isSideEffectFree(readOnly))` at line 213 passes without any
   READONLY support because the function declaration has no effect-kind
   descendants, so `syntax.isSideEffectFree` already returns true. Downstream,
   `emitFor` already treats any non-writing condition as the `readOnly`
   fallback at `0.5`, so READONLY's actual effect on validation is only the
   `pure` branch confidence (`0.6`). A test that merely asserts a VALIDATION
   annotation exists would therefore also pass without READONLY.

   Fix: add a validation rule-pack test with a rejecting guard whose condition
   is a direct call to a READONLY-annotated function, and assert the VALIDATION
   source confidence is `0.6` (with a control call without the directive
   expecting `0.5`). Strengthen or remove the vacuous declaration assertion;
   assert on an effectful expression if the declaration-level behavior matters.

3. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/facts/view/CapabilityView.kt:93-98` — the new predicate also changes PURE view behavior, contrary to the ticket and without a test.**

   The new `isSideEffectFree` arms include `context.isUserPure(node)` and
   `context.isUserPureCall(node)`. That makes a user-PURE call that syntax
   considers effectful newly side-effect-free, which feeds
   `ValidationRulePack`'s `pure` branch and raises such guards from `0.5` to
   `0.6`. The PURE derivative test never calls `isSideEffectFree`, and the new
   view test only exercises the READONLY call, so neither PURE arm is pinned.
   The scope says PURE semantics are unchanged.

   Fix: decide and pin the intent. Either drop the PURE arms so only READONLY
   changes view behavior, or treat the extension as deliberate (semantically it
   is reasonable, since PURE implies no side effects), assert that a user-PURE
   call is side-effect-free, and note the change in the ticket/docs so "PURE
   behavior is unchanged" is not misleading.

4. **`docs/intent-model.md:300-302` (with the new section at `docs/intent-model.md:31-40`) — the detailed derivative-use policy omits READONLY and still calls both directives "purity semantics".**

   The 0.7 list still says "annotated pure calls, and user-pure calls", which
   contradicts the new section and `DerivativeComposition.kt:249` now that
   `user-readonly` calls also derive at `0.7`. Separately, line 32 describes
   both names as "purity semantics" even though `READONLY` is explicitly not
   pure.

   Fix: add user-readonly calls to the 0.7 list and reword "purity semantics" to
   "directive semantics" (or similar), keeping the PURE/READONLY distinction
   explicit.

### Concurs / Dissents

- Concurs: `READONLY` is a generic user intent through `UserAnnotationResolver`,
  `UserAnnotationsJson`, the host merge and removal path; it stays in the
  published intent set and provenance. The updated `HostUserAnnotationsTest`
  pins presence, merge, published intents and removal, though only as part of a
  multi-intent record.
- Concurs: `UserAnnotationResolver.isUserReadOnly` and the `RuleContext`
  default methods preserve the contribution-level check
  (`rule == USER_CONTRIBUTOR_ID` and the intent present), so a pack-owned
  `READONLY` intent cannot enable user-directive semantics.
- Concurs: `CapabilityView.isAnnotatedPure` and the function-level derivative
  gate use `isUserPure` only; a READONLY function is not marked annotated pure
  and does not get the pure-function derivative. The new view test pins the
  `isAnnotatedPure` distinction and the call-level `isSideEffectFree`.
- Concurs: moving the old private `isUserPureCall` into
  `RuleContext.isUserCall` preserves its receiver/callee/lookup behavior, and
  `readOnlyConfidence` now recognizes both directives at the call root.
- Concurs: the renderer completion includes `PURE` and `READONLY`, the new nvim
  block persists and reloads a READONLY record, and the shipped default-policy
  path is untouched.
- Dissents: findings 1-4.

### Residual observations (not filed)

- `CapabilityView.isSideEffectFree` checks the directive on the annotated
  declaration as well as on calls. That makes an annotated function/property
  node itself side-effect-free, which is not needed for validation composition
  and is only meaningful if a rule passes a declaration node. Harmless today but
  worth deciding if the view contract should be call-focused.
- READONLY on a visual range still only affects provenance; like PURE on a
  range, it will not enable call-level purity because `isUserReadOnlyCall`
  requires an exact function/declaration span.
- READONLY is only recognized when the call is the expression root. A READONLY
  call nested inside a larger initializer or condition (`readOnlyFn() + 1`,
  `readOnlyFn() && flag`) still leaves the containing expression effectful for
  `isSideEffectFree` and at the unknown `0.55` derivative confidence. This
  mirrors the existing root-only `isAnnotatedPureCall` behavior and the
  validation read-only fallback still qualifies, so it is not filed; it does
  limit how far the directive reaches.
- Static review only; no source edit, build, test, lint, or formatter command
  was run by this role, per `docs/workflow.md`.

## Round 2

Verdict: Ready; no findings. All four Round 1 findings are addressed, and the
new assertions pin the behavior instead of passing vacuously. The derivative
test now distinguishes READONLY from an ignored intent by confidence, the real
ValidationRulePack is exercised for both directives with a no-directive
control, the PURE side-effect-free extension is deliberate and recorded in the
ticket, and the intent-model docs match the implementation. Persistence, merge,
removal, provenance and renderer paths are unchanged and still correct.

### Findings

None.

### Concurs / Dissents

- Concurs: Round 1 finding 1 is fixed.
  `UserAnnotationPureTest.userReadOnlyAnnotationPromotesDerivativeUse` now
  asserts `0.7` on the `val source` derivative and runs the same fixture with no
  directive as a `0.55` control. READONLY is the only input that changes that
  confidence, so deleting `ctx.isUserReadOnlyCall` fails the test; the `fun
  make` absence assertion still pins the non-pure distinction.
- Concurs: Round 1 finding 2 is fixed.
  `ValidationReadOnlyTest.userDirectivesUseSideEffectFreeValidationConfidence`
  drives the actual validation pack with READONLY and PURE annotations on direct
  call guards and asserts `0.6`/`0.6`/`0.5`, so it fails if either call-level
  view arm or the validation wiring regresses. The vacuous declaration
  assertion was replaced with call assertions in `CapabilityViewTest`.
- Concurs: Round 1 finding 3 is resolved by clarification rather than removal.
  The ticket now states that user PURE and READONLY calls both use the
  side-effect-free validation confidence, while only PURE participates in the
  pure-function derivative path. The PURE call arm is pinned by the new
  validation test and the view test; the view test also pins READONLY.
- Concurs: Round 1 finding 4 is fixed. `docs/intent-model.md` now lists
  user-readonly calls in the `0.7` derivative policy, uses "directive
  semantics", and no longer claims annotations carry `evidence`.
- Concurs: the test harness change is additive and safe; the existing
  `analyzePack`/`validationAnalysis` callers keep the empty-annotation default,
  and the new overload resolves records through the same user-annotation path as
  production.
- Dissents: none.

### Residual observations (unchanged, not filed)

- The declaration-level `isUserPure`/`isUserReadOnly` arms in
  `CapabilityView.isSideEffectFree` remain redundant for current consumers and
  are not exercised by the new tests; the meaningful call-level arms are.
- READONLY is still only recognized when the call is the expression root, and
  READONLY on a visual range still only affects provenance. Both mirror existing
  PURE behavior and no current path depends on the broader form.
- Static review only; no source edit, build, test, lint, or formatter command
  was run by this role, per `docs/workflow.md`.

Summary for the implementor: the Round 1 gaps are closed. The derivative test
now pins the `0.7` READONLY promotion against a `0.55` unannotated control, the
validation test pins READONLY/PURE at `0.6` against a `0.5` plain control, the
PURE side-effect-free view extension is explicitly documented in both the ticket
and `intent-model.md`, and the derivative docs now include user-readonly calls.
No new correctness issue was found, so this dimension can close.
