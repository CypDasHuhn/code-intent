# 079 correctness review

## Round 1

Verdict: **FINDINGS**

1. `analysis-engine-v2/ipc/src/test/kotlin/dev/cint/ipc/FrozenTranscriptTest.kt:30-94,97-159` — Ordering, IDs, and V1 values are genuinely asserted, but every step only round-trips through `JsonRpcCodec`; no request reaches `IpcConnection` or `InitializeParams`. The corpus's `protocol-error-flow` initializes with protocol 3, which `IpcConnection.kt:268` rejects, yet the test cannot notice. Fix: replay each step through a fake handler and compare outputs to the transcript/allow-list.

2. `analysis-engine-v2/ipc/src/main/kotlin/dev/cint/ipc/IpcConnection.kt:110-123` — `disconnect()` calls `decoder.finish()` and discards its frames, dropping a complete trailing line without a newline (contradicting `PROTOCOL.md:5-6`) and any unterminated oversized-frame error. Fix: process `decoder.finish()` before marking the connection closed.

3. `IpcConnection.kt:91-108` — The decoder buffer, `seenRequestIds`, `pendingRequestIds`, `initialized`, and `closed` are unsynchronized; concurrent `feed`/`disconnect` can interleave bytes or lose visibility. Fix: synchronize the connection or confine it to one event-loop thread and document that contract.

4. `IpcConnection.kt:92,157-166` — IDs are retained for the connection lifetime and inserted before method/handshake validation. Failed fingerprint, unknown-method, and handshake-required requests consume the ID, so a retry becomes a duplicate-id error; the set also grows without bound. Fix: record IDs only after successful routing in a bounded/recent-ID structure, or scope IDs to request lifetime.

5. `IpcConnection.kt:233,245-246` — `shutdown`, `status`, and `metrics` never decode params, so unknown fields and wrong parameter shapes succeed, unlike analyze/config and the module-graph hard-error rule. Fix: add strict empty/params DTOs and decode them before dispatch.

6. `IpcConnection.kt:196-207` — Unknown or pre-handshake notifications return an `ErrorResponse` with a null ID. JSON-RPC 2.0 forbids replies to notifications, and such replies cannot be correlated. Fix: drop/log them, or document the deviation and ensure callers ignore null-ID errors.

7. `analysis-engine-v2/ipc/src/main/kotlin/dev/cint/ipc/ProtocolMessages.kt:128-152` (also 103-126, 173-192, 194-221) — Flat span fields on `IpcAnnotation`, `IpcProvenance`, `CandidateOperation`, and `IpcCandidate` are not validated; negative or start-after-end spans deserialize successfully. Only `IpcSpan` enforces invariants. Fix: validate the computed `span` in each `init`.

8. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/ports/Transports.kt:10-25` versus `IpcConnection.kt:7-10,25-45` and `ProtocolMessages.kt:423-437` — The port requires `contracts.ipc.IpcMessage`, but the connection uses a separate `JsonRpcMessage`/DTO family with divergent handshake, config, and status types. `IpcConnection` cannot implement `IpcTransport`, and the schemas can drift silently. Fix: implement the port over one DTO set or add an explicit adapter and remove the duplicate definitions.

Summary: NDJSON framing, UTF-8 handling, oversize resynchronization, JSON-RPC field rejection, fingerprint mismatch reporting, and request/response correlation are implemented cleanly, and the frozen transcript test does assert corpus ordering and values rather than merely parsing. The remaining correctness gaps are lifecycle handling at disconnect, unsynchronized connection state and ID bookkeeping, strictness holes for parameterless methods, JSON-RPC notification behavior, span validation, and the parallel contract/implementation message models. The frozen test's inability to exercise `IpcConnection` is the main reason these divergences currently pass unnoticed.

## Round 2

1. CONCUR — `FrozenTranscriptTest.kt:97-118` now replays every frozen request through `IpcConnection`; protocol 3 is accepted (`IpcConnection.kt:291,376`). Caveat: it replays recorded handler responses, so it proves routing/ordering, not handler semantics.
2. CONCUR — `IpcConnection.kt:139-154` flushes `decoder.finish()` before closing.
3. CONCUR — `IpcConnection.kt:128-139` serializes `feed`/`finish`/`disconnect`; lifecycle fields are volatile.
4. CONCUR — `IpcConnection.kt:109-110,187-224` uses in-flight plus bounded recent IDs; failed routing no longer consumes them (`IpcConnectionTest.kt:225-242`).
5. CONCUR — `IpcConnection.kt:246-268` decodes strict `NoParams`.
6. CONCUR — notifications are dropped (`IpcConnection.kt:228-238`); their errors are now invisible without logging.
7. CONCUR — flat-span validation was added to each `init` (`ProtocolMessages.kt:121-127,147-154,193-199,221-229`).
8. DISSENT — the port is bridged, but mapping is wrong: `ContractIpcTransport.kt:60-71` writes line/column values as byte offsets and zeroes revisions; `:35-36` treats default `Handshake.schemaVersion=1` as the protocol; `:150-156` cannot decode V2 `AnalyzeResult` into contracts `AnalysisResult`, so responses degrade to `Wire`; `:119-120,244-250` leaves `pending` after write failure and lets adapter/encode exceptions escape `send`. `pending` is also unsynchronized.

New regressions are the adapter defects above. Six findings are resolved; finding 8 needs correction before the port is usable.

## Round 3

CONCUR — all Round 2 adapter defects are fixed. `ContractIpcTransport.kt:63-92` now maps `PublishRequest` fields and `PublishedAnnotation.startByte/endByte` explicitly, rejecting missing values; test `:101-166`. `requestedProtocol` (`:141-145`) uses the explicit handshake protocol and defaults schemaVersion 1 to protocol 5; acknowledgements set `negotiatedProtocol` from the actual response (`:435-436`; test `:74-98`). `AnalyzeResult.toContracts` (`:244-337`) maps files, candidates, annotations, provenance, and diagnostics, and invalid wire results raise `IpcAdapterException`, converted to `SerializationError` in `receive` (`:430-434`; test `:182-265`). `send` removes pending on both adapter and write failures (`:392-402`), pending access is synchronized (`:373,392,397,400,426,443`), and adapter/encode exceptions are contained in `send`/`receive`. No remaining Round 2 defect found.
