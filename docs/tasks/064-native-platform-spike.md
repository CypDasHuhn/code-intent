---
name: Kotlin/Native platform spike
status: todo
depends-on: [063]
reviewers: [architecture]
---

## Goal

Prove the native-platform capabilities V2 depends on before adapters are scheduled.

## Scope

- Compile commonMain and nativeMain and link one native binary.
- Prove tree-sitter C interop with one grammar.
- Prove JSON encode/decode, Unix-domain sockets, child-process spawn, and HTTP/TLS.
- Record exact versions, blockers, and fallback options in docs/rewrite/native-spike.md.

## Acceptance

- A native binary parses one fixture through tree-sitter.
- The binary opens a Unix socket, spawns a child process, and performs one JSON round trip.
- Any HTTP/TLS limitation is documented with a concrete fallback.

## Out of scope

- Production language adapters.
- A complete LSP implementation.

## Notes

- Timebox this spike; do not turn it into the LSP client.
- Findings feed 072, 073, and 079.
