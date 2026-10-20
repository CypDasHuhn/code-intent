# Detection-fidelity review — ticket 013 (config migration off intents.yaml)

## Round 1

Verdict: The migration assignment is complete and the defaults do reproduce the
legacy language profiles. Every `intents.yaml` method, factory, receiver,
qualifier, import, `params` entry and `overrides.<language>` value is owned by
the expected pack, and the new host path no longer reads `intents.yaml` for
detection. Default parity is asserted over the supported `testing-grounds`
fixtures. Three gaps remain: the golden-fixture half of the acceptance has no
manual-gate entry, the parity test skips the Vue fixture and does not compare the
whole annotation identity, and core composition still carries a global fallback
for the validation pack's magic comment.

### Findings

1. **`docs/manual-test.md:7-13` with `docs/tasks/013-config-migration-off-intents-yaml.md:25` — golden-fixture parity is unrecorded.**
   The acceptance criterion is parity "over the fixtures and goldens", but no
   golden fixture or `tags.json` exists in the worktree, and there is no
   `manual-test.md` row naming 013. The existing `m003` only covers the logging
   pack for ticket 006; it does not cover error-handling, bookkeeping or
   validation parity for this migration. The automated test walks only
   `testing-grounds/`, so the acceptance is silently narrowed, exactly the gap
   `workflow.md`'s manual gate is meant to expose.
   Suggested fix: add a manual-test row naming 013 for golden-fixture parity
   across the migrated packs, `unverified`, stating that no golden `tags.json`
   exists and that `testing-grounds/` parity is automated in
   `HostConfigWiringTest`.

2. **`analysis-engine/frontend/src/test/kotlin/dev/cint/frontend/HostConfigWiringTest.kt:99-160` — the parity walk skips the Vue fixture and does not compare roles.**
   The loop maps only `kt`/`js`/`ts`/`cs`; `testing-grounds/vue/Sample.vue` is a
   fixture named by the ticket but is never compared. The production Vue path
   now obtains pack config through `settings.intentConfig(region.language, ...)`,
   yet no test compares that path to the legacy region analysis, so a
   region-config translation error would pass. In addition, `shape()` omits
   `Annotation.role`, so a source/scaffold/derivative role regression with
   otherwise identical fields is invisible, and `compared >= 9` permits a
   supported fixture to disappear without failing.
   Suggested fix: extend the parity test to split the Vue fixture and compare
   each region against legacy with the same region language, include `role` in
   the compared shape, and assert the exact set/count of compared fixtures
   rather than a lower bound.

3. **`analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:200-204` — the validation magic comment still has a global core fallback.**
   `hasValidationComment` falls back to the literal `"validation"` when the
   `IntentConfig` has no `VALIDATION.magicComment`. That is a pack-owned
   detection value duplicated in core, and it can drift if the pack default or a
   project override changes. The host wiring currently always supplies the
   value through `HostSettings.intentConfig`, but the fallback means a core
   caller with missing/partial config silently uses the old global value instead
   of the pack's schema.
   Suggested fix: treat a missing `magicComment` as no match (or source it from
   a single core policy that is explicitly not pack detection), and add a host
   parity case with a custom `validation.magicComment` that proves both the
   validation rule and return categorization use the configured value.

### Concurs / Dissents

- Concurs: every `intents.yaml` key is assigned. Logging's Kotlin `printCalls`,
  `printQualifiers`, `methods`, `types`, `factories`, `receiverNames`,
  `qualifiers`, `guardNames` and `imports`, plus the JavaScript/TypeScript and
  C# `overrides`, match `LoggingConfig.defaults()`; error-handling `calls` and
  `throws` and the JS/TS/C# overrides match `ErrorHandlingConfig.forLanguage`;
  validation `predicates`/`predicatePrefixes` match `ValidationConfig`; purity
  matches `DEFAULT_PURITY_ANNOTATIONS`. The `blocks` entries are rule structure,
  correctly no longer config.
- Concurs: the override semantics are preserved. The old per-language override
  maps are flattened into per-language pack defaults, project overrides replace
  only named values and retain the rest, and the language-scoped error-handling
  pack ids (`error-handling-<language>`) select the same calls/qualifiers/throws
  profiles as the old `overrides.<language>` entries.
- Concurs: default host detection no longer loads `intents.yaml`; CLI, server and
  split regions build `IntentConfig` from `HostSettings`, and the legacy loader
  remains only on the test/parity overloads pending 014.
- Concurs: `LoggingRulePackTest` already compares legacy and new logging under
  null, resolved-logger and resolved-non-logger oracles, so the default
  `NullOracle` fixture walk is sufficient for the config migration itself.
- Concurs: `HostSettings` carries only core purity plus validation
  predicate/prefix/magic-comment values, all sourced from the owning pack; no
  logging, error-handling or bookkeeping detection value is reintroduced into
  core apart from finding 3.
- Dissents: findings 1-3.

### Residuals (not findings)

- Custom project config is exercised only for Kotlin logging/error-handling and
  validation predicates. A direct legacy-vs-new comparison for a non-Kotlin
  override (for example JavaScript logging or C# error-handling qualifiers) would
  pin the language-specific config mapping more strongly, but the default
  language profiles are covered by the fixture walk.
- The parity walk uses `NullOracle` only; the oracle-sensitive logging branches
  are covered by the existing 006 tests rather than this ticket's migration test.
- Static review only; no source edit, build, test or formatter command was run by
  this role, per `workflow.md`.

## Round 2

Verdict: The three Round-1 findings are addressed. `m006` records the
golden-fixture gap for 013, the parity walk now asserts all 11 `testing-grounds`
files including Vue script regions and compares source roles, and the validation
magic comment is config-driven with no core fallback. One new host-default
divergence remains: wiring `core.lsp` replaced the live daemon's 750 ms default
with the shared 5000 ms policy default, which can change oracle-dependent
logging detection.

### Findings

1. **`analysis-engine/server/src/main/kotlin/dev/cint/server/LiveServer.kt:211-236` with `analysis-engine/core/src/main/kotlin/dev/cint/core/host/HostConfig.kt:16-20` and `analysis-engine/core/src/main/kotlin/dev/cint/core/config/ProjectConfig.kt:14-16` — the live daemon's default oracle timeout changed from 750 ms to 5000 ms.**
   Before this round `LiveServer` used `OracleOptions(useLsp, LIVE_LSP_TIMEOUT_MS =
   750)`. It now derives options from `settings.oracleOptions()`, whose default
   `LspPolicy.timeoutMs` is 5000. That is a production default change, not just
   config plumbing, and it changes oracle-dependent detection: a language server
   that answers between 750 ms and 5000 ms can now resolve a receiver type that
   the old daemon would have treated as unresolved, turning a heuristic
   `LOGGING` match into a typed match or a non-logger veto. No test or
   `manual-test.md` entry compares the live oracle path against the old default.
   Suggested fix: preserve 750 ms as the live host default (per-host override of
   the core policy) or explicitly approve the unified 5000 ms default and record
   an oracle-dependent live parity check in `manual-test.md`.

### Concurs / Dissents

- F1: concurs — `docs/manual-test.md:14` adds `m006` naming 013, lists the four
  migrated packs, marks it `unverified`, and states both the missing golden
  fixtures and the `HostConfigWiringTest` coverage.
- F2: concurs — `HostConfigWiringTest` now enumerates all 11 fixture paths,
  asserts the exact set, splits `vue/Sample.vue` through `VueFrontend`, compares
  each region against the legacy split path, and compares
  `Role.SOURCE_INTENT` annotations including `role`. The remaining omission of
  non-source roles from `shape()` is a composition-test residual, not a
  source-detection parity gap.
- F3: concurs — `Composition.hasValidationComment` now returns false when the
  `VALIDATION.magicComment` is absent (`Composition.kt:200-205`), and
  `configuredValidationMagicCommentDrivesRuleAndReturnCategorization` proves a
  custom `validation.magicComment` reaches both the validation rule and return
  categorization. No global fallback remains.
- Concurs: the key assignment, override semantics and default language profiles
  reviewed in Round 1 are unchanged. The Vue parity path uses
  `settings.intentConfig(region.language, ...)`, so the migrated config is what
  the split fixture compares.
- Dissents: the new live oracle-timeout finding above. F1-F3 are not re-reported
  as findings.

### Residuals (not findings)

- Removing the fallback means `IntentConfig` loaded directly from `intents.yaml`
  has no `magicComment`; the legacy-config `SplitAnalyzer` overload and tests
  that use the new `RuleEngine` with `loadIntentConfig` will no longer recognise
  comment-only validation guards for return categorization. Production hosts
  supply the value through `HostSettings`, so this is confined to the legacy
  parity scaffolding pending 014.
- `shape()` still compares non-source annotations without `role`; only
  `sourceShape()` includes it. Source-role regressions are now covered, but a
  scaffold/derivative role swap would not be caught by this walk.
- The fixture parity walk remains `NullOracle`-only; oracle-sensitive logging
  parity is still covered by the 006 tests rather than this migration test.
- Static review only; no source edit, build, test or formatter command was run by
  this role, per `workflow.md`.

## Round 3

Verdict: The Round-2 finding is addressed. The live daemon again defaults to
750 ms, inherits the 5000 ms framework default for CLI/HTTP, and gives an
explicit `core.lsp.timeoutMs` precedence over both; `m007` records the remaining
live oracle-dependent check. No new detection-fidelity finding.

### Findings

- None.

### Concurs / Dissents

- Round-2 finding: concurs — `LIVE_LSP_TIMEOUT_MS = 750` is restored as the
  live host's default (`LiveServer.kt:33,213-214`),
  `HostSettings.oracleOptions(useLsp, defaultTimeoutMs)` only substitutes that
  default when the config did not set `core.lsp.timeoutMs`
  (`HostConfig.kt:17-26`), and `parseProjectConfig` records the explicit-key
  presence (`ProjectConfig.kt:38,72,81,93`). CLI/HTTP still pass no per-host
  default and therefore keep 5000. `LiveServerConfigTest` and `HostConfigTest`
  pin the 750/5000 split and the explicit 900 ms override, and `m007` names 013
  with the live oracle-dependent scenario marked `unverified`.
- Concurs: explicit `core.lsp.enabled` remains honored in all hosts
  (`policy.enabled && useLsp`), including the live heuristic split path, which
  passes `oracleOptions(false)`.
- Concurs: the Round-2 conclusions on key assignment, override semantics,
  default language profiles, Vue parity, source-role comparison and the
  config-driven validation magic comment are unchanged.
- Dissents: none.

### Residuals (not findings)

- `lspTimeoutConfigured` is a parse-time signal carried on `HostSettings`, not on
  `ProjectConfig`; a caller that builds `HostSettings` programmatically with an
  explicit non-default timeout and then uses the live host would still get 750 ms.
  All production hosts go through `HostConfig.load`, so this is an API-shape
  residual, not a detection path.
- Removing the validation fallback still leaves the legacy-config
  `SplitAnalyzer` overload unable to recognise comment-only validation guards
  for return categorization, because `intents.yaml` has no `magicComment`; this
  remains confined to the parity scaffolding pending 014.
- Non-source roles remain excluded from the full `shape()` comparison, and the
  fixture walk remains `NullOracle`-only; both remain test-coverage residuals,
  not detection-path defects.
- Static review only; no source edit, build, test or formatter command was run by
  this role, per `workflow.md`.
