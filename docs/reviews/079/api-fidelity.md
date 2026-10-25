# 079 api-fidelity review

Verdict: **FINDINGS**

1. `analysis-engine-v2/ipc/src/main/kotlin/dev/cint/ipc/ProtocolMessages.kt:431` — `InitializeResult` drops V1 `root`, `config`, and `suggestions` and adds `fingerprint`, `server`, and `capabilities`. The renderer still consumes these fields (`frozen-v1-corpus/renderer-visible-v1.json:1256`), but the only related allow-list entry is tentative and covers `suggestions` alone (`frozen-v1-corpus/known-differences.json:47`). `KNOWN_DIFFERENCES.md` also describes `capabilities` as an object, while the DTO is `List<String>`. Fix: restore the V1 fields (or add decided allow-list entries for each), reconcile the documented `capabilities` type, and register `fingerprint`/`server`.

2. `analysis-engine-v2/ipc/src/main/kotlin/dev/cint/ipc/IpcConnection.kt:268` — Legacy negotiation is a silent incompatibility. Protocol 4 is accepted without a fingerprint, yet the connection still emits protocol 5/V2 payloads and `InitializeResult` always reports protocol 5; protocol 3 is rejected outright, so `protocol-error-flow` (whose initialize uses protocol 3) cannot replay. No allow-list entry covers the accepted request range or legacy wire behaviour. Fix: either reject protocol 4 unless a V1 encoder exists, or track the negotiated protocol and emit matching shapes; add a machine-readable difference for the legacy range.

3. `ProtocolMessages.kt:28` — The V2 contract documents `cint/restrictions` GET-style (`known-differences.json:170`), but no such method exists; only `cint/restrictions/list` is routed. Fix: add/dispatch `cint/restrictions`, or correct the contract to the implemented name.

4. `ProtocolMessages.kt:386` — Restriction payloads are untyped escape hatches: list/remove take only `uri`/`id`, upsert takes an arbitrary `JsonObject`, and no restriction or list/remove result DTO exists. Renderer-critical V1 fields (`add`, `remove`, `status`, `unitFingerprint`, spans, provider/model) are neither modelled nor allow-listed. Fix: define typed restriction DTOs preserving those fields and register the intentional additions (`scope`, `condition`).

5. `ProtocolMessages.kt:174` — `CandidateOperation` changes renderer-visible operation fields: V1 uses singular `intent` plus `source` and optional `rationale`, V2 uses `intents` plus `origin` and has no `rationale`. `known-differences.json` mentions only `operations` by name (lines 123, 145, 218), not this field remap. Fix: document the exact operation mapping/allow-list or preserve V1 fields.

6. `ProtocolFingerprint.kt:9` — The fingerprint hashes only method names and publishTags/publishCandidates/annotation/candidate/error fields. Initialize, suggest results, config, status, restrictions, and diagnostics are absent, so incompatible servers can share a fingerprint. Fix: derive the canonical schema from every wire DTO (including these payloads).

7. `analysis-engine-v2/ipc/src/test/kotlin/dev/cint/ipc/FrozenTranscriptTest.kt:97` — Tests assert frozen V1 shapes and hand-built V2 DTOs but never feed frozen requests through `InitializeParams`/`IpcConnection` or verify every V2 output delta is allow-listed. Fix: add a compatibility test that replays each frozen request and asserts each response difference matches a known-differences entry.

Status, metrics, config read/write/schema, publish field renames, structured errors, and NDJSON framing match the documented contract. The core gap is that several renderer-critical changes (initialize result, legacy protocol handling, restrictions, operation fields) are not registered in the machine-readable allow-list, so "intentional differences" cannot be mechanically distinguished from regressions.

## Round 2

1. CONCUR — `InitializeResult` restores `root`/`languages`/`config`/`suggestions` and makes `capabilities` an object (`ProtocolMessages.kt:499`). Residual: `fingerprint`/`server`/`capabilities` still have no `known-differences.json` entry; only tentative `initialize.suggestions` exists (`known-differences.json:47`).
2. CONCUR — protocols 1–4 negotiate without fingerprint and expose `negotiatedProtocol`/`isLegacy` (`IpcConnection.kt:121`, `:291`); protocol 3 now replays. Residual: `InitializeResult.protocol` still defaults to 5 (`ProtocolMessages.kt:500`), so exact legacy output depends on handler override with no enforcement.
3. CONCUR — `cint/restrictions` is defined and routed with `cint/restrictions/list` (`ProtocolMessages.kt:28`, `IpcConnection.kt:269`).
4. CONCUR — typed restriction DTOs preserve V1 add/remove/status/symbol/unitFingerprint/provider/model fields and add scope/condition (`ProtocolMessages.kt:407`).
5. CONCUR — operations retain `intent`/`source`/`rationale` (`ProtocolMessages.kt:189`). Residual: `encodeDefaults`/`explicitNulls` (`:515`) still emit V2 `intents`/`origin` when that DTO is serialized, so exact legacy output requires raw JSON.
6. CONCUR — fingerprint now enumerates all wire serializers (`ProtocolFingerprint.kt:14`). Residual: `descriptorSchema` records names/types only (`:83`), not optionality/nullability/defaults, so those contract changes do not rotate it.
7. CONCUR — replay and decided-difference coverage tests added (`FrozenTranscriptTest.kt:97`, `:121`). Residual: coverage ignores tentative entries, so added initialize fields remain unchecked.

Gaps: register initialize additions; include optionality in fingerprint; register `cint/candidates/decide` (`ProtocolMessages.kt:32`), absent from known-differences and PROTOCOL.

Verdict: all seven findings CONCUR; close after registering initialize fields, hashing field optionality, and documenting `cint/candidates/decide`.

## Round 3

- Initialize additions, `cint/candidates/decide`, legacy range, and operation fields: CONCUR — registered in `ipc/known-differences-v2.json`; frozen corpus is untouched (`git status frozen-v1-corpus` clean).
- Legacy initialize preservation/encoder: CONCUR — `legacyInitializeResult` rewrites the negotiated protocol and strips V2 fields (`IpcConnection.kt:342`); `encodeLegacy` omits defaults/nulls (`ProtocolMessages.kt:517`); regression tests cover both.
- Fingerprint: DISSENT (partial). Enum/kind/optionality/nullability are included (`ProtocolFingerprint.kt:86`), but default values are still not hashed: the schema records only `isElementOptional`, never the default itself. Changing a default (for example `StatusResult.status`) would not rotate `CURRENT`. Fix: include canonical default values per element.
- Residual: `known-differences-v2.json` is a separate registry; `frozen-v1-corpus/known-differences.json` and `verify.py` do not reference it, so corpus comparison tooling will not consume these entries.

Verdict: CONCUR on all gaps except fingerprint default-value coverage, which remains an external-contract blind spot.

## Round 4

- Fingerprint defaults: CONCUR — canonical defaults are recorded per field (`ProtocolFingerprint.kt:60`), `fingerprintWithDefaults` is injectable (`:73`), and the regression test proves a `StatusResult.status` default change rotates the fingerprint (`ProtocolRegressionTest.kt:80`). Current DTO defaults are covered; only future non-generic defaults need updating the explicit map.
- Registry vs frozen corpus: CONCUR — `PROTOCOL.md:87` documents `known-differences-v2.json` as distinct from the read-only V1 corpus, and `frozen-v1-corpus` remains unmodified.

Verdict: all Round 3 gaps closed; no remaining external-contract findings.
