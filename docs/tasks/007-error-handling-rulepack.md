---
name: error-handling rulepack
status: done
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

## Outcome (2026-10-20)

Bundled error-handling packs (`ErrorHandlingRulePack.kt`) use language-scoped
pack/rule ids with `selection(language)` so a usable pack set schedules without
duplicate ids. Detection matches legacy: configured calls, receiver qualifier
gating (`Debug`/`Trace`/`Contract` for C#), throws, trailing lambdas, and
per-language defaults. Mixed-language co-load deliberately reports
`DuplicateIntentOwner`. Parity and Vue/assert/detectThrows cases covered; both
reviewers concur.
