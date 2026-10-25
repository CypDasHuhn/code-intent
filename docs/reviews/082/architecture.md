# Architecture Review — 082

Verdict: FINDINGS

1. `analysis-engine-v2/runtime/src/main/kotlin/dev/cint/runtime/RuntimeFactory.kt:12-17` — `createDefault` hardcodes `RuleEvaluatorCatalog.bundled()` with no evaluators, reintroducing the empty-default regression 078 closed when `RuntimeComponents.kt:58-64` made a missing registry fail fast. It also puts production bootstrap policy in runtime, and only MCP uses it. MCP can start and `analysis.run` can return empty results while 084 needs real parity. Fix: remove `createDefault` from runtime; let the app build `RuntimeComponents` with a non-empty registry, or make the factory require one.

2. `apps/mcp/.../McpServer.kt:49-148` and `McpRuntimeTools.kt:36-77` parse raw JSON-RPC into ad-hoc `JsonObject`s and dispatch tool names as strings, bypassing `contracts/mcp/McpMessages.kt:15-133` and `contracts/ports/Transports.kt:27-31`. The contracts MCP DTO/port set is dead in production while the app owns a competing schema; they already differ on `AnalysisRun` fields and init tools. This contradicts `docs/rewrite/module-graph.md:178,382` ("tool DTOs live in contracts") and blocks 083/084 reuse. Fix: define tool request/response DTOs in contracts and leave only JSON-RPC framing in the app; delete or align the unused port.

3. `KnowledgeInputs.kt:190-260`, called from `McpRuntimeTools.kt:167,212,249,300`, duplicates intent, timestamp, scope, and pack-default validation owned by `knowledge` (`module-graph.md:225-228`). Two validation policies will drift. Fix: rely on KnowledgeStore/KnowledgeChange validation and map returned `ContractError`s to structured MCP errors.

4. `McpRuntimeTools.kt:33-34,283-326` stages proposals in an app-local map. Proposals vanish on restart, are invisible across sessions, and have no checkpoint, directly blocking 083's resumability and idempotent init. Fix: make proposals a runtime/knowledge port (or apply changes directly); add durable checkpoints in 083.

5. `Main.kt:28-35` starts `createDefault` with a root; construction materializes defaults when no store exists (`DefaultRuntime.kt:370-376`) and `shutdown` saves (`:167-175`). The tool surface correctly omits init, but startup can still write knowledge before the agent acts. Fix: add an explicit no-materialize/deferred-open mode for 082, leaving init to 083.

Boundaries otherwise hold: MCP imports only contracts/runtime (McpBoundaryTest), graph edges match `graph-guard.json:35-36`, resources map knowledge/status, and mutations use the shared KnowledgeStore API. The surface covers `knowledge-model.md:197-206` except the deliberately deferred `knowledge.materializeDefaults/suggestDefaults`; `repo.index` is only a status alias.

Summary: the app boundary, graph edges, resources, and shared mutation path are sound, but the runtime factory reintroduces an empty evaluator default, contracts no longer own the live MCP DTOs, knowledge validation is duplicated in the app, and proposal state is session-local. Those seams will force 083's resumable agent workflow and 084's parity suite to target a second schema and app-local semantics; move bootstrap, DTOs, validation, and proposal lifecycle to their owners first.

## Round 2

Verdict: PASS

1. CONCUR — `RuntimeFactory.kt:5-10` no longer exposes `createDefault`; `RuntimeComponents.kt:44,58-64` supplies `BundledRuleEvaluators.registry()` rather than an empty catalog, and MCP starts through `RuntimeFactory.create` (`Main.kt:28-36`).

2. CONCUR — live tool DTOs are in `contracts/mcp/McpTools.kt:7-68`; `McpServer.kt:216-266` decodes `McpToolCall`/`McpResourceReadRequest` and emits `McpToolResult`/`McpResourceReadResult`, and `Transports.kt:29-39` defines the matching port. Residual: `McpMessages.kt:15-133` is now a second, test-only request/response family including init tools; retire or explicitly align it before 083.

3. CONCUR — app-local `validate*` helpers are gone; mutations call `KnowledgeStore` directly (`McpRuntimeTools.kt:169-178,218-227,254-263,318-323`) and returned `ContractError`s become structured tool failures.

4. CONCUR — `Main.kt:33-34` disables detection and materialization; `RuntimeConfig.kt:21` gates seed materialization, `DefaultRuntime.kt:371` honors it, and `:169-171` saves only materialized stores.

5. CONCUR — proposals use `ConcurrentHashMap`/`AtomicLong`, with atomic remove and restore on failure (`McpRuntimeTools.kt:34-35,308-326`); durable checkpointing is named for 083 in `apps/mcp/DEFERRALS.md:3-10`.

6. CONCUR — main sources import only contracts/runtime (`McpBoundaryTest`), and `graph-guard.json:35-36` plus test-only MCP edges match the test scopes in `apps/mcp/build.gradle.kts`.

Summary: all five round-1 findings are addressed with no new boundary leak; only the stale `McpMessages` contract family remains as non-blocking debt.
