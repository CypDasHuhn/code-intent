---
name: V2 service init parity
status: todo
depends-on: [065, 069, 070, 075]
reviewers: [detection-fidelity]
---

## Goal

Match V1 service initialization detection.

## Scope

- Constructor injection and constructor parameter spans.
- Lifecycle function, connection, DI, and creation evidence.

## Acceptance

- The service-init defects currently assigned to this ticket in the 084 parity
  report are resolved.
- Service-init annotations and candidates match the frozen corpus.
