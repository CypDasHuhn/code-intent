# Correctness review — 073 LSP provider

Verdict: FINDINGS

1. `SemanticProvider.kt:9-15`, `LspSemanticProvider.kt:31-54` — didOpen/didChange/didClose exist only on the concrete class; the port exposes neither sync nor text/version, so index/runtime cannot push in-memory (dirty) text and requests fall back to `ensureOpen` reading disk. Extend the port with a document-sync method (or a separate sync port) and have the pipeline call it before requests.

2. `LspSession.kt:120-124` — `ensureOpen` only checks whether the URI is open, ignoring the requested version, so a request for version N can be served against older text. Compare the stored version and fail (`InvalidInput`) or re-sync instead of silently using stale content.

3. `LspSession.kt:102-109,145` — `changeDocument` never invalidates `pushedDiagnostics`; `diagnosticsFor` returns cached diagnostics from the previous version until the server publishes again. Clear the cache on open/change or key diagnostics by document version/resultId.

4. `LspSemanticProvider.kt:518-539,226-241` — pull diagnostics assumes `items`; LSP 3.17 `kind:"unchanged"` reports throw `LspResultException`/`SerializationError`. Track resultIds and map `unchanged` to cached diagnostics or an empty success.

5. `LspSession.kt:62-68`, `LspSemanticProvider.kt:411-424` — `sendNotificationInternal("initialized")` is unguarded. If the write fails, initialize throws out of `ContractResult`, and `startSession` never closes the session, leaking the managed process/threads. Wrap the notification and initialize body in try/catch; close the transport and return `DependencyUnavailable`.

6. `LspSemanticProvider.kt:90-101,244-252,313-326` — editor mode bypasses deadline/timeout entirely, ignores `hostChannelConfig`, and `shutdown()` discards `channel.close()`'s result and returns success. Enforce the request deadline (or document host ownership), construct/use the config channel, and propagate close errors.

7. `LspSession.kt:77-116,159-207,388-406` — document state is mutated outside `sendLock`, so a concurrent change can reach the server before didOpen; a request can register in `pending` after `close()` snapshots it, leaving it to time out. Serialize state plus notification per URI and make close/reject atomic with registration.

8. `LspSession.kt:287-294` — every JSON-RPC server error becomes `DependencyUnavailable`, including method-not-found, content-modified, and cancelled, so callers may treat a request error as provider death. Map codes to `NotFound`/`InvalidInput`/`Cancelled`/`InternalError`.

9. `LspSession.kt:93-109,408-430` — the client always sends full-text didChange and never reads the server's `textDocumentSync.change`; servers advertising incremental sync can reject or corrupt state. Honor the advertised sync kind or fail clearly.

10. `LspSemanticProvider.kt:31-38,396-409` — document sync defaults use raw `language`, not `config.languageOverrides`; session defaults honor overrides, so API-opened documents differ. Also `rootUri` is always null, so managed servers get no workspace root. Resolve the language once and derive/pass a root.

11. Tests (`LspSemanticProviderTest.kt:63-66`, `ScriptedLspServer.kt:13-21,203-212`) — diagnostics assertions always hit the pushed cache, so the pull path (including `unchanged`) is never exercised; `initDelayMs` is unused, so init timeout is untested; there is no didClose, concurrent-request, managed-endpoint, framing, or process-cleanup test. Add configurable publish/pull cases and lifecycle assertions.

Summary: JSON-RPC framing/Content-Length handling, request-id correlation, transport selection, basic lifecycle, and the absence of LSP protocol types in contracts look sound. The main correctness gaps are document sync being unreachable through the port, stale diagnostics/version handling, an initialize path that can throw and leak processes, editor-mode deadline/shutdown semantics, and document/close races; the test suite also leaves the pull-diagnostics, init-timeout, and cleanup paths unverified.

## Round 2

All round-1 findings CONCUR: 1 `SemanticProvider.kt:15-29`; 2 `LspSession.kt:163-188`; 3 `LspSession.kt:99,143,158,192-203,443-447`; 4 `LspSemanticProvider.kt:319-341` (tested `LspSemanticProviderTest.kt:588-612`); 5 `LspSession.kt:53-67`, `LspSemanticProvider.kt:561-606`; 6 `LspSemanticProvider.kt:144-174,343-362,429-459`; 7 `LspSession.kt:225-232,491-519`; 8 `LspSession.kt:361-398` (test `:423-468`); 9 `LspSession.kt:126-141,525-538,637-657`; 10 `LspSemanticProvider.kt:407-416,540-567,89,101`; 11 improved by `LspSemanticProviderTest.kt:323-336,470-490,588-612,615-640,700-721`.

New regression: document operations hold `documentLock` while performing blocking `sendNotification` (`LspSession.kt:94-112,121-151,155-160`), but the reader must take the same lock to store publishDiagnostics (`LspSession.kt:444-448`). If a server blocks writing diagnostics while the client blocks writing a notification, the reader stops reading and both sides deadlock. Update state under `documentLock`, then release and send via a separate per-URI ordering queue/lock the reader never acquires.

Residual: `textDocumentSync` NONE and `openClose=false` are ignored (`openClose` is never parsed; didOpen/didClose are still sent). The managed-endpoint test's child never binds the endpoint, so it cannot detect a child that starts but never listens (`LspSemanticProviderTest.kt:82-104`); the UTF-16 test never asserts the emitted position (`:643-665`). Contract additions are defaulted and source-compatible; `Diagnostic.source` changed default, but LSP parsing still emits "lsp" (`LspSession.kt:702`), so no protocol leak. Framing and id correlation remain sound.

Verdict: FINDINGS — round-1 items are fixed, but the new lock introduces a deadlock risk.

## Round 3

CONCUR: the Round-2 deadlock is fixed — mutation/send locks are split (`LspSession.kt:98-106,149-153`), the reader takes only `documentLock` (`:456-460`), and the regression test exercises blocking diagnostics (`LspSemanticProviderTest.kt:703-727`). CONCUR: NONE/`openClose=false` are parsed and rejected (`LspSession.kt:537-577`; test `:673-700`); the managed child binds the real endpoint (`ManagedEndpointChildMain.kt:16-35`; test `:82-104`); UTF-16 position is asserted (`LspSemanticProviderTest.kt:646-665`).

DISSENT: the split introduces an ordering race. `openDocument`/`changeDocument` publish new state under `documentLock` (`LspSession.kt:102-105,149-152`) before sending didOpen/didChange (`:106,153`), while a concurrent request reads that state via `documentFor` (`:175-199`) without the per-URI lock and can acquire `sendLock` first. A hover/definition can therefore reach the server before the sync notification, against text it has not seen. Update state after the notification is written, or make requests wait for the version to be marked synced (hold the per-URI lock for snapshot+send; the reader never takes it, so no deadlock).

Verdict: FINDINGS — blockers are fixed, but the lock split introduced a state-before-notification race.

## Round 4

CONCUR: the Round-3 ordering race is closed. didOpen/didChange/didClose are written before state is published or removed (`LspSession.kt:106-124,157-171,178-185`), and `documentFor`/`textFor` take `uriLock` before reading (`:189-221`), so a request cannot observe new state before the notification write; the new test gates didChange and verifies hover waits and wire order (`LspSemanticProviderTest.kt:788-841`). CONCUR: no deadlock is reintroduced — senders hold `uriLock`, not `documentLock`, and the reader takes only `documentLock` (`:476-480`).

DISSENT: publishing state after the write opens a diagnostics race. A server can publish didOpen/didChange diagnostics before `documents[uri]` is updated (`LspSession.kt:476-479`); a versioned publish is then dropped against the old version, or an unversioned one is stored as old and erased by the subsequent `clearDiagnostics` (`:119-121,165-167`), leaving missing/stale diagnostics. Publish/clear state before sending under `uriLock` (which alone closes Round 3), reverting on send failure; or track the pending version for diagnostics association.

Verdict: FINDINGS — ordering and deadlock are fixed, but the reorder introduces a diagnostics association race.

## Round 5

CONCUR: the Round-4 diagnostics race is closed. State and diagnostics are published/cleared before the sync write under `uriLock` (`LspSession.kt:108-124,163-175,194-200`), so fast versioned and unversioned publishes associate with the new version; failure paths restore prior state/diagnostics (`:125-130,176-185,201-206`). `fastDiagnosticsPublishDuringSyncIsAssociated` blocks after the write and asserts both modes retain diagnostics (`LspSemanticProviderTest.kt:844-898`).

CONCUR: no deadlock is reintroduced. Senders release `documentLock` before writing and retain `uriLock`; requests acquire `uriLock` before reading (`:211-243`); the reader takes only `documentLock` (`:499-503`). This preserves the Round-2/3 lock order and their regression tests.

Verdict: PASS.
