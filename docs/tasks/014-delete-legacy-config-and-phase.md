---
name: delete legacy config and phase
status: todo
depends-on: [010, 013]
reviewers: [correctness, architecture]
---

## Goal

Delete the legacy path now that rulepacks, scheduler, composition and config have
replaced it (architecture.md §11.6): `intents.yaml`, the `Phase` enum, and
`rules/Blocks.kt`.

## Scope

- Remove `core/src/main/resources/intents.yaml` and `IntentConfig`'s loader for
  it.
- Remove `enum class Phase` and the `runPhase` machinery in
  `core/.../engine/Engine.kt`; strata replace phases.
- Remove `rules/Blocks.kt` (all detection lives in rulepacks; composition in
  core composition).
- Remove now-dead plumbing and imports; delete `:core`'s hardcoded detection
  entry points.

## Acceptance

- No reference to `intents.yaml`, `Phase`, or `rules/Blocks.kt` remains.
- Engine behavior is unchanged from 013 + 010.
- `just build` and `just test-engine` green.

## Out of scope

- Rewriting `intent-model.md` / `notes.md` (historical reference).

## Notes

- Confirms README #5: the legacy path is deleted here, after composition and
  config land, rather than immediately at step 3.
