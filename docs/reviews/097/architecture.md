# 097 Architecture Review

Verdict: core structure is sound; changes requested for stale active docs and dead graph edges.

## Verified

- `buildSrc/`, `frozen-v1-corpus/`, `tools/v1-syntax-capture/`, `tests/parity/`, and the parity scripts are gone; no active code, build, CI, justfile, or script references remain.
- `settings.gradle.kts` project names and module boundaries are unchanged and match `docs/rewrite/module-graph.md`.
- The inlined guard (`build.gradle.kts:88-260`) is logic-identical to the deleted `buildSrc` validator, and `graphGuard` is retained (`build.gradle.kts:320`). Dropping the self-test is per ticket. Adding Gson through `buildscript` (`build.gradle.kts:10-17`) is acceptable, though the root script now owns ~200 lines of untested guard logic; a follow-up test outside `buildSrc` would reduce risk.

## Findings

1. Canonical active docs still assert frozen-corpus replay: `docs/ipc/PROTOCOL.md:19-20,37`. Reword to legacy V1 wire shapes/clients, matching `orchestration/ipc/PROTOCOL.md:22`.
2. `docs/summary/current-state.md:20` still says legacy versions exist for "legacy replay", and `:29` claims 084 left "committed baselines"; the replay harness and baselines are gone. Reword and mark 084 historical.
3. `docs/summary/glossary.md:14` still says strata run against "frozen earlier output"; align with `docs/architecture.md:59`.
4. `docs/summary/development-and-testing.md:41-42` still claims "surface" integration tests; `SurfaceIntegrationTest` is deleted. Say pack and end-to-end only.
5. Dead graph edges: `graph-guard.json:46` allows `integration-tests -> knowledge-persistence`, never declared in `tests/integration/build.gradle.kts`. After parity deletion, `:index` (10), `:lsp` (14), `:runtime` (16), `:ipc` (18), `:cli` (19), `:server` (20), `:mcp` (21) are unused; matching allowed edges (`graph-guard.json:44,49,51,53-56`) are dead. Remove both sides.
6. `apps/server/build.gradle.kts:18` (`:languages`) is unused after `FrozenProtocolParityTest` deletion; remove with `graph-guard.json:66`.
7. Minor: `docs/rulepack-support-plan.md:50` still requires parity tests; the queue index `docs/tasks/README.md:13,84-85` still says the frozen corpus persists; rename the `Frozen` test name at `orchestration/ipc/src/test/kotlin/dev/cint/ipc/ProtocolMessagesCompatibilityTest.kt:20`.

## Round 2

CONCUR.

- Docs fixes verified: `docs/ipc/PROTOCOL.md`, `docs/summary/current-state.md`, `glossary.md`, `development-and-testing.md`, `rulepack-support-plan.md`, and `docs/tasks/README.md` no longer claim frozen-corpus replay/baselines; the `Frozen` test is renamed.
- Graph and deps verified: `tests/integration/build.gradle.kts` now lists only used modules, and its `allowedTestEdges` match exactly; the dangling `knowledge-persistence` edge is gone. `apps/server :languages` remains declared and allowed. Its evidence is indirect (`RuntimeConfig()` defaults reference `LanguageOptions`; the `languages = ...` literal is `ServerOptions`), but the edge is valid and harmless, so the round-1 finding is withdrawn.
- Inlined guard is unchanged; no `buildSrc` recreation. Residual risk accepted per ticket: the guard's illegal/reverse/root/missing-edge behavior has no independent test. Recommend a follow-up test outside `buildSrc`, not a 097 blocker.
