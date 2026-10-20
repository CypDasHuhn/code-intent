# Correctness review — ticket 026 (test topology and hermetic tests)

## Round 1

Verdict: The substantive behavior of the moved tests is preserved. The scheduler
tests now live in `core` with fake syntax, composition and the two host tests
live in `integration-tests`, the language/rulepack tests moved to
`rulepacks-tests`, the snapshots are byte-identical, and the shared
`TestAnalysis`/parser-dispatch helpers are consolidated. Fixture-dependent tests
now skip explicitly with a reason instead of depending on a hardcoded home path,
and the LSP oracle is genuinely opt-in and configurable. One acceptance gap
remains: host/rulepack integration tests still remain in `frontend` and
`rulepacks`, so the "no backwards test-only dependencies" criterion is not fully
met.

### Findings

1. **`analysis-engine/frontend/build.gradle.kts:6-7`, `analysis-engine/rulepacks/build.gradle.kts:4` — host/rulepack integration tests still live in the lower-level test modules.**
   `frontend/src/test/kotlin/dev/cint/frontend/VueSplitTest.kt:7-8,59,98,135`
   still constructs `HostPipeline` and `BundledRulePackSelection` for its
   pipeline/coordinate/provenance cases, so `frontend` continues to
   test-depend on both `:host` and `:rulepacks`.
   `rulepacks/src/test/kotlin/dev/cint/rulepacks/BundledRulePackSelectionTest.kt:4,28-33`
   likewise drives `HostPipeline` directly, leaving `rulepacks` test-depending
   on `:host`. These are the same host integration paths that
   `HostConfigWiringTest` and `LegacyParitySnapshotTest` were moved out of
   `frontend` for. Coverage is not lost, but ownership is still split and the
   acceptance criterion is not met.
   Fix: split `VueSplitTest`; keep the two pure region cases in `frontend` and
   move the pipeline/coordinate/provenance cases to `integration-tests`; move
   the host-pipeline selection case there as well, keeping the two
   `BundledRulePackSelection.constructed` assertions as rulepack unit tests.
   Then remove the now-unused `:host`/`:rulepacks` test edges.

### Concurs / Dissents

- Concurs: coverage is preserved for the deleted tests. The five convergence,
  two emit-validation, three scheduler-error and four stratification cases moved
  to `core` with the same assertions; the five composition classes retain the
  same thirteen test methods; `HostConfigWiringTest` (four cases) and
  `LegacyParitySnapshotTest` (two cases) retain their bodies; the C# and web
  block tests retain their eight cases each; and the moved `legacy-pre014.json`
  and `pipeline-pre014.json` resources are byte-identical to the old ones.
- Concurs: replacing the Kotlin parser with `FakeSyntax` in the scheduler tests
  is appropriate for their ownership and still exercises the scheduler/fixpoint
  cases, including derived iteration limits and zero-length identities. The
  composition tests still use real parsers, so parser integration remains
  covered.
- Concurs: the old core `CapabilityViewTest` assertion is retained under
  `CapabilityViewProvenanceTest`; no test method was dropped. The remaining
  treesitter `CapabilityViewTest` covers a larger real-parser surface.
- Concurs: fixture lookup is now hermetic enough to be explicit. `TestFixtures`
  accepts `-Dcint.testingGrounds`/`CINT_TESTING_GROUNDS`, otherwise searches
  upward for the tracked `testing-grounds` directory, and calls JUnit
  `assumeTrue` with a named skip reason when absent. An explicitly configured
  but missing root fails loudly, and a found root with a missing fixture also
  fails loudly rather than silently skipping.
- Concurs: `LspOracleTest` no longer contains `/home/cyp/repos/mtquery`; root,
  file and symbol are configurable via properties/environment. An unconfigured
  run is skipped with a reason, while a configured run fails on a missing
  fixture, a missing server or a missing symbol instead of printing and
  returning.
- Concurs: `PackTestHarness`, `PackTestHarnessTest`, `TestAnalysis` and
  `ParserSupport` now share parser dispatch, config and pack construction;
  `rulepacks-tests` and `integration-tests` consume one copy, and the duplicate
  `TestAnalysis` files are gone.
- Concurs: `just build` and `just test-engine` include the new modules and
  `:host`; `treesitter` no longer has a test dependency on `:rulepacks`.
- Dissents: finding 1 above.

### Residuals (not filed as findings)

- `analysis-engine/test-support/build.gradle.kts:2-7` uses `implementation` for
  modules whose types appear in the harness's public signatures
  (`IntentConfig`, `RulePack`, `SyntaxTree`, `SyntaxModel`, `SourceFile`). The
  current two consumers happen to redeclare those modules, so the build is
  green, but a third consumer depending only on `:test-support` would not
  compile. Applying `java-library` and exposing the needed edges, or making the
  module test fixtures, would make the harness self-contained.
- `TestFixtures` imports JUnit's `Assume` from `test-support` `src/main`, so the
  harness is tied to the JUnit 4 runner. All current test modules use it, but a
  future JUnit 5 consumer would not treat the assumption as a skip.
- `TestFixtures.rootOrNull` can select a `testing-grounds` directory above the
  checkout when the property/env is unset. The tracked repo copy makes the
  default reproducible, but CI should set the explicit root to avoid an
  ambiguous ancestor.
- `just test-lsp` advertises `-Dcint.lsp.root`, but a Gradle command-line `-D`
  is not automatically forwarded to the test JVM; the environment variable is
  the reliable path unless a `systemProperty` is wired. The test also requires
  an already-running server even though `KotlinLspOracle.connect` can start one.
- Fixture-dependent parity/composition tests now skip when the fixture root is
  absent; because `testing-grounds` is tracked, normal checkouts still run them,
  but a package without that directory would report green with those tests
  skipped.
- The core `CapabilityViewTest` was renamed rather than deleted; if the ticket
  intended to remove the duplicate view coverage rather than just the duplicate
  class name, the larger treesitter test is still the other copy.

No build, test or formatter command was run by this role; the handover was
treated as green per `workflow.md`.

## Round 2

Verdict: Finding 1 is addressed, and the round-1 residuals about the shared
harness are resolved. `VueSplitTest` now contains only the two parser-only
region cases; its three pipeline/coordinate/provenance cases are in
`integration-tests/.../VuePipelineTest.kt` with unchanged bodies.
`BundledRulePackSelectionHostTest` holds the moved host-pipeline assertion,
leaving two pure `BundledRulePackSelection.constructed` cases in `rulepacks`.
`frontend` no longer test-depends on `:host` or `:rulepacks`, and `rulepacks`
no longer test-depends on `:host`. The `CapabilityViewTest` duplicate is gone:
the treesitter test moved to `integration-tests` and the core provenance case
was folded into it, so all eight parser-backed cases plus the provenance case
remain as nine tests. The former `test-support` main module is replaced by
`test-fixtures` using `java-test-fixtures` with `testFixturesApi`, and
`testConfig` is consolidated into core test fixtures; JUnit no longer appears in
shared main sources. The structural guard now pins the new topology. I found no
new correctness defect in the moved tests, build wiring, fixture-skip behavior,
or LSP opt-in.

### Findings

- None.

### Concurs / Dissents

- F1: concurs. The frontend/rulepacks host edges and tests are removed, and the
  moved cases preserve their assertions and expected counts (`VuePipelineTest`
  three, `BundledRulePackSelectionHostTest` one, `CapabilityViewTest` nine).
- Concurs: coverage remains intact across the move. The composition, host,
  language, scheduler and snapshot coverage reported in round 1 is unchanged;
  `VueSplitTest` retains the two region cases, and the rulepack selection test
  retains its two construction cases.
- Concurs: fixture skip policy is now owned by test code: `TestFixtures` is free
  of JUnit, exposes a throwing `root()` for direct use, and both
  `integration-tests` and `rulepacks-tests` wrap `rootOrNull()` with an explicit
  `assumeTrue` reason. Configured-but-missing roots and missing fixtures still
  fail loudly.
- Concurs: `test-fixtures` is a proper test-fixtures module, its consumers use
  `testFixtures(...)`, its exposed API uses `testFixturesApi`, and the previous
  “third consumer cannot compile” residual no longer applies.
- Concurs: the structural guard checks the removed frontend/rulepacks/treesitter
  edges, keeps production modules off `test-fixtures`/`integration-tests`/
  `rulepacks-tests`, and enumerates test-only modules from settings.
- Concurs: the LSP oracle behavior is unchanged from round 1 and remains opt-in
  and non-silent.
- Dissents: none.

### Residuals (Round 2)

- `just test-lsp` still advertises `-Dcint.lsp.root`, which a Gradle command-line
  `-D` does not necessarily forward to the test JVM; the environment variable
  remains the reliable opt-in path. The configured test still requires an
  already-running server even though `KotlinLspOracle.connect` can start one.
- `TestFixtures.rootOrNull` can still choose a `testing-grounds` directory above
  the checkout when no explicit root is set; the tracked fixtures make normal
  runs reproducible, but CI should set the property/env.
- Fixture-dependent parity/composition tests still skip when the root is absent,
  so a checkout without `testing-grounds` can report green with those tests
  skipped.
- The shared `assumeFixtureRoot`/`fixture` wrappers are duplicated in
  `integration-tests` and `rulepacks-tests`; this is minor test code duplication,
  not a correctness issue.
- The engine README module table still does not mention `test-fixtures`,
  `integration-tests` or `host`; that remains documentation work.

No build, test or formatter command was run by this role; the handover was
treated as green per `workflow.md`.
