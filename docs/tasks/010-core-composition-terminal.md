---
name: core composition terminal
status: todo
depends-on: [003, 004]
reviewers: [correctness, architecture]
---

## Goal

Move composition out of the legacy rule blocks and into `core` as the graph's
terminal node (architecture.md §11.4): coverage, intent scaffolds, derivative
use, and return categorization, consuming the union of all source intents and
naming no rule or pack.

## Scope

- Coverage and containing-intent resolution (current `Engine.kt` behavior).
- Scaffolds: bottom-up union of guard/body intents including `UNTAGGED`;
  side-effect-free guards neutral; effectful guards add `UNTAGGED`; bare
  `return`/`continue`/`break` neutral; `return expr` carries the expression's
  intents; mixed `if`/`else` splits into then/else spans so an untagged else does
  not force the then branch; whole-try classification requires full coverage,
  try-header only covered catches; catch spans include the try-body closing
  brace; fully-classified function bodies become scaffolds.
- Derivative use: an explicitly pure initializer (literal, local read, or
  annotated pure call); promotion only when all reads are classified; reads
  resolve from the smallest containing annotation; function promotion only when
  explicitly pure.
- Return categorization from the CFG `ReturnBlock` (`§1` lists return category
  under core composition): a return on a validation guard's true edge is
  `VALIDATION`; a rejection sentinel (`null`, `false`, `0`, bare return, ...) on
  the other edge is `VALIDATION`; everything else is `UNTAGGED`.
- Composition adds tags only; adding or removing a pack can never change it.

## Acceptance

- All composition output is `SCAFFOLD`/`DERIVATIVE_USE`; no plugin can produce or
  remove it.
- Pack-independence holds: the composition result is invariant to the set of
  loaded packs except for added source intents.
- Parity with the current composition behavior over the fixtures, or a
  documented, approved divergence.
- `just test-engine` green.

## Out of scope

- Purity inference (`§10`).
- Switch/when returns and Kotlin non-local returns stay as-is (`notes.md §20`);
  do not widen scope here unless explicitly approved.
