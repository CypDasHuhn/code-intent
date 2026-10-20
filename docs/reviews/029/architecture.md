# Architecture review — ticket 029 (rulepack registration consistency)

## Round 1

Verdict: The registration and selection direction is correct. Error handling is
now one service-registered, multi-language pack with a pack-local language config
map, `BundledRulePackSelection` and the constructed-pack branch are gone, and both
production hosts construct through `HostRuntime` and share `HostPipeline`'s
language-filtered, loader-validated snapshot. What remains is that
error-handling-specific knowledge was moved into the host rather than removed:
`ProjectConfigLoader` now carries the pack id, its language set and legacy id
rewriting, creating a second source of truth and a pack-specific compatibility
path in generic host code. A leftover test-only language-construction API also
remains.

### Findings

1. **`analysis-engine/host/src/main/kotlin/dev/cint/host/ProjectConfigLoader.kt:35-56` — the host still special-cases error-handling, now in config canonicalization.**

   `canonicalizePackConfig` hardcodes `error-handling`, a duplicate language set,
   and the legacy `error-handling-<language>` naming convention. This is pack
   identity/registration knowledge in the host layer, duplicated from
   `ErrorHandlingRulePack`/`ErrorHandlingConfig.languages`; adding a language or
   changing an alias requires editing host code that is otherwise pack-agnostic.
   It also rewrites config before `RulePackLoader` sees it, so a legacy key is
   silently absorbed even if the pack failed to register, and a third-party pack
   with an `error-handling-<known-language>` id would have its config captured.
   The acceptance criterion says host special-casing should be removed; it has
   only been relocated.

   Fix: move legacy alias/canonicalization behind a generic extension point owned
   by the pack or loader — for example a pack-declared set of config aliases, or
   an alias mapping supplied at registration, applied by `RulePackLoader` after
   it knows the pack id and languages. Then `ProjectConfigLoader` stays generic
   and unknown-pack diagnostics stay in the loader.

2. **`analysis-engine/test-fixtures/src/testFixtures/kotlin/dev/cint/test/fixtures/TestPacks.kt:9-17` — the language-scoped test convenience API remains.**

   `testPacks(language)` still advertises language-based pack selection but
   suppresses the unused parameter and returns every pack. This is the test-side
   counterpart of the selection model the ticket set out to remove, so tests can
   believe they are exercising language selection while bypassing the
   registered/loader path; it will also silently keep including a pack if its
   language set later narrows. This is the remaining "test-only convenience API"
   from scope.

   Fix: drop the parameter and update the three call sites, or implement it via
   the registered packs and language filter. If fixtures are intentionally direct
   unit setup, name them accordingly (`allBundledPacks`) and keep selection tests
   against `HostRuntime`/`RulePackLoader`.

### Concurs / Dissents

- Concurs: `ErrorHandlingRulePack` now follows the logging model: one service
  provider, one pack/rule id, a `languages` set, and per-language config resolved
  from `View.language()`; the per-language construction helpers are gone.
- Concurs: `META-INF/services` lists all four bundled packs; no production host
  imports `dev.cint.rulepacks.*` or constructs packs.
- Concurs: CLI and both server paths use `HostRuntime.pipeline`, and
  `HostPipeline.snapshot` is the single language-filter/validate path; the
  constructed-pack merge and its duplicate-owner workaround are removed.
- Concurs: Bookkeeping's `vue` entry is removed and the other bundled packs
  already omit `vue`, so language selection is aligned with splitting before
  planning.
- Dissents: findings 1-2. No build, test or formatter command was run; handover
  is assumed green per `docs/workflow.md`.

## Round 2

Verdict: Round 1 is addressed. The host module no longer names error-handling or
its languages; migration lives with the bundled packs and is injected through
core's generic `RulePackConfigResolver`. `RulePackLoader` now discovers provider
ids first, resolves config, then configures and validates, so a third-party pack
that owns a legacy id keeps its own config, and a missing bundled pack leaves
legacy keys for normal unknown-pack reporting. `testPacks()` is language-free.
One new issue remains: the resolver has no failure channel, so invalid legacy
language sections are silently discarded instead of being reported.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/BundledRulePackConfigMigration.kt:20-29` — invalid legacy config is dropped without a load failure.**

   `migratedIds += legacyId` happens before `isValid`, and the final loop removes
   every migrated key whether it migrated or not. When `isValid` is false the
   values are never added to `error-handling` and nothing is reported, so an
   operator with a typo such as `error-handling-javascript.throws: not-a-boolean`
   gets default detection with no `INVALID_PACK_CONFIG`. The behavior is
   codified by `BundledRulePackConfigMigrationTest.invalidLegacyLanguageIsDroppedWithoutDroppingOtherLanguages`
   and `HostConfigWiringTest.invalidLegacyLanguageOverrideDoesNotDisableOtherErrorHandlingLanguages`.
   This conflicts with architecture §8 ("Values are validated against the owning
   pack's schema. Unknown packs or keys are reported") and with the round-1
   requirement that diagnostics remain at the loader boundary.

   Fix: give `RulePackConfigResolver` a failure/observation channel, or return a
   resolved config plus failures. Emit `INVALID_PACK_CONFIG` for the invalid
   legacy language while applying the valid ones, and merge those failures with
   the loader's own. Keep the third-party-id and absent-pack short-circuits.

### Concurs / Dissents

- Concurs with R1 F1: `ProjectConfigLoader`, `HostPipeline` and `HostRuntime` are
  pack-agnostic; no host source references the error-handling id or language set,
  and `ProjectConfigLoader` only forwards the resolver.
- Concurs with R1 F2: `testPacks()` no longer takes a language and all three call
  sites are updated.
- Concurs: `RulePackLoader` resolves config between discovery and configuration
  using the discovered id set, so the round-1 third-party-id capture concern is
  addressed and absent-pack diagnostics are preserved.
- Concurs: CLI and both server paths use `HostRuntime` with the bundled migration
  resolver; language selection remains a single validated snapshot path.
- Dissents: finding 1.

### Residuals (not filed as findings)

- Migration wiring is repeated at three entry points (`cli/Main.kt`, server main
  and the server per-request config path), while `HostRuntime` defaults to
  `RulePackConfigResolver.NONE`. The resolver is explicit composition rather than
  host policy, so this is not a finding, but a single bundled-runtime entry point
  would stop a new host from silently omitting legacy migration.
- No build, test or formatter command was run; handover is assumed green per
  `docs/workflow.md`.

## Round 3

Verdict: Round 2 is addressed. The resolver now carries issues out through
`RulePackConfigIssue`; `RulePackDiscovery` converts them to
`INVALID_PACK_CONFIG`, and the host tests assert both the per-language isolation
and the reported failure. `HostRuntime`, `HostPipeline` and `ProjectConfigLoader`
now default to the service-discovered bundled migration, so CLI and server no
longer repeat resolver wiring and the host remains pack-agnostic. The pack
service model and the single post-discovery selection path are unchanged. One new
architecture issue remains in how the default resolver itself is discovered: it
is a global first-wins service with no duplicate or version validation.

### Findings

1. **`analysis-engine/host/src/main/kotlin/dev/cint/host/DefaultRulePackConfigResolver.kt:6-13` with `analysis-engine/api/src/main/kotlin/dev/cint/api/RulePackConfigResolver.kt:8-17` and `analysis-engine/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePackConfigResolver:1` — the default config resolver is a global, classpath-ordered, single-provider service.**

   `defaultRulePackConfigResolver()` takes `ServiceLoader.load(...).firstOrNull()`
   and silently falls back to `NONE`. Because the interface is in the plugin `api`
   module, any JAR on the classpath can register a resolver that rewrites every
   pack's config, not just its own; if more than one provider is present, the
   winner depends on classpath order and the others are ignored. A corrupt
   provider is also swallowed and disables migration without a reported cause.
   This is unlike pack discovery, which rejects duplicate identities and reports
   failures, so the new default reintroduces an unpredictable global extension
   point at the host boundary.

   Fix: select the bundled resolver once in a composition root that depends on
   both `host` and `rulepacks` (keeping `HostRuntime` generic), or keep service
   discovery but require exactly one provider, reject duplicates and
   `ServiceConfigurationError` with a reported failure, and define the resolver's
   API/version contract. Scoping resolvers per pack id would also avoid one
   provider rewriting another pack's config.

### Concurs / Dissents

- Concurs with R2 F1: `BundledRulePackConfigMigration` now emits
  `RulePackConfigIssue` for invalid legacy sections while retaining valid
  languages, and `RulePackDiscovery` reports them as `INVALID_PACK_CONFIG`;
  `BundledRulePackConfigMigrationTest` and
  `BundledRulePackConfigMigrationHostTest` cover the diagnostic.
- Concurs: the resolver remains generic in `api`/`core`, the migration remains in
  `rulepacks`, and `ProjectConfigLoader` contains no pack id or language
  knowledge; no detection logic has returned to `core`.
- Concurs: `HostRuntime.pipeline(...)` and `HostPipeline(...)` defaulting to the
  service-provided resolver removes the per-caller opt-in from CLI/server while
  preserving the single language-filter/validate snapshot path.
- Concurs: third-party packs owning a legacy id still keep their config, and an
  absent bundled pack still leaves legacy keys for unknown-pack reporting.
- Dissents: finding 1.

### Residuals (not filed as findings)

- `resolve` is called directly by `discoverRulePacks` without a try/catch, so a
  resolver that throws aborts host construction rather than degrading; the
  service-loading path catches only provider instantiation/lookup failures.
- The resolver API was added to the frozen `api` surface without an explicit API
  version or compatibility note; additive today, but it is now part of what
  plugin JARs may implement.
- No build, test or formatter command was run; handover is assumed green per
  `docs/workflow.md`.

## Round 4

Verdict: Round 3 is addressed. The global `ServiceLoader` resolver and its
service resource are gone; `HostRuntime`, `HostPipeline` and
`ProjectConfigLoader` default to `RulePackConfigResolver.NONE`, while the two
composition roots (CLI and server) explicitly pass
`BundledRulePackConfigResolver`. `RulePackDiscovery` now catches a resolver
exception, reports it as `INVALID_PACK_CONFIG`, and falls back to the original
config, so a broken resolver degrades instead of aborting host construction. The
host module stays generic, migration stays in `rulepacks`, and pack selection is
still the single post-discovery snapshot path. No new architecture findings.

### Findings

None.

### Concurs / Dissents

- Concurs with R3 F1: resolver selection is explicit composition, not classpath
  discovery; `DefaultRulePackConfigResolver` and the resolver service file are
  removed, and `BundledRulePackConfigResolver` is a plain object passed by the
  CLI and both server paths. There is no first-wins provider ambiguity and no
  global rewrite hook available to arbitrary JARs.
- Concurs: `RulePackDiscovery` contains the resolver call in a catch-all
  non-`VirtualMachineError` block and reports `INVALID_PACK_CONFIG` before
  continuing with the original config;
  `HostPipelineTest.configResolverFailuresAreReportedAsInvalidConfig` covers the
  path.
- Concurs: the bundled migration is still applied where intended
  (`HostRuntime.pipeline(project, BundledRulePackConfigResolver)`) and is
  deliberately absent when a host omits it; the new host test pins the
  no-rewrite fallback.
- Concurs: removing service discovery does not change pack registration,
  language selection, third-party legacy-id ownership, or duplicate-owner
  rejection; `BundledRulePackRegistrationTest` and the host registration tests
  continue to cover the uniform pack model.
- Dissents: none.

### Residuals (not filed as findings)

- A resolver exception is reported with
  `RulePackLoadFailureKind.INVALID_PACK_CONFIG` and an empty `packIds` set. That
  is visible and degrades correctly, but it conflates a resolver failure with
  invalid pack config; a dedicated failure kind or resolver attribution would
  make the diagnostic clearer.
- `RulePackConfigResolver` and `RulePackConfigIssue` remain in the frozen `api`
  surface without an explicit API version note. They are additive and required
  for `rulepacks` to implement the resolver without a `core` dependency, but the
  compatibility contract should be recorded when the API is next versioned.
- No build, test or formatter command was run; handover is assumed green per
  `docs/workflow.md`.
