# Architecture review — ticket 026 (test topology and hermetic tests)

## Round 1

Verdict: The direction is right. The scheduler tests now live in `core` and run
against fake syntax, the composition and host pipelines have moved to
`integration-tests`, `treesitter` no longer test-depends on `rulepacks`, and the
LSP oracle is opt-in/configurable and fails loudly when configured without a
fixture or server. The remaining gaps are in test ownership and module shape:
two modules still carry the host/rulepack test dependencies the ticket set out to
remove, the new `test-support` module is an integration-level hub in the
production source set, one `CapabilityViewTest` duplicate remains (the core copy
was renamed, not removed), and the structural guard does not pin the new test
topology.

### Findings

1. **`analysis-engine/frontend/build.gradle.kts:6-7`, `analysis-engine/rulepacks/build.gradle.kts:4` — host integration tests still live in frontend/rulepacks.**

   `frontend/src/test/kotlin/dev/cint/frontend/VueSplitTest.kt:7-8,59,98,135`
   still imports `HostPipeline` and `BundledRulePackSelection` for four
   pipeline/coordinate/provenance tests, and
   `rulepacks/src/test/kotlin/dev/cint/rulepacks/BundledRulePackSelectionTest.kt:28-30`
   drives `HostPipeline` directly. These are the same host/rulepack integration
   tests the ticket moved out of `frontend` (`HostConfigWiringTest`,
   `LegacyParitySnapshotTest`), so `frontend` still test-depends on `:host` and
   `:rulepacks`, and `rulepacks` still test-depends on `:host`. The ownership is
   split: the parsing-only cases belong in `frontend`, the pipeline cases belong
   in `integration-tests`.

   Fix: split `VueSplitTest`; keep `regionsUseScriptLang` and
   `skipsExternalScripts` in `frontend` (no host/rulepacks imports) and move the
   three pipeline tests to `integration-tests`. Move
   `hostPipelineValidatesConstructedErrorHandlingPacks` there as well, keeping
   the two `BundledRulePackSelection.constructed` assertions as rulepack unit
   tests. Then remove `testImplementation(project(":host"))` and
   `testImplementation(project(":rulepacks"))` from `frontend` and
   `testImplementation(project(":host"))` from `rulepacks` if no other test
   needs them.

2. **`analysis-engine/test-support/build.gradle.kts:1-7`, `analysis-engine/test-support/src/main/kotlin/dev/cint/test/support/TestFixtures.kt:3,26-33` — `test-support` is an integration-level hub built as a main module.**

   The module depends on `:api`, `:core`, `:frontend`, `:rulepacks` and
   `:treesitter`, and its `src/main` imports JUnit for `assumeTrue`. That places
   every parser/pack fixture above the modules that own them: `core` and
   `treesitter` cannot consume the shared helpers without pulling in `frontend`
   and all bundled packs, which is why
   `treesitter/src/test/kotlin/dev/cint/treesitter/support/TestConfig.kt` is now a
   second copy of `test-support`'s `testConfig`. The name reads as a
   layer-neutral fixture module, but it also owns pack construction
   (`TestAnalysis.kt`) and filesystem skip policy (`TestFixtures.kt`), so its
   ownership boundary is unclear and it looks like a shippable module in
   `just build`.

   Fix: split the fixtures by owner. Keep parser/config fixtures with
   `frontend`/`treesitter` test fixtures (for example the Gradle
   `java-test-fixtures` plugin), and keep pack fixtures (`testPacks`,
   `testConfig`) in `rulepacks-tests` or `integration-tests`. Remove JUnit from
   `src/main` by returning a nullable root or throwing a plain error and letting
   each test module apply its own assumption API. Rename the remaining shared
   module to something that states its scope (`test-fixtures` or
   `integration-test-support`) or document that it is test-only. Prefer the
   existing `*-tests` convention for test-source modules; `integration-tests`
   and `rulepacks-tests` are consistent, `test-support` is the odd one out.

3. **`analysis-engine/core/src/test/kotlin/dev/cint/core/facts/view/CapabilityViewProvenanceTest.kt:75-77`, `analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/view/CapabilityViewTest.kt:13,21-22` — the duplicate `CapabilityViewTest` was renamed, not removed.**

   The old core `CapabilityViewTest` became `CapabilityViewProvenanceTest`, but
   its single provenance assertion still duplicates behavior covered by the
   larger `treesitter/view/CapabilityViewTest`, which remains in the parser
   module because it uses `KotlinParser`. That remaining test is the reason for
   the new local `treesitter/support/TestConfig.kt`. Core-owned view semantics
   therefore still depend on a parser-module test, and the ticket's
   "remove duplicate `CapabilityViewTest`" is not met.

   Fix: choose one home for the view coverage. Either move the whole
   `treesitter/view/CapabilityViewTest` to `integration-tests` (it uses the real
   parser) and delete the duplicate core provenance test, or rewrite its
   core-level cases in `core` with `AnalysisContext`/fake syntax and delete the
   treesitter copy. Delete the local `TestConfig` once the shared fixture is
   reachable from the remaining home.

4. **`analysis-engine/core/src/test/kotlin/dev/cint/core/architecture/StructuralBoundariesTest.kt:24-28` — the new test topology has no regression guard.**

   The structural test pins only the `core` and `host` production dependency
   sets. Nothing would fail if a production module added
   `implementation(project(":test-support"))` or
   `implementation(project(":integration-tests"))`, or if `test-support`
   acquired a host dependency, so "no backwards test-only dependencies" is
   convention only. The helper also discovers modules only by
   `src/main/kotlin`, so it cannot currently inspect the test-only
   `integration-tests` module.

   Fix: extend the guard to enumerate module build files and assert that
   production modules' `implementation`/`api` sets exclude `:test-support` and
   `:integration-tests`, that `test-support` has an explicit allowed dependency
   set, and that `rulepacks-tests`/`integration-tests` are leaves with no
   production dependents. Make module discovery handle test-only modules.

### Concurs / Dissents

- Concurs: the scheduler tests in `core/src/test/kotlin/dev/cint/core/runtime/engine`
  now use `FakeSyntax`/`RuleEngineFixtures` and no longer need a parser module;
  `core`'s build stays `:api`-only in production and the fake fixtures do not
  pull `treesitter` or `rulepacks` into the core test classpath.
- Concurs: `treesitter/build.gradle.kts` drops
  `testImplementation(project(":rulepacks"))`, and `CsharpBlockTest`/`WebBlockTest`
  moved to `rulepacks-tests` where their rulepack assertions belong. The
  treesitter module no longer has a backwards test dependency on rulepacks.
- Concurs: composition and host pipeline tests now sit under
  `integration-tests`, whose `testImplementation` edges point only to
  `api`, `core`, `host`, `treesitter`, `frontend`, `rulepacks` and
  `test-support`; no production module depends on it.
- Concurs: `LspOracleTest` no longer uses `/home/cyp/repos/mtquery`; the root,
  fixture file and symbol come from `-Dcint.lsp.*`/`CINT_LSP_*`, the test skips
  explicitly when unconfigured, and asserts a missing fixture, missing server or
  missing symbol instead of printing and returning.
- Concurs: the duplicate `TestAnalysis` helpers are gone, and
  `PackTestHarness` now uses the shared parser dispatch and fixture lookup.
- Concurs: `TestFixtures` searches the tracked repo-root `testing-grounds` and
  reports an explicit skip message; no hardcoded home-directory fixture path
  remains in test source.
- Dissents: findings 1-4 above.

### Residuals (not filed as findings)

- There is still no CI workflow in the repo; 026 leaves `just build` and
  `just test-engine` as the gates. Both now include `integration-tests` (and
  `test-engine` adds `host`), and `just build` still runs Gradle tests despite
  its "without running tests" comment; `:lsp:build` also reaches the now
  opt-in LSP test and skips it when unconfigured. Ticket 027 owns the recipe
  comment and CI wiring, but CI should run `just test-engine` explicitly rather
  than rely on `just build` for tests.
- `test-support`'s `testPacks` reconstructs the bundled pack selection instead
  of using the production `BundledRulePackSelection`, so pack-selection changes
  can make tests exercise a different set from the hosts. It is test-only, but
  a parity check or a shared selection helper would remove the drift.
- `integration-tests/.../CompositionTestSupport.kt` still constructs
  `KotlinParser`/`KotlinSyntaxModel` directly instead of using the shared
  `Frontends` dispatch; acceptable because those tests are Kotlin-specific, but
  it is only partial parser-dispatch sharing.
- `TestFixtures.rootOrNull` walks up from the Gradle working directory and can
  select an ancestor `testing-grounds` outside the checkout when the explicit
  property/env is unset; the fixtures are tracked, so the default is
  reproducible, but CI should set the property to avoid ambiguity.
- `rulepacks` tests still use `:core` (and, for the host-pipeline case,
  `:host`) for integration coverage; the `:host` edge is the remaining
  host-layer test dependency if finding 1 is accepted.
- The engine README module table is not updated for `test-support`,
  `integration-tests` or `host`; 027 is the docs ticket.
- Reviewer performed read/reason only; no build, test or formatter command was
  run, and the handover is assumed green per `docs/workflow.md`.

## Round 2

Verdict: All four Round-1 findings are addressed. The Vue pipeline/host cases
and the host-pipeline selection case now live in `integration-tests`, and the
`frontend`/`rulepacks` test dependencies on `:host`/`:rulepacks` are gone;
`test-support` has been replaced by a proper `java-test-fixtures` module with
`core` owning the shared `testConfig`; the duplicate `CapabilityViewTest` copies
are removed in favor of one real-parser test under `integration-tests`; and the
structural guard now pins the test-only module topology. No new architecture
finding remains.

### Findings

None.

### Concurs / Dissents

- Concurs with F1: `frontend/build.gradle.kts` now declares only
  `testImplementation(kotlin("test"))`; `VueSplitTest` keeps the two region-only
  cases and the three pipeline/coordinate/provenance cases moved to
  `integration-tests/.../frontend/VuePipelineTest.kt`. The host-pipeline case
  moved to `integration-tests/.../host/BundledRulePackSelectionHostTest.kt`, and
  `rulepacks/build.gradle.kts` no longer test-depends on `:host`. The two
  `BundledRulePackSelection.constructed` assertions remain in `rulepacks` where
  they belong. No production or test module now depends on a higher-level host
  to exercise its unit behavior.
- Concurs with F2: `test-support` is gone. `test-fixtures` uses the Gradle
  `java-test-fixtures` plugin and exposes its dependencies through
  `testFixturesApi`; `core` owns `dev.cint.core.test.testConfig` in its own
  `testFixtures` source set, so `treesitter` can use the shared config helper
  without pulling in `frontend`/`rulepacks`. The shared module no longer contains
  a `main` source set or JUnit; fixture skipping is implemented by
  `assumeFixtureRoot` in the two consuming test modules. The naming now matches
  the roles: `test-fixtures` for fixtures, `integration-tests` and
  `rulepacks-tests` for test-source suites.
- Concurs with F3: the only remaining `CapabilityViewTest` is
  `integration-tests/.../view/CapabilityViewTest.kt`, which carries the full
  previous real-parser suite including
  `annotationsRebuildIntentsFromSurvivingProvenance`; the core and treesitter
  copies and the local `TestConfig` are deleted. Core view behavior is covered
  once, in the module that can supply the real parser and core context.
- Concurs with F4: `StructuralBoundariesTest` now asserts that `frontend`,
  `rulepacks` and `treesitter` test dependencies do not include their
  higher-level offenders, that production modules do not depend on
  `:test-fixtures`, `:integration-tests` or `:rulepacks-tests`, and that
  `test-fixtures` has an explicit dependency set. The module discovery was
  adjusted to find test-only modules, so the guard covers the new topology
  rather than just `core`/`host`.
- Concurs: no production module depends on a test-only module, and the only
  edges into `test-fixtures` come from the two test suites. The move preserves
  the one-way dependency direction: `integration-tests` and `rulepacks-tests`
  are terminals; `test-fixtures` aggregates fixtures; `core` test fixtures stay
  at the bottom.
- Concurs: the new modules are picked up by `just build`, `just test-engine` and
  the ktlint source glob, so the CI surface does not need a separate module
  list beyond the recipe changes 027 owns.
- Dissents: none.

### Residuals (not filed as findings)

- `assumeFixtureRoot`/`fixture` are duplicated in `integration-tests` and
  `rulepacks-tests`. Keeping JUnit out of the shared fixture module is a
  reasonable trade; if a third consumer appears, a shared nullable
  `rootOrNull`-based helper or a test-only assumption utility would remove the
  duplication without re-coupling the fixture module to a test runner.
- `test-fixtures`'s `testPacks` still reconstructs the bundled pack selection
  rather than using the production `BundledRulePackSelection`, so it can drift
  from the host set. Unchanged from Round 1; a parity test or shared selection
  helper would close it.
- `CompositionTestSupport` still constructs `KotlinParser`/`KotlinSyntaxModel`
  directly rather than using the shared `Frontends` dispatch. Acceptable for
  Kotlin-specific composition tests, but it remains only partial parser-dispatch
  sharing.
- `TestFixtures.rootOrNull` still searches upward for `testing-grounds` when no
  property/env is set. The repo-tracked fixtures make the default reproducible,
  but CI should set the explicit root to avoid an ancestor directory winning.
- The structural guard's project-dependency scan is regex- and line-oriented; a
  future multiline Gradle declaration could escape it. Current build files are
  single-line, so this is a maintenance note rather than a current violation.
- The `justfile` comment/CI reconciliation and the engine README module table
  remain with 027.
- Reviewer performed read/reason only; no build, test or formatter command was
  run, and the handover is assumed green per `docs/workflow.md`.
