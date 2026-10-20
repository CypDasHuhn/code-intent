# Detection-fidelity review — ticket 021 (simplify bookkeeping pack)

## Round 1

Verdict: The rewrite preserves the visible bookkeeping behavior: annotation and
magic-comment declarations, write inheritance, read exclusion, source tiers,
spans/evidence and byte offsets still match the frozen legacy snapshots, and the
pack is now a pure `View -> List<Emit>` with no grammar-kind tables or AST
traversal. The Kotlin for-condition and companion/object ownership fixes also
work. One deferred write-attribution edge remains: destructured loop bindings
are only partly extracted, so a non-first binding (and nested patterns) can
still leak writes to a same-named field.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/syntax/DeclarationFacts.kt:227-237` — pattern destructuring registers only part of a loop binding.**

   `bindingNameNodes` returns on the first `VARIABLE_NAME` field. For C#
   `foreach (var (key, value) in map)`, the profile maps `loopBinding` to `left`
   (`syntax/csharp.yaml:74`), `left` is a `tuple_pattern`, and both tuple
   identifiers carry a `name` field. `childByField("name")` therefore yields
   only `key`; `value` never becomes a `WriteTarget`. A write to `value` inside
   the loop then resolves to a same-named class field, so the pack emits a false
   `BOOKKEEPING` write. The same non-recursive extraction affects nested JS/TS
   patterns such as `for (const [[a], b] of list)`, where only the direct
   identifier `b` is found. This is the deferred "loop binding names" edge; the
   new shadow tests only cover a C# identifier foreach and flat
   array/Kotlin-tuple patterns (`BookkeepingRulePackTest.kt:185-208`,
   `SemanticFactsLanguageTest.kt:699-737`).

   Fix: make `bindingNameNodes` recurse through pattern containers
   (`tuple_pattern`, `array_pattern`, `object_pattern`, `pair_pattern`, nested
   bindings) and collect every identifier leaf instead of returning the first
   field/direct identifier; add C# tuple-deconstruction and nested JS/TS loop
   regressions that assert the fields receive no writes.

### Concurs / Dissents

- Concurs: the rule now uses only `declarations()`/`hasAnnotation()`/
  `hasMagicComment()`/`writes()` and the `emit` builders
  (`BookkeepingRulePack.kt:57-74`); it is under the 80-line target and has no
  raw node kinds or parent climbing.
- Concurs: annotation behavior is preserved. `DeclarationFact.hasAnnotation`
  applies the same normalized exact/suffix matching for `@Bookkeeping`,
  `[Bookkeeping]`, Kotlin use-site targets and decorators, and annotation-sourced
  spans remain the declaration node (which includes Kotlin/TS annotation text).
- Concurs: magic-comment behavior is preserved. Contiguous preceding comments
  are scanned, the nearest matching `cint:<magicComment>` is used, the comment
  starts the declaration span, evidence is `"<comment> <declaration>"`, and the
  configured `magicComment` drives the lookup. C#, JS, Kotlin and TS fixture
  snapshots match the pre-014 pipeline exactly.
- Concurs: writes inherit the declaration's source tier and are deduplicated by
  span; reads remain untagged. The pack never consults `references()` or
  `annotations()`, so the empty `reads` contract stays honest.
- Concurs: the new tests pin shadowing for Kotlin multi-variable loops, flat
  JS/TS destructuring, C# identifier foreach and parameters, Kotlin iterable
  conditions, and companion/object body ownership. The Kotlin `class` group
  additions (`kotlin.yaml:34`) and loop-condition exclusions
  (`DeclarationFacts.kt:653-660`) are the right semantic fixes.
- Concurs: removing `annotationKinds`/`modifierKinds`/`propertyKinds`/
  `declarationWrappersByRoot` from the config is consistent with the ticket, and
  `PackConfigMigrationTest` now pins that old grammar keys are rejected.
- Dissents: finding 1 above leaves one deferred loop-binding case unresolved.

### Residuals (not findings)

- `loopBindingNames` prefers the `left` field for JS `for (obj.prop of list)` /
  `for (this.count of list)`; `bindingNameNodes` can read the member `property`
  field as a binding because `variableName` includes `property`. That creates a
  phantom loop binding which can suppress an unqualified field write in the
  loop. This was already wrong in the old pack for the direct-identifier forms,
  but the field-based extraction makes the member-expression form newly
  reachable.
- Magic-comment matching now enforces a name boundary, so
  `// cint:bookkeeping-v2` no longer matches the default comment where the old
  regex did. The stricter behavior looks intentional, but no fixture pins it.
- For a multi-line declaration with a magic comment, the new evidence
  normalizes newlines to spaces; the old comment path did not. The frozen
  fixtures are single-line and still match exactly.
- Static review plus grammar AST inspection only; no source edit, build, test or
  formatter command was run by this role, and the green handover is treated per
  `docs/workflow.md`.

## Round 2

Verdict: The Round-1 finding is addressed for the reported cases: the new
`bindingName`/`pattern` roles make tuple/nested/alias patterns recurse, C#
`foreach (var (key, value) ...)` now registers both names, and the added
C#/JS/TS/Kotlin regressions pin the flat and nested forms. One new
default-value pattern gap remains in the same extraction path, so this round
still dissents.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/syntax/DeclarationFacts.kt:227-238` with `syntax/javascript.yaml:41` / `typescript.yaml:41` — destructuring defaults either lose the binding or gain a phantom one.**

   `bindingNameNodes` now recurses through every child of a `pattern` node.
   That is correct for plain/alias/rest patterns, but wrong for defaults in two
   ways. JavaScript object shorthand defaults (`for (const {a = fallback} of
   list)`) use `object_assignment_pattern`, which is absent from `pattern`:
   `object_pattern` has no nested `binding`/`pattern` child, so the function
   returns empty and `a` is not registered as a loop binding; a write to a field
   named `a` in the body is then falsely tagged. Array defaults (`[a =
   fallback]`) and aliased defaults (`{a: x = fallback}`) use
   `assignment_pattern`, which is in `pattern`; recursing all children collects
   the right-hand default expression's identifiers too, so `fallback` is
   registered as a phantom binding and a write to a field named `fallback` is
   suppressed. Neither case is covered by the new pattern tests, which use only
   identifiers, aliases and nesting (no `=` defaults).

   Fix: treat `object_assignment_pattern` as a pattern and recurse only its
   left/pattern side, and for `assignment_pattern` recurse only the `left`
   field rather than every child; add JS/TS regressions for shorthand defaults
   and array/aliased defaults that assert the bound name shadows while the
   default expression name does not.

### Concurs / Dissents

- Concurs: F1 is addressed for the prior cases. `tuple_pattern` and the JS/TS
  `array_pattern`/`object_pattern`/`pair_pattern`/`rest_pattern` roles
  (`javascript.yaml:40-41`, `typescript.yaml:40-41`,
  `csharp.yaml:40-41`) make aliases and nesting resolve, including the nested
  Kotlin `(key, (a, b))` form.
- Concurs: the regressions now cover C# tuple deconstruction
  (`SemanticFactsLanguageTest.kt:753-765`, `BookkeepingRulePackTest.kt:215-233`),
  JS/TS aliases and nested patterns (`SemanticFactsLanguageTest.kt:766-806`,
  `BookkeepingRulePackTest.kt:234-274`) and Kotlin nested destructuring
  (`SemanticFactsLanguageTest.kt:725-739`). The C# tuple names are found before
  the first `VARIABLE_NAME` shortcut, so the Round-1 leak is gone.
- Concurs: round-1 conclusions on annotation/comment behavior, write source
  tiers, read exclusion, spans/evidence, the config cleanup, Kotlin for
  conditions and object/companion ownership remain unchanged; the frozen
  legacy snapshots still match.
- Concurs: the prior member-expression residual is incidentally improved —
  `property_identifier` is not a `bindingName`, so `for (obj.prop of ...)` no
  longer creates a phantom `prop` binding through the `property` field.
- Dissents: default-value patterns still produce both false positives and false
  negatives as described in finding 1.

### Residuals (Round 2)

- `bindingNameNodes` still trusts a single `VARIABLE_NAME` field for
  non-pattern nodes; for C# that is now bypassed for `tuple_pattern`, but any
  future multi-name field shape would hit the same first-only behavior.
- Default-value expressions are reads, not bindings. The current recursion has
  no notion of which side of an assignment pattern is the binding, so this
  residual class extends beyond the listed examples to computed keys/default
  expressions if those nodes are added to `pattern`.
- Static review plus grammar AST inspection only; no source edit, build, test or
  formatter command was run by this role. The handover's green test results were
  treated as reported, not rerun.

## Round 3

Verdict: The Round-2 default-pattern finding is addressed: `assignment_pattern`
and `object_assignment_pattern` now recurse only their `left` field, so
`[a = fallback]`, `{a = fallback}` and `{b: c = fallback}` bind the correct name
and leave the default expression as a read, with the added JS/TS default+rest
tests pinning the behavior. One new gap remains in the same pattern traversal:
computed object keys are still treated as bindings, so this round dissents.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/syntax/DeclarationFacts.kt:232` with `syntax/javascript.yaml:41` / `typescript.yaml:41` — a computed destructuring key is collected as a loop binding.**

   `pair_pattern` is in `pattern`, so `bindingNameNodes` recurses through every
   child of the pair. Its `key` may be a `computed_property_name` containing a
   plain identifier; for `for (const {[key]: value} of list)`, the traversal
   reaches that key identifier and returns it alongside `value`. The loop then
   has a phantom binding named `key`, and a write to a real field named `key`
   inside the loop is suppressed. Aliased keys (`{a: value}`) are harmless
   because the key is a `property_identifier`, which is not a `bindingName`, but
   computed identifier keys are not filtered. The new default tests cover
   aliases, defaults and rest, but not computed keys.

   Fix: treat `pair_pattern` as a binding-side pattern too and recurse only its
   `value` field (or add a pair-specific field mapping for `value`), keeping the
   key expression out of binding extraction; add a JS/TS
   `for (const {[key]: value} of list)` regression asserting the `key` field
   still receives its writes while `value` is shadowed.

### Concurs / Dissents

- Concurs: Round-2 F1 is addressed. `patternBindingKinds` plus the
  `patternBinding: [left]` field mappings (`javascript.yaml:42,81`,
  `typescript.yaml:42,81`) make `assignment_pattern` and
  `object_assignment_pattern` resolve only the binding side; the JS/TS default
  and rest cases (`SemanticFactsLanguageTest.kt:821-890`,
  `BookkeepingRulePackTest.kt:287-338`) assert that `fallback` remains a field
  write and that the bound/rest names do not inherit bookkeeping.
- Concurs: plain, aliased, nested and rest patterns still resolve through
  `pattern` (`bindingNameNodes:227-241`), and C# tuple and Kotlin nested
  destructuring remain covered by the Round-2 regressions.
- Concurs: annotation/comment behavior, write source tiers, read exclusion,
  spans/evidence, config cleanup, Kotlin for conditions and object/companion
  ownership are unchanged; the frozen legacy snapshots still match.
- Dissents: computed keys still produce a phantom binding as described in
  finding 1.

### Residuals (Round 3)

- The same key-side issue would apply to any future identifier-bearing key
  expression added to `pattern`; the fix should make binding-side field
  selection explicit rather than relying on child recursion.
- Static review plus grammar AST inspection only; no source edit, build, test or
  formatter command was run by this role. The green handover was treated as
  reported, not rerun.

## Round 4

Verdict: The Round-3 computed-key finding is addressed. `pair_pattern` now lives
in `patternBinding` rather than `pattern`, and the `patternBinding: [left,
value]` mapping resolves its `value` field, so computed keys are no longer
visited while aliases, nested patterns and defaults still resolve. The new
JS/TS computed-key regressions pin both the real `key` write and the shadowed
`value`. No new detection-fidelity finding was found.

### Findings

- None. The Round-3 finding (computed object keys extracted as loop bindings) is
  fixed at the binding-side dispatch, and the added tests exercise the exact
  failure mode.

### Concurs / Dissents

- Concurs: F1 is addressed — `pair_pattern` moves from `pattern` to
  `patternBinding` (`javascript.yaml:41-42`, `typescript.yaml:41-42`), and
  `bindingNameNodes:229-231` follows the `value` field, so the key expression is
  excluded while the bound value is still visited. `patternKinds` retaining
  `array_pattern`, `object_pattern` and `rest_pattern` keeps plain, nested and
  rest destructuring working.
- Concurs: the computed-key tests pin both sides of the contract:
  `SemanticFactsLanguageTest.kt:893-932` asserts the `key` field receives
  `key = 1` and the `value` field receives no write, and
  `BookkeepingRulePackTest.kt` adds the pack-level companion case asserting the
  computed-key field write is emitted while the pair value write is not.
- Concurs: Round-2 defaults/rest and Round-1 tuple/nested/alias behavior remain
  intact; `assignment_pattern` and `object_assignment_pattern` still use their
  `left` fields, and C# tuple plus Kotlin nested patterns still resolve through
  `pattern`/`binding`.
- Concurs: annotation/comment behavior, write source tiers, read exclusion,
  spans/evidence, config cleanup, Kotlin for conditions and
  companion/object ownership are unchanged; the frozen legacy snapshot tests
  remain green in the reported handover.
- Dissents: none.

### Residuals (Round 4)

- JS/TS destructured function parameters (`function f({a})`) still are not
  registered as binding targets because the `parameterList` branch only reads
  direct identifiers; this predates 021 and is outside the loop-binding scope,
  but the new pattern roles would make a future fix straightforward.
- Static review plus grammar AST inspection only; no source edit, build, test or
  formatter command was run by this role. The green handover was treated as
  reported, not rerun.
