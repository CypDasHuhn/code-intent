---
name: validation rulepack
status: todo
depends-on: [003, 004, 005, 008]
reviewers: [correctness, detection-fidelity]
---

## Goal

Re-express validation detection as a bundled rule that runs in stratum 1 because
it `reads = {BOOKKEEPING}` (architecture.md §4), replacing the current
`ValidationBlock`/`ReturnBlock` source path.

## Scope

- Recognise a then-branch with a direct `return` / `continue` / `break`.
- Tag the guard `VALIDATION` when one of: the branch contains a `BOOKKEEPING`
  write; the condition is a validation predicate (`isEmpty`, `isBlank`, `none`,
  `any`, `all`, `isValid*`, `has*`, ...); the condition is side-effect-free; or
  `// cint:validation` precedes the guard.
- Contribute an ambient `VALIDATION` scaffold over the guard so plumbing locals
  read inside become `DERIVATIVE_USE` with `VALIDATION`.
- `return false` and bare `return` do not make a scaffold `UNTAGGED`.
- Keep the current behavior or an explicitly approved narrowing.

## Acceptance

- Parity with the current `ValidationBlock`/`ReturnBlock` behavior over the
  fixtures, or a documented, approved narrowing.
- The rule declares `reads = {BOOKKEEPING}`; the scheduler places it in stratum 1.
- Pure rule, no store mutation.
- `just test-engine` green.

## Out of scope

- Candidate/established states and occurrence persistence (`§10`).
- Solving the validation-vs-cache-hit-vs-found-value gradient.

## Notes

- The known problem (side-effect-free conditions are too broad; the gradient
  cannot be drawn with local shape heuristics) stays deferred per `intent-model.md
  §2` and `§10`.

## Contract note (2026-10-20)

The validation pack needs generic syntax facts that the frozen `View` did not
expose. The following are additive `api` members, pinned by a contract test and
gated by 011's `CINT_API_VERSION = 1` compatibility check:

- `View.isConstant(node)`
- `View.isSideEffectFree(node)`
- `View.statements(node)`
- `View.isExit(node)`

External `View` implementations must implement these members. No removal or
behavioral change to existing members is involved.
