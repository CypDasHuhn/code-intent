# Correctness review — ticket 005 (thin rule DSL)

## Round 1

Verdict: The DSL's execution semantics are correct for the representative rule.
Same-typed `eq`/`ne` (including null), ordered `gt`/`lt`, `Condition` composition,
`where`, and `Predicate` short-circuiting all behave as intended, and the rule uses
the named forms rather than `==`/`&&`/`||`. Two correctness gaps remain: `eq`/`ne`
allow unrelated operand types to unify to a common supertype and evaluate false
instead of failing to compile; and the false case of `ne` is not pinned by any test,
so an always-true `NotEquals` would pass the suite. I do not re-report the
api-fidelity findings about extra `where`/`predicate` surface or the evaluated-value
`Condition` leaves.

### Findings

1. **analysis-engine/api/src/main/kotlin/dev/cint/api/Dsl.kt:49-51 — `eq`/`ne` can accept unrelated operand types by widening `T` to a common supertype.**
   `infix fun <T> T.eq(other: T)` (and `ne`) constrains only that both operands share
   some inferred `T`; Kotlin can choose a common supertype (`Any?` or an intersection).
   A mistyped comparison such as `call.callee eq 1` (`String?` against `Int`) therefore
   compiles, produces a condition that can never be true, and silently prevents the
   rule from matching. `gt`/`lt` avoid this with `T : Comparable<T>`, but the equality
   forms have no equivalent guard, which is weaker than the ticket's type-safety
   rationale for the named forms. The DSL tests only use compatible operands, so they
   do not expose this.
   Suggested fix: decide and enforce the contract. If strict same-type comparison is
   required, use a shape that does not permit least-upper-bound widening (typed
   overloads for the supported leaf types, or a projection wrapper) and add a
   compile-time negative test. If the widening is accepted as Kotlin-generic noise,
   document it explicitly and add a test that pins the intended behavior; do not leave
   the type-safety claim stronger than the signature.

2. **analysis-engine/api/src/test/kotlin/dev/cint/api/DslTest.kt:112-120 — the false case of `ne` is untested.**
   `infixComparisonsEvaluate` asserts only `"a" ne "b"` and `missing ne "a"` (both
   true). A `NotEquals.test()` implemented as `true`, or as an implementation that
   ignores its operands, would leave all seven DSL tests green; the representative
   rule also uses `ne` only where it is true (`call.receiver?.text ne null` with
   non-null receivers). `gt`/`lt` boundaries are indirectly covered by
   `whereAppliesConditionLambdaToFacts`, which includes the boundary values, but `ne`
   has no equivalent.
   Suggested fix: add `assertFalse(("a" ne "a").test())` and
   `assertFalse((missing ne null).test())` (and, optionally, a representative-rule
   call whose receiver is present to exercise the false branch of `ne null`).

### Concurs / Dissents

- Concurs: same-typed equality is correct, including nullable equality (`missing eq null`
  is true, `missing ne "a"` is true); `gt`/`lt` use the natural `Comparable` ordering in
  the intended direction.
- Concurs: `Condition.And`/`Or` are data-shaped, compose left/right correctly, and
  short-circuit at evaluation; the nested data-shape test passes.
- Concurs: `Predicate.and`/`or` evaluate left-to-right on the same `View` and
  short-circuit; the representative gate composes import and call predicates correctly.
- Concurs: `where` evaluates the condition per element and filters correctly; its eager
  `List` result matches the test's expectations.
- Concurs: the representative rule reads only `View` facts and uses the named DSL forms
  for its comparisons and logic, with no `==`/`&&`/`||` fallback in rule logic.
- Dissents: `eq`/`ne` do not enforce the operand type safety claimed for the named
  forms (finding 1).
- Dissents: the false semantics of `ne` are not pinned (finding 2).

### Residuals

- `Predicate`/`predicate` are opaque `(View) -> Boolean` closures, and `and`/`or` build
  further closures, so View-level predicates are not data-shaped. This is permitted by
  architecture §7's explicit lambda allowance; I read the ticket's reification
  acceptance as limited to `Condition` leaves. If View-level predicates must later be
  reified, this layer will need a sealed predicate AST.
- `and`/`or` are ordinary infix functions, so mixed chains left-associate
  (`a or b and c` groups as `(a or b) and c`); the example correctly parenthesizes every
  mix. This is Kotlin precedence rather than a DSL defect, but it should be documented.
- `gt`/`lt` on `Double` use `Comparable.compareTo`, so NaN ordering differs from
  primitive `>`/`<`; only relevant if rules compare floating-point facts.

## Round 2

Verdict: Both round-1 findings are addressed. `DslValue<T>` fixes the receiver type
before comparison, so the accidental common-supertype widening of `eq`/`ne` is gone,
and the new negative assertions plus the null-receiver representative-rule test pin
the false branch of `ne`. The removal of `where`/`predicate` and the internal
condition leaves are the api-fidelity follow-ups and do not change the DSL's
correctness. No new correctness finding.

### Findings

- None.

### Concurs / Dissents

- F1: concurs — `DslValue<T>` (`analysis-engine/api/src/main/kotlin/dev/cint/api/Dsl.kt:7-10`)
  wraps the operand before `eq`/`ne`/`gt`/`lt` (`:54-60`). The receiver now fixes `T`,
  so `DslValue(call.callee) eq 1` no longer infers `Any?`; unrelated operands require
  an explicit widened `DslValue<Any?>`, which is an intentional opt-out rather than an
  accidental inference. The old unrelated-operand hazard is addressed.
- F2: concurs — `infixComparisonsEvaluate` now asserts
  `DslValue("a") ne "a" == false` and `DslValue(missing) ne null == false`
  (`analysis-engine/api/src/test/kotlin/dev/cint/api/DslTest.kt:114-127`), and
  `composedConditionsEvaluateByBehavior` (`:161-168`) exercises a rejected
  `ne`-false composition. The representative rule also includes a null-receiver
  `warn` call (`:178`) and a dedicated rejection test (`:193-198`), so an always-true
  `NotEquals` would now fail.
- Concurs: `conditionsWorkInsideFilterLambdas` (`DslTest.kt:141-147`) still covers the
  `gt`/`lt` boundary values after `where` was removed; `filter` + `.test()` is
  equivalent for the facts used here.
- Concurs: `DslValue` construction and the `internal` condition implementations
  preserve the DSL's behavior; the new behavior-only test asserts accepted/rejected
  composition without depending on internal leaf classes.
- Concurs: `Predicate.and`/`or` remain left-to-right and short-circuiting, and the
  representative rule still reads only `View` facts with the named DSL forms.
- Dissents: none.

### Residuals

- An explicit `DslValue<Any?>(...)` can still widen an operand, or a value explicitly
  statically typed as `Any?` can be wrapped; this is deliberate caller opt-in, not the
  accidental inference the round-1 finding covered.
- `Predicate`/`Predicate.and`/`or` remain opaque `(View) -> Boolean` closures and are
  not reifiable; this is unchanged from Round 1 and permitted by architecture §7's
  lambda allowance.
- Mixed `and`/`or` chains still left-associate as ordinary infix functions, and
  `gt`/`lt` on `Double` still follow `Comparable.compareTo` for NaN; both are unchanged
  residuals.
