# Correctness review — ticket 046 (structural intents)

## Round 1

Verdict: Not ready. The three packs are registered, the outer-chain dedup and
return/co-occurrence shapes work, and `RETURN_VALUE`'s expression span and
literal gate match the ticket. However the `LOCAL_COPY` suppression path is
disconnected from the structural pack's configuration, the copy heuristic can
establish field-to-field assignments as parameter copies, and `CHAINING`'s
boolean exclusion is a raw substring check that both suppresses valid chains and
misses the intended syntax boundary. Docs and several negative paths required by
the ticket are also missing. Read-only review of branch `ticket/046`, commit
`e999413`; no build, tests, formatter, or source edit was run.

### Findings

1. **Mutation suppression ignores the configured `LOCAL_COPY` policy.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:176`
   calls
   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/LocalCopyRulePack.kt:28-29`,
   which builds `LocalCopyMatch(LocalCopyLanguageConfig())` — always the default
   prefixes, never the effective `LocalCopyConfig` for the language.

   With `local-copy` configured with `prefixes: ["store_"]`,
   `LocalCopyRulePackTest.configuredPrefixesExtendNormalization` shows
   `store_db = db` establishes `LOCAL_COPY`, but the mutation pack still emits
   `LOCAL_VALUE_MODIFY` for the same write because `store_db` is not normalized
   by the hardcoded defaults. That breaks the ticket's "prefer suppressing
   generic local mutation for pure parameter copies" policy and makes the two
   packs disagree about the same write. The new suppression test only uses
   default `_` prefixes.

   Fix: share the normalized-copy policy (prefixes plus the copy test) between
   the structural and mutation packs, or have the mutation rule read the
   `LOCAL_COPY` annotation for the write (with a fallback heuristic) instead of
   reconstructing a default policy. Add a test with configured prefixes plus
   `MutationRulePack` asserting no `LOCAL_VALUE_MODIFY`.

2. **`LOCAL_COPY` never checks that the source is a parameter, so field-to-field
   copies establish.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/LocalCopyRulePack.kt:102-113,144-148`.

   Establishment is only "normalized LHS name equals normalized RHS identifier
   and the raw texts differ". In a method that has no parameter `_db`,
   `this._db = _db` normalizes both sides to `db`, satisfies `left != right`, and
   emits established `LOCAL_COPY` at 0.8. Finding 1's suppression then hides the
   `LOCAL_VALUE_MODIFY` for a field-to-field (effectively self) assignment. The
   same applies to `this.x = x` when `x` is a property rather than a parameter.
   The docs promise "with parameter source", and the ticket's out-of-scope note
   excludes whole-program aliasing, not a local parameter check.

   Fix: prove the RHS identifier against the enclosing function's parameter list
   before establishing; otherwise emit a candidate or nothing. At minimum do not
   establish an owner-qualified LHS whose unqualified RHS is only name-equal
   without parameter evidence. Add a negative test such as `this._db = _db`
   inside a parameterless method, plus a positive `this._db = db` with `db` a
   parameter.

3. **`CHAINING`'s boolean exclusion is a substring test over the receiver.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/ChainingRulePack.kt:90`.

   `"||" in receiver.text` suppresses any chain whose receiver text contains
   `||` anywhere, including a string literal or a nested argument. Thus
   `Factory.For("a||b").Use().Build()` and
   `Factory.For(a || b).Use().Build()` emit no `CHAINING` even though the fluent
   chain itself is not a boolean chain. Conversely the added
   `booleanOrChainsAreNotChaining` test uses `a.isEmpty() || b.isEmpty()`, which
   contains no chain at all, so it passes without exercising this branch.

   Fix: test for a top-level `||` operator of the receiver expression (unwrapping
   parentheses and ignoring string/comment leaves) rather than a text substring.
   Add one suppressed case, e.g. `(a || b).Use().Build()`, and one established
   case where `||` only appears in an argument.

4. **`isDirectLink` mixes byte offsets with character indices.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/ChainingRulePack.kt:106-114`.

   `offset` is a byte delta (`next.node.endByte - receiver.startByte`) but is
   used as a `String` index into `receiver.text`. Any non-ASCII text before the
   link makes the slice wrong, so direct chains can be downgraded to candidates
   or vice versa. Fix by comparing byte-relative spans consistently, or by
   deriving the trailing slice from `receiver.endByte - next.node.endByte` and
   comparing against the text tail. Add a non-ASCII receiver test.

5. **Docs in ticket scope are still incomplete.**

   `docs/intent-model.md:164-166` has the definitions, but the bundled-ownership
   section at `:182-192` has no entries for `return-value`, `chaining`, or
   `local-copy`; the overlap/precedence section at `:200-217` does not cover
   `CHAINING` vs `SERVICE_INIT`/`DATA_TRANSFORMATION`, `LOCAL_COPY` vs
   `LOCAL_VALUE_MODIFY`, or `RETURN_VALUE` additivity; the client default tag list
   at `:498-499` and the implementation snapshot at `:546-548` omit the three new
   tags/packs. The diff changes `nvim-renderer/lua/cint/core/config.lua` but no
   docs file. The ticket explicitly requires intent definitions,
   overlap/precedence, and scratchpad examples.

   Fix: add the three ownership bullets and overlaps, update the default-tag and
   pack lists, and record the `Factory.For().Use().Build()`, `return
   _adminUserId.Value`, and `_db = db` examples, or defer the docs task to a named
   ticket with a reason.

6. **Tests do not pin the risky paths.**

   - `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/structural/ChainingRulePackTest.kt:67-80`
     does not test a boolean expression that actually contains a chain.
   - `LocalCopyRulePackTest.kt` has no configured-prefix + mutation-suppression
     case and no field-to-field false positive.
   - `ReturnValueRulePackTest.kt:44-54` covers a bare `return` and a string
     literal only; it does not cover a bare labeled return (`return@forEach`),
     where `ReturnValueRulePack.kt:52-58` would treat the last non-comment child
     (the label) as an expression, nor numeric/`null`/boolean literals.
   - No test asserts that `LOCAL_COPY` and `LOCAL_VALUE_MODIFY` both stay silent
     when the mutation pack is configured with non-default copy prefixes.

   Fix: add the negative cases from findings 1–4 and the labeled-return/literal
   variants; assert source/candidate emptiness, not just text lists.

### Concurs / Dissents

- Concurs: `RETURN_VALUE` emits on the returned expression, excludes bare
  returns and literal-only expressions by default, honours `includeLiterals`,
  and co-exists with `SEARCH`/`VALIDATION` at the same span
  (`ReturnValueRulePackTest` and the updated pipeline snapshot).
- Concurs: `CHAINING` emits the outer call once, treats the inner calls as
  members rather than separate emits, requires two calls, keeps ambiguous
  receivers as candidates, and allows overlap with `SERVICE_INIT` and domain
  intents.
- Concurs: registration is complete — the three providers are in
  `META-INF/services/dev.cint.api.RulePack`, the bundled loading/migration/
  registration/host lists include the ids and intents, `TestPacks` includes the
  packs, the pipeline snapshot was regenerated, and the nvim default tag map has
  all three tags.
- Concurs: `LOCAL_COPY` correctly avoids general mutation, persistence,
  increments, and self-assignment `x = x`, and keeps ambiguous owner-qualified
  field assignments as candidates.
- Dissents: findings 1–3 are correctness/behavior gaps (one affects mutation
  output even when the structural pack is configured, one creates false-positive
  copies, one suppresses legitimate chains); finding 4 is a smaller
  Unicode/span bug; finding 5 is an unaddressed ticket-scope requirement; finding
  6 is the missing regression fence for the above.

## Round 2

Verdict: Not ready, but substantially improved. Mutation co-occurrence is now
deliberate and tested, the field/self/local copy cases and the non-ASCII span
path are fixed, and the boolean-OR and argument-call chaining tests are real.
However the no-exact-link branch over-suppresses valid multi-link chains,
labeled returns with values are now dropped, the copy rule still establishes
without proving the RHS is a parameter, and the required docs update is still
missing. Read-only review of `93e86e8`; no build, tests, formatter, or source
edit was run.

### Findings

1. **`CHAINING` drops valid chains whose base contains a call.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/ChainingRulePack.kt:100-102`.

   When the receiver contains calls but none shares the receiver's exact span,
   `chainFor` returns `null` for the whole chain. That is correct for the added
   single-link cases (`a[b()].c()`, `(a + b()).c()`), but a real two-link chain
   such as `a[b()].c().d()` also returns `null`: the walk reaches `d -> c`, then
   `c`'s receiver `a[b()]` contains `b()` with no exact match, so the already
   collected `{d, c}` chain is discarded. The index/argument test only has
   single-link shapes, so it cannot catch this.

   Fix: when `exact` is empty, `break` out of the receiver walk instead of
   returning `null`; the current members remain a valid chain, and single-link
   roots still fail the `minimumLinks` check. Add a multi-link index/base test
   such as `a[b()].c().d()` and `(a + b()).c().d()`.

2. **`RETURN_VALUE` now discards labeled returns that carry a value.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/ReturnValueRulePack.kt:50-51`.

   `statement.startsWith("return@")` rejects every labeled return, not just a
   bare one. Kotlin permits a value with a labeled return, e.g.
   `run { return@run 42 }` or `items.map { return@map it.trim() }`; those are
   returns with an expression and should emit on the expression per the ticket's
   "return statements with an expression". The new test only covers the bare
   `return@forEach` shape.

   Fix: skip only when the statement has no expression after the label; if a
   `ReturnFact.expression` is present beyond the label, emit it. Add bare and
   value-bearing labeled-return tests.

3. **`LOCAL_COPY` still does not prove the source is a parameter.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/LocalCopyRulePack.kt:144-147`.

   The new `!isFieldLike(right)` guard fixes `this._db = _db` (the RHS is
   field-like by its underscore), but a field without a prefix is still treated
   as a parameter. In a class with a property `db`, `this._db = db` establishes
   `LOCAL_COPY` at 0.8 even though the RHS is the field, not a parameter. The
   docs say "with parameter source" and the ticket's out-of-scope note excludes
   whole-program aliasing, not a function-local parameter check.

   Fix: resolve the RHS identifier against the enclosing function's parameter
   list before establishing; otherwise emit a candidate. Add a negative test with
   a class property named `db` and no parameter.

4. **Docs in ticket scope are still missing.**

   `docs/intent-model.md:182-192` still has no structural ownership bullets,
   `:200-217` has no `CHAINING`/`LOCAL_COPY` overlap entries, and the client
   default list at `:498-499` plus the implementation snapshot at `:546-548`
   still omit the new tags/packs. `git diff e999413..93e86e8 -- docs` is empty
   and no named deferral was recorded.

   Fix: apply the round-1 docs update, including the now-deliberate
   `LOCAL_COPY`/`LOCAL_VALUE_MODIFY` co-occurrence policy, or defer it to a named
   ticket with a reason.

### Concurs / Dissents

- Concurs: mutation co-occurrence is now explicit. The `ignorePureCopies` config
  and the hardcoded helper call were removed; `_db = db` emits both
  `LOCAL_COPY` and `LOCAL_VALUE_MODIFY` for a local write, and
  `structuralPacksKeepSearchReturnAndMutationBoundaries` asserts both. This
  resolves the round-1 configuration mismatch, though it chooses co-existence
  over the ticket's "prefer suppressing" wording, so docs should say so.
- Concurs: `LOCAL_COPY` field/self handling is much tighter. `this._db = _db`
  stays a candidate rather than established, `_x = _x` is dropped, `_db = db`
  and `this.x = x` establish, and declaration/assignment copies are now
  considered. The remaining parameter-proof gap is finding 3.
- Concurs: the boolean-OR fixes work for the tested shapes. Boolean operands and
  parenthesized boolean receivers are suppressed, while `||` inside string and
  call arguments no longer suppresses `Factory.For("a||b").Use().Build()` or
  `Factory.For(flag || other).Use().Build()`. The ancestor walk in
  `isBooleanOrOperand` can still suppress a valid chain nested inside an
  argument/lambda of a larger OR expression, but that is a lower-risk edge than
  finding 1.
- Concurs: `isDirectLink` is now span-based, so the byte/char mix is gone;
  `nonAsciiReceiversKeepDirectLinks` exercises the previous failure with `café`.
- Concurs: the index/argument-call tests are meaningful for the old
  largest-contained-call selection; they only need the multi-link extension in
  finding 1.
- Dissents: findings 1 and 2 are new false negatives introduced by the fixes,
  finding 3 is a residual false positive from round 1, and finding 4 is still an
  unaddressed acceptance item. The loop cannot close until findings 1–4 are
  fixed or explicitly deferred.

## Round 3

Verdict: Not ready, but the round-2 code findings are addressed. The chaining
base/candidate/OR behavior, labeled returns, LOCAL_COPY parameter/property/case
handling, and the read-based mutation suppression are implemented coherently and
covered by meaningful tests. Two scope/coverage items remain: class-member
declaration copies are still outside `LOCAL_COPY`, and the required docs update
is still absent with no named deferral. Read-only review of `2953c98`; no build,
tests, formatter, or source edit was run.

### Findings

1. **Class-member declaration copies are still missed.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/LocalCopyRulePack.kt:65-67,124-132`.

   The new declaration path requires
   `view.enclosingFunction(property.node) != null`, so it covers local
   declarations such as `val _db = db` inside a function, but not a class-member
   field initialized from a constructor parameter:
   `class C(db: Database) { private val _db = db }`. That is a parameter-to-field
   copy in the ticket's `LOCAL_COPY` meaning, and the class property initializer
   is never evaluated. `declarationAndLocalCopiesAreHandledSafely` only exercises
   function-local declarations.

   Fix: allow class property initializers whose RHS resolves to the enclosing
   class/constructor parameter set, or document that only function-local
   declarations are in scope and defer the rest to a named ticket. Add a
   class-member positive test.

2. **Docs in ticket scope are still missing.**

   `docs/intent-model.md:182-192` still has no structural ownership bullets,
   `:200-217` has no `CHAINING`/`LOCAL_COPY` overlap entries, and the client
   default list at `:498-499` plus the implementation snapshot at `:546-548`
   still omit the new tags/packs. `git diff 93e86e8..2953c98 -- docs` is empty
   and no named deferral was recorded.

   Fix: apply the docs update, including the deliberate mutation-suppression
   policy (`ignorePureCopies` default true, configurable off), or defer it to a
   named ticket with a reason.

3. **The labeled-return test bypasses the real extraction path.**

   `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/structural/ReturnValueLabeledReturnTest.kt:101-123`
   constructs `ReturnFact` instances by hand, including the value expression, so
   it validates the rule but not `ControlFlow`/`exitExpression` for
   `return@map it + 1`. The bare-label regex and value path are otherwise sound.

   Fix: add an `analyzePack` case for a value-bearing labeled return and a bare
   labeled return, so the CFG extraction and the rule are both pinned.

### Concurs / Dissents

- Concurs: the CHAINING base fix works. `a[b()].c().d()` and
  `(a + b()).c().d()` now establish, while `a[b()].c()` and `(a + b()).c()` stay
  below the link minimum; the array-literal minimum-links test prevents a call
  inside a literal from fabricating a link. Conditional receivers still stay
  candidates.
- Concurs: the OR fix is correctly scoped. Chains directly under `||` are
  suppressed, while `||` in strings/arguments no longer suppresses chains, and
  the new argument/lambda tests cover the boundary walk.
- Concurs: `return@label` is fixed in the rule. The bare regex matches only
  `return@label`, and value-bearing labeled returns emit the value expression;
  the remaining gap is the test extracting the real CFG expression (finding 3).
- Concurs: LOCAL_COPY now checks declared properties and enclosing parameter
  names, removes case-folding (so `_db = DB` stays non-established), keeps
  `this._db = _db` from establishing, and still establishes `_db = db` /
  `this.x = x` when the RHS is a parameter. The class-member declaration gap is
  finding 1.
- Concurs: mutation suppression is now policy-driven and tested. `reads`
  includes `LOCAL_COPY`, default `ignorePureCopies = true` suppresses established
  copies, configured prefixes are honoured through the annotation rather than a
  duplicate heuristic, and setting `ignorePureCopies = false` restores
  co-occurrence. The added dependency means a mutation-only pipeline now needs
  the local-copy pack, which is consistent with the existing cross-pack reads
  but is worth keeping in mind for pack selection.
- Dissents: findings 1 and 2 are unaddressed acceptance/scope items, and finding
  3 is a coverage gap for the labeled-return path. The loop cannot close until
  findings 1 and 2 are fixed or explicitly deferred and finding 3 is added.

## Round 4

Verdict: Concur. The three round-3 findings are addressed, and no remaining
correctness finding was found. Class/constructor declaration copies now
establish through the class-scope gate, the labeled-return test exercises the
real CFG/`exitExpression` path rather than a hand-built view, and the scribe docs
in the working tree document the structural packs, ownership, overlaps, default
tags, scheduler dependency, and mutation-suppression policy. Read-only review of
`93dda96` plus the uncommitted scribe docs; no build, tests, formatter, or source
edit was run.

### Concurs / Dissents

- Concurs: class/constructor LOCAL_COPY is covered.
  `LocalCopyRulePack.kt:72-74` admits property declarations whose enclosing scope
  is `CLASS`, so `class C(db: Database) { private val _db = db }` establishes on
  the property declaration. The declared-property exclusion still keeps
  `this._db = _db` and field-to-field shapes out of the established set, case
  variants remain non-established, and `constructorScopedDeclarationCopiesEstablish`
  pins the new path. `isReferenceIdentifier` also keeps literals such as `null`
  from becoming copy candidates.
- Concurs: the labeled-return coverage is now real. `ControlFlowBuilder` adds
  previously unseen `return@...` sites using `syntax.exitExpression`, orders them
  by source position, and `ReturnValueLabeledReturnTest` now runs the pack through
  `analyzePack`, asserting `it + 1` emits while bare `return@forEach` does not.
  This exercises the same CFG path used in production rather than the former
  synthetic `ReturnFact`.
- Concurs: docs are addressed in the working tree. `docs/intent-model.md` gains
  the structural definitions, ownership bullets, overlap/precedence bullets,
  scratchpad examples, client default tags, and implementation-snapshot entries;
  `docs/engine.md` documents detection behavior and config keys;
  `docs/architecture.md` updates the bundled-pack description and strata; and
  `docs/tasks/046-structural-intents.md` plus the task index record the work as
  in-progress pending this review.
- Concurs: the docs accurately describe the implementation choices that mattered
  in review: exact-link chaining and candidate conditional bases, additive
  `RETURN_VALUE` spans, case-sensitive LOCAL_COPY normalization with heuristic
  parameter proof, and mutation's `LOCAL_COPY` read with `ignorePureCopies`
  default-on plus candidate non-suppression.
- No dissents. The loop can close once the scribe docs are committed and the
  ticket status is updated to done; no further code or docs work is required for
  this review dimension.
