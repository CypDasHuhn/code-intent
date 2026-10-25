# 081 correctness review

Verdict: **FINDINGS**

1. `analysis-engine-v2/apps/server/src/main/kotlin/dev/cint/v2/server/Main.kt:63` — the executable cannot start: `RuntimeFactory.create(RuntimeConfig(...))` uses `RuntimeComponents()` whose `ruleEvaluators` is null, so `createPipeline` fails (`runtime/.../RuntimeComponents.kt:57-61`). Every test injects `TestRuntime`, so this is masked. Fix: pass `RuleEvaluatorCatalog.bundled()` (or default it in `createPipeline`) and test the real entry point.

2. `LiveServer.kt:19,62` — one `Runtime` is shared by all sessions, but `DefaultRuntime` and `InMemoryIndex` are not thread-safe (`indexedPaths`, `supports`, `entries`, `providerEpochs` are plain maps). Concurrent `analyzeSource`/`status` can corrupt state or throw, and sessions editing the same URI share one index key. Fix: serialize runtime calls or use one runtime per session.

3. `LiveServer.kt:37-49,69` — the socket directory is chmod-ed 0700 even when pre-existing (e.g. `/tmp`); `deleteIfExists` unconditionally unlinks a live server's socket, so two servers can coexist and the loser's exit deletes the winner's socket; permission failures are swallowed and applied only after bind. Fix: use a dedicated 0700 directory, verify a socket is stale before unlinking, fail on chmod errors, and only delete a socket this process owns.

4. `SessionHandler.kt:235-242,323-328` — `close` cancels jobs but never completes `AnalysisJob.future`; `await` uses unbounded `get()` while `IpcConnection.feed` holds the connection monitor. A long or interruption-ignoring analysis, or a task dropped by `shutdownNow`, makes `ClientSession.close` → `disconnect()` block forever, hanging shutdown; `runtime.shutdown()` can then race a still-running analysis. Fix: complete futures with `Cancelled`, bound the wait, interrupt/join workers, close the channel before disconnect.

5. `DocumentStore.kt:54-63` — `publishIfCurrent` holds the document monitor while calling `session.deliver`, which performs blocking socket I/O. A slow client blocks didOpen/didChange/didClose and other sessions' `status` (`server.activeDocuments`). Fix: assign the revision under the lock, then write outside it.

6. `SessionHandler.kt:279-291` — the cancelled/current check and `runtime.publish` are not atomic; a didChange/didClose between them lets a superseded result mutate the shared runtime while the client publish is suppressed. Fix: re-check the generation immediately before publish (or publish inside `publishIfCurrent`).

7. `SessionHandler.kt:132-145` + `IpcMapping.kt:39-52` — multi-target `analyze` merges every annotation into `files.single()` keyed by the first target, misattributing results. Fix: emit one `AnalyzedFile` per target (analyze per path) or reject multiple targets.

8. `SessionHandler.kt:120-130,244-250` — analyze on an open document returns the cached job even after it failed or was cancelled, and ignores `language`, `options`, and `forceFullAnalysis`; there is no way to retry. Fix: evict terminal failed jobs and re-run explicit analyze with the request parameters.

9. `LiveServerTest.kt:258-262,417-526` — tests are real socket-level but default to `TestRuntime`; actual startup, real `Runtime.analyzeSource`/`InMemoryIndex`, metrics, didClose cancellation, close/reopen, and same-URI multi-session are untested. `status.indexedFiles == sources.size` is tautological, legacy assertions check key presence not values, and the 300 ms negative wait is flaky. Fix: add real-runtime/startup tests and exact-value assertions.

Protocol wiring, stale-version rejection, per-document generations/revisions, publish ordering, unsupported-operation errors, and malformed-JSON handling are otherwise sound, and the `Runtime.analyzeSource`/`api` model changes are justified. However, the shipped server cannot start, the shared runtime is unsafe under the multi-session model, and cancellation/close and socket lifecycles can hang or delete the wrong socket. The socket tests do not catch these because they replace the runtime and never launch the real entry point.

## Round 2

1. CONCUR — `RuntimeComponents.kt:58` defaults to `BundledRuleEvaluators`; real startup covered by `LiveServerTest.kt:406`.
2. CONCUR — every Runtime call is gated (`RuntimeGate.kt:10-16`; `SessionHandler.kt:84,152,157,215,230,297,314`). Residual: non-open `runtime.analyze` (`:152`) is unbounded.
3. CONCUR — directory chmod only when created (`LiveServer.kt:116-122`), non-socket/live-socket refusal (`:125-149`), inode-guarded unlink (`:152-160`); tested `:453`.
4. CONCUR — cancel completes futures (`SessionHandler.kt:558-561`), bounded closed-aware await (`:344-370`), channel closed before disconnect (`ClientSession.kt:57-62`); tested `:487`. Residual: `gate.shutdown` result is ignored (`LiveServer.kt:82`), so `runtime.shutdown` can be skipped.
5. CONCUR — revision bump under `synchronized(this)`, delivery outside it (`DocumentStore.kt:56-77`). `publishLocks` is never evicted (`:18,66`).
6. DISSENT — `didChange`/`didClose` (`DocumentStore.kt:31-42`) do not take the per-URI publish lock, so they can interleave after the generation check/revision bump (`SessionHandler.kt:305-322`) and a superseded publish can still reach the client; `runtime.publish` (`:314`) has the same check-to-call window. Test `:166` misses that window. Make change/close take the publish lock or revalidate under it.
7. CONCUR — per-target files (`IpcMapping.kt:54-70`; `SessionHandler.kt:147-169`).
8. CONCUR — explicit analyze reschedules (`SessionHandler.kt:136-138`), jobs removed in `finally` (`:333`), legacy republish tested (`:318`).
9. CONCUR — 15 tests, including real runtime (`:406,425`), socket refusal (`:453`), didClose/reopen/same-URI (`:166,189,204`). `TestRuntime.status` remains `sources.size` (`:832`), and `main`/`parseArguments` remain untested.

New regression: `standaloneRevisions` is one session-wide counter (`SessionHandler.kt:70,161`), while open-document revisions are per-URI; a closed-URI analyze followed by didOpen can decrease that URI's revision; use per-URI counters.

Round 2 resolves eight of nine findings; the stale-publish race, per-URI revisions, and skipped shutdown remain. Tests are materially stronger but still do not exercise the publish/close interleaving. Verdict: **FINDINGS**.

## Round 3

- CONCUR per-URI locking: `DocumentStore.kt:24-44` routes open/change/close through `withPublishLock`; `publishIfCurrent` (`:63-79`) uses the same lock; regression test `LiveServerTest.kt:486`. Client-visible stale publish is closed.
- CONCUR bounded non-open analyze: `SessionHandler.kt:379-421` runs it off-loop and bounds `awaitFuture` with `analysisTimeoutMs` plus cancellation cleanup; test `:427`.
- CONCUR shutdown surfacing: `LiveServer.kt:86-89` records the failure and still deletes the socket; `Main.kt:23-26` exits 1; test `:540`. Residual: an exception thrown by `runtime.shutdown()` still skips cleanup/`stopped`.
- CONCUR per-URI monotonic revisions: `DocumentStore.kt:38-49` retains counters through close and `SessionHandler.kt:164` uses `nextRevision`; test `:399`.
- CONCUR legacy null omission (`IpcMapping.kt:143-146`; test `:462`) and real byte hashing (`SessionHandler.kt:425-441`; test `:399`). Residual: `contentHash` resolves relative targets against `options.root`, while `runtime.analyze` receives the unresolved path (`:150-160`), so `--root` != cwd can hash a different file.
- Residual: `runtime.publish` still precedes the publish lock (`SessionHandler.kt:312-323`); harmless with the default NONE publisher.

All six claimed fixes hold and the new regression tests target them. No new socket/lifecycle regression found. Verdict: **PASS** with the two residuals above.
