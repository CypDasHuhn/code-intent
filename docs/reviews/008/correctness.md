# Correctness review — ticket 008 (bookkeeping rulepack)

## Round 1

Verdict: The pack is contract-clean — it is pure, emits only its owned
`BOOKKEEPING`, declares no reads, and its declaration/write/comment shapes match
the legacy `BookkeepingBlock` on the tested fixtures. The declaration-source
lookup, however, is driven by a text-suffix "wrapper" guess rather than the
declaration wrappers the legacy syntax model uses. That guess escapes the
declaration into statement lists and file roots, so unrelated annotations and
comments can create false `BOOKKEEPING` spans (and inherited writes) outside the
fixtures. Two findings below.

### Findings

1. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/BookkeepingRulePack.kt:94-115 (with isWrapper at :180-189) — annotation detection scans unrelated ancestors and can declare an unannotated property as bookkeeping.**
   `hasAnnotation` ascends through every parent whose normalized text merely
   ends with the declaration text, then `annotationWithin` walks *all* of that
   ancestor's earlier children looking for an annotation. For the last
   declaration in an unbraced container — Kotlin `statements`, or `program` /
   `source_file` for top-level declarations — the ascent reaches the whole
   statement list or file root. A concrete divergence:

   ```kotlin
   fun f() {
       @Bookkeeping
       var a = 0
       var x = 0
   }
   ```

   `x` inherits the annotation from the earlier `a` because, as the last
   statement, its parent `statements` ends with `x`'s text; `annotationWithin`
   then finds `a`'s `modifiers`/`annotation` node. The new rule emits `x` as an
   annotation-sourced `BOOKKEEPING` declaration (and any writes to `x`), while
   the legacy `annotationTexts` ascent stops at the property/wrapper chain and
   emits nothing for `x`. The same happens at file scope after an earlier
   annotated top-level property or an annotated parameter, and if `x` actually
   had a magic comment it would be misclassified as `annotation` and lose the
   comment from its span. The parity test misses this because every fixture
   declaration is inside a braced class/block and there is no second
   unannotated declaration in the same unbraced list.
   Fix: do not infer annotation ownership from suffix-equal ancestors. Restrict
   the scan to the property's own annotation/modifier containers and the actual
   declaration-wrapper chain, or expose the syntax model's normalized
   annotation texts as a `View` fact (follow-up) and use it. Add regression
   tests for consecutive local variables and consecutive top-level declarations
   where only the first is annotated.

2. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/BookkeepingRulePack.kt:122-130 and :170-187 — the same wrapper guess lets a magic comment cross non-declaration nodes and changes the write scope.**
   `precedingMagicComment` and `declarationHolder` use the same text-suffix
   ascent. In JavaScript, `if (c) var skipped = 0;` immediately preceded by
   `// cint:bookkeeping` makes the comment a sibling of the `if_statement`, not
   of the variable. The new code climbs `lexical_declaration -> if_statement ->
   program`; at the `program` level it finds the comment and attributes it to
   `skipped`. Legacy `precedingComments` stops after the declaration wrappers
   and only sees the condition/token siblings, so it reports no comment. In the
   same shape, `declarationHolder` returns the containing `program` instead of
   the legacy `if_statement`, so a later write to the same `var` is tagged by
   the new rule but not by legacy. Because this is only reachable through
   brace-less wrappers/roots it is narrower than finding 1, but it is the same
   unsound heuristic and should be fixed with it.
   Fix: stop the ascent at statement lists, control statements and file roots,
   or mirror the syntax model's `declarationWrapper`/`precedingComments`
   behavior through an exposed fact. Add a brace-less control-body test and a
   stmt-after-declaration write test.

### Concurs / Dissents

- Concurs: `RulePack`/`Rule` contract — id `bookkeeping:bookkeeping`, one owned
  intent, `reads = emptySet()`, `run` returns emits only and mutates no store;
  the engine's exact-owned-intent check is satisfied.
- Concurs: declaration emits carry the full declaration span for annotations
  and the comment-start-to-declaration-end span/bytes/evidence for magic
  comments, matching legacy `raw`/`tagRange`; `source`, `confidence` and
  evidence are the legacy values.
- Concurs: writes are resolved through `view.writes`, scoped to the declaration
  holder, filtered away from the declaration name, deduplicated by span, and
  emitted with the declaration's source tier; reads are not tagged.
- Concurs: the parity test does compare role/intents/evidence/span/source/
  confidence/bytes against `BookkeepingBlock` for Kotlin, JavaScript, TypeScript
  and C#, including an inline C# attribute, and the dedicated write/read and
  comment-span tests pin the scope bullets on the ticket.
- Concurs: all detection values are pack-local and there is no language branch
  in core; the supported-language set covers the current profiles.
- Dissents: findings 1 and 2.

### Residuals (not findings)

- `BundledRulePacks.packs()` is still empty and there is no `ServiceLoader`
  registration; pack loading/registration is ticket 011, not 008.
- `BookkeepingConfig.annotationKinds` hardcodes tree-sitter kind names; moving
  language-specific values into typed pack config is 012/013.
- The parity test is limited to the existing fixtures, so it cannot catch the
  unbraced/root and brace-less-control cases above until those tests are added.

## Round 2

Verdict: Both round-1 findings are addressed. The text-suffix `isWrapper`
heuristic is gone; `hasAnnotation`, `precedingMagicComment` and
`declarationHolder` now follow the same declaration-wrapper ascent as the legacy
syntax model, keyed by the file root (`source_file`, `program`,
`compilation_unit`). The added parity and dedicated tests cover the false
positive for consecutive declarations, the brace-less control-body comment case,
and both attribute/comment multi-declarators. No new correctness finding.

### Findings

- None.

### Concurs / Dissents

- F1: concurs — `hasAnnotation` (`BookkeepingRulePack.kt:94-110`) now inspects
  only the declaration's own modifier/annotation container and ascends through
  the configured property/wrapper kinds, matching `ProfileSyntaxModel.annotationTexts`
  and stopping before `statements`/`program`/`source_file`. The false-positive
  Kotlin local/top-level neighbours are pinned by the two new parity cases and by
  `unannotatedNeighboursAreNotDeclared`.
- F2: concurs — `precedingMagicComment` and `declarationHolder`
  (`BookkeepingRulePack.kt:112-120`, `:150-160`) use the same wrapper-kind ascent,
  so the JS brace-less `if` comment no longer reaches the inner declaration and
  the holder no longer widens to the whole `program`. The brace-less case is in
  the parity table; multi-declarator wrappers now resolve for non-final
  declarators as well.
- Concurs: the per-root wrapper map matches the legacy declaration wrappers for
  the supported roots (`source_file` has none, `program` has
  `lexical_declaration`/`export_statement`, `compilation_unit` has the C#
  declaration wrappers), and the observable shapes, sources, spans and byte
  offsets remain legacy-equivalent on the new cases.
- Concurs: multi-declarator source inheritance, write scope and write emission
  are covered for C# attributes and comments plus JavaScript/TypeScript comments;
  four annotations (two declarations, two writes) are asserted in each case.
- Dissents: none.

### Residuals (not findings)

- The wrapper map is keyed by root kind rather than by language/profile. It is
  equivalent for the currently supported roots; an unrecognised root silently
  gets no wrappers. Revisit when the language registry/profile becomes available
  to the pack or when a new grammar root is introduced.
- Pack registration remains ticket 011's scope, unchanged from round 1.
