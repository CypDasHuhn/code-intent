# V2 Renderer IPC Protocol

The published external contract, compatibility matrix, and JSON Schema live in
[`docs/ipc/PROTOCOL.md`](../../docs/ipc/PROTOCOL.md) and
[`ipc/protocol.schema.json`](protocol.schema.json). This file documents the
engine-side implementation.

The renderer boundary is NDJSON: one UTF-8 JSON-RPC 2.0 object per line. Blank
lines are ignored. `NdjsonFrameDecoder` accepts arbitrarily fragmented reads,
rejects malformed UTF-8, handles CRLF, resynchronizes after an oversized frame,
and treats a trailing line without a newline as a complete frame at EOF.

`IpcConnection` is the connection layer for the V2 server. It owns handshake,
request-id uniqueness, method routing, explicit structured errors, and
disconnect state. Handlers implement `IpcHandler`; the connection never imports
runtime, index, parser, or server classes.

## Handshake

`initialize` is the first message. The current protocol is 5 and requires
`InitializeParams.fingerprint` to equal `ProtocolFingerprint.CURRENT`.
Protocols 3-4 are accepted without a fingerprint so frozen V1 clients can
connect; protocols 1-2 are rejected. `initialize` must include `protocol`
explicitly and omission fails with `data.kind = "missing-protocol"`. A fingerprint mismatch fails with `-32000` and
`data.kind = "fingerprint-mismatch"`; a version outside
`ProtocolCompatibility.supportedVersions` fails with
`data.kind = "unsupported-protocol"` and the supported range in the message and
details. Any request before initialize fails with `-32002`; a second initialize
fails explicitly.

## Errors

Errors use standard JSON-RPC codes (`-32700`, `-32600`, `-32601`, `-32602`,
`-32603`) plus protocol codes (`-32000`, `-32002`, `-32003`, `-32004`) and a
structured `data` object containing `kind` and optional `details`. Invalid
JSON, invalid envelopes, invalid params, unknown methods, duplicate ids, and
closed connections all fail explicitly. The connection never exposes runtime
internals.

## V2 shapes and intentional V1 differences

The method names remain the renderer-facing V1 names. The frozen V1 transcript
envelopes, method ordering, and request ids are accepted unchanged. The decided
differences from `frozen-v1-corpus/known-differences.json` are represented by
the V2 DTOs:

- `PublishTagsParams`: `configRevision` + `analysisVersion` + `diagnostics`
  (V1 `revision` + `analysisRevision` + no diagnostics).
- `PublishCandidatesParams`: unified `proposals` + `proposalRevision`
  (V1 `groups` + `candidates` + `suggestionRevision`).
- `IpcAnnotation` keeps the flat span and adds diagnostics, populated
  `nodeAnchor`, `provenance.ruleId`, `provenance.packId`, and optional evidence.
- `IpcCandidate` uses `origin`, `category`, `fingerprints`, and `operations`
  instead of V1 `source`, `reason`, and `aiEligible`.
- Suggest requests use an optional `selection` span; stream and history methods
  are additive.
- Config is JSON-first (`ConfigResult.config` + `sources`) with write and schema
  operations.
- `cint/status` is a real V2 method instead of V1's unknown-method error.
- Errors carry structured `data.kind`/`data.details` instead of only
  `{code, message}`.

Protocol version is pinned to 5. `ProtocolCompatibility.matrix` is the
authoritative compatibility matrix, and `ipc/protocol.schema.json` is generated
from the wire DTOs by the `:ipc:generateProtocolSchema` task. Ticket 086 owns
the external versioning policy.

## Round 2 clarifications

- The connection serializes `feed`/`finish`/`disconnect`; state is thread-safe, and
  handlers run under that connection lock. Callers should still prefer one event loop.
- `disconnect()` finishes the decoder first, so a complete trailing line without a
  newline is processed before the connection is marked closed.
- Duplicate request ids are checked against the in-flight set and a bounded recent
  set of 256 successfully routed ids. Failed routing (unknown method, pre-handshake,
  fingerprint mismatch) does not consume an id, so clients may retry it.
- Notifications never receive JSON-RPC replies. Unknown or pre-handshake
  notifications are dropped rather than answered with an uncorrelatable null id.
- `shutdown`, `cint/status`, and `cint/metrics` decode a strict empty-params DTO.
- Legacy protocol versions 3–4 are accepted without a fingerprint and are exposed
  via `IpcConnection.negotiatedProtocol`/`isLegacy` and `ProtocolCompatibility`;
  protocol 5 requires the current fingerprint. Handlers emit the negotiated shape.
- `InitializeResult` retains the V1 `root`, `languages`, `config`, and `suggestions`
  fields and adds `fingerprint`, `server`, and an object-valued `capabilities`.
- `cint/restrictions` is routed alongside `cint/restrictions/list`. Restrictions are
  typed DTOs preserving V1 add/remove/status/symbol/unitFingerprint/provider/model
  fields and adding `scope`/`condition`; list/remove results are typed.
- Candidate operations preserve V1 `intent`, `source`, and `rationale` while also
  carrying V2 `intents` and `origin`.
- `ContractIpcAdapter`/`ContractIpcTransport` explicitly bridge the contracts
  `IpcTransport` port. `IpcMessage.Wire` carries raw JSON-RPC losslessly; the other
  contract DTOs map to typed V2 wire messages.
- `ProtocolFingerprint` derives its canonical schema from every wire DTO serializer,
  including initialize, suggest, config, status, restrictions, and diagnostics.
  Each field records its type, enum/kind, optionality, nullability, and actual
  canonical default value, so default changes rotate `CURRENT`.

## Server wiring

The V2 server binds `analyze`, `textDocument/didOpen`, `textDocument/didChange`,
`textDocument/didClose`, `cint/status`, and `cint/metrics` to `runtime`.
Initialize reads the runtime pack catalog through `Runtime.packs`. Open-document
analysis uses `Runtime.analyzeSource` with an in-memory source, per-document
generations, serialized runtime access, bounded cancellation, and monotonic
publish revisions. Explicit analyze re-runs and republishes before its response;
legacy protocol 4 receives V1 publish and analyze shapes. Runtime annotation
sources are mapped to `treesitter`, `heuristic`, `annotation`, `cfg`, or `ai`.
Config, suggest, restrictions, and `cint/candidates/decide` return a structured
`unsupported-operation` error with the requested method in `data.details` until
runtime exposes those surfaces. Unsupported operations, source vocabulary, and
legacy pack field descriptors are recorded in `known-differences-v2.json`.

## V2-owned difference registry

`ipc/known-differences-v2.json` is the machine-readable V2-owned registry for
renderer differences that cannot modify or rehash the frozen V1 corpus. It is
validated by the `ipc` tests and is distinct from
`frozen-v1-corpus/known-differences.json`, which remains read-only V1-era
comparison data and must not be edited or rehashed. The V2 registry
registers the initialize additions (`fingerprint`, `server`, object-valued
`capabilities`), the legacy protocol range, operation fields, and
`cint/candidates/decide`, alongside the publish, annotation, candidate,
suggest, config, restrictions, status, error, and fingerprint differences.
`ProtocolRegressionTest` asserts every entry is present and each documented
field is represented by the wire DTOs or fingerprint schema.

Legacy negotiation is enforced in `IpcConnection`: when the negotiated protocol
is below 5, the initialize result has its protocol rewritten to the negotiated
version and V2-only `fingerprint`/`server`/`capabilities` fields removed.
`IpcJson.encodeLegacy` uses `encodeDefaults=false`/`explicitNulls=false` so
legacy-shaped DTOs do not gain V2 defaults or nulls.
