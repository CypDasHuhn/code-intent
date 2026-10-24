---
name: Version the external renderer protocol
status: todo
depends-on: [085]
reviewers: [correctness, api-fidelity]
---

## Goal

Turn the renderer IPC into a versioned contract suitable for clients in separate repositories.

## Scope

- Explicit protocol version and compatibility rules.
- Schema publication or generated client types.
- Engine-side compatibility tests for supported versions.
- Handshake failure behavior.

## Acceptance

- An editor client can pin a protocol version.
- The engine rejects incompatible clients with a clear error.
- The protocol is documented outside the engine internals.

## Out of scope

- Moving the renderer to another repository.

## Notes

- Required before editor extraction; not required during the V2 PoC.
