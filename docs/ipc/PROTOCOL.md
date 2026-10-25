# CodeIntent Renderer IPC Protocol

This is the external contract between the CodeIntent engine and editor clients
such as the sibling `code-intent.nvim` repository. The engine implementation
notes are in
[`orchestration/ipc/PROTOCOL.md`](../../orchestration/ipc/PROTOCOL.md).

Transport is NDJSON: one UTF-8 JSON-RPC 2.0 object per line over a Unix socket.
Blank lines are ignored.

## Versions and compatibility

The current protocol is **5**. `ProtocolCompatibility.matrix` in
`orchestration/ipc` is the machine-readable source of truth and is published
in the schema (see below).

| version | status | fingerprint required | initialize shape | behavior |
|---|---|---|---|---|
| 3 | legacy | no | V1 | frozen replay; V2-only initialize fields are removed and handlers emit V1 shapes |
| 4 | legacy | no | V1 | frozen replay; same V1 shapes plus legacy restrictions/cache capability flags |
| 5 | current | yes | V2 | full V2 shapes; fingerprint must equal the published schema fingerprint |

Rules:

- Clients pin an integer protocol version and the matching schema fingerprint.
- Protocols 1-2 are not supported. The engine rejects versions outside
  `3, 4, 5` with JSON-RPC `-32000`,
  `data.kind = "unsupported-protocol"`, and the supported list.
- `initialize.params.protocol` is required. Omitting it or sending null fails
  with `-32000`, `data.kind = "missing-protocol"`, and an incompatible-handshake
  message. There is no silent legacy default.
- For the current protocol, a missing or stale fingerprint is rejected with
  `-32000`, `data.kind = "fingerprint-mismatch"`, with `expected` and `actual`.
- Adding, removing, or changing a wire field, enum value, method, or default
  rotates the fingerprint. That is a protocol change and requires a new version
  or an explicit compatibility decision.
- Legacy versions exist only for frozen V1 replay. New editor work targets the
  current version.

## Handshake

`initialize` must be the first request and must name its protocol. Clients on
protocol 5 send:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocol": 5,
    "fingerprint": "<x-schema-fingerprint from protocol.schema.json>",
    "client": { "name": "my-editor", "version": "1.0.0" },
    "capabilities": []
  }
}
```

The result contains `protocol`, `fingerprint`, `root`, `languages`, `config`,
`capabilities`, and `server`. Any request before initialize fails with `-32002`;
a second initialize fails explicitly. Notifications before initialize are
dropped because they have no correlatable reply.

## Published schema

[`orchestration/ipc/protocol.schema.json`](../../orchestration/ipc/protocol.schema.json)
is a checked-in JSON Schema (draft 2020-12) generated from every wire DTO. It
contains:

- `x-protocol-version`, `x-schema-fingerprint`, `x-supported-versions`
- `x-compatibility`: the same matrix shown above
- `x-requests`: client-to-server request methods
- `x-notifications.clientToServer`: client-to-server notifications
- `x-notifications.serverToClient`: engine notifications, including
  `cint/publishTags`, `cint/publishCandidates`, `cint/suggest/progress`, and
  `cint/error`
- `x-types` and `$defs`: per-type property shapes, required fields, nullability,
  optionality, enums, maps, and arrays. `InitializeParams.protocol` is required.

Regenerate after changing a wire DTO:

```sh
./gradlew :ipc:generateProtocolSchema
```

`ProtocolSchemaTest` fails if the checked-in schema drifts from the DTOs.
`ProtocolCompatibilityTest` exercises a handshake for every supported version
and the explicit failure paths. `ProtocolFingerprint` derives the fingerprint
from the same descriptor set.

## Requests and notifications

Client-to-server requests: `initialize`, `shutdown`, `cint/analyze`, the
`cint/suggest*` family, the `cint/config/*` family, `cint/status`,
`cint/metrics`, `cint/restrictions*`, and `cint/candidates/decide`.

Client-to-server notifications: `textDocument/didOpen`,
`textDocument/didChange`, and `textDocument/didClose`. Notifications never
receive a JSON-RPC reply.

Server-to-client notifications: `cint/publishTags`, `cint/publishCandidates`,
`cint/suggest/progress`, and `cint/error`. They are not routable client request
methods and are therefore published separately in the schema. See the engine
protocol notes and `orchestration/ipc/known-differences-v2.json` for
intentional V1/V2 shape differences.

## Errors

Standard JSON-RPC codes (`-32700`, `-32600`, `-32601`, `-32602`, `-32603`) and
protocol codes (`-32000`, `-32002`, `-32003`, `-32004`). Every protocol error
carries `data.kind` and optional `data.details`; clients must not parse error
message strings for control flow.

## Cross-repo compatibility

The Neovim renderer lives in its own repository and pins
`PROTOCOL_VERSION`/`PROTOCOL_FINGERPRINT`. Its `scripts/compat.sh` compares
those values with the engine's checked-in schema, starts the real installed
`cint-server`, and fails with expected/actual values on drift. The check uses
the sibling engine checkout and installed distribution because no engine release
artifact is published yet. Renderer install, development, and test instructions
live in that repository's `README.md`.
