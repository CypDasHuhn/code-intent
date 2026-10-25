# 086 API Fidelity Review

Verdict: pass with follow-ups.

Verified:
- Explicit current version 5 is published in `ProtocolFingerprint`, `protocol.schema.json`, `docs/ipc/PROTOCOL.md`, and the renderer pin.
- Compatibility matrix is consistent across `ProtocolCompatibility`, schema `x-compatibility`, and external docs. Legacy 1-4, current 5.
- Schema is generated from all wire DTO serializers; `ProtocolSchemaTest` checks drift, version, fingerprint, matrix, methods, and key types.
- `ProtocolCompatibilityTest` handshakes every supported version, rejects 0/6 as `unsupported-protocol`, and rejects missing/stale fingerprints on 5 as `fingerprint-mismatch`. Live renderer tests cover the real server handshake.
- Client rejection uses `-32000` with structured `data.kind`; no internals leak.
- Renderer pins version 5 and fingerprint `38959a7c...ca88`, matching the schema; `protocol_spec.lua`, `compat.sh`, and `v2_server_spec.lua` test it.

Silent compatibility gaps:
1. `InitializeParams.protocol` is optional and defaults to legacy 4 (`ProtocolMessages.kt:494`). A client omitting `protocol` is silently downgraded to V1 shapes instead of rejected. The schema marks it optional but publishes no default. For an "explicit" version contract, require `protocol` or explicitly document and test the default.
2. The matrix collapses versions 1-4 into one legacy path. Tests verify handshake shape only, not version-specific method behavior. Frozen V1 transcripts contain protocol-3-specific gating, so the claim that each legacy version behaves as V1 is not fully evidenced.
3. `supportedVersions = 1..CURRENT` auto-accepts any future version once the constant increments. A version bump without a new matrix entry would silently accept it.

No source changes made.

## Round 2

Verdict: CONCUR.

All prior gaps are closed:
- `InitializeParams.protocol` is required; omission/null returns `-32000` `missing-protocol` and leaves the connection uninitialized.
- Supported matrix is explicit `[3, 4, 5]`; 1-2 and 6+ are rejected as `unsupported-protocol`. Live tests verify protocol-3 vs protocol-4 capability flags.
- Schema publishes required `protocol`, `x-requests`, both notification directions, matrix, and fingerprint; drift tests cover them.
- External docs state the required handshake and no silent legacy default.
- Renderer pin was updated to protocol 5 and fingerprint `ce1e3590...48b5`, matching the schema, with supported `{3,4,5}`.

No remaining silent compatibility gap found. No source changes made.
