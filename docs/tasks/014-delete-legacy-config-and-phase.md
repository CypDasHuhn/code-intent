---
name: delete legacy config and phase
status: done
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

## Outcome (2026-10-20)

The legacy path is gone: `intents.yaml`, `rules/Blocks.kt`, `Phase`/`runPhase`,
`AnalysisEngine`, `IntentBlocks`, `loadIntentConfig`, the legacy `RuleBlock`
surface and the legacy `SplitAnalyzer` overloads were removed. Production and
tests no longer reference any of them. `IntentConfig` remains only as a typed,
config-driven carrier for validation params/purity. Behavior parity is frozen by
`LegacyParitySnapshotTest` using pre-014 (3f20435) snapshots over all 11 fixtures
including Vue (`m008`, verified); `m001` remains an unverified CLI-level run.
README and IntentDefinition dead members were cleaned up. Build, engine tests,
renderer tests and install are green.
