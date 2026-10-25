---
name: V2 live server app
status: done
depends-on: [078, 079]
reviewers: [correctness, api-fidelity]
---

## Goal

Provide the long-lived editor daemon over the V2 runtime and IPC protocol.

## Scope

- Unix-socket lifecycle and client sessions.
- Open-document state, analysis revisions, cancellation, and publish.
- Protocol 4-compatible renderer behavior from 066.

## Acceptance

- A scripted client completes analyze and publish over the socket.
- Stale versions and disconnects are safe.
- No analysis logic in the server app.

## Out of scope

- Renderer changes and MCP.

## Notes

- The executable is `cint-server`; the temporary V2 suffix was dropped at cutover.
