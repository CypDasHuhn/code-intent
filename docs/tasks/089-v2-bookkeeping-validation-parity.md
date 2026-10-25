---
name: V2 bookkeeping and validation parity
status: done
depends-on: [065, 068, 069, 070, 075]
reviewers: [detection-fidelity]
---

## Goal

Match V1 declaration/intent semantics for bookkeeping and validation.

## Scope

- @Bookkeeping and `cint:bookkeeping` declaration spans, writes, sources, and confidence.
- Validation rejection, predicate, comment, bookkeeping gating, and rejection-value handling.

## Acceptance

- Bookkeeping and validation defects in the 084 parity report are gone.
- Full CLI JSON annotations match the frozen corpus for those rules.
