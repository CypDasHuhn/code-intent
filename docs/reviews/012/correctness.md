# Correctness review — ticket 012 (typed pack config)

## Round 1

Verdict: The main config path is sound. Pack defaults apply without a file, a
pack-id section reaches `ConfigurableRulePack.configure`, invalid keys abort only
that pack with `INVALID_PACK_CONFIG`, and `core:` policy is parsed and kept in
core rather than passed to packs. `LoggingConfig.withOverrides` replaces only the
named values, and the loader/normalization tests cover the happy path and the
logging unknown-key path. Two correctness gaps remain: unknown-pack reporting is
computed from which ids were seen during configuration, so a configured pack
that exists but fails before or during configuration is misreported as unknown;
and `core.lsp.timeoutMs` accepts fractional/out-of-range numbers and silently
truncates them despite requiring a positive integer. I concur on the config
plumbing and the core/pack boundary and dissent on those two points.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:74-86`, `:238-307`, `:319-335` — a configured pack that fails to load is reported as an unknown pack.**

   `seenPackIds` is updated only at line 334, inside `configure`, after
   `Class.forName`, the `RulePack` assignability check, the
   `@RulePackApi`/`getApiVersion` checks, constructor invocation, and the
   `pack.id` read. A provider that exists but fails any of those steps never
   marks its id, so the post-discovery loop at lines 76-86 emits
   `UNKNOWN_PACK_CONFIG` for its config key. For example, a project config with
   a `logging:` section while the logging provider is annotated for another API
   version produces both `INCOMPATIBLE_API_VERSION` and
   `UNKNOWN_PACK_CONFIG`, and the override is silently dropped. The same happens
   for a provider whose constructor is missing a dependency, a provider class
   that is present but not a `RulePack`, or a pack whose `id` getter throws
   (`configure` returns the pack at line 330 without recording it). When
   discovery itself fails, every configured key is reported as unknown. The
   ticket requires unknown packs to be reported; the current implementation
   reports unsuccessful packs as unknown and loses the distinction.

   Fix: separate "configured but the pack did not load" from "no such pack".
   Record configured keys claimed by every discovered provider independently of
   successful configuration (or only run the unknown-key loop when discovery
   completed without provider failures), and report a distinct
   ignored/invalid-config failure for a configured pack that was skipped.
   Add a regression with a configured version-mismatched or
   constructor-failing provider.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/config/ProjectConfig.kt:164-170` — `core.lsp.timeoutMs` accepts non-integral and out-of-range values.**

   `(rawValue as? Number)?.toLong()` accepts YAML doubles, floats, and big
   integers, then truncates or saturates them. `timeoutMs: 1.5` becomes `1`,
   `timeoutMs: Infinity` becomes `Long.MAX_VALUE`, and an oversized integer is
   truncated, while the issue text promises "expected a positive integer". A
   typo therefore silently changes the oracle timeout instead of reporting a
   config issue, and the parsed policy no longer matches the declared schema.

   Fix: accept only integral values (`Int`/`Long`, or a checked `BigInteger`),
   reject fractional, non-finite, and out-of-range values with
   `core.lsp.timeoutMs`, and add regressions for `1.5`, `Infinity`, and an
   overflowing integer. An upper bound on the timeout is worth considering.

### Concurs / Dissents

- Concurs: defaults apply without a config file; `LoggingConfig.withOverrides`
  replaces only the named language settings, and `LoggingConfigTest` plus
  `RulePackLoaderTest` cover defaults, per-pack overrides, unknown language, and
  unknown key. `LoggingRulePackTest.configuredLoggingPackUsesPackOverrides`
  proves the configured logging rule uses the override rather than the legacy
  values.
- Concurs: config is keyed by pack id and only that pack's map is passed to
  `configure`; core's `ProjectConfig`/`ProjectConfigLoader` remains generic, and
  `CorePolicy`/`DEFAULT_PURITY_ANNOTATIONS` are core responsibilities. No
  detection values from the logging pack leaked into core.
- Concurs: `RulePackConfigException` is caught separately and converted to
  `INVALID_PACK_CONFIG`; generic pack failures are also contained, and an
  invalid pack is skipped while other packs load.
- Concurs: `parseProjectConfig` reports missing/unreadable/malformed files,
  malformed `core` sections, unknown core keys, and non-map pack sections
  without discarding the rest of a valid parse; `ProjectConfigLoader` surfaces
  both parse issues and loader failures.
- Concurs: the existing loader behavior (validation, duplicate/conflict
  handling, graceful degradation) is not changed for the no-config call path.
- Dissents: findings 1 and 2 above.

### Residuals

- Only the logging pack implements `ConfigurableRulePack` and declares a typed
  config. Error-handling and bookkeeping (and validation once 009 lands) still
  have no typed config; 013 must add them when it moves the remaining
  `intents.yaml` values.
- `ProjectConfigLoader`, `CorePolicy.oracleOptions`, and
  `IntentConfig.withCorePolicy` have no production caller yet; the acceptance
  item about the framework reading `core:` is currently demonstrated only by
  tests. Host wiring is deferred to the later cutover/migration tickets.
- `RulePackConfigException.path` is never populated by `LoggingConfig`, and the
  loader formats only `message`; callers cannot consume a structured path. The
  field is either dead or needs to be part of the error contract.
- The `INVALID_PACK_CONFIG` branches for a non-configurable pack, a changed pack
  id, and a generic `Throwable` from `configure` have no direct regressions; the
  fixture covers only the `RulePackConfigException` path.
- `RulePackLoader` never applies a project-config override to the actual
  logging pack through `ProjectConfigLoader` in a test; the loader tests use a
  fixture and the logging test calls `configure` directly.
- `DEFAULT_PURITY_ANNOTATIONS` duplicates the defaults still in `intents.yaml`
  until 013/014 remove the legacy source; the LSP default timeout (5000 ms) also
  differs from the live server's 750 ms, so wiring needs an explicit decision.
- The top-level key `core` is implicitly reserved for policy; a pack id `core`
  cannot be configured. Either document the reservation or reject it when the
  pack registers.

No build, test or formatter command was run by this role; the handover was
treated as green per `workflow.md`.

## Round 2

Verdict: F1 and F2 are addressed, as are the API-gate ordering, empty-section,
and dead-exception changes. `preflight` records an id before the version check so
a configured version-mismatched pack is claimed rather than unknown; constructor
and annotation failures no longer emit `UNKNOWN_PACK_CONFIG`; configuration runs
only after the instance API gate; empty sections are no-ops; timeout parsing is
integral-only; and `RulePackConfigException` no longer advertises an unused path.
One new correctness hole remains: the global `DiscoveryState.incomplete` guard
disables unknown-pack reporting for every configured key whenever any
discovery/provider failure occurs, so genuinely unknown pack ids are silently
unreported in degraded runs. I concur on the addressed items and dissent on the
new finding.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:74-88`, `:118-214`, `:261-334`, `:349-362`, `:832-835` — `incomplete` hides genuinely unknown pack config.**

   `state.incomplete` is set for every resource/provider failure: enumeration
   errors, malformed service lines, discovery limits, missing classes,
   non-`RulePack` providers, absent or annotation-mismatched versions,
   constructor failures, unreadable/blank ids, and any unexpected `Throwable`.
   `load` then skips the entire unknown-key loop whenever the flag is set
   (lines 76-88), not only when the set of provider ids is actually unknowable.
   Consequently a config with an actually missing pack key is reported only when
   the rest of discovery is flawless. For example, with `good` and
   `FixtureThrowingConstructorPack` on the classpath, a config containing both
   `zzz-throwing-constructor` and `missing-pack` reports the constructor failure
   but never reports `missing-pack`. The new
   `configuredConstructorFailureIsNotReportedAsUnknownPack` and
   `configuredAnnotatedMismatchIsNotReportedAsUnknownPack` regressions assert
   only `none { UNKNOWN_PACK_CONFIG }` and do not include a genuinely unknown
   key, so this passes. The previous code over-reported failed packs as unknown;
   this fix under-reports unknown packs in exactly the degraded runs where config
   diagnosis is most needed.

   Fix: do not use one global flag to suppress all unknown reporting. Track
   per-key claim status and, when a provider failure leaves an id unknowable,
   report each unseen configured key with an explicit "unverified/config could
   not be applied because discovery was incomplete" failure (or still
   `UNKNOWN_PACK_CONFIG` with that caveat) instead of dropping it. Add a
   regression combining `missing-pack` with a constructor-failure,
   annotated-mismatch, or malformed-service failure and assert the unknown key is
   reported. Consider exposing `DiscoveryState.incomplete` on
   `RulePackLoadResult` if callers must distinguish "no unknown keys" from
   "unknown check skipped."

### Concurs / Dissents

- F1: concurs — `preflight` (`RulePackLoader.kt:339-408`) reads and records
  `seenPackIds` at `:373` before the version check, so a configured
  version-mismatched pack is no longer unknown;
  `apiVersionGateRunsBeforePackConfiguration` pins that `configure` is not
  called. Constructor and annotation failures set `incomplete` and no longer
  emit `UNKNOWN_PACK_CONFIG`; the new regressions pin those two cases. The new
  finding is the trade-off introduced by that fix, not a recurrence of F1.
- F2: concurs — `positiveLong` (`analysis-engine/core/src/main/kotlin/dev/cint/core/config/ProjectConfig.kt:180-194`)
  accepts only positive integral `Byte`/`Short`/`Int`/`Long` and checked
  `BigInteger`, and `fractionalNonFiniteAndOverflowingTimeoutsAreReported`
  covers `1.5`, `.inf`/`Infinity`, and `Long.MAX_VALUE + 1`.
- Concurs: API-gate ordering — `preflight` checks `pack.apiVersion` before
  `configure`; `FixtureConfigurableMismatchPack` proves zero configuration
  calls. Config still runs before the configured result's own metadata/rules are
  validated, so a config-returned pack with a bad version is rejected by
  `validate`; that is the intended order.
- Concurs: empty-section handling — `configure` returns the pack for
  `values.isEmpty()` (`RulePackLoader.kt:415-417`) before the non-configurable
  check; `emptyConfigSectionDoesNotDisableNonConfigurablePack` and
  `nonEmptyConfigForNonConfigurablePackIsReported` pin both sides.
- Concurs: dead error path — `RulePackConfigException` now carries only its
  message (`analysis-engine/api/src/main/kotlin/dev/cint/api/Config.kt:7-9`),
  and `ContractTest.configurableRulePackIsAdditiveAndVersionPinned` pins the
  additive interface and version.
- Dissents: the new finding above.

### Residuals (Round 2)

- `DiscoveryState.incomplete` is not surfaced on `RulePackLoadResult`; a caller
  cannot tell "no unknown keys" from "unknown check skipped." The finding's
  remediation covers this.
- A configured pack skipped by an API/constructor failure still receives no
  config-specific failure, only the pack failure, so the ignored values are
  implicit. F1's false-positive problem is fixed; this is a diagnostic gap.
- `positiveLong` still accepts `Long.MAX_VALUE`; no practical upper bound was
  added.

No build, test or formatter command was run by this role; the handover was
treated as green per `workflow.md`.

## Round 3

Verdict: F3 is addressed. Every unseen config key is now reported: with a
complete set of identified providers it is `UNKNOWN_PACK_CONFIG`, and when any
provider id could not be read it is `UNVERIFIED_PACK_CONFIG` rather than being
silently dropped. The constructor-failure and known-id-corrupt regressions pin
both branches, while F1/F2 and the Round 2 fixes remain addressed. I concur and
find no new source correctness defect; the remaining notes are conservative
classification residuals.

### Findings

- None.

### Concurs / Dissents

- F3: concurs — the unknown loop (`RulePackLoader.kt:75-98`) now reports every
  key not in `seenPackIds`; `unidentifiedProviderFailure` (`:840-842`) selects
  `UNVERIFIED_PACK_CONFIG` instead of `UNKNOWN_PACK_CONFIG`.
  `configuredConstructorFailureIsNotReportedAsUnknownPack` and
  `configuredAnnotatedMismatchIsNotReportedAsUnknownPack` pin the unidentifiable
  cases, while `trulyUnknownConfigIsReportedAlongsideAKnownIdCorruptPack` pins
  that a known-id `FixtureCorruptPack` failure still leaves a genuinely unknown
  key classified `UNKNOWN_PACK_CONFIG`. No unseen key is silently dropped.
- F1: concurs — `preflight` still records `seenPackIds` before the version check
  (`RulePackLoader.kt:339-408`), and the constructor/annotation regressions keep
  configured failed packs from being reported as unknown.
- F2: concurs — `positiveLong` remains integral-only
  (`analysis-engine/core/src/main/kotlin/dev/cint/core/config/ProjectConfig.kt:180-194`),
  with the fractional/non-finite/overflow regression intact.
- Concurs: API-gate ordering, empty-section handling, and the removed
  `RulePackConfigException.path` remain as reviewed in Round 2
  (`RulePackLoader.kt:415-417`, `analysis-engine/api/src/main/kotlin/dev/cint/api/Config.kt:7-9`).
- Dissents: none.

### Residuals (Round 3)

- When any provider id is unreadable, `unidentifiedProviderFailure` is global, so
  every unseen key is classified `UNVERIFIED_PACK_CONFIG`, including a key that
  is actually unknown; `trulyUnknownConfigIsReportedWhenAProviderIdCannotBeRead`
  pins this. That is the conservative and safe report because the unreadable
  provider could own the key, but the ticket wording should not promise
  `UNKNOWN_PACK_CONFIG` in that case.
- `discoverProvider`'s outer catches (`RulePackLoader.kt:335-344`) set
  `unidentifiedProviderFailure` for any escaping `Throwable`, even if
  `preflight` already recorded the id. An exotic post-preflight failure (for
  example a throwing `rules` iterator or `onFailure` callback) would downgrade a
  genuinely unknown key from `UNKNOWN` to `UNVERIFIED`; normal known-id failures
  such as `FixtureCorruptPack`, version mismatch, and invalid config remain
  classified correctly and are tested.
- `UNVERIFIED_PACK_CONFIG` is not documented in the ticket/architecture, and no
  production host consumes `ProjectConfigLoader` failures yet.

No build, test or formatter command was run by this role; the handover was
treated as green per `workflow.md`.
