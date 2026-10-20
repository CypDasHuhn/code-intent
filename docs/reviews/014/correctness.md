# Correctness review — ticket 014 (delete legacy config and phase)

## Round 1

Verdict: The legacy path is genuinely gone. `intents.yaml`,
`Phase`/`AnalysisEngine`/`RuleBlock`, `rules/Blocks.kt`, the
`loadIntentConfig` loader and the legacy `SplitAnalyzer` overloads are removed;
`rg` finds no remaining production or test reference to them outside historical
docs. The production diff is deletion-only apart from dropping
`IntentDefinition.blocks`, so behavior from 013 + 010 is preserved by
inspection: hosts already used `RuleEngine`/`HostPipeline`, composition and
rulepacks are untouched, and the `IntentConfig` carrier still supplies only core
purity plus validation parameters. The main problems are in the test rewrite:
the parity evidence that backed 013 and the manual gate is removed or reduced to
a non-empty smoke check, and the manual rows still claim that evidence exists.

### Findings

1. **`analysis-engine/frontend/src/test/kotlin/dev/cint/frontend/HostConfigWiringTest.kt:147-190`; `analysis-engine/frontend/src/test/kotlin/dev/cint/frontend/LoggingRulePackTest.kt:108`; `analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/ErrorHandlingRulePackTest.kt:214-220`; `BookkeepingRulePackTest.kt`, `ValidationRulePackTest.kt`, `CompositionTerminalTest.kt` — the rewrites remove the parity assertions and replace the host fixture walk with `assertTrue(actual.isNotEmpty(), case.path)`.**

   `hostDefaultsAnalyzeEveryFixture` no longer compares annotation shape against
   the 013 baseline for any of the 11 fixtures, including the Vue region path.
   `ErrorHandlingRulePackTest.assertDetects` only checks that at least one hit
   exists, and the fixture parity tests in the logging, bookkeeping, validation
   and composition suites are deleted. That drops per-fixture verification of
   spans, intents, source tiers, confidence and rule identity, and leaves
   `m003`/`m006` claiming automated parity that no longer exists. The deletion
   itself is correct once the legacy engine is gone, but a frozen expectation
   snapshot would preserve the acceptance evidence without re-introducing
   `Blocks.kt`. Fix: freeze the 013/HEAD annotation shapes as test data (or keep
   equivalent exact assertions per fixture, including role and the Vue region
   config path) and use those instead of a non-empty check; if that is not
   wanted, mark the affected manual rows unverified with a reason.

2. **`docs/manual-test.md:9,11,14` — the manual gate still assumes the deleted parity tests and an uncaptured pre-014 baseline.**

   `m003` says `testing-grounds/` parity is automated in `LoggingRulePackTest`,
   but this diff deletes `matchesLegacyLoggingOverTestingGrounds`. `m006` says
   parity across all fixtures, including Vue, is automated in
   `HostConfigWiringTest`, but that test now only asserts non-empty output.
   `m001` says the pre-restructure baseline must be captured before 014 deletes
   the legacy path, and it is still `unverified`; after this change the legacy
   path is only in history. Fix: either restore frozen parity evidence, or update
   `m003`/`m006` to say the automated comparison was removed and record the
   resulting gap; capture or explicitly defer/record `m001` before closing 014.

3. **`analysis-engine/core/src/main/kotlin/dev/cint/core/config/IntentConfig.kt:3-12` — dead `IntentDefinition` members remain after removing `blocks`.**

   `IntentDefinition.name` is now only written by `HostConfig.intentConfig` and
   `TestAnalysis.testConfig` and never read, and `IntentDefinition.has(...)` has
   no caller after `Blocks.kt` was deleted. That is the "now-dead plumbing" the
   ticket scope asks to remove. Fix: drop the unused members (or replace the
   map with the validation-parameter carrier that composition actually needs),
   while keeping `language`/`pureAnnotations`.

4. **`analysis-engine/README.md:65-100,208-211` — the edited README still presents the deleted block model and stale rule ids.**

   The classification section is headed `Blocks:` and still lists legacy block
   names, and the artifact example uses `typed-receiver` / `intent-scaffold`
   while the current contributors emit pack/core ids such as
   `logging:logging` and `core:scaffold`. Since this ticket deletes the block
   path and the README is part of the diff, the cleanup should not leave the
   deleted concept documented as current. Fix: retitle the section to rulepacks
   / rules and update the artifact example rule ids.

### Concurs / Dissents

- Concurs: no reference to `intents.yaml`, `Phase`, `AnalysisEngine`,
  `IntentBlocks`, `RuleBlock`, `loadIntentConfig` or `rules/Blocks.kt` remains
  in `analysis-engine` production or test sources; historical docs are the only
  remaining mentions and are explicitly out of scope.
- Concurs: deleting the legacy `SplitAnalyzer` overloads is safe; CLI, server
  and live paths already used the plan/config overload at HEAD.
- Concurs: `HostConfig` no longer needs `IntentDefinition.blocks`, and the
  validation/purity values still flow from pack config into composition, so the
  config carrier is behaviorally equivalent to 013.
- Concurs: composition, rulepacks, scheduler and engine code are otherwise
  unchanged; no new error path is introduced, and missing/invalid project config
  still fails at `HostConfig.load`.
- Dissents: findings 1-4.

### Residuals

- `analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/TestAnalysis.kt`
  is untracked; it is required by the modified tests and must be staged, or the
  handover will not build.
- No build, test or formatter command was run by this role; the handover is
  treated as green per `workflow.md`.

## Round 2

Verdict: All four Round-1 findings are addressed. `LegacyParitySnapshotTest`
freezes the full annotation output for all 11 `testing-grounds/` fixtures,
including the Vue region path, and compares role, intents, evidence, span,
source, confidence and byte offsets; the second snapshot additionally pins the
current rule ids. The two frozen snapshots agree with each other apart from the
rule field, and the current host path still matches the frozen shape. The manual
rows now describe the snapshot coverage accurately, `m008` records the verified
pre-014 parity check, the dead `IntentDefinition.name`/`has` members are gone,
and the README no longer documents the deleted block model or old rule ids. No
new correctness finding.

### Findings

- None.

### Concurs / Dissents

- F1: concurs. The parity evidence is restored via frozen snapshots rather than
  a non-empty smoke check; the fixture set, Vue region languages, annotation
  fields and rule identity are all covered.
- F2: concurs. `m001` is still correctly `unverified` with an accurate note,
  `m003`/`m006` now point at the snapshot test instead of the deleted parity
  tests, and `m008` records the verified pre-014 comparison.
- F3: concurs. `IntentDefinition` now carries only `params`; no caller writes or
  reads `name`/`has`, and `HostConfig`/test helpers are updated.
- F4: concurs. The README classification section now names rulepacks and core
  contributors, and the artifact example uses `logging:logging` /
  `core:scaffold`.
- Dissents: none.

### Residuals

- `LegacyParitySnapshotTest.kt`, `src/test/resources/snapshots/`, `TestAnalysis.kt`
  and this review directory are still untracked; they must be staged with the
  ticket or the test source set will not build.
- The snapshot `commit` field is self-declared metadata; I could not independently
  regenerate the legacy file without running a build, but its contents are
  consistent with the pre-014 pipeline snapshot and the 013 parity result.
- No build, test or formatter command was run by this role; the handover is
  treated as green per `workflow.md`.
