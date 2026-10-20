# Detection-fidelity review — ticket 006 (logging rulepack)

## Round 1

Verdict: The one-rule/three-matcher shape is correct, and the matcher logic is a
faithful port of `PrintBlock`/`LoggerInteractionBlock`/`LoggingStateBlock`. The
configured values match `intents.yaml` for Kotlin/JS/TS/C#, and span, evidence,
confidence, source and `reads` construction are unchanged. However, parity is
only *demonstrated* on the `NullOracle` path: the oracle fallback/veto branches
are never compared against the legacy engine, the added `vue` language entry
diverges from the legacy language lookup, and the golden-fixture half of the
acceptance criterion is not exercised. Findings are work.

### Findings

1. **`analysis-engine/frontend/src/test/kotlin/dev/cint/frontend/LoggingRulePackTest.kt:63-84,135-163` — fixture parity never exercises the LSP-oracle branches against legacy.**
   Both `legacyAnnotations` and `newAnnotations` default to `NullOracle`, and
   `matchesLegacyLoggingOverTestingGrounds` always calls them without one. That
   means the only legacy-vs-new comparison covers the no-oracle path. The oracle
   tests at `:206-275` assert the *new* engine against hand-written expectations;
   they do not compare to legacy. In particular `testing-grounds/kotlin/Heuristics.kt`,
   whose purpose is the resolved-non-logger veto, runs through the parity loop
   with no oracle and therefore only proves the false positive in both engines.
   A regression in the oracle offset, `source`/`confidence` propagation, or the
   veto/fallback decision would pass this suite.
   Fix: run the fixture loop with at least one deterministic oracle in addition
   to `NullOracle` (or add legacy-vs-new assertions to each oracle test), covering
   a configured logger type hit (`source = lsp`, oracle confidence), a resolved
   non-logger veto on both `receiver-name` and `name-import-gated`, an unresolved
   fallback, and the factory-symbol shortcut that must not call the oracle.

2. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/LoggingRulePack.kt:24-29,102-113,117-119` — the `vue` config entry diverges from legacy and does not match how Vue is analyzed.**
   `VueFrontend` splits a `.vue` file into script regions and labels those
   regions `javascript`/`typescript`; `SplitAnalyzer` then loads config with the
   region language. Legacy `loadIntentConfig(null, "vue")` has no override, so it
   falls back to the Kotlin base params. The new defaults add a `vue` entry with
   JavaScript values and advertise `vue` in `RulePack.languages`. If a host ever
   passes the frontend language (`vue`) instead of the region language, new
   detection is JS-style while legacy is Kotlin-style, and the parity test never
   sees it because it deliberately passes `region.language`. The `vue` entry is
   dead for the real split path and is an extra, drifting source of truth; the
   test at `LoggingRulePackTest.kt:127-133` currently pins it.
   Fix: remove the `vue` entry and drop `vue` from `languages`, documenting that
   the pack is applied per script region with the region language; or, if a
   direct `vue` invocation is intended, define the behavior and add a parity test
   for it.

3. **`docs/tasks/006-logging-rulepack.md:30-33` with `LoggingRulePackTest.kt:135-166` — golden-fixture parity is claimed but not exercised, and no golden fixtures exist in this worktree.**
   The only fixture walk is `fixtureRoot()`, which resolves `testing-grounds/`.
   There is no `tags.json` or golden fixture anywhere in the tree (including git
   history), so the acceptance item “parity ... over `testing-grounds/` and the
   golden fixtures” is silently narrowed to `testing-grounds/`. The fixtures are
   a development check, but the criterion names them.
   Fix: point the test at the golden fixtures and include them, or, since they
   are unavailable here, record the golden-fixture spot check as unverified in
   `docs/manual-test.md` naming 006, per `workflow.md`’s manual gate, so the
   acceptance gap is visible rather than implicit.

### Concurs / Dissents

- Concurs: matcher behavior matches the legacy blocks. `print` keeps the exact
  callee + receiver-text gate (`Blocks.kt:66-89`); `logger-interaction` keeps the
  same factory path match (`IntentConfig.kt:13-16`), import gate, `methods`
  gate, receiver-name and qualified-static heuristics, oracle consultation only
  for `receiver-name`/`name-import-gated`/no-heuristic, configured-type match,
  and resolved-non-logger veto (`Blocks.kt:91-172`); `logging-state` keeps the
  same `contains(..., ignoreCase = true)` over guard conditions
  (`Blocks.kt:174-190`).
- Concurs: `LoggingLanguageConfig.defaults()` reproduces the `intents.yaml`
  Kotlin, JavaScript, TypeScript and C# values (print calls/qualifiers, methods,
  types, factories, receiver names, qualifiers, guard names, imports). The
  `types` sets are empty for JS/TS/C# as in the legacy overrides.
- Concurs: span, evidence, source and confidence are preserved. `emit()` mirrors
  the legacy `raw()` (`node.text.replace("\n", " ").take(160)`, `node.span()`,
  start/end bytes); print is `treesitter`/1.0, logging-state is `treesitter`/0.7,
  the heuristics keep 0.8/0.6/0.8/0.5, and a typed receiver keeps the oracle’s
  `source` and `confidence`. Trailing lambdas are included because both engines
  emit `call.site`, and the new test asserts that span/evidence behavior.
- Concurs: `reads` is empty and the rule touches only core facts
  (`calls`/`properties`/`imports`/`guardConditions`) plus `typeAt`; there is no
  annotation or undeclared-intent access. `CapabilityView.typeAt` only delegates
  to the oracle using the same `receiverOffset` call as the legacy block.
- Concurs: no core language branching was introduced; language values live in
  the pack and `CapabilityView` only exposes `context.language`.
- Concurs (deferral): `BundledRulePacks.packs()` still returns an empty list, so
  no production path executes the pack yet. Ticket 011 owns `ServiceLoader`
  registration of bundled packs, so this is acceptable for 006; if 011 does not
  register it, the rule remains test-only.
- Dissents: findings 1-3.

### Verified

- No build, test or formatter command was run by this role; the handover was
  treated as green per `workflow.md`, and this review is read/reason only.
- Reviewed the uncommitted diff, the new `LoggingRulePack`/test, the legacy
  `Blocks.kt` logging blocks, `intents.yaml`, `IntentConfig.has`, the syntax
  facts and `guardConditions`, `CapabilityView`, `RuleEngine`, the
  `AnnotationStore` grouping, the Vue split path, and all `testing-grounds/`
  fixtures.

## Round 2

Verdict: F1 and F2 are addressed. F3 is not: the claimed manual-test deferral
does not exist in the worktree, so the golden-fixture acceptance gap remains.
The source tightening in `callPath` is consistent with the legacy call-path
semantics for the tested shapes and introduces no new detection-fidelity
finding.

### Findings

- F1: concurs — the fixture parity loop now runs each file, and each Vue script
  region, against `null`, `resolved-logger` and `resolved-non-logger` oracles
  (`LoggingRulePackTest.kt:108-114,148-185`). This exercises the resolved
  non-logger veto on `Heuristics.kt`, the import-gated `fake` positive typed
  match, and the factory-symbol shortcut. The dedicated tests now also compare
  legacy to new for the factory shortcut and its zero oracle calls
  (`:223-241`), the ambiguous fallback and its single oracle call (`:258-275`),
  the receiver-name veto (`:277-290`), the typed hit including `source`/confidence
  (`:292-308`), and the import-gated fallback/veto (`:310-327`). The new
  `nonCallInitializerFactoryCallDoesNotBypassOracleVeto` test (`:243-256`) also
  pins that a nested factory call inside an `if` initializer does not become a
  logger symbol in either engine.
- F2: concurs — the `vue` entry is gone from `LoggingConfig.defaults()`, the
  pack derives `languages` from the remaining Kotlin/JS/TS/C# keys, and
  `packDeclaresOneLoggingRule` now asserts `"vue" !in pack.languages`
  (`LoggingRulePackTest.kt:136-146`). `forLanguage("vue")` therefore falls back
  to the Kotlin config, matching the legacy `loadIntentConfig(null, "vue")`
  behavior. No new detection-fidelity finding from the removal.
- F3: dissents — the deferral is not recorded. `docs/manual-test.md` still
  contains only `m001` and `m002`, it is not modified in the worktree, and
  neither the task nor any doc names 006 or the golden fixtures. The acceptance
  criterion is therefore still silently narrowed to `testing-grounds/`. Fix:
  add a manual-test row naming 006 (for example, golden-fixture logging parity,
  `unverified`, “no golden fixtures or tags.json exist in this tree; only
  testing-grounds parity is automated”), as required by `workflow.md`’s manual
  gate.

### Concurs / Dissents

- Concurs: the narrowed `callPath` (`LoggingRulePack.kt:208-218`) now requires
  the property initializer to be the call node itself, which matches the legacy
  `syntax.callPath` behavior for direct calls and removes the previous
  containment false positive without changing any fixture match.
- Concurs: the new oracle variants preserve legacy behavior rather than
  introducing a blended expectation: both engines are run with the same
  `FixedOracle`, and the dedicated cases assert legacy and new output together.
- Concurs: matcher logic, language defaults (now without `vue`), span/evidence/
  confidence/source, trailing-lambda spans, and the empty `reads` remain as
  verified in round 1; no core language branching was added.
- Dissents: F3 only.

### Verified

- No build, test or formatter command was run by this role; the handover was
  treated as green per `workflow.md`. Reviewed the updated untracked
  `LoggingRulePackTest.kt` and `LoggingRulePack.kt`, `docs/manual-test.md`, the
  ticket, and the worktree status.

## Round 3

Verdict: F3 is addressed by `m003`, which names 006, describes the golden-fixture
logging-parity check accurately, records it as `unverified`, and explains why no
automated run exists here. No new detection-fidelity findings.

### Findings

- F3: concurs — `docs/manual-test.md:11` now carries `m003` for “Logging
  golden-fixture parity”, ticket `006`, status `unverified`, with the note that
  no golden fixtures or `tags.json` exist in this worktree or its history and
  that `testing-grounds/` parity is automated in `LoggingRulePackTest`. That
  satisfies the manual-gate requirement and makes the acceptance narrowing
  visible instead of implicit.

### Concurs / Dissents

- Concurs: the task’s new contract note (`docs/tasks/006-logging-rulepack.md:48-63`)
  records the additive `View.language`/`typeAt`/`guardConditions` surface and
  points the compatibility gate at 011; it changes no detection behavior.
- Concurs: the round-2 detection conclusions still hold — matcher parity,
  language defaults without `vue`, span/evidence/confidence/source, trailing
  lambdas, and honest empty `reads` are unchanged.
- Dissents: none.

### Verified

- No build, test or formatter command was run by this role; the handover was
  treated as green per `workflow.md`. Reviewed the new `m003` row and the task
  contract note against the existing review and the worktree status.
