---
name: engine module layout
status: done
depends-on: [024, 027]
reviewers: [correctness, architecture]
---

## Goal

Reduce `analysis-engine` root to <=8 entries by moving Gradle modules under
`analysis-engine/modules`, while keeping project names/tasks stable.

## Acceptance

- `analysis-engine` root has <=8 entries under dir-size rules.
- `./gradlew` task names unchanged; build/install/test/lint/nvim pass.
- All path references (justfile, docs, README, CI) updated.

## Outcome (2026-10-20)

Gradle modules moved under `analysis-engine/modules/{core,languages,hosts,tests}`
with project names/tasks unchanged and settings.gradle.kts authoritative.
Engine root is 7 entries; no analysis-engine dir is OVERCROWDED. Structural test
derives module roots from settings and enforces `modules/<group>/<name>`. Engine
README moved to `docs/engine.md` with corrected paths. Reviewer concurs;
build/test-engine/lint/test-nvim/install pass.

## Update (2026-10-21)

The intermediate `analysis-engine/modules/` layer was removed. Groups now live at
`analysis-engine/{core,languages,hosts,tests}`. The engine root intentionally has
10 entries and is accepted as `OVERCROWDED` under the current threshold.
