# Correctness review — ticket 013 (config migration off intents.yaml)

## Round 1

Verdict: The migration itself is complete and the defaults match the legacy
profiles: every `intents.yaml` detection value now has a pack owner, the hosts
build `IntentConfig` from `HostSettings`, and no production host reads the legacy
resource. Three config-application gaps remain: constructed packs are configured
before the API-version gate, constructed/snapshot config failures never reach
`HostPipeline.failures`, and `core.lsp` is parsed but ignored by every host.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/host/HostPipeline.kt:67-84` with `analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:103-140` — a constructed pack receives config before the API-version gate.**
   `snapshot` calls `configureConstructed` (which calls the new public
   `RulePackLoader.configure`) before `loader.validate(selected + additions)`.
   The public `configure` reads only `pack.id`; it never compares
   `pack.apiVersion` with `expectedApiVersion`. The version check lives in
   `validate`, which runs afterwards, so an incompatible constructed
   `ConfigurableRulePack` executes `configure(values)` across the frozen
   boundary and only then is rejected. This reintroduces the ordering hole fixed
   for service-discovered packs in 012.
   Suggested fix: preflight constructed-pack metadata (id and api version) before
   applying config, reusing `preflight`/splitting `validate`, then configure and
   validate the result. Add a constructed-pack fixture with a mismatched
   `apiVersion` whose `configure` records a call and assert the call never
   happens and the pack is skipped as `INCOMPATIBLE_API_VERSION`.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/host/HostPipeline.kt:32,60-85` — constructed-pack and snapshot config failures disappear from `HostPipeline.failures`.**
   `failures` returns only the service-discovery failures captured at
   construction. `configureConstructed` calls `loader.configure(pack, values)`,
   whose failures are accumulated in a local list and delivered only to
   `onFailure`; a subsequent `loader.validate(selected + additions)` result is
   also discarded except for `.packs`. Consequently an invalid constructed
   config such as `error-handling-kotlin: {bogus: true}` drops the pack (and its
   intent from the artifact vocabulary) while `pipeline.failures` stays empty.
   With the silent loader used by `HostPipelineTest` there is then no observable
   diagnostic at all, despite the ticket requiring unknown packs/keys to be
   reported. The same applies to a constructed pack rejected by validation.
   Suggested fix: make `snapshot` return the `RulePackLoadResult` (or merge its
   failures into a per-language failure list) and expose those failures through
   `HostPipeline.failures`; add a regression asserting `INVALID_PACK_CONFIG` is
   reported and the pack is skipped.

3. **`analysis-engine/core/src/main/kotlin/dev/cint/core/host/HostConfig.kt:14-30`, `analysis-engine/core/src/main/kotlin/dev/cint/core/config/ProjectConfig.kt:96-97`, `analysis-engine/cli/src/main/kotlin/dev/cint/cli/Main.kt:59-63,82`, `analysis-engine/server/src/main/kotlin/dev/cint/server/Main.kt:115-124`, `analysis-engine/server/src/main/kotlin/dev/cint/server/LiveServer.kt:232-253` — `core.lsp` policy is parsed but never applied.**
   `HostSettings.intentConfig` copies `project.core.purityAnnotations` but the
   parsed `CorePolicy.lsp` is still unused; `CorePolicy.oracleOptions()` has no
   production caller. CLI and server keep deriving `OracleOptions` from flags and
   hard-coded timeouts, and the live server from `useLsp`/`LIVE_LSP_TIMEOUT_MS`.
   A project config setting `core.lsp.enabled: false` or a custom timeout
   therefore has no effect on any host, so the `core:` section advertised by
   architecture §8 is only half applied (012 deferred this host wiring to the
   migration ticket).
   Suggested fix: derive the oracle options from `settings.project.core.lsp` in
   all three hosts (and in split recursion), with an explicit precedence rule
   for the `--no-lsp` flag, and add tests that a config-supplied enabled/timeout
   reaches the CLI/server/split paths.

### Concurs / Dissents

- Concurs: every `intents.yaml` detection value has a pack owner. Logging's
  Kotlin parameters and JavaScript/TypeScript/C# overrides match
  `LoggingConfig.defaults()`; error-handling `calls`/`throws` and the JS/TS/C#
  overrides match `ErrorHandlingConfig.forLanguage`; validation
  `predicates`/`predicatePrefixes` match `ValidationConfig`; purity moved to
  `ProjectConfig.core`. `blocks` are rule structure and correctly stopped being
  config.
- Concurs: override semantics are preserved. Project values replace only the
  named pack field, the language-scoped `error-handling-<language>` ids select
  the same profiles as the old `overrides.<language>` entries, and validation
  reaches both the rule and core return categorization through the same typed
  values.
- Concurs: hosts no longer drive detection from `intents.yaml`. CLI, HTTP,
  live and split-region paths construct `IntentConfig` from `HostSettings`; the
  remaining `loadIntentConfig` calls are on legacy `SplitAnalyzer` overloads
  used only by tests and are scheduled for removal in 014.
- Concurs: config decoding stays pack-local. `ConfigDecoding`, the pack
  `withOverrides` methods, and `RulePackConfigException` keep key schemas out of
  core; unknown pack ids and unknown keys are reported by the loader and one bad
  pack does not abort the others.
- Concurs: defaults apply without a config file and the migration is pinned by
  `HostConfigWiringTest` over `testing-grounds` plus the pack-level decoder
  tests.
- Dissents: findings 1-3 above.

### Residuals (not findings)

- `HostSettings.intentConfig` takes `validationParams` from the caller instead
  of deriving them from `project`; all current hosts compute the same map, but
  the API accepts an empty/partial map (as `HostConfigTest` demonstrates) and
  would silently disable predicate-based return categorization while the
  validation rule still uses its own config. Resolving validation params once
  in `HostSettings` would remove that second source of truth.
- Discovered-pack config failures are reported only through the loader's
  `onFailure` (stderr by default); no host inspects `pipeline.failures`, so a
  batch run can continue with a degraded pack set and exit successfully. This
  matches graceful degradation, but the batch/server error policy is still
  implicit.
- Custom config application is tested for logging, error-handling and
  validation; bookkeeping overrides and custom purity through a host have only
  decoder-level coverage.
- Static review only; no source edit, build, test or formatter command was run
  by this role, per `workflow.md`.

## Round 2

Verdict: Round-1 findings F1-F3 are addressed: constructed packs are preflighted
before configure, `HostPipeline` now exposes constructed/snapshot failures, and
`core.lsp` reaches host oracle options. The strengthened parity test and m006
cover the earlier detection gaps, and removing the composition magic-comment
fallback is backed by a host test. One new correctness regression remains: the
live daemon's default LSP timeout changed from 750 ms to the framework's 5000 ms.

### Findings

1. **`analysis-engine/server/src/main/kotlin/dev/cint/server/LiveServer.kt:211-219` with `analysis-engine/core/src/main/kotlin/dev/cint/core/config/ProjectConfig.kt:15-18` — the live LSP timeout default regressed from 750 ms to 5000 ms.**
   The round-1 wiring replaced `LIVE_LSP_TIMEOUT_MS = 750L` with
   `settings.oracleOptions()`. With no project config, `ProjectConfig.core.lsp`
   defaults to `LspPolicy(enabled = true, timeoutMs = 5000)`, so `oracleFor` and
   split-region analysis in the daemon now wait up to 5 s on a hung oracle
   instead of the previous 750 ms cap. This is a behavior change in the live
   editor path, and it is exactly the 5000-vs-750 decision 012 recorded as
   needing an explicit choice. The CLI and HTTP hosts previously used 5000 ms,
   so only the daemon regresses.
   Suggested fix: preserve a live-specific default or cap (for example an
   `oracleOptions(defaultTimeoutMs)`/cap parameter, or a LiveServer clamp) while
   still honoring an explicitly configured `core.lsp.timeoutMs`; add a test
   that LiveServer without config uses 750 ms and that an explicit config value
   is applied.

### Concurs / Dissents

- F1: concurs — `HostPipeline.snapshot` now calls `loader.preflight(candidate)`
  before `configureConstructed`, and `RulePackLoader.preflight` checks the id
  and API version. `constructedPackApiVersionIsCheckedBeforeConfiguration`
  asserts zero `configure` calls and an `INCOMPATIBLE_API_VERSION` failure.
- F2: concurs — `HostPipeline.failures` merges discovery, per-snapshot
  preflight/config, and validation failures; `constructedPackConfigFailureIsReported`
  asserts `INVALID_PACK_CONFIG` and that the failed pack is absent.
  `configureConstructed` passes its observer through, so the failure is no
  longer only written to `onFailure`.
- F3: concurs — `HostSettings.oracleOptions` derives enabled/timeout from
  `project.core.lsp`, and CLI, HTTP, live and split paths use it (with
  `--no-lsp` preserving the disable override). `coreLspPolicyReachesHostOracleOptions`
  covers the parsed policy. The new finding is the live default, not the wiring
  mechanism.
- Concurs: the parity test now pins the exact `testing-grounds` fixture set,
  compares Vue script regions, and compares both the full annotation shape and
  source-intent identity including role; m006 records golden-fixture parity as
  unverified with the reason.
- Concurs: removing the composition fallback is consistent with pack ownership.
  `configuredValidationMagicCommentDrivesRuleAndReturnCategorization` proves the
  configured magic comment reaches both the validation rule and return
  categorization, so the removed literal is not needed by the host path.
- Concurs: no production host reads `intents.yaml`; the remaining
  `loadIntentConfig` calls are on the legacy `SplitAnalyzer` overloads used by
  tests and are scheduled for removal in 014.
- Dissents: the new Round-2 finding above.

### Residuals (Round 2)

- `HostPipeline.failures` is lazy: constructed-pack preflight/config failures
  become visible only after `packs`/`plan`/`analyze` creates the relevant
  language snapshot. `configIssues` remains always empty because
  `HostConfig.load` hard-fails parse issues before constructing a pipeline.
- Public `RulePackLoader.configure(pack, values)` still does not preflight the
  api version itself; `HostPipeline` is the only production caller and now
  preflights, but a future direct caller must remember to do the same.
- `HostSettings.intentConfig` still accepts `validationParams` from the caller
  rather than deriving them from `project`; with the fallback now removed, an
  empty/partial map silently disables explicit magic-comment and predicate
  return categorization. All current hosts pass the correct map.
- Static review only; no source edit, build, test or formatter command was run
  by this role, per `workflow.md`.

## Round 3

Verdict: The Round-2 timeout finding is addressed. `parseProjectConfig` records
whether `core.lsp.timeoutMs` was explicitly present, `HostSettings.oracleOptions`
uses the live 750 ms default only when it was not, and CLI/HTTP continue to use
the 5000 ms framework default; an explicit configured timeout overrides the live
default. LiveServerConfigTest and m007 cover the behavior. No new correctness
finding.

### Findings

- None.

### Concurs / Dissents

- Concurs: `overridesLspTimeout` detects an explicit `core.lsp.timeoutMs` from
  the raw project config, and `HostConfig.load` carries that provenance into
  `HostSettings`. `HostSettings.oracleOptions(defaultTimeoutMs = 750)` therefore
  returns 750 for an absent or timeout-less config and 900 for an explicit
  timeout, while `oracleOptions()` without a default still returns 5000.
- Concurs: `LiveServer.oracleOptions` is the only caller that supplies the live
  default; CLI (`settings.oracleOptions(!args.noLsp)`) and HTTP
  (`settings.oracleOptions(useLsp)`) pass no default and keep the framework
  5000 ms timeout. `LiveServerConfigTest.liveOracleKeepsItsDefaultTimeoutWithoutExplicitConfig`
  and `liveOracleHonorsAnExplicitConfiguredTimeout` pin both sides, and m007
  records the live/hover distinction.
- Concurs: the Round-2 API-version preflight and failure aggregation remain in
  place; the only changes in Round 3 are the timeout-provenance flag and its
  consumers, so F1 and F2 stay addressed.
- Concurs: the `core.lsp.enabled` semantics are unchanged: `useLsp = false` or a
  configured `enabled = false` disables the oracle while preserving the selected
  timeout.
- Dissents: none.

### Residuals (Round 3)

- The 750 ms live default is selected by the parse-time
  `HostSettings.lspTimeoutConfigured` flag, not by the `ProjectConfig` value
  itself. A caller that constructs `HostSettings` directly around a
  `ProjectConfig` with a custom `LspPolicy` (rather than loading through
  `HostConfig`) gets the default 750 ms in LiveServer because the flag defaults
  to false. All production paths use `HostConfig`, and
  `ProjectConfigLoader` does not carry the flag, so this is a latent API
  limitation rather than a current host failure.
- The timeout flag is not surfaced by `ProjectConfigLoader`/`LoadedProjectConfig`,
  so only the `HostConfig` path can currently distinguish "explicitly 5000" from
  "default 5000"; a future non-HostConfig live host would need to preserve the
  same provenance.
- Static review only; no source edit, build, test or formatter command was run
  by this role, per `workflow.md`.
