# Correctness review — ticket 021 (simplify bookkeeping pack)

## Round 1

Verdict: The pack is now a genuine semantic-fact consumer. It has no
grammar-kind tables, parent climbing, comment scanning, or manual span
construction; annotation precedence, magic-comment spans, declaration/write
inheritance, and function exclusion preserve the old observable contract. The
core write-attribution work also fixes the deferred loop/parameter shadowing and
object/companion ownership cases for the patterns covered by the new tests. One
binding-extraction gap remains for destructuring patterns that use named fields:
C# tuple deconstruction registers only the first loop variable, and JS/TS
renamed object patterns register none, so writes can still leak to outer fields.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/syntax/DeclarationFacts.kt:227-236` (called from `loopBindingNames` at `:205`) — `bindingNameNodes` returns only the first `name` field, so C# tuple destructuring registers only the first binding.**
   For `foreach (var (key, value) in map)`, tree-sitter makes `left` a
   `tuple_pattern` with two `name:` fields. `fieldNode(node, VARIABLE_NAME)`
   resolves the first `name` (`key`) and the early `return listOf(it)` prevents
   scanning the second. No binding target is created for `value`, so a write
   such as `value = ...` inside the loop is unqualified, contained by the outer
   class scope, and is attributed to an outer field named `value`. If that field
   is annotated/bookmarked this produces a false `BOOKKEEPING` write; if not,
   `DeclarationFact.writes()` still reports the wrong target. The same root
   affects JS/TS renamed object patterns: `for (const {a: x, b: y} of list)`
   yields an `object_pattern` whose children are `pair_pattern` nodes, so
   `bindingNameNodes` returns an empty list. The new
   `loopBindingsShadowFieldsAcrossPatternsAndTypedHeaders` test covers Kotlin and
   JS/TS array patterns plus a simple typed C# header, but not C# tuple
   destructuring or renamed object patterns, so it does not catch this. Fix by
   collecting every `name` field (or recursing into pattern children) rather than
   returning the first field, and add a C# tuple fixture plus a renamed JS/TS
   object fixture.

### Concurs / Dissents

- Concurs: `BookkeepingRulePack.kt:57-74` preserves the old rule contract:
  annotation wins over magic comment, the comment is included in declaration
  evidence/span, only named non-function declarations are considered, and writes
  reuse the declaration source. The `DeclarationKind.FUNCTION` filter is the
  equivalent of the old properties-only traversal for function declarations.
- Concurs: `DeclarationFacts.declarationWrites` (`:111-152`) now resolves
  same-name targets by narrowest containing scope and rejects writes before a
  local binding is declared. The loop/catch/parameter targets (`:154-198`)
  suppress the tested shadowing leaks, and the Kotlin `for` condition fallback
  (`:653-661`) now skips the binding and returns the iterable/range.
- Concurs: adding `companion_object`, `object_declaration`, and `object_literal`
  to the Kotlin `class` group (`kotlin.yaml:34`) makes their `class_body` scopes
  owned by the nearest companion/object rather than the outer class; the new
  semantic and bookkeeping tests cover the companion, named-object, and object
  literal cases.
- Concurs: removing the four grammar-kind config fields and making their keys
  unknown is consistent with the strict `ConfigSchema` contract; the migration
  test now pins `bookkeeping.propertyKinds: unknown key`.
- Dissents: finding 1.

### Residuals (not blocking this ticket)

- Loop conditions for non-Kotlin `for`-each constructs are still not the
  iterable: JS/TS `for (const x of list)` falls through to the `const` token, and
  C# `foreach` falls through to the declared type, because their loop nodes have
  no `condition` field and the fallback has no `right`-side/container mapping.
  This predates 021 and is not consumed by bookkeeping, but `guards()` /
  `guardConditions()` expose it.
- JS/TS destructured parameters (for example `function f({a})`) are not captured
  by `bindingTargets` because `formal_parameters` only contributes direct
  identifier children and `object_pattern` is not in `parameterKinds`. This is
  the same attribution family as finding 1 but outside the ticket's
  C#/Kotlin-specific note.
- No end-to-end test exercises a configured bookkeeping annotation/magic-comment
  name through rule execution; `PackConfigMigrationTest` only pins decoding and
  unknown-key rejection. The matching code is straightforward, but the override
  path is not covered by `BookkeepingRulePackTest`.

No build, test, or formatter command was run by this role; the handover was
treated as green per `workflow.md`.

## Round 2

Verdict: Round-1 finding 1 is fixed. `bindingNameKinds`/`patternKinds` and the
recursive `bindingNameNodes` now extract all C# tuple elements, JS/TS renamed
object properties, nested patterns, and rest bindings, and the new bookkeeping
and semantic regressions exercise those shapes. The recursion has one new
over-inclusion: `assignment_pattern` is treated as a pattern and all of its
children are traversed, so a default initializer's identifiers become loop or
parameter bindings and can shadow real field writes.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/syntax/DeclarationFacts.kt:229-232` — recursive pattern traversal includes assignment-pattern initializers, so default-value identifiers are registered as bindings.**
   `assignment_pattern` is in the JS/TS `pattern` group, and
   `bindingNameNodes` recurses over every child. For
   `for (const [a = fallback] of list) { fallback = 2; }`, both the `left`
   identifier `a` and the `right` identifier `fallback` are added as loop
   bindings. The loop binding scope is narrower than the class scope, so the
   candidate selection in `declarationWrites` prefers the synthetic
   `fallback` binding and the write is no longer attributed to an outer
   `fallback` field; an annotated field therefore loses a legitimate
   `BOOKKEEPING` write (and `DeclarationFact.writes()` under-reports). The same
   happens for object defaults such as
   `for (const {x: a = fallback, ...rest} of list)`; complex default expressions
   can add additional identifiers. This is new in Round 2 because Round 1 did
   not classify `assignment_pattern` as a pattern. Recurse only into the
   binding side of `assignment_pattern` (its `left`/first pattern child) and the
   argument of `rest_pattern`, and add a default/rest fixture to
   `loopBindingsShadowFieldsAcrossPatternsAndTypedHeaders`.

### Concurs / Dissents

- Concurs: Round-1 F1 is resolved. C# `tuple_pattern` children are now visited
  individually, `pair_pattern` skips its property key and keeps its value
  identifier, and nested object/array patterns and rest patterns are reached.
  The added C# tuple, JS alias, and TS nested-pattern cases plus the bookkeeping
  `destructuredLoopBindingsDoNotInheritBookkeeping` case cover the previously
  missing shapes; the recorded test results show 8/8 bookkeeping and 13/13
  semantic tests green.
- Concurs: the pack remains free of raw node kinds and AST traversal, and the
  Round-1 bookkeeping, scope/ownership, and config conclusions are unchanged.
- Dissents: new finding 1 (default-value identifiers over-shadow field writes).

### Residuals (Round 2)

- Round-1 residual remains: JS/TS `for-in` and C# `foreach` guard conditions are
  still the `const`/`let` token or declared type rather than the iterable.
- JS object-pattern parameters (for example `run({a})`) are still not captured
  because `formal_parameters` only contributes direct identifier children; TS
  object-pattern parameters are now reached through `required_parameter`.
- No end-to-end test exercises a configured bookkeeping annotation/magic-comment
  name through rule execution.

No build, test, or formatter command was run by this role; the handover was
treated as green per `workflow.md`.

## Round 3

Verdict: Round-2 finding 1 is fixed. `assignment_pattern` and
`object_assignment_pattern` are now `patternBinding` nodes and recurse only
through their `left` field, so default initializer identifiers no longer become
bindings while rest patterns still shadow. The JS/TS default+rest regressions
and the semantic `defaultPatternExpressionsAreNotLoopBindingsAndRestStillShadows`
case exercise the fix. One over-inclusion remains in the same recursive pattern
walk: `pair_pattern` still traverses all children, so a computed property key's
expression identifiers are treated as loop bindings.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/syntax/DeclarationFacts.kt:232` with the JS/TS `pattern` groups — `pair_pattern` recursion includes computed property keys as binding names.**
   `pair_pattern` is in `patternKinds`, so `bindingNameNodes` calls
   `children.flatMap(::bindingNameNodes)` and visits both the `key` and `value`
   children. For a normal key (`property_identifier`) the key is not a binding
   name and is ignored, which is why aliases work, but for
   `for (const {[key]: x} of list)` the key is a `computed_property_name`
   containing an `identifier`, and that identifier is returned as a loop
   binding. The loop binding scope is narrower than the class scope, so a write
   to an outer annotated field named `key` inside the loop is attributed to the
   synthetic binding and the `BOOKKEEPING` write is lost (the same false-shadow
   class as Round 2). Fix by treating `pair_pattern` like a pattern binding whose
   binding side is its `value` field (or otherwise excluding the `key` subtree),
   and add a computed-key regression; the new default/rest fixtures do not cover
   computed keys.

### Concurs / Dissents

- Concurs: Round-2 F1 is resolved. Default/rest patterns now yield only the
  `left` binding, both JS ordinary and object assignment forms are covered, and
  TS aliased defaults are covered; the recorded results show 9/9 bookkeeping and
  14/14 semantic tests green.
- Concurs: C# tuple patterns, JS/TS aliases, nested patterns, rest bindings,
  object/companion ownership, and the bookkeeping pack/config conclusions remain
  correct and unchanged.
- Dissents: new finding 1 (computed destructuring keys over-shadow field
  writes).

### Residuals (Round 3)

- Round-1/2 residuals remain: JS/TS `for-in` and C# `foreach` guard conditions
  are still the `const`/`let` token or declared type rather than the iterable.
- JS object-pattern parameters (for example `run({a})`) are still not captured
  because `formal_parameters` only contributes direct identifier children.
- TS default parameters without destructuring (for example `f(x = fallback)`)
  still expose both the parameter pattern and the `value` identifier through the
  direct-child fallback, so the initializer can shadow a field write; this
  predates the pattern work.
- No end-to-end test exercises a configured bookkeeping annotation/magic-comment
  name through rule execution.

No build, test, or formatter command was run by this role; the handover was
treated as green per `workflow.md`.

## Round 4

Verdict: Round-3 finding 1 is fixed. `pair_pattern` moved from the generic
`pattern` group to `patternBinding`, whose lookup now tries the `left` then
`value` fields. `pair_pattern` has no `left` field, so only its `value` subtree
is traversed for bindings; computed keys are no longer registered while aliases,
nested values, and defaults continue to resolve through the value side. The new
computed-key semantic and bookkeeping regressions pass, and no new correctness
finding was found.

### Findings

- None. The computed-key over-shadowing case is resolved by traversing only
  `pair_pattern.value`; the default/rest and alias behavior from Rounds 2–3 is
  unchanged, and the recorded results show 10/10 bookkeeping and 15/15 semantic
  tests green.

### Concurs / Dissents

- Concurs: the JS/TS `patternBinding` group now contains
  `assignment_pattern`, `object_assignment_pattern`, and `pair_pattern`, and the
  `patternBinding` field list `[left, value]` resolves each to its binding side.
  `fieldNode` tries `left` first, so assignment/object-assignment patterns still
  take the left binding, while `pair_pattern` falls through to `value`.
- Concurs: computed keys are read expressions, not bindings; excluding them
  restores writes such as `key = 1` to an outer `key` field while the pair value
  still shadows a same-named field, as the new tests assert.
- Concurs: C# tuple patterns, Kotlin/JS/TS destructuring, nested patterns, rest
  bindings, object/companion ownership, and the bookkeeping pack/config
  conclusions remain correct and unchanged.
- Dissents: none.

### Residuals (Round 4)

- Round-1/2/3 residuals remain: JS/TS `for-in` and C# `foreach` guard conditions
  are still the `const`/`let` token or declared type rather than the iterable.
- JS object-pattern parameters (for example `run({a})`) are still not captured
  because `formal_parameters` only contributes direct identifier children.
- TS default parameters without destructuring (for example `f(x = fallback)`)
  still expose both the parameter pattern and the `value` identifier through the
  direct-child fallback, so the initializer can shadow a field write.
- No end-to-end test exercises a configured bookkeeping annotation/magic-comment
  name through rule execution.

No build, test, or formatter command was run by this role; the handover was
treated as green per `workflow.md`.
