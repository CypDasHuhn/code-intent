# Renderer and Live Protocol

## Repository boundary

The Neovim client lives in the external sibling repository `code-intent-nvim`.
This engine repository contains no Neovim code and runs no renderer tests. The
canonical external contract is [`../ipc/PROTOCOL.md`](../ipc/PROTOCOL.md); the
engine-side implementation notes are
[`../../analysis-engine/ipc/PROTOCOL.md`](../../analysis-engine/ipc/PROTOCOL.md).

The external repo owns the plugin entry points, commands, render handlers,
profiles/shards, hover, review UX, and its headless Neovim test suite. It pins
the engine protocol version and schema fingerprint, installs the engine with
`just install`, and tests against the installed `cint-server`.

## Versions

The current protocol is **5**. Protocols 3-4 are legacy replay versions; 1-2 are rejected. The
engine publishes `ProtocolCompatibility.matrix` and
`analysis-engine/ipc/protocol.schema.json`; the fingerprint is derived from all
wire DTOs. Protocol 5 clients must send the exact current fingerprint during
`initialize` or the engine returns `-32000` with
`data.kind = "fingerprint-mismatch"`. Versions outside the supported range fail
with `data.kind = "unsupported-protocol"`.

The renderer checks the reported protocol and fingerprint on connect. A
mismatch is an explicit error rather than partial rendering. Its
`scripts/compat.sh` performs the cross-repository check against the installed
engine and reports expected/actual values.

## Methods

Transport is NDJSON JSON-RPC over a Unix socket. Methods are `initialize`,
`shutdown`, `cint/analyze`, `cint/publishTags`, `cint/publishCandidates`, the
`cint/suggest*` family, the `cint/config/*` family, `cint/status`,
`cint/metrics`, `cint/restrictions*`, `cint/candidates/decide`, and the
`textDocument/didOpen|didChange|didClose` notifications. The engine currently
returns structured `unsupported-operation` errors for config, suggest,
restrictions, and candidate decisions until runtime exposes those surfaces.

See the protocol documents for payloads, errors, revisions, and the intentional
V1/V2 differences in `analysis-engine/ipc/known-differences-v2.json`.

## Renderer tests

Renderer tests run in `code-intent-nvim`:

- `tests/run.sh` runs the headless suite.
- `tests/protocol_spec.lua` pins version/fingerprint compatibility.
- `tests/v2_server_spec.lua` spawns the installed `cint-server` and verifies the
  handshake.
- `scripts/compat.sh` makes cross-repo pin failures explicit.
