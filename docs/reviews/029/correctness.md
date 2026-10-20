# Correctness review — ticket 029 (rulepack registration consistency)

## Round 1

Verdict: The runtime identity and selection model is coherent. Error handling is
now one service-registered pack (`error-handling`) with one rule id
(`error-handling:error-handling`), the same four-language set as the other
bundled packs, no `vue` entry, and no per-language construction path. Both hosts
go through `HostRuntime`/`HostPipeline`, and per-language snapshots are filtered
and validated through the shared loader, so the bundled packs have unique owners.
The legacy `error-handling-<language>` config spelling still reaches the new
pack for valid configs. Two config-compatibility problems remain, and the new
duplicate-owner contract is only tested among the bundled packs.

### Findings

1. **`analysis-engine/host/src/main/kotlin/dev/cint/host/ProjectConfigLoader.kt:38-56` — legacy-key rewriting happens before discovery, so it can bind config to the wrong pack and cannot tell an old bundled key from a third-party id.**

   `canonicalizePackConfig` rewrites every `error-handling-<known-language>` key
   to `error-handling` without checking whether a provider with that legacy id is
   actually registered. A third-party or older rulepack whose id is
   `error-handling-kotlin` therefore loses its own config to the bundled
   `error-handling` pack; both packs then own `ERROR_HANDLING`, and
   `settleRulePacks` removes both (`RulePackSettlement.kt:71-76`). The rewrite
   also means unknown-pack diagnostics name `error-handling` instead of the key
   the user wrote. Fix: apply the legacy aliases after discovery, only when no
   provider declares the legacy id (or have the pack declare its aliases), and
   preserve the original config key in unknown-pack failures.

2. **`analysis-engine/host/src/main/kotlin/dev/cint/host/ProjectConfigLoader.kt:38-56` with `analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/errorhandling/ErrorHandlingRulePack.kt:81-99` and `analysis-engine/core/src/main/kotlin/dev/cint/core/runtime/rulepack/RulePackSupport.kt:175-210` — migration makes error-handling config atomic across all four languages.**

   All legacy language sections are merged into one pack config, so one invalid
   section such as `error-handling-kotlin: {throws: "yes"}` makes the single
   `configure` call fail and drops `error-handling` for JavaScript, TypeScript,
   and C# as well. Before the change each language was a separate constructed
   pack, so a bad Kotlin section only disabled Kotlin. This is a behavior change
   in config failure isolation, not just a key spelling change. Fix: either
   decode each language section independently and keep defaults for failing
   sections, or declare the atomic behavior as the new contract and pin it with a
   test that mixes a bad legacy section with valid sections for the other three
   languages; also add positive legacy-key tests for JavaScript, TypeScript, and
   C#, which the current suite does not cover.

3. **`analysis-engine/rulepacks/src/test/kotlin/dev/cint/rulepacks/BundledRulePackRegistrationTest.kt:48-64` and `analysis-engine/integration-tests/src/test/kotlin/dev/cint/integration/host/BundledRulePackRegistrationHostTest.kt:12-31` — duplicate-owner behavior is only tested among the bundled packs.**

   The old host deliberately kept a discovered `ERROR_HANDLING` owner and skipped
   the constructed error-handling pack. With uniform registration, a third-party
   pack owning `ERROR_HANDLING` and the bundled pack are both removed by
   `settleRulePacks`, so the intent disappears entirely rather than the custom
   owner winning. That may be the intended reject-duplicates policy, but the
   acceptance criterion calls out "no duplicate-owner surprises" and no test
   exercises a collision with an extra provider. Fix: add a loader/host test with
   an additional pack owning a bundled intent and pin the intended outcome; if a
   third-party owner is meant to override the bundled pack, resolve that in the
   loader instead of the host.

### Concurs / Dissents

- Concurs: pack identity is internally consistent. `ERROR_HANDLING_PACK_ID` and
  `ERROR_HANDLING_RULE_ID` are derived from one constant, the rule id remains
  inside the pack namespace, and the service file, loader registration test,
  scheduler test, host registration test, and snapshots agree on the new id.
- Concurs: per-language selection is correct for the four supported languages.
  The pack declares all four, the host filters by declared language, the matcher
  resolves `View.language()` through the language config map, and `vue` is
  excluded from every bundled pack because Vue regions are split to
  JavaScript/TypeScript before planning.
- Concurs: the legacy Kotlin config key is exercised end to end through
  `HostRuntime` and still drives detection; the new nested pack config is
  exercised at the pack level.
- Concurs: snapshot changes are limited to the rule identity; detection output
  is otherwise unchanged in `pipeline-pre014.json`.
- Dissents: findings 1-3.

### Residuals (not filed as findings)

- `analysis-engine/README.md:84,132` still documents
  `error-handling-<language>:error-handling` and only the old config shape. The
  old config shape remains accepted, but the documented rule id is stale.
- `ErrorHandlingRulePack` declares `ErrorHandlingConfig.languages` (the default
  language set) while `LoggingRulePack` declares `config.languages.keys`; a
  programmatically constructed `ErrorHandlingConfig` with a different language
  map would not narrow the pack's declared languages.
- No test covers the new `error-handling: {kotlin: ...}` config shape through the
  host, nor legacy sections for all four languages.

No build, test or formatter command was run by this role; the handover is
assumed green per `docs/workflow.md`.

## Round 2

Verdict: The three round-1 findings are addressed in substance. Legacy migration
now lives in `rulepacks`, the loader exposes a generic post-discovery
`RulePackConfigResolver`, `ProjectConfigLoader` no longer knows pack ids, and the
migration only rewrites a legacy key when the canonical pack is registered and no
provider owns the legacy id. Invalid legacy sections no longer disable the other
languages, and the duplicate-owner collision with a third-party owner is pinned
in the host test. One new correctness problem remains: invalid legacy sections
are now dropped silently instead of being reported, and compatibility depends on
each caller supplying the resolver because the runtime factory defaults to
`NONE`.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/BundledRulePackConfigMigration.kt:20-25,36-45` with `analysis-engine/core/src/main/kotlin/dev/cint/core/runtime/rulepack/RulePackSupport.kt:43-51` — invalid legacy language sections are discarded without a failure.**

   `isValid` decodes the section, but `resolve` only uses the result to skip the
   section; the resolver API returns just a config map and has no way to report a
   failure. An `error-handling-javascript: {throws: not-a-boolean}` section is
   removed from the resolved config, so `RulePackLoader` never sees it and never
   emits `INVALID_PACK_CONFIG`; JavaScript simply falls back to defaults while
   `HostPipeline.failures` stays empty. The new host test
   (`HostConfigWiringTest.kt:63-104`) pins only that other languages keep
   working, not that the bad section is surfaced, and
   `BundledRulePackConfigMigrationTest.invalidLegacyLanguageIsDroppedWithoutDroppingOtherLanguages`
   codifies the silent drop. This is a config-diagnostic regression: a typo can
   re-enable throw detection or otherwise change behavior with no evidence. Fix:
   have the resolver return diagnostics/failures, or accept a report callback, and
   report `INVALID_PACK_CONFIG` with the original `error-handling-<language>`
   path while still keeping the valid sections; assert that failure in the host
   test.

2. **`analysis-engine/host/src/main/kotlin/dev/cint/host/HostRuntime.kt:7-15` — legacy compatibility is opt-in at each caller.**

   `HostRuntime.pipeline` and `ProjectConfigLoader.load` default to
   `RulePackConfigResolver.NONE`, so `HostRuntime.pipeline(settings)` alone no
   longer applies the legacy config migration. The CLI and both server paths
   correctly pass `BundledRulePackConfigMigration::resolve`, but any other
   embedder using the central factory, or a future host added without the
   resolver argument, silently loses the compatibility behavior that was
   previously intrinsic to the host. Fix: make the resolver explicit and
   non-default at the host factory, or have the composition root provide one
   runtime factory that bundles the resolver so a host cannot accidentally omit
   it.

### Concurs / Dissents

- Concurs with round-1 finding 1: migration is now owned by `rulepacks`, runs
  after discovery with the discovered id set, leaves a third-party
  `error-handling-kotlin` provider's config untouched, and the host file has no
  pack-specific constants. The new third-party host test pins the intended
  behavior.
- Concurs with round-1 finding 2: each legacy language section is decoded
  independently, invalid sections are omitted while valid sections are retained,
  and the host wiring test demonstrates Kotlin and JavaScript continue to detect
  after a bad JavaScript section. The remaining problem is reporting, not
  isolation.
- Concurs with round-1 finding 3: the colliding third-party `ERROR_HANDLING`
  owner test now pins the reject-duplicates outcome, including the absence of
  both packs and of the intent from the plan. That is the uniform loader policy,
  so the previous host-specific "do not displace a discovered owner" behavior is
  intentionally gone rather than accidentally lost.
- Concurs: `ErrorHandlingRulePack` now derives `languages` from
  `config.languages.keys`, matching `LoggingRulePack`, and `testPacks()` no
  longer advertises an unused language parameter.
- Dissents: finding 1. Finding 2 is a residual risk rather than a broken
  production path today, but it is worth removing the default so the
  compatibility contract cannot be dropped by omission.

### Residuals (not filed as findings)

- `analysis-engine/README.md:84,132` still documents the old per-language rule
  id and only the legacy config shape; the code now accepts both shapes.
- The host suite still has no end-to-end case for the new
  `error-handling: {kotlin: ...}` config shape; only the pack-level migration
  test covers it.
- The resolver's migration order means a valid legacy section overwrites the
  same language in the new nested form when both are present; the deterministic
  choice is undocumented.

No build, test or formatter command was run by this role; the handover is
assumed green per `docs/workflow.md`.

## Round 3

Verdict: Round 2 is addressed. `RulePackConfigResolver` now has an issue channel,
`discoverRulePacks` turns those issues into `INVALID_PACK_CONFIG` failures before
configuration, and the migrating resolver reports each rejected legacy section
with the original `error-handling-<language>` key in the message while applying
the valid sections. `HostRuntime`, `HostPipeline`, and `ProjectConfigLoader`
default to a resolver discovered through `ServiceLoader`, whose rulepack provider
is registered in `META-INF/services`; CLI, both server paths, and the host tests
exercise the default path. The duplicate-owner and third-party-id behavior from
round 2 is unchanged and still pinned. I found no new correctness defect.

### Findings

- None.

### Concurs / Dissents

- Concurs with round-2 finding 1: invalid legacy sections are no longer silent.
  `BundledRulePackConfigMigration.resolve` appends a `RulePackConfigIssue` with
  the legacy id in the message, `RulePackDiscovery` reports it as
  `INVALID_PACK_CONFIG`, and
  `BundledRulePackConfigMigrationHostTest.hostRuntimeUsesTheBundledMigrationByDefaultAndReportsInvalidLegacySections`
  verifies the failure reaches `HostPipeline.failures` while Kotlin and
  JavaScript still load the pack. The unit test also pins the issue text.
- Concurs with round-2 finding 2: the bundled migration is now the host default.
  `HostRuntime.pipeline(project)` with no explicit resolver applies it through
  the service provider, and CLI plus both server paths construct the pipeline
  without repeating the resolver wiring.
- Concurs: the failure channel is generic. Core owns `RulePackConfigIssue` and
  the loader reports it, while the API resolver remains independent of the
  error-handling pack; the host module names neither the pack nor its languages.
- Concurs: the resolver's issue is reported once during construction and is
  included in `loaded.failures`, so `HostPipeline.failures` and `isDegraded`
  reflect rejected legacy config. Valid sections remain isolated per language.
- Concurs: third-party legacy ids still keep their own config, and a colliding
  `ERROR_HANDLING` owner still fails as a duplicate, because the new reporting
  path does not change the discovery/config ordering or duplicate handling.
- No dissent.

### Residuals (not filed as findings)

- `DefaultRulePackConfigResolver.defaultRulePackConfigResolver` catches any
  non-VM `Throwable` from `ServiceLoader` and silently falls back to
  `RulePackConfigResolver.NONE`. A broken or missing resolver provider therefore
  disables legacy migration without a diagnostic; the shipped distribution
  includes the provider and the integration test covers the happy path.
- `ServiceLoader.firstOrNull()` makes an arbitrary choice if more than one
  resolver provider is on the classpath; there is no conflict diagnostic.
- `analysis-engine/README.md:84,132` still documents the old per-language rule
  id and only the legacy config shape.
- The host suite still has no end-to-end case for the new
  `error-handling: {kotlin: ...}` config shape, and a valid legacy section still
  overwrites the same language in the nested shape when both are present; that
  precedence remains undocumented.

No build, test or formatter command was run by this role; the handover is
assumed green per `docs/workflow.md`.

## Round 4

Verdict: Round 3 is addressed. Global resolver discovery is gone: there is no
`META-INF/services` entry for `RulePackConfigResolver`, `HostRuntime` and
`HostPipeline` default to `NONE`, and the CLI plus both server paths inject
`BundledRulePackConfigResolver` explicitly. Resolver exceptions are caught at the
discovery boundary, reported as `INVALID_PACK_CONFIG`, and the loader falls back
to the original config, so a failing resolver no longer aborts pack loading.
Invalid legacy sections still surface with the original key in the message, and
the duplicate-owner/third-party-id behavior is unchanged. I found no new
correctness defect.

### Findings

- None.

### Concurs / Dissents

- Concurs with round-3 residuals about hidden global discovery: the service file
  and `DefaultRulePackConfigResolver` are gone, and
  `BundledRulePackConfigMigrationHostTest.configResolversAreNotDiscoveredGlobally`
  pins that no resolver is discoverable through `ServiceLoader`.
- Concurs with the resolver exception path:
  `RulePackDiscovery.discoverRulePacks` catches non-VM failures, emits
  `INVALID_PACK_CONFIG` with the resolver error, and continues with the original
  config; `HostPipelineTest.configResolverFailuresAreReportedAsInvalidConfig`
  verifies the failure reaches `HostPipeline.failures` while configured packs
  still load.
- Concurs: explicit host wiring is consistent. CLI, both server entry points, and
  the config-wiring and migration host tests all pass
  `BundledRulePackConfigResolver`; ordinary tests without legacy config use the
  `NONE` default, and the migration test pins that omitting the resolver leaves
  legacy keys to normal unknown-pack reporting rather than silently migrating.
- Concurs: no change was made to pack identity, per-language selection, snapshot
  contents, or the duplicate-owner rejection path, so the round-2 conclusions
  still hold.
- No dissent.

### Residuals (not filed as findings)

- `HostRuntime.pipeline` and `HostPipeline` still default to
  `RulePackConfigResolver.NONE`, so a future host can omit the resolver and lose
  legacy config migration at compile time without error. The omission now
  produces `UNKNOWN_PACK_CONFIG` rather than silent migration, and all current
  production hosts are wired explicitly; this remains an integration risk rather
  than a current defect.
- When the resolver throws, the fallback is the original config, so a legacy key
  can produce both the resolver-failure `INVALID_PACK_CONFIG` and a subsequent
  `UNKNOWN_PACK_CONFIG` for that key. The behavior is safe but noisy.
- `analysis-engine/README.md:84,132` still documents the old per-language rule
  id and only the legacy config shape.
- The host suite still has no end-to-end case for the new
  `error-handling: {kotlin: ...}` config shape, and valid legacy versus nested
  precedence remains undocumented.

No build, test or formatter command was run by this role; the handover is
assumed green per `docs/workflow.md`.
