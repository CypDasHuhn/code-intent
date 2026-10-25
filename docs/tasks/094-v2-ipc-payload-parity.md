---
name: V2 IPC payload parity
status: done
depends-on: [065, 066, 079, 081]
reviewers: [correctness, detection-fidelity]
---

## Goal

Close the remaining full-payload IPC differences that are not covered by detection or composition tickets.

## Scope

- Legacy publish hashes/revisions and V2 message payload fields in frozen session replay.
- Any residual protocol payload mismatch surfaced by `ipc.payload` defects.

## Acceptance

- IPC defects assigned to this ticket are resolved or registered with transcript evidence.
- `just parity` regenerates the committed report with a reduced IPC baseline.
