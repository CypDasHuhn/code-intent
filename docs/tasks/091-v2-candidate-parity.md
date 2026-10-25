---
name: V2 candidate parity
status: todo
depends-on: [065, 070, 075]
reviewers: [detection-fidelity]
---

## Goal

Finish candidate identity, grouping, and emission parity.

## Scope

- Candidate spans, source/reason/confidence, mutation and service candidate branches.
- Candidate ordering and grouping behavior visible to the CLI and IPC.

## Acceptance

- Candidate defects in the 084 parity report are gone.
- Full candidate JSON matches the frozen corpus except registered intentional differences.
