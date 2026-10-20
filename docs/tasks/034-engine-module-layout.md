---
name: engine module layout
status: in-progress
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
