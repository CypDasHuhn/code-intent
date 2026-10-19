---
name: bookkeeping rulepack
status: todo
depends-on: [003, 004, 005]
reviewers: [correctness, detection-fidelity]
---

## Goal

Re-express bookkeeping detection as a bundled rule on the new API, replacing the
current `BookkeepingBlock`.

## Scope

- Declared once on a property with `@Bookkeeping` or `// cint:bookkeeping`.
- The declaration and all writes (`x++`, `x += 1`, assignment) inherit
  `BOOKKEEPING`; reads are not tagged as bookkeeping.
- A preceding magic comment is part of the declaration span.
- No inference from variable names; explicit declaration is the source of truth.

## Acceptance

- Parity with the current `BookkeepingBlock` over the fixtures.
- Pure rule, honest `reads`.
- `just test-engine` green.

## Out of scope

- Validation (009).

## Notes

- The validation rule depends on this one (`reads = {BOOKKEEPING}`, `§4`).
