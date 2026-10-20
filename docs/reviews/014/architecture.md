# Architecture review — ticket 014 (delete legacy config and phase)

## Round 1

Verdict: The legacy deletion is complete and the module boundary is intact. The
`intents.yaml` resource, `rules/Blocks.kt`, `Phase`, `RuleBlock`,
`AnalysisEngine`, `IntentBlocks` and `loadIntentConfig` are gone from source and
tests; no production code imports `:rulepacks`, `:treesitter`, `:frontend` or
`:lsp`, and every host and test path now goes through
`RuleEngine`/`RuleScheduler`. Two gaps remain before the ticket closes: deleting
the legacy oracle also deleted the only automated evidence for the ticket's
"behavior unchanged" acceptance criterion, and one dead `IntentConfig` helper is
still present.

### Findings

1. **`analysis-engine/frontend/src/test/kotlin/dev/cint/frontend/HostConfigWiringTest.kt:184`, `analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/ErrorHandlingRulePackTest.kt:218-223`, `CompositionTerminalTest.kt` (deleted parity tests), `docs/manual-test.md:9,11,14` — the deletion removes the parity oracle without replacing its evidence.**
   `hostDefaultsMatchTheLegacyEngineOverTheFixtures` used to compare every
   `testing-grounds` fixture against the legacy engine; it is now
   `hostDefaultsAnalyzeEveryFixture` and only asserts `actual.isNotEmpty()`.
   `LoggingRulePackTest.matchesLegacyLoggingOverTestingGrounds` is removed
   entirely, and the rulepack fixture checks were reduced to
   "error handling was not detected". `CompositionTerminalTest` also drops both
   legacy composition parity tests. The ticket acceptance is
   "Engine behavior is unchanged from 013 + 010", but there is now no automated
   or recorded comparison. The manual gate still says the pre-restructure
   baseline "must be captured before 014 deletes the legacy path"
   (`manual-test.md:9`), while `m003`/`m006` still claim fixture parity is
   automated by `LoggingRulePackTest`/`HostConfigWiringTest`, which this diff no
   longer does.
   Suggested fix: before closing 014, capture the parent-commit fixture
   annotations as checked-in snapshots (or run and record `m001`) and compare the
   new engine against them; update `m001`/`m003`/`m006` so no manual-test row
   claims coverage that has been removed. If no baseline is captured, add an
   explicit 014 manual-test entry with that reason rather than silently dropping
   the acceptance evidence.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/config/IntentConfig.kt:9-12` — `IntentDefinition.has` is dead after the `Blocks.kt` deletion.**
   The only call sites of the extension-style helper lived in the removed
   detection path; `rg` finds no remaining callers, and the carrier now uses only
   `list()` plus construction-time values. This is precisely the "now-dead
   plumbing" the ticket scope asks to remove, and leaving it behind keeps an
   unused legacy-shaped helper on the core config carrier.
   Suggested fix: delete `has`; keep `IntentDefinition` only if `list`/`name`
   are still needed by composition or hosts.

### Concurs / Dissents

- Concurs: the named legacy surface is gone. No source or test file references
  `intents.yaml`, `Phase`, `rules/Blocks.kt`, `RuleBlock`, `AnalysisEngine`,
  `IntentBlocks` or `loadIntentConfig`; remaining textual references are in
  historical docs, reviews and task records, which are not in scope. The
  `BlockTest` names that remain are ordinary test class names, not references to
  the deleted rule path.
- Concurs: deletion is complete at the file level (`core/resources/intents.yaml`,
  `core/rules/Blocks.kt`, `treesitter/BlockTest.kt`,
  `treesitter/RuleBoundaryTest.kt`), and `IntentConfig` no longer imports
  SnakeYAML or reads a resource.
- Concurs: no detection leaked back into core. `:core` production code imports
  no `:rulepacks`/`:treesitter`/`:frontend`/`:lsp` classes; the built-in
  detectors remain in `:rulepacks`, selected by the hosts and tested through
  `RuleEngine`.
- Concurs: module boundaries still hold. `SplitAnalyzer` now takes
  `ExecutionPlan`/`IntentConfig` and delegates to `RuleEngine`; the removed
  five-argument overloads have no callers; `HostConfig`/`HostPipeline` remain
  the configuration adapter; `PublicEngineSurfaceTest` now checks
  `RuleEngine`/`RuleContext` rather than the deleted engine types.
- Concurs: the config carrier is no longer a file loader. `HostSettings`
  synthesizes `IntentConfig` from `ProjectConfig` plus the validation pack's
  typed values, and no production path reads `intents.yaml`. The remaining
  `IntentConfig` map is a core-internal carrier, not a detection schema source.
- Residual (not filed as a finding): `Composition.isValidationPredicate` and
  `hasValidationComment` (`core/.../composition/Composition.kt:174-206`) still
  read validation `predicates`, `predicatePrefixes` and `magicComment` through
  the untyped `IntentConfig` carrier. This is inherited from 009/013 and was
  accepted by the earlier composition/config reviews, so it is not a new 014
  regression; if architecture §1 is enforced strictly, moving that decision
  fully into the validation pack (or a typed core policy) remains a follow-up.
- Residual: the fixture tests still contain now-unused imports from the removed
  legacy comparisons (for example `ProfileSyntaxModel`/`SyntaxProfiles` in
  `CompositionTerminalTest`); this is harmless cleanliness, not a boundary issue.
- Dissents: findings 1 and 2 above.

## Round 2

Verdict: Both round-1 findings are addressed and no new architecture finding
remains. The frozen legacy/pipeline snapshots restore exact fixture parity across
all 11 `testing-grounds` files, including Vue regions; `manual-test.md` m008
records the 014 verification and the older rows no longer claim coverage from
deleted tests; and `IntentConfig` no longer carries the dead `name`, `blocks` or
`has` members. The module boundary is unchanged: the snapshots are test-only,
core still imports no rulepack or language modules, and no legacy engine type is
reachable.

### Findings

- None.

### Concurs / Dissents

- F1: concurs — `LegacyParitySnapshotTest` compares current host output against
  `legacy-pre014.json` and `pipeline-pre014.json` for all 11 fixtures, including
  the Vue region path. The legacy snapshot omits rule identity and records the
  return-role normalization; the pipeline snapshot pins the current
  `logging:logging`, `error-handling-<language>:error-handling`,
  `bookkeeping:bookkeeping`, `validation:validation` and `core:*` ids.
  `manual-test.md` m008 is the 014 entry, and `m001`/`m003`/`m006` now point to
  it instead of the deleted tests.
- F2: concurs — `IntentDefinition` now carries only `params` and `list()`;
  `HostConfig` and the treesitter test helper construct it without `name` or
  `blocks`, and the dead `has` helper is gone.
- Concurs: no new module-boundary or detection-leak regression. The snapshot
  tests live under `frontend/src/test`, the production path still synthesizes
  `IntentConfig` in the host adapter and runs `RuleEngine`, and no
  `intents.yaml`, `Phase`, `rules/Blocks.kt`, `AnalysisEngine`, `IntentBlocks`
  or `RuleBlock` reference was reintroduced.
- Concurs: the engine README now describes rulepack ids, core composition ids
  and typed pack config consistently with the implementation.
- Residual (not a finding): `LegacyParitySnapshotTest` hardcodes the 11 fixture
  paths rather than walking `testing-grounds`; the current set is pinned, but a
  future fixture addition needs a matching snapshot update. The snapshot
  provenance is recorded in the JSON and m008, so the frozen files are the parity
  oracle now that the legacy code is gone.
- Residual: static review only; no build, test or formatter command was run by
  this role per `workflow.md`.
- Dissents: none.
