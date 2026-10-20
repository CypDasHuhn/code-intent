---
name: simplify error-handling and validation packs
status: todo
depends-on: [020]
reviewers: [correctness, detection-fidelity]
---

## Goal

Move error-handling and validation onto the support layer.

## Scope

- Error handling: configured calls/qualifiers, throws, trailing lambdas via
  call/emit helpers.
- Validation: guard/predicate/purity/comment certificate via guard/condition
  facts and emit helpers.
- Remove duplicated traversal and config boilerplate.
- Preserve parity; document any deliberate widening.

## Acceptance

- Packs use only `View` facts/config/emit builders.
- No grammar node kinds or manual scope walking in these packs.
- `just test-engine`, `just lint` green.
