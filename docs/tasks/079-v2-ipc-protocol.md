---
name: V2 renderer IPC protocol
status: todo
depends-on: [065, 066]
reviewers: [correctness, api-fidelity]
---

## Goal

Implement the NDJSON renderer protocol for V2 while preserving the frozen V1 transcript behavior.

## Scope

- Message DTOs and framing for analyze, publish, suggest, config, and status.
- Handshake and fingerprint.
- Invalid-message, partial-read, and disconnect handling.
- Transcript tests from 066.

## Acceptance

- Protocol transcripts match the frozen V1 behavior or documented diffs.
- Runtime internals are not visible in IPC DTOs.
- Malformed input fails explicitly.

## Out of scope

- Server app, MCP, and external version matrix.

## Notes

- IPC is the renderer boundary, not the AI boundary.
