# Correctness Review — 082 V2 MCP Tool Server

Verdict: FINDINGS

1. `analysis-engine-v2/runtime/src/main/kotlin/dev/cint/runtime/RuntimeFactory.kt:12-16` and `analysis-engine-v2/apps/mcp/src/main/kotlin/dev/cint/v2/mcp/Main.kt:28-35` — `createDefault` installs `RuleEvaluatorCatalog.bundled()`, but `bundled()` defaults to an empty registry (`intent-pipeline/.../RulePlans.kt:22-23`) while bundled packs declare rules. `DefaultIntentPipeline.kt:54` binds them and `RuleEvaluation.kt:90-95` fails with `NotFound("rule-evaluator", ...)`, so the shipped MCP runtime starts but `analysis.run` fails on real Kotlin input. The fake test runtime hides this. Fix: compose the real evaluator registry in `RuntimeComponents` (or fail fast at startup), and test through `createDefault`.

2. `KnowledgeInputs.kt:41-42,96-97,171-172` and `McpCatalog.kt:274-275` accept client-supplied `createdAt`/`updatedAt`; `InMemoryKnowledgeStore.kt:111-117,186-189,233-238` then stores a future `updatedAt` and rejects subsequent server-time writes as older revisions. An agent can lock an annotation, override, or pattern by writing a far-future timestamp. Fix: ignore client timestamps for live MCP writes (use server time) or reject/clamp timestamps beyond now plus a small skew; add a regression test.

3. `McpServerTest.kt:59-345,351-420,422-563` — the pipe-based scripted client is real, but the backend is `TestRuntime` plus a `TestKnowledgeStore` that performs no validation or persistence, and `TestRuntime.analyze` fabricates output. The test therefore cannot catch the empty-registry failure or prove mutations pass through real `KnowledgeStore` validation. `changes.apply` success, `patterns.*`, `repo.index`, span/pattern resources, and actual `runtime.shutdown` are unexercised; `shutdownCalled` is never asserted. Fix: drive the client against `DefaultRuntime` with a real `InMemoryKnowledgeStore` and a temp `PersistentKnowledgeStore`; assert store contents after set/propose/apply/validate and after restart, and assert structured errors for store-level rejections.

4. `McpServer.kt:32-47,232-240` and `McpRuntimeTools.kt:129,316` — the reader thread runs all work serially and ignores `notifications/cancelled`; runtime calls use the default `CancellationToken.NONE`. A long `analysis.run` blocks `ping`, `shutdown`, and `exit`, clients cannot cancel, and `proposals` would race if handlers were concurrent. Fix: track request ids, map cancellation notifications to tokens passed to runtime, dispatch long calls off the stdin loop with a synchronized writer (or keep control methods responsive), and guard proposal state.

5. `McpServer.kt:49-148` and `McpRuntimeTools.kt:36-77` — the live server parses ad-hoc JSON-RPC and never uses `contracts/mcp/McpMessages.kt:15-133` or `McpTransport` (`contracts/ports/Transports.kt:27-38`). The contract's `requestId: String`, `AnalysisRun` fields, and `RepoIndex` shape already differ from the wire, so contract tests do not cover the actual schema and other transports cannot reuse it. Fix: move the tool envelopes into contracts and serialize them in the server, leaving only JSON-RPC framing in the app; test the live envelopes.

6. `McpRuntimeTools.kt:266-279` — `patterns.validate` resolves `id` by identity key, pattern text, or path with `firstOrNull`; two hypotheses sharing a pattern or path silently pick one and accept or reject the wrong entry. Fix: require the identity key or reject ambiguous matches, and add patterns list/propose/validate persistence tests.

Handshake negotiation, capability advertisement, tool/resource discovery, unknown tool/resource errors, structured tool errors, newline framing, and the import boundary are sound. Mutations use the runtime-provided `KnowledgeStore` and import no core/adaptors, so shared validation is not bypassed in code. The blocking issues are the unusable production analysis path, client-controlled timestamps, a fake-backend test suite, missing cancellation/concurrency, and unused, divergent MCP contracts.

## Round 2

Verdict: FINDINGS

1. CONCUR — `RuntimeFactory.kt:5-10` no longer adds `createDefault`; `Main.kt:28-36` builds the server runtime through `RuntimeFactory.create`, and `RuntimeComponents.kt:58` now defaults to `BundledRuleEvaluators.registry()`. `BundledRuleEvaluatorsTest.kt:21-34` covers every default-enabled pack rule, and `McpServerTest.kt:74-108` runs a real `DefaultRuntime`/pipeline analysis.

2. CONCUR — `KnowledgeInputs.kt:45-46,98-99,174-175` uses `now`; `McpRuntimeTools.kt:307,378-414` stamps change batches; `McpCatalog.kt:269-276` no longer advertises timestamps. `McpServerTest.kt:110-125,141-157,204-225` verifies future input is ignored.

3. CONCUR — `McpServerTest.kt:72-347` drives a real `DefaultRuntime` over a `PersistentKnowledgeStore`; an invalid intent is rejected by the store (`:165-175`), `changes.apply` persists (`:216-225`), and a restart reloads the persisted annotation and patterns (`:340-346`).

4. DISSENT — cancellation is wired (`McpServer.kt:88-117,269-281`; test `:349-389`), but the new 4-thread executor (`McpServer.kt:35-38`) runs all `tools/call` and `resources/read` concurrently while `DefaultRuntime` (`DefaultRuntime.kt:44-48,69-84,149-165,178-202,236-250`) and `InMemoryIndex` (`InMemoryIndex.kt:69-75`) are unsynchronized. Concurrent analyses/status/shutdown can corrupt `supports`, `indexedPaths`, or index maps, or shut a provider mid-analysis; `patterns.validate` also loses read-modify-write updates. Fix: serialize runtime/index access (single tool thread or a lock) or make them thread-safe, and add a concurrent analysis/status/shutdown test.

5. CONCUR with residual — `McpServer.kt:220-237` now decodes/encodes `McpToolCall`, `McpToolResult`, and resource DTOs from `contracts/mcp/McpTools.kt` (`SerializationTest.kt:626-651`). `McpMessages.kt` envelopes and `McpTransport` remain unused by the server (only `FakeMcpTransport` implements the port); delete or wire them.

6. CONCUR — `resolvePattern` (`McpRuntimeTools.kt:359-376`) rejects duplicate pattern/path matches; `McpServerTest.kt:282-302` covers ambiguity and identity-key success.

New regression: `McpSupport.kt:22-27,38-42` builds `McpToolContent` with its default `type`, while `McpJson.kt:14-17` encodes with `encodeDefaults=false`; `McpServer.kt:237` therefore omits the required `"type":"text"` in tool results, and `McpServerTest` never inspects `content`. Fix: make `type` non-default or encode MCP envelopes with defaults, and assert `content[0].type`.

Delegated validation, deferred materialization (`Main.kt:34`, `RuntimeConfig.kt:21`, `DefaultRuntime.kt:371`; test `:392-430`), and the 083 deferral (`DEFERRALS.md`) check out.

## Round 3

Verdict: PASS

1. CONCUR — `McpServer.kt:36-41` uses one tool thread plus `ReentrantLock`; `dispatch` locks only `tools/call`/`resources/read` (`:152,161`), while `ping` (`:147`) and cancellation (`:286-288`) stay on the reader thread. `McpServerTest.kt:432-493` pipelines seven calls and verifies serialized runtime access; cancellation coverage remains (`:362-401`).

2. CONCUR — `McpJson.kt:16` sets `encodeDefaults=true`; `McpServerTest.kt:405-429` asserts exact `content:[{type:"text",text:"{}"}]`, and `:484-485` checks `content[0].type`.

3. CONCUR — `McpMessages.kt:6-27` now contains only `McpNotification`; `McpTools.kt` is the sole live DTO source, no `McpRequest`/`McpResponse`/`McpEnvelope` references remain, and `Transports.kt:29-46` uses the tool/resource DTOs.

Residual: `McpServer.kt:62-68` shuts the runtime down after a 5s executor wait, so a tool that ignores cancellation could still overlap shutdown; a narrow liveness edge only.
