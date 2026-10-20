# API-fidelity review — ticket 005 (thin rule dsl)

## Round 1

Verdict: The required DSL is in the right place and covers the required shape. `eq`,
`ne`, `gt`, `lt`, `and`, `or` are infix functions returning DSL values, lambda
predicates over `View` are supported, and the representative rule uses `View` facts
without falling back to `==`/`&&`/`||` in rule logic. The frozen contract types are
untouched. Two API-fidelity problems remain: the change adds convenience surface the
ticket never asked for to the frozen `api` module, and it freezes a concrete
evaluated-value condition IR that the deferred reification work would have to replace.

### Findings

1. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Dsl.kt:61-71` — unrequested convenience surface was added to the frozen API.**
   The ticket scope names only the infix `eq`/`ne`/`gt`/`lt`/`and`/`or` forms and
   lambda predicates over `View`. `Iterable<T>.where` is a generic collection-filter
   helper, and `predicate(...)` is a redundant factory because `Predicate` is already a
   `fun interface` (`Predicate { view -> ... }`). Neither appears in architecture §7,
   neither has a production consumer, and both now become part of the permanent plugin
   contract in `:api`; removing them later is an API break.
   Fix: delete `where` and `predicate` and use ordinary `filter` and `Predicate { ... }`
   in the representative rule/test. If they are wanted as deliberate DSL surface,
   record them in ticket 005 / architecture §7 and make them an explicit acceptance
   item rather than incidental additions.

2. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Dsl.kt:3-47` with `analysis-engine/api/src/test/kotlin/dev/cint/api/DslTest.kt:152-166` — the concrete condition leaves are public and pinned as an evaluated-value IR.**
   `Condition.Equals`/`NotEquals`/`GreaterThan`/`LessThan`/`And`/`Or` are public members
   of the frozen module, and `comparisonLeavesAreDataShapedForFutureReification` pins
   their exact constructors with literal operands. The leaves record only the values
   that were already evaluated: `call.callee eq "log"` produces
   `Equals("log", "log")`, not a fact/field selector, so the test proves literal-value
   equality rather than a shape the engine could later evaluate over a `View`. That
   makes the current implementation IR part of the public contract and means §10
   reification cannot replace it without an API break or a test rewrite, contrary to
   the ticket's "deliberately thin so it can be replaced later" requirement.
   Fix: keep `Condition` public as the operator return type but make the concrete leaf
   types implementation details (internal/private in `Dsl.kt`), and assert DSL behavior
   (`(3 gt 2).test()`, `((3 gt 2) and (2 gt 3)).test()`) instead of the internal
   constructors. If the leaf shape is intended to be frozen now, record that decision
   and drop the "replaceable later" claim; otherwise design the selector-shaped leaf
   with §10 rather than pinning it here.

### Concurs / Dissents

- Concurs: placement is correct for this ticket. `Dsl.kt` lives in `:api`, there is no
  `:dsl` module in `settings.gradle.kts`, and 001 Outcome / README decision #4 plus
  architecture §9 permit folding `dsl` into `api` initially. `api/build.gradle.kts` is
  unchanged and needs no new dependency.
- Concurs: the frozen contract is not altered. `git diff` is empty; the only changes are
  the untracked `Dsl.kt` and `DslTest.kt`, and `Contract.kt`, `Facts.kt`, `Model.kt`,
  `Rule`, `View`, `Emit`, `Annotation` and `Contribution` are unchanged. No competing
  `View`, fact or annotation shape was introduced.
- Concurs: the required DSL shape is present. All six infix operators return
  `Condition`; `Predicate` accepts lambda bodies over `View` and composes with infix
  `and`/`or`; the representative rule in `DslTest.kt:70-105` reads `View.imports` and
  `View.calls` and uses only the named DSL forms for its comparisons and logic.
- Concurs: there is no reification overreach in the sense of a CEL/Datalog engine or a
  second evaluator over `View` facts; `Condition.test()` is the thin local evaluation
  of already-read values, and no `core` or detection logic moved into `api`.
- Dissents: the extra `where`/`predicate` surface is feature creep into the frozen API
  (finding 1).
- Dissents: the public, constructor-pinned condition leaves freeze the deferred
  reification IR (finding 2).

### Verified

- No build, test or formatter command was run by this role; the handover was treated as
  green per workflow.md. The review is limited to the untracked DSL source and its test.
- The module graph is unchanged: `:api` still depends on nothing else in the tree,
  `:rulepacks` remains api-only, and no new module was added.
- The DSL's only interactions with the contract are `View` (for `Predicate`) and the
  existing `Emit`/`Span` types in the test rule; the frozen annotation/artifact payload
  is untouched.

## Round 2

Verdict: Both round-1 findings are addressed. The unrequested `where`/`predicate`
helpers are gone, the concrete condition leaves are no longer public API, and the test
now checks DSL behavior rather than pinning an internal IR shape. No new api-fidelity
findings.

### Findings

- F1: concurs — `Dsl.kt` now exposes only `Condition`, `DslValue`, the six infix
  operators and `Predicate`; `Iterable.where` and the `predicate(...)` factory are
  removed, and `DslTest` uses `filter` and `Predicate { ... }` directly
  (`DslTest.kt:142-147`, `:150-158`).
- F2: concurs — `EqualsCondition`/`NotEqualsCondition`/`GreaterThanCondition`/
  `LessThanCondition`/`AndCondition`/`OrCondition` are now `internal`, while `Condition`
  remains the public operator return type (`Dsl.kt:3-52`). The replacement test
  `composedConditionsEvaluateByBehavior` (`DslTest.kt:161-168`) asserts evaluation
  behavior instead of `Condition.*` constructors, so the deferred reification IR is no
  longer frozen by a public/test-pinned shape.
- New findings: none.

### Concurs / Dissents

- Concurs: the new public `DslValue<T>` wrapper is the mechanism that keeps `eq`/`ne`/
  `gt`/`lt` on DSL values instead of every Kotlin type (`Dsl.kt:7-10`, `:54-60`). It is
  an opaque operand carrier with an internal `raw`; it adds no second `View`/fact/
  annotation contract, no detection logic and no reification evaluator, so it does not
  create a new fidelity problem.
- Concurs: the required DSL behavior remains intact. The six infix operators still
  return `Condition`, lambda predicates still take `View`, and the representative rule
  uses `View.imports`/`View.calls` with named DSL forms only (`DslTest.kt:70-107`).
- Concurs: the frozen contract and module graph are unchanged. The only files touched
  are the untracked `Dsl.kt` and `DslTest.kt`; `Contract.kt`, `Facts.kt`, `Model.kt`,
  `Rule`, `View`, `Emit`, `Annotation`, `Contribution` and the `:api` dependencies are
  untouched.
- Concurs: no reification overreach was introduced. The internal leaves are still plain
  data carriers with local `test()` behavior, and no selector/CEL/Datalog layer was
  added.
- Dissents: none.

### Verified

- No build, test or formatter command was run by this role; the handover remains assumed
  green per workflow.md.
- `git diff` is empty and `git status` still shows only the two untracked DSL files plus
  the review directory; there is no hidden source change outside the reviewed pair.
- No competing `Condition`, `Predicate`, `View` or annotation type was introduced, and
  the rule-facing contract still receives `View` facts through the frozen `View`.
