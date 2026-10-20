# Correctness review — ticket 015 (host cutover: cli and server)

## Round 1

Verdict: The hosts are genuinely cut over: the batch CLI, HTTP host and live
daemon now load one pack snapshot through `RulePackLoader`, plan through
`RuleEngine`/`RuleScheduler`, select packs by language, cache plans, and route
Vue script regions through the scheduler with per-region language and host-
coordinate shifting. The production paths no longer instantiate
`AnalysisEngine`/`IntentBlocks`, and the live payload still carries the existing
transport fields. The cutover is not yet behavior-preserving, however: the new
plan has no `VALIDATION` detector, custom `--config` detection values are now
ignored by the rules, and the artifact's `intents` list is not derived from the
selected plan. The batch path also treats new-engine analysis failures
differently from split/parse failures.

### Findings

1. **`analysis-engine/cli/src/main/kotlin/dev/cint/cli/Main.kt:116`, `analysis-engine/server/src/main/kotlin/dev/cint/server/LiveServer.kt:240`, `analysis-engine/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack:1-2` — the cutover drops `VALIDATION` detection before ticket 009 lands.**
   The service file registers only `logging` and `bookkeeping`; the per-language
   `error-handling` pack is added by `BundledRulePackSelection`. No validation
   rulepack exists, so `IntentBlocks`' `ValidationBlock`/`ReturnBlock`
   (`analysis-engine/core/src/main/kotlin/dev/cint/core/rules/Blocks.kt:265,357,731,738`)
   are no longer on any host path. A concrete fixture regresses:
   `testing-grounds/javascript/Sample.js:16` (`if (values.isEmpty()) return;`)
   was a validation guard with the legacy engine and now produces no
   `VALIDATION` source annotation. The artifact's `intents` array can still say
   `VALIDATION` because it is copied from the old config, which masks the loss.
   This is the acceptance's batch-parity risk (`m001`), not merely an unverified
   manual step.
   Fix: land the validation rulepack (009) before or with this cutover, keep the
   legacy validation path alive until then, or explicitly narrow the parity
   criterion and record the resulting behavior change; capture the `m001`
   baseline before 014 removes the legacy path.

2. **`analysis-engine/cli/src/main/kotlin/dev/cint/cli/Main.kt:64,116` and `analysis-engine/server/src/main/kotlin/dev/cint/server/HostRulePacks.kt:25-31` — the loaded `IntentConfig` no longer drives detection, but the hosts still accept it as if it did.**
   `IntentBlocks.build(config)` used every intent's configured `blocks` and
   `params`. The new rules use their pack-local defaults (and
   `RuleEngine`/`Composition` consult `config` only for `language` and
   `pureAnnotations`). A user-supplied `--config` (or HTTP `config`) that
   overrides `LOGGING` calls/guard names, removes `BOOKKEEPING`, or changes
   `ERROR_HANDLING` values now silently has no effect on detection. That is a
   regression in the CLI/server contract while tickets 012/013 are still todo,
   not just a different internal path.
   Fix: thread the typed pack config into the selected packs (012/013), defer
   host cutover until then, or reject/document custom detection config with a
   named follow-up so the flag does not silently no-op.

3. **`analysis-engine/cli/src/main/kotlin/dev/cint/cli/Main.kt:53-59` and `analysis-engine/server/src/main/kotlin/dev/cint/server/HostRulePacks.kt:33-39` — artifact intents are built from config keys plus all service-loaded packs, not from the packs selected for the language.**
   The per-language `error-handling` pack is added only during
   `BundledRulePackSelection.select`, after the loader snapshot, so its
   `ERROR_HANDLING` owner is absent from `intents()` unless the old config
   happens to declare it. Conversely, a service-loaded pack whose `languages`
   excludes the current language still contributes its intent to the artifact.
   With a config that omits `ERROR_HANDLING` (or once 014 removes the config
   file), the artifact can omit an intent the engine emits, and for a language
   with no selected error pack it can advertise an intent that cannot fire.
   The default `intents.yaml` currently hides this only by listing all four
   intent names.
   Fix: derive `intents` from the selected plans (including the per-language
   error pack and, for split hosts, the script-region languages), or at minimum
   union every bundled per-language pack's owned intents and filter service
   packs by language; add a test with a config that omits a pack intent.

4. **`analysis-engine/cli/src/main/kotlin/dev/cint/cli/Main.kt:111-117` — a new-engine analysis exception aborts the whole batch, unlike parse and split errors.**
   `frontend.parse` is wrapped in `runCatching`, and split analysis is wrapped
   around the entire `SplitAnalyzer` call, but the non-split `packs.analyze`
   call is unguarded. `RuleEngine` can throw by design (`NonConvergentRule`,
   `NonMonotoneRule`, or a rule's own exception), so one problematic file or
   third-party rule terminates the run and produces no artifact rather than
   reporting/skipping that file. The live host catches analysis failures and
   falls back to heuristics; the batch host does not.
   Fix: wrap `packs.analyze` per file with the same failure reporting used for
   the split path (and distinguish analysis from parse failures), or state that
   analysis exceptions are fatal for a batch run.

### Concurs / Dissents

- Concurs: all production host paths use `RulePackLoader`/`RuleEngine`; no
  `AnalysisEngine`, `IntentBlocks` or `Phase` reference remains in `cli` or
  `server` production code, including the Vue split and heuristic-fallback paths.
- Concurs: `BundledRulePackSelection` filters by declared `languages`, adds
  exactly one per-language `error-handling` pack, and leaves a selected
  third-party owner in place; `BundledRulePackSelectionTest` covers the four
  error languages, a `vue` host, and a third-party `ERROR_HANDLING` owner.
- Concurs: split analysis passes the region language to `planFor`, reloads the
  region config, and shifts spans/contributors back to host coordinates; the new
  `VueSplitTest` exercises the scheduler path for a TypeScript region.
- Concurs: plan caches are keyed by language, the loaded snapshot is immutable,
  and the CLI/server plans are built once per language, so the shared
  `RuleEngine` does not change plans between files or requests.
- Concurs: the artifact writer still emits structured provenance, and the live
  `cint/publishTags` shape (uri, version, hash, annotations) is unchanged.
- Concurs: for the default `intents.yaml`, the artifact's `intents` list still
  has the legacy order/set (`LOGGING`, `BOOKKEEPING`, `ERROR_HANDLING`,
  `VALIDATION`, `UNTAGGED`), so the intent-name list itself is in parity; the
  emitted annotations are not (finding 1).
- Concurs: `m004` exists and describes a plausible live-socket verification;
  `m001`/`m002` remain unverified, which is allowed by the manual gate.
- Dissents: findings 1-4 above.

### Residuals

- The old five-argument `SplitAnalyzer.analyze` overload still uses the legacy
  `AnalysisEngine` and is still used by the older split tests; production uses
  the new overload, but the legacy path remains a second implementation until
  014 deletes it.
- No automated test constructs `cli.HostRulePacks`/`server.HostRulePacks` or
  drives the CLI/server, so the loader-to-host wiring is covered only indirectly
  by `BundledRulePackSelectionTest`, `BundledRulePackLoadingTest` and the new
  `VueSplitTest` case.
- `HostRulePacks` is duplicated (private in `cli`, public in `server`); the two
  copies currently agree, but future changes to language selection or intent
  reporting can drift.
- The live host's heuristic fallback re-runs the same scheduler with a null
  oracle; a deterministic rule exception is not rescued by that fallback.

No build, test or formatter command was run by this role; the handover was
treated as green per `workflow.md`.

## Round 2

Verdict: Findings 2-4 are addressed. `HostPipeline` centralizes discovery,
language selection, constructed-pack validation, plan caching and the intent
vocabulary; the CLI and server now reject custom config explicitly; and the CLI
wraps non-split analysis per file. The constructed error-handling pack now goes
through `RulePackLoader.validate` instead of bypassing validation. Finding 1 is
still open: the scheduler plan has no validation rule, and the new plan-derived
vocabulary now removes `VALIDATION` from the artifact's `intents` list as well
as from its annotations. One new correctness finding remains in the config
cutover: the renderer still forwards `--config` to a server that now refuses it.

### Findings

1. **`nvim-renderer/lua/cint/live.lua:57-60` versus `analysis-engine/server/src/main/kotlin/dev/cint/server/Main.kt:68` — the explicit unsupported-config error breaks the renderer's live launch path.**
   `ensure_server` still appends `--config <opts.config>` whenever the renderer
   setup includes a `config` value, but `cint-server` now calls
   `error(UNSUPPORTED_CONFIG)` before binding or starting `LiveServer`. The
   renderer therefore waits for the socket, reports "server did not start", and
   live rendering plus `:CintServerRestart` fail for any user of that option.
   The HTTP `request.config` rejection is explicit and fine, but the renderer
   side of the same contract was not updated. The CLI usage string also still
   advertises `--config` (`cli/Main.kt:133-134`) before rejecting it.
   Fix: remove the renderer's `--config` forwarding and surface the unsupported
   option in the client (or keep a supported engine-config path until 012/013),
   and remove `--config` from the CLI usage/help; add a live-start check or
   manual entry covering the chosen behavior.

### Concurs / Dissents

- F1: dissents. The service file still registers only `logging` and
  `bookkeeping`, `BundledRulePackSelection` constructs only per-language
  `error-handling`, and no validation rulepack exists
  (`analysis-engine/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack:1-2`).
  `HostPipeline.intents()` now derives the artifact vocabulary from
  `plan(...).terminal.sourceIntents`, so `VALIDATION` disappears from the
  artifact's `intents` array too; the legacy `intents.yaml` had it, and
  `testing-grounds/javascript/Sample.js:16` still cannot produce the old
  validation annotation. `m001` therefore cannot pass as written.
- F2: concurs. `cli/Main.kt:35` rejects `--config`, `server/Main.kt:68` rejects
  `--config`, and `server/Main.kt:103` returns an explicit 400 for
  `AnalyzeRequest.config`; custom detection values no longer silently no-op. The
  new live-config finding above is the remaining cross-module consequence.
- F3: concurs. `HostPipeline.intents()` unions the terminal intents of the plans
  for the languages actually used; the CLI supplies non-split config language or
  the split-region languages, and the server supplies the split-region
  languages. The constructed error-handling pack is included because it now
  participates in the validated snapshot, so `ERROR_HANDLING` no longer depends
  on the legacy config key.
- F4: concurs. Non-split `packs.analyze` is now wrapped with per-file failure
  reporting (`cli/Main.kt:93-95`), matching the split and parse paths; the batch
  continues instead of aborting on a rule exception.
- Concurs: `HostPipeline` is a single shared composition path for both hosts,
  removes the duplicated `HostRulePacks` classes, and keeps the caches keyed by
  normalized language.
- Concurs: constructed packs are filtered by language, skipped when a discovered
  pack already owns their intent, and passed through
  `RulePackLoader.validate`/the scheduler; `HostPipelineTest` covers validation
  failure, dependency on a discovered pack, owner preservation and union
  vocabulary.
- Concurs: language selection still yields exactly one error-handling owner per
  language, and the new tests cover construction and host-plan wiring.
- Concurs: split analysis still uses the region language, host-coordinate
  shifting and the new plan function; the transport payload is unchanged.
- Dissents: F1 and new finding 5.

### Residuals (Round 2)

- `server/Main.kt:141-148` passes an empty language set to
  `HostPipeline.intents()` for a split buffer with no regions, yielding only
  `UNTAGGED`; the CLI falls back to `config.language`
  (`cli/Main.kt:105-106`). The two hosts therefore disagree on the vocabulary
  for an empty split buffer, and both differ from the legacy full intent list.
- `RulePackLoader.load()` still validates all discovered packs before language
  filtering, so two discovered packs with disjoint `languages` that own the same
  intent are rejected globally even though `HostPipeline` would never schedule
  them together. The bundled error packs avoid this only by being constructed
  after discovery.
- The old five-argument `SplitAnalyzer` overload and the legacy `Phase`/
  `AnalysisEngine` path remain for tests until 014; the new host coverage still
  does not drive the CLI/server entry points directly.
- The constructed-pack pre-filter silently drops any constructed pack whose
  intent overlaps a discovered owner instead of reporting a collision; this is
  intended for the bundled error pack but is broader than the current use.
- No build, test or formatter command was run by this role; the handover was
  treated as green per `workflow.md`.

## Round 3

Verdict: Finding 5 is addressed: `HostConfig` validates a supplied config, warns
when it is valid but not wired into rulepacks, and falls back to defaults, so a
valid `--config` no longer prevents `cint-server` from starting; missing and
invalid configs remain hard failures as requested. The server's empty-region
vocabulary fallback also now matches the CLI. Findings 2-4 remain addressed. F1
is still open: the new validation test is generic and does not add a validation
rule to the production service/constructed packs, so the hosts still cannot emit
the legacy `VALIDATION` annotations or list that intent in the plan-derived
artifact vocabulary. No new correctness finding was introduced by this round.

### Findings

- None beyond the unresolved F1 described below.

### Concurs / Dissents

- F1: dissents. `analysis-engine/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`
  still registers only `logging` and `bookkeeping`, and
  `BundledRulePackSelection.constructed()` still supplies only per-language
  `error-handling`. `HostPipelineTest.serviceValidationPackJoinsSelectionAndVocabulary`
  proves that a service-supplied validation pack would flow through selection and
  vocabulary, but it does not put such a pack on the production classpath. The
  default hosts therefore still omit `VALIDATION` from emitted annotations and
  from `HostPipeline.intents()`; `testing-grounds/javascript/Sample.js:16` and
  `m001` remain unmet. This needs 009 (or an approved narrowing), not another
  synthetic host test.
- F2: concurs. `HostConfig.load()` validates a supplied config and warns before
  continuing with defaults; the CLI, HTTP entry point and live startup all route
  through it. Missing or invalid configs still throw, so the no-op is no longer
  silent.
- F3: concurs. `HostPipeline.intents()` still derives the vocabulary from the
  selected plans, and the server now falls back to `config.language` for a split
  buffer with no regions, matching the CLI. The remaining `VALIDATION` absence is
  F1, not a vocabulary-derivation defect.
- F4: concurs. Per-file CLI analysis failure handling is unchanged and still
  matches the split/parse paths.
- F5: concurs. The renderer may continue to forward `opts.config`; the server now
  accepts a valid path, logs the unsupported-config warning, and starts the live
  daemon, so live rendering and `:CintServerRestart` no longer fail on that
  option. Invalid or missing paths still fail before startup, as intended.
- Concurs: `HostPipeline` remains the single validated loader/selection/plan
  path for both hosts, constructed packs still pass through
  `RulePackLoader.validate`, and language filtering still yields one
  error-handling owner per language.
- Concurs: split analysis, host-coordinate shifting, structured provenance and
  the live transport shape are unchanged.
- New findings: none.

### Residuals (Round 3)

- The validation test uses a synthetic service resource and never exercises a
  real bundled validation rule; the production detection gap remains until 009.
- `HostConfig` validates the custom file and then loads the default file, so a
  future removal of the default `intents.yaml` (014) must update this fallback.
- The custom-config warning is written to the daemon/CLI stderr rather than
  surfaced by the renderer; the renderer still forwards `--config`, so a user
  must read the server log to learn that detection values were ignored.
- `RulePackLoader.load()` still validates all discovered packs before language
  filtering, so disjoint-language discovered packs that own the same intent
  cannot both load; the bundled error packs avoid this by being constructed after
  discovery.
- The old five-argument `SplitAnalyzer` overload and the legacy `Phase`/
  `AnalysisEngine` path remain for tests until 014.
- No build, test or formatter command was run by this role; the handover was
  treated as green per `workflow.md`.
