---
name: config migration off intents.yaml
status: todo
depends-on: [006, 007, 012]
reviewers: [correctness, detection-fidelity]
---

## Goal

Move the detection values in `core/src/main/resources/intents.yaml` (methods,
factories, receiver names, qualifiers, imports, `params`, `overrides.<language>`)
into the owning packs' typed config, preserving current behavior
(architecture.md §11.5).

## Scope

- Enumerate every key in `intents.yaml` and assign it to the pack that owns it.
- Reproduce current detection from pack defaults; `overrides.<language>` becomes
  pack config rather than a global override map.
- Keep the old file loadable only as long as needed to prove parity; it is
  removed in 014.

## Acceptance

- Behavior parity with the current engine over the fixtures and goldens after
  migration.
- No detection value remains global that a pack should own.
- `intents.yaml` no longer drives detection (only parity scaffolding remains).
- `just test-engine` green.

## Out of scope

- Deleting the old loader (014).

## Notes

- Covers logging, error-handling, bookkeeping and validation values; validation
  predicates are validation-pack config.
