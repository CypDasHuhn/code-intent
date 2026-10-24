---
name: V2 parity and integration suite
status: todo
depends-on: [066, 080, 081, 083]
reviewers: [correctness, detection-fidelity]
---

## Goal

Prove V2 against the frozen V1 corpus and the V2 protocol, CLI, server, and MCP surfaces.

## Scope

- Deterministic corpus parity with documented intentional differences.
- IPC transcript parity and renderer-visible behavior checks.
- CLI, server, and MCP integration tests.
- Performance and failure-path smoke checks.

## Acceptance

- Parity report identifies every intentional difference.
- All V2 integration suites pass on CI.
- No V1 module is required at runtime.

## Out of scope

- Cutover and renderer extraction.

## Notes

- Cutover is blocked by this ticket.
