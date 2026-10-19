---
name: error-handling rulepack
status: todo
depends-on: [003, 004, 005]
reviewers: [correctness, detection-fidelity]
---

## Goal

Re-express error-handling detection as a bundled rule on the new API, replacing
the current `ErrorHandlingBlock`.

## Scope

- `throw`, plus the configured checks `require`, `requireNotNull`, `check`,
  `checkNotNull`, `error`, `TODO`, `assert`.
- A configured call with a receiver only when the receiver text is in the
  configured qualifiers (C# sets `Debug`/`Trace`/`Contract`); an arbitrary
  `receiver.Assert(...)` stays untagged.
- Trailing lambda spans are included.

## Acceptance

- Parity with the current `ErrorHandlingBlock` over the fixtures, including the
  qualified/unqualified receiver distinction and the C# profile.
- Pure rule, honest `reads`, no store mutation.
- `just test-engine` green.

## Out of scope

- Purity, composition.

## Notes

- Configuration values (methods, qualifiers) move to pack config in 013; keep
  them pack-local from the start.
