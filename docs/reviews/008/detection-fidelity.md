# Detection-fidelity review — ticket 008 (bookkeeping rulepack)

## Round 1

Verdict: The rule matches `BookkeepingBlock` on the existing fixtures: annotation
and magic-comment declarations, write inheritance, read exclusion, comment-in-span
behavior, and the span/evidence/source/confidence/byte shapes all line up. It is a
pure `(View) -> List<Emit>` with an honest empty `reads`. Two detection-parity gaps
remain, both caused by the pack-local text-suffix `isWrapper` heuristic rather than
the syntax model's declaration-wrapper facts. The first is the wrapper-ascent
false positive also reported by the correctness review; the second is a
multi-declarator case that neither review's tests cover.

### Findings

1. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/BookkeepingRulePack.kt:94-115 (with `isWrapper` at :180-189) — wrapper ascent lets unrelated preceding annotations declare a property.**

   `hasAnnotation` climbs through any parent whose normalized text ends with the
   declaration text, then `annotationWithin` recursively scans every descendant of
   that parent before the declaration's name. For the last declaration in an
   unbraced container (`statements`, `program`, `source_file`), the ascent reaches
   the whole list/root and finds annotations that belong to earlier declarations:

   ```kotlin
   fun f() {
       @Bookkeeping
       var a = 0
       var x = 0
   }
   ```

   `x` is emitted as an annotation-sourced `BOOKKEEPING` declaration, and writes to
   `x` inherit that tag. Legacy `annotationTexts` only inspects the declaration and
   its modifier/wrapper ancestry, so it emits nothing for `x`. The same ascent in
   `precedingMagicComment` can cross a brace-less control statement and attribute
   an earlier comment (and a wider write scope) to the inner declaration. The
   correctness review reports this root cause; from a detection-fidelity standpoint
   the consequence is false `BOOKKEEPING` spans and inherited writes outside the
   fixtures.

   Suggested fix: stop inferring annotation/comment ownership from suffix-equal
   ancestors. Restrict the scan to the declaration's own annotation/modifier
   containers and its true declaration-wrapper chain (the profile's
   `declarationWrapper` facts), and add regressions for consecutive local
   declarations and consecutive top-level declarations where only the first is
   annotated.

2. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/BookkeepingRulePack.kt:94-105, :122-130, :170-189 — non-final declarators in a multi-declarator declaration lose their annotation/comment source and write scope.**

   `isWrapper` decides wrappers by text suffix, so a declaration whose wrapper
   ends with a sibling declarator is not recognized as a wrapper for the earlier
   declarators. In:

   ```csharp
   class C {
       // cint:bookkeeping
       private int a = 0, b = 0;

       void F() {
           a++;
           b++;
       }
   }
   ```

   for `a`, `isWrapper(variable_declaration, variable_declarator(a))` is false
   because the `variable_declaration` text does not end with `a = 0`. As a result
   `hasAnnotation`/`precedingMagicComment` never reach the field-level attribute or
   comment, and `declarationHolder` returns the inner `variable_declaration`
   instead of the class body, so `a` and its write are untagged. `b` works only
   because it happens to be the final declarator. JavaScript/TypeScript
   `let a = 0, b = 0` has the same asymmetry (the legacy syntax model ascends
   `variable_declaration`/`lexical_declaration` by kind for both). The fixture
   parity test uses only single-declarator properties and therefore cannot catch
   this.

   Suggested fix: use the syntax model's declaration-wrapper kinds (or an exposed
   `View`/syntax fact for the declaration holder) instead of text suffix matching,
   and add multi-declarator parity tests in C#/JavaScript/TypeScript for both
   `[Bookkeeping]`/`@Bookkeeping` and `// cint:bookkeeping`, including writes to
   each declared property.

### Concurs / Dissents

- Concurs: `@Bookkeeping`, qualified annotations, C# `[Bookkeeping]`, and
  `// cint:bookkeeping` declarations match legacy on the tested fixtures, including
  the annotation-preferred-over-comment ordering and the magic-comment span
  starting at the comment.
- Concurs: writes are obtained through `view.writes`, scoped to the declaration
  holder, filtered away from the declaration name, deduplicated by span, and
  emitted with the declaration's source tier. The Kotlin test pins `x++`, `x += 1`,
  assignment, and the absence of a read-only `BOOKKEEPING` tag.
- Concurs: declaration and write spans, byte offsets, evidence truncation,
  `source` tiers, and confidence `1.0` match the legacy `raw`/`tagRange` shapes;
  for magic comments the declaration evidence and span include the comment.
- Concurs: `reads = emptySet()` is honest. The rule consults only core `View`
  facts (`properties`, `references`, `writes`) plus raw syntax nodes, never
  `annotations()`/`intentsAt()`, and does not mutate the store.
- Concurs: no variable-name inference; the explicit declaration is the only
  source, and the JavaScript name/initializer test covers the obvious lookalike
  case.
- Dissents: wrapper inference by normalized text suffix produces the false
  positives in finding 1 and the multi-declarator omissions in finding 2.
- Dissents: the parity test's fixture set does not exercise the multi-declarator
  form, so the second gap is not pinned.

### Verified

- Static review only; no source edit, build, or test command was run by this role.
  The implementor's handover is treated as green per workflow.md.
- Compared the new rule against `BookkeepingBlock` in
  `analysis-engine/core/src/main/kotlin/dev/cint/core/rules/Blocks.kt:192-263`, the
  legacy `annotationTexts`/`precedingComments`/`declarationHolder` behavior in
  `ProfileSyntaxModel`, the `BOOKKEEPING` entry and language profiles in
  `intents.yaml`, the testing-grounds fixtures, and the new test.

## Round 2

Verdict: Both round-1 detection-fidelity findings are addressed. The rule now
selects declaration-wrapper kinds from the tree root and mirrors the legacy
`annotationTexts`/`precedingComments`/`declarationHolder` ascent
(`BookkeepingRulePack.kt:16-30`, `:106-177`), so annotation discovery no longer
scans unrelated ancestors and wrapper ascent reaches every declarator in a
multi-declarator declaration. The added parity cases cover consecutive
unannotated neighbours, C#/JavaScript/TypeScript multi-declarators, and the
brace-less JavaScript control-body case; the multi-declarator cases now produce
the same declaration sources and write spans as legacy. No new
detection-fidelity finding.

### Findings

- None.

### Concurs / Dissents

- F1: concurs — annotation lookup is now limited to the declaration, its
  modifier/annotation container, and the configured declaration-wrapper/property
  kinds (`BookkeepingRulePack.kt:106-124`). It no longer recurses through a
  suffix-equal `statements`/`program`/`source_file` node, so the earlier
  unannotated neighbour cannot inherit a previous declaration's annotation.
  `unannotatedNeighboursAreNotDeclared` covers the local and top-level Kotlin
  forms, and the kind-based ascent matches `ProfileSyntaxModel.annotationTexts`.
- F2: concurs — `precedingMagicComment` and `declarationHolder` now use the same
  wrapper-kind ascent as the legacy syntax model (`BookkeepingRulePack.kt:131-143`,
  `:166-177`). C#/JavaScript/TypeScript multi-declarators therefore reach the
  field/lexical wrapper and class/file holder for every declarator; the added
  C# attribute, C# comment, JS and TS multi-declarator cases compare against
  legacy and pin both declarations and both writes.
- Concurs: the wrapper sets for `source_file`, `program` and `compilation_unit`
  match the legacy per-language `declarationWrapper` groups, and the annotation,
  modifier and property kind sets match the profiles. The brace-less JavaScript
  case is a no-tag parity check; the old suffix ascent through
  `variable_declaration`/`if_statement` is gone because those kinds are not in
  the JavaScript wrapper set.
- Concurs: declaration/write spans, byte offsets, evidence, source tiers and
  confidence remain unchanged from Round 1 and continue to match the legacy
  `raw`/`tagRange` shapes. `reads = emptySet()` remains honest; the rule still
  uses only core `View` facts and does not mutate the store.
- Dissents: none.

### Verified

- Static review only; no source edit, build, or test command was run by this role.
  The implementor's handover is treated as green per workflow.md.
