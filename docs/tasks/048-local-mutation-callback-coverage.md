---
name: local mutation and callback effect coverage
status: done
depends-on: [040, 046]
reviewers: [correctness, detection-fidelity]
---

## Goal

Make `LOCAL_VALUE_MODIFY` coverage more consistent for mutable local state and
callback-style side effects without painting non-writes.

The motivating range is `NeighboursCli.publish`: local `var` declarations,
assignments like `jsonTemp = stage(...)`, and callback calls like
`move(jsonTemp, out)` currently get mixed treatment.

## Scope

- Decide and implement mutable-local declaration coverage: `var`/mutable locals
  with later writes may emit `LOCAL_VALUE_MODIFY` on the declaration (config
  toggle, default chosen from snapshots/tests), while `LOCAL_COPY` suppression
  keeps working.
- Classify calls to function-typed parameters/callbacks used for effects:
  - candidate/`SERVICE_DELEGATION` for generic callback invocation;
  - `DATA_MODIFY` when the callback's default/known implementation is a durable
    operation (for example `Files.move`), if that can be derived without
    whole-program dataflow.
- Keep loop counters, bookkeeping, pure copies, and ordinary non-writes
  suppressed.
- Tests over a `NeighboursCli.publish`-shaped fixture and focused declaration/
  callback cases.
- Update snapshots and docs.

## Acceptance

- All assignment writes in the motivating range are consistently tagged.
- Mutable declaration policy is explicit and tested; it does not flood ordinary
  `val` declarations.
- A callback call whose known/default implementation is durable is either
  `DATA_MODIFY` or a documented candidate; generic callbacks are not silently
  painted as local mutations.
- Loop counters/bookkeeping/pure copies stay suppressed.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Whole-program alias/dataflow or a general purity engine.
- A new role.
- Treating every function-parameter call as an effect.

## Notes

- Current build already tags the assignment lines in the motivating range; the
  open lines are mutable `var` declarations and the two `move(...)` calls.
- Prefer explicitness: if declaration tagging is noisy, make it candidate-only
  or config-gated rather than silently widening the established set.
