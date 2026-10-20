# API-fidelity review — ticket 012 (typed pack config)

## Round 1

Verdict: The config boundary is in the right place. The new `api` types are
optional and additive, core passes opaque per-pack maps and keeps the `core:`
policy separate, logging validates its own typed schema, the new parser does not
reproduce the `intents.yaml` structure, and the annotation/artifact contract is
untouched. Four boundary gaps remain: the new frozen API surface is not recorded
or versioned, configuration is applied before the API-version gate, an empty
section disables a non-configurable pack, and `RulePackConfigException.path` is
exposed but never used.

### Findings

1. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Config.kt:3-10` and `docs/tasks/012-typed-pack-config.md` — the new frozen surface is unrecorded and has no versioning decision.**
   `ConfigurableRulePack` and `RulePackConfigException` are new public members of
   the frozen `api` module, but the ticket has no contract note and
   `CINT_API_VERSION` stays at 1. Architecture §9 says the API changes only
   deliberately and with versioning, and ticket 006 set the precedent of
   recording additive API members and their compatibility consequence. The
   additions are optional at the implementation level (existing `RulePack`s are
   unaffected), but this is the first published config surface: a pack compiled
   against it and a host that predates it both advertise version 1, so the
   version check cannot distinguish them. Nothing in the tree records whether
   that is acceptable, whether `CINT_API_VERSION` should move, or what the
   compatibility rule is.
   Suggested fix: add a contract note to ticket 012 (or architecture §8) stating
   that `ConfigurableRulePack` is an additive, opt-in API, naming the version
   consequence and whether `CINT_API_VERSION` remains 1. Pin the decision in the
   `api` contract test with a pack that implements `configure` and verifies the
   returned pack contract.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:307-308,319-346,445-474` — project config is applied before the API version is checked.**
   `discoverProvider` calls `configure(...)` before `validate(...)`, and
   `validate` is where `pack.apiVersion` is compared with `expectedApiVersion`.
   For the unannotated-pack path already left residual by 011, a version-mismatched
   pack that implements `ConfigurableRulePack` now receives project values and
   executes `configure` before the loader reports `INCOMPATIBLE_API_VERSION`; its
   `languages` getter is also read only after configuration. This runs
   incompatible pack code across the frozen boundary and makes the version gate
   non-binding for the new config path.
   Suggested fix: perform the id/version preflight before calling `configure` (a
   narrow per-pack version check, or split `validate` into metadata and rule
   phases), then validate the configured result. Add a fixture whose
   `configure` would record a call and whose `apiVersion` is mismatched, and
   assert the call never occurs and the pack degrades as incompatible.

3. **`analysis-engine/core/src/main/kotlin/dev/cint/core/config/ProjectConfig.kt:195-200,81-86` with `RulePackLoader.kt:335-345` — an empty pack section disables a non-configurable pack.**
   `stringKeyedMap(null)` returns an empty map, so a valid section such as
   `bookkeeping:` (or any pack whose section is present with no keys) is added to
   `ProjectConfig.packs` as `emptyMap`. The loader treats the presence of the key
   as a configuration request, sees that the pack is not `ConfigurableRulePack`,
   reports `INVALID_PACK_CONFIG` with an empty key list, and returns `null`,
   dropping the whole pack. A section with no keys is not an unknown key and
   should not disable a pack; only non-empty values for a pack that cannot accept
   them should be rejected.
   Suggested fix: skip empty value maps in `parseProjectConfig`, or return the
   pack unchanged in `configure` when `values.isEmpty()` before the
   `ConfigurableRulePack` check. Add a regression with a non-configurable fixture
   and an empty config section asserting the pack still loads with defaults.

4. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Config.kt:7-10` with `RulePackLoader.kt:362-370` and `analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/LoggingRulePack.kt:96-101` — `RulePackConfigException.path` is dead public surface.**
   The new exception advertises a structured `path`, but `LoggingConfig` always
   throws it with only a message, and the loader catches it, prefixes the message,
   and never reads or forwards `path`. A pack cannot populate the field without
   changing every throw site, and a host cannot receive it through
   `RulePackLoadFailure`, so the frozen contract promises structured config
   diagnostics that the implementation cannot produce. This is the same kind of
   extra, unimplemented API surface that ticket 005 removed from `api`.
   Suggested fix: either remove `path` from the exception and keep the message as
   the only error payload, or make the pack populate it and carry it into the
   failure (for example a `configPath` on `RulePackLoadFailure`/`ProjectConfigIssue`)
   with a test that a bad key reports its path structurally.

### Concurs / Dissents

- Concurs: the new API is optional and additive at the type level. `RulePack`,
  `Rule`, `View`, `Emit`, `Annotation`, `Contribution`, and the pack version
  annotation are unchanged, so existing packs compile and load without
  modification.
- Concurs: the annotation/artifact contract consumed by the renderer is
  untouched; no `Role`, `Span`, provenance, byte-offset, or `NodeAnchor` shape
  changed in this diff.
- Concurs: core keeps the pack boundary opaque. `RulePackLoader.load` receives
  `Map<String, Map<String, Any?>>` keyed only by pack id, and
  `ProjectConfigLoader` forwards it without knowing logging keys. The typed
  schema, defaults, language matching, and value validation live in
  `LoggingConfig` under `:rulepacks`, not in `core`.
- Concurs: core policy stays core policy. Only `core.purity.annotations` and
  `core.lsp.*` are parsed into `CorePolicy`; unknown core keys are reported
  outside pack config, and packs cannot claim the `core` section.
- Concurs: the new project parser does not leak the `intents.yaml` structure:
  it has no `intents`/`params`/`overrides`/`blocks` handling, and
  `IntentConfig.withCorePolicy` is only a legacy migration bridge in core, not a
  second config schema. It should disappear with 014.
- Concurs: unknown pack ids produce `UNKNOWN_PACK_CONFIG`; unknown keys inside a
  configurable pack produce `INVALID_PACK_CONFIG` and skip that pack. The tests
  in `RulePackLoaderTest` and `ProjectConfigLoaderTest` exercise both paths, and
  the pack-local logger defaults still apply when no config is supplied.
- Concurs: `LoggingRulePack.configure` returns a new pack built from
  `LoggingConfig.withOverrides`, leaving the original pack immutable, and
  `ProjectConfigLoaderTest.packOverridesAndCorePolicyLoadTogether` demonstrates
  that pack values and core policy load together.
- Dissents: findings 1-4 above.

### Residuals (not filed as findings)

- Only `logging` currently opts into `ConfigurableRulePack`. `BookkeepingConfig`
  and `ErrorHandlingConfig` are typed but not project-configurable; 013 owns
  migrating their `intents.yaml` values, so the mechanism is exercised only by
  logging in this ticket.
- The loader returns `LoadedRulePack` wrappers, so a host cannot downcast a
  result to `LoggingRulePack` to inspect its typed config. This is the 011
  wrapper residual, not introduced here, and the current host contract only
  needs `RulePack`.
- `ProjectConfig` duplicates the legacy purity defaults in
  `DEFAULT_PURITY_ANNOTATIONS`; that is intentional core policy, but 014 should
  ensure the legacy and project defaults do not drift.

No build, test, or formatter command was run by this role; the handover was
treated as green per `workflow.md`.

## Round 2

Verdict: All four round-1 findings are addressed. Configuration now runs only
after an id/version preflight, an empty pack section is a no-op, the dead
`RulePackConfigException.path` is gone, and the additive config surface is pinned
to API version 1 by a contract test. The capability/policy boundary is unchanged:
core still forwards opaque per-pack maps, `core:` stays framework policy, no
`intents.yaml` structure appears in the new parser, and the annotation/artifact
contract is untouched. No new api-fidelity finding.

### Findings

- F1: concurs — `ContractTest.configurableRulePackIsAdditiveAndVersionPinned`
  (`analysis-engine/api/src/test/kotlin/dev/cint/api/ContractTest.kt:76-87`)
  pins `CINT_API_VERSION` at 1 and exercises the opt-in shape: a
  `ConfigurableRulePack` keeps its id and api version, and `configure` returns a
  different rule set without breaking the base contract. Existing `RulePack`
  implementations are untouched, so the addition is additive.
- F2: concurs — `discoverProvider` now calls `preflight` before `configure`
  (`RulePackLoader.kt:324-325`). `preflight` reads and records the id, checks
  `pack.apiVersion` against `expectedApiVersion`, and returns before any config
  is applied (`:339-408`). `FixtureConfigurableMismatchPack` records
  configuration calls, and
  `apiVersionGateRunsBeforePackConfiguration` (`RulePackLoaderTest.kt:608-623`)
  asserts zero calls and `INCOMPATIBLE_API_VERSION` for a configured,
  mismatched pack.
- F3: concurs — `configure` returns the pack unchanged when the section is empty
  (`RulePackLoader.kt:415-417`), before the non-configurable rejection.
  `emptyConfigSectionDoesNotDisableNonConfigurablePack`
  (`RulePackLoaderTest.kt:584-593`) keeps `fixture-good` loaded with no
  failures, while `nonEmptyConfigForNonConfigurablePackIsReported` (`:595-606`)
  still pins rejection of a non-empty unknown section.
- F4: concurs — `RulePackConfigException` now carries only its message
  (`analysis-engine/api/src/main/kotlin/dev/cint/api/Config.kt:7-9`), so the
  frozen surface no longer promises a structured path the loader discards. Pack
  messages still carry the `logging.<language>.<key>` path text.

### Concurs / Dissents

- Concurs: the loader's new `DiscoveryState` keeps unknown-pack reporting
  separate from packs that were discovered but failed before their id was
  readable; the added constructor-failure and annotated-mismatch regressions pin
  that a failed configured pack is not misreported as unknown.
- Concurs: `preflight` still records the id before the version check, so a
  configured version-mismatched pack is treated as claimed rather than unknown;
  other unknown config keys remain reportable when discovery completed.
- Concurs: the empty-section change does not alter non-empty key validation,
  pack-id dispatch, configurable pack overrides, or the no-config defaults path.
- Concurs: the API contract test and the loader tests are the in-tree record for
  keeping the version at 1; no existing pack or renderer-facing type changes.
- Dissents: none. Residual, not a finding: the ticket and architecture §8 still
  do not state in prose that `ConfigurableRulePack` is an additive opt-in
  surface; the contract test now pins the behavior, and a scribe note would make
  the decision easier to find, but this does not block the ticket.

No build, test, or formatter command was run by this role; the handover was
treated as green per `workflow.md`.
