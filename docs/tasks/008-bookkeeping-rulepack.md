---
name: bookkeeping rulepack
status: done
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

## Outcome (2026-10-20)

Bundled `bookkeeping` pack owns `BOOKKEEPING` and detects annotation/comment
declarations plus write inheritance. Declaration ascent uses kind-based wrapper
nodes rather than a text-suffix heuristic, fixing cross-declaration leakage and
multi-declarator loss (C#/JS/TS). Reads stay untagged and explicit declarations
remain the only source. Parity and regression tests cover consecutive
declarations, control bodies and multi-declarators; both reviewers concur.
