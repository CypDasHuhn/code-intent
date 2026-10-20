# Architecture review — ticket 015 (host cutover: cli and server)

## Round 1

Verdict: The cutover is directionally correct. Both production hosts now load a
pack snapshot, plan through `RuleScheduler`, analyze through `RuleEngine`, and
have no runtime dependence on `Phase`, `AnalysisEngine`, or `IntentBlocks`; the
Vue split path and the live transport are preserved. It is not yet
architecturally clean: the loader is no longer the single validated source of
the packs that run, and host composition policy is duplicated and derives the
published intent vocabulary outside the selected plans.

### Findings

1. **`analysis-engine/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack:1-2`, `analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/BundledRulePackSelection.kt:24-30`, `analysis-engine/server/src/main/kotlin/dev/cint/server/HostRulePacks.kt:16-22`, `analysis-engine/cli/src/main/kotlin/dev/cint/cli/Main.kt:36-42` — the bundled error-handling pack bypasses the loader/discovery path.**

   `RulePackLoader` discovers only `LoggingRulePack` and `BookkeepingRulePack`;
   `BundledRulePackSelection` then constructs `ErrorHandlingRulePack` directly
   and appends it to the plan after loading. That pack is not in
   `RulePackLoadResult.packs` and never passes the loader's API-version, pack-id,
   or rule-namespace validation. The same applies to any future pack introduced
   by selection rather than service registration, so the loader result is not an
   authoritative snapshot of what will run. This also puts host pack-selection
   policy inside the plugin distribution, which the hosts now import directly.
   Fix: make the selected packs flow through the same validation path as
   discovered packs. Either register a single error-handling provider that
   chooses per-language config through `View.language()` (as the logging pack
   does), or make selection/validation language-aware so the selected set is
   validated before planning. Keep one validated snapshot as the source of both
   plans and intent reporting.

2. **`analysis-engine/server/src/main/kotlin/dev/cint/server/HostRulePacks.kt:15-39`, `analysis-engine/cli/src/main/kotlin/dev/cint/cli/Main.kt:35-59`, `analysis-engine/server/src/main/kotlin/dev/cint/server/Main.kt:138-140` — host composition is duplicated and the artifact's intent vocabulary is not derived from the selected plan.**

   The CLI and server each contain a private/public copy of the same
   loader/selection/plan-cache/intents policy. More importantly, `intents()`
   unions the legacy `config.intents.keys` with every loaded pack's owned
   intents, without applying language selection and without including the
   directly constructed error-handling pack. A `vue` batch therefore renders a
   `vue` plan's intents while its annotations come from TypeScript/JavaScript
   regions, and a config without `ERROR_HANDLING` can omit an intent the plan
   emits. This leaves the artifact contract coupled to the legacy config and to
   loader inventory rather than to the plan that produced the annotations, and
   the two host copies can drift. This is the module/host-policy side of the
   correctness review's finding 3. Fix: extract one shared host composition
   helper used by both hosts, and derive `intents` from the selected plan(s)
   (union of owned intents plus `UNTAGGED`, including split-region languages).
   Remove `config.intents` from the publishing path; 014 removes that legacy
   surface anyway.

### Concurs / Dissents

- Concurs: both production hosts use a single `RulePackLoader` snapshot,
  `RuleEngine`/`RuleScheduler` plans cached by language, and no `Phase`,
  `AnalysisEngine`, or `IntentBlocks` reference remains in `cli` or `server`
  production code.
- Concurs: `BundledRulePackSelection` filters packs by declared language, adds
  exactly one per-language error-handling pack, and preserves a selected
  third-party `ERROR_HANDLING` owner; the unit test covers the four error
  languages, a `vue` host, and a third-party owner.
- Concurs: split analysis uses the new overload, passes the region language to
  the plan lookup, re-loads the region config, and shifts spans and provenance
  into host coordinates.
- Concurs: the live `cint/publishTags` transport shape (uri, version, hash,
  annotations) and the structured provenance fields are unchanged; payload
  redesign remains 016.
- Concurs with correctness findings 1-4; finding 2 above is the architecture
  expression of its finding 3.
- Dissents: findings 1-2 above.

### Residuals

- The old five-argument `SplitAnalyzer.analyze` overload still instantiates
  `AnalysisEngine(IntentBlocks.build(config))` and is still used by the older
  split tests. Production hosts do not reach it, but until 014 deletes
  `Blocks.kt`/`Phase` it remains a second split implementation and must be
  removed with them.
- No automated test constructs either host `HostRulePacks` or drives the
  loader-to-selection-to-plan wiring; coverage is only indirect through
  `BundledRulePackLoadingTest`, `BundledRulePackSelectionTest`, and the new
  `VueSplitTest` case.
- `m004` records a verified live-socket run; `m001`/`m002` remain unverified,
  which the manual gate allows. No build, test, or formatter command was run by
  this role; the handover was treated as green per `workflow.md`.

## Round 2

Verdict: Both round-1 architecture findings are addressed. Host composition now
lives in one `HostPipeline` shared by the CLI and both server modes, the artifact
intent vocabulary is derived from the selected plans (including split-region
languages), and constructed packs pass through `RulePackLoader.validate` with the
same API-version, pack-id, rule-namespace, duplicate-owner, and scheduler checks
as service-discovered packs. No new architecture finding.

### Findings

None.

### Concurs / Dissents

- Concurs: finding 1 is addressed. `BundledRulePackSelection.constructed` keeps
  the per-language error-handling pack out of the global service file, while
  `HostPipeline.snapshot` filters it by language, combines it with discovered
  packs, and calls `RulePackLoader.validate` (`core/host/HostPipeline.kt:50-72`,
  `core/rulepack/RulePackLoader.kt:74-90`). The constructed pack is no longer an
  unvalidated addition, and `HostPipelineTest` covers invalid API versions,
  dependency on discovered packs, and owner non-displacement.
- Concurs: finding 2 is addressed. Both hosts use
  `HostPipeline(constructedPacks = BundledRulePackSelection::constructed)` and
  no longer carry duplicate loader/selection/intent logic. `intents()` unions
  `plan(language).terminal.sourceIntents` for the languages actually analyzed,
  appends `UNTAGGED`, and no longer reads `config.intents`
  (`cli/Main.kt:51,73-107`, `server/Main.kt:70,138-155`). Split hosts collect
  their region languages for the vocabulary as well.
- Concurs: the hosts still reject custom detection config before analysis rather
  than silently ignoring it, and the batch path now guards per-file analysis; the
  core/module boundary and transport shape remain as reviewed in round 1.
- Dissents: none.

### Residuals

- Constructed packs are still not `ServiceLoader` providers; this is now a
  documented language-scoped exception that is validated through the same loader
  path, so it is not a finding.
- The legacy five-argument `SplitAnalyzer.analyze` overload remains until 014
  removes `Blocks.kt`/`Phase`; production hosts continue to use only the new
  overload.
- The artifact's `intents` array is now emitted in sorted plan order rather than
  the legacy config order. The acceptance criterion is set-based and no renderer
  consumer reads the top-level array, so this is a contract note rather than an
  architecture finding; `m001` should compare intent sets, not order.
- CLI and server still collect their own analyzed-language sets because their
  inputs differ (files vs request); this is host-specific input handling, not
  duplicated composition policy.
