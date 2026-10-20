# Detection-fidelity review — ticket 023 (simplify logging pack)

## Round 1

Verdict: The rewrite is behaviorally faithful. `print`, `logger-interaction` and
`logging-state` keep the same inputs, heuristic precedence, confidences, source
tiers, oracle fallback/veto policy, import gate, spans and evidence as the
pre-refactor pack. The language defaults are unchanged and the new constants
preserve `logging`/`LOGGING`/`logging:logging`. One oracle-variant branch is not
pinned by the tests, so a regression in the "strong heuristics bypass the
oracle" policy would pass the suite.

### Findings

1. **`analysis-engine/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/logging/LoggingRulePackTest.kt:170-255` — oracle policy coverage does not pin the qualified-static bypass or the typed override of a weak heuristic.**

   The tests cover the factory-symbol shortcut with a counting oracle
   (`factoryLoggerSymbolSkipsOracle`), the receiver-name and import-gated veto,
   the unresolved fallback, the null-heuristic typed match, and the non-call
   initializer veto. They do not exercise a `QUALIFIED` call against a resolved
   non-logger type, so the `QUALIFIED(0.8)` flag at
   `LoggingRulePack.kt:138` could flip to `consultsOracle = true` and still pass:
   the fixture snapshots use `NullOracle`, where consulting the oracle falls
   back to the same 0.8 heuristic. Similarly, no case covers a receiver-name or
   import-gated call whose oracle resolves to a configured logger type, so the
   branch at `LoggingRulePack.kt:86-87` that replaces a 0.6/0.5 heuristic with
   the oracle's `source`/`confidence` is not distinguished from the fallback.
   Both are part of the ticket's "preserve parity over ... oracle variants".

   Suggested fix: add three focused cases using `FixedOracle`: a qualified call
   (for example `System.out.info(...)`) with a resolved non-logger type that
   still emits `heuristic`/0.8 (and, with `CountingOracle`, zero oracle calls); a
   receiver-name call with a resolved `org.slf4j.Logger` type that emits the
   oracle source/confidence instead of 0.6; and an import-gated call whose
   resolved logger type emits the oracle source/confidence instead of 0.5.

### Concurs / Dissents

- Concurs: `print` still filters on `callee in printCalls` and
  `receiver == null || receiver.text in printQualifiers`, emits `call.site` at
  `treesitter`/1.0, and therefore keeps trailing-lambda spans and the legacy
  evidence text.
- Concurs: `loggerInteraction` keeps the `methods` gate, receiver presence
  requirement, and heuristic order `FACTORY`/`RECEIVER_NAME`/`QUALIFIED`/
  `IMPORTED` with confidences 0.8/0.6/0.8/0.5
  (`LoggingRulePack.kt:63-87`).
- Concurs: the oracle policy is reproduced exactly. Factory and qualified
  matches do not call the oracle; receiver-name, import-gated and no-heuristic
  calls do; an unresolved type falls back to the heuristic; a resolved
  non-matching type vetoes; a resolved matching type emits the oracle
  source/confidence. `view.receiverType(call)` is equivalent to the old
  `view.typeAt(receiver)` because both call the oracle with
  `syntax.receiverOffset(receiver)`.
- Concurs: `loggerSymbols` still requires an exact initializer/call node-span
  match and matches `path == factory || path.endsWith(".$factory")`.
  `CallFact.path` is populated by core with the same receiver-plus-callee
  derivation the removed local `callPath` used, and a null callee still yields
  no symbol.
- Concurs: the import gate is unchanged: an exact import or a prefix
  `import.startsWith("$it.")` enables the `IMPORTED` heuristic, and it is still
  lower precedence than a configured receiver name or qualifier.
- Concurs: `loggingState` still checks `guardNames` with
  `contains(..., ignoreCase = true)` over `guardConditions` and emits
  `treesitter`/0.7 with the condition node's evidence and span.
- Concurs: spans/evidence are identical. `CallFact.emit` emits `call.site`;
  `Emit.of` uses `node.evidence()` (newline collapse plus 160-character
  truncation), `node.span()` and start/end bytes, matching the removed private
  `emit`.
- Concurs: `LoggingConfig.defaults()` keeps the Kotlin/JavaScript/TypeScript/C#
  values (including the empty `types` sets for JS/TS/C#) and no `vue` entry.
  Caching it in `LANGUAGE_DEFAULTS` does not mutate or alias caller data:
  `LanguageConfig.withOverridesResult` copies the map before applying overrides.
  `withOverrides` now uses `LOGGING_PACK_ID`, which is the same `"logging"`
  decode path and error prefix.
- Concurs: `LoggingRule` is private and the pack still owns only `LOGGING` with
  empty reads; no grammar kind or AST traversal remains in the pack. The
  `LegacyParitySnapshotTest` fixture walk still pins the default `LOGGING`
  sources, spans, evidence, tiers and confidences over all `testing-grounds`
  fixtures, including Vue script regions.
- Dissents: finding 1.

### Residuals (not findings)

- The fixture snapshot compares `NullOracle` only; the finding above targets the
  missing oracle-variant assertions rather than the snapshot itself.
- `loggerSymbols` builds `callsBySpan` with `associateBy`, so duplicate call
  node spans would resolve last-wins instead of the old `firstOrNull` first-wins.
  Core call extraction yields one fact per node, so no current path changes.
- The pack now trusts `CallFact.path`; an alternate `View` that supplies a
  non-null callee/receiver with a deliberately null or inconsistent path would
  differ from the old local derivation. Core always populates it.

### Verified

- Static review only; no source edit, build, test or formatter command was run
  by this role, per `workflow.md`. Compared the diff with the prior
  implementation, `CallFacts`/`CallFact.path`, `AnalysisContext.receiverType`,
  `CapabilityView.typeAt`, the `Emit` builders, the logging config defaults,
  the logging tests and the pre-014 snapshot fixtures.

## Round 2

Verdict: The Round-1 finding is addressed. The three added cases pin the
qualified-static no-oracle bypass and the typed override of both weak
heuristics, so the oracle fallback/veto policy is now demonstrated in both
directions. No new detection-fidelity finding.

### Findings

- None.

### Concurs / Dissents

- F1: concurs — `qualifiedStaticBypassesOracleEvenForResolvedNonLoggerType`
  (`LoggingRulePackTest.kt:239-260`) exercises `System.out.info` with both a
  counting oracle and a resolved non-logger `FixedOracle`; both runs emit
  `heuristic`/0.8 and the counting oracle records zero calls. That pins the
  `QUALIFIED(0.8)` enum entry as non-oracle, so flipping it to
  `consultsOracle = true` would now fail.
- F1: concurs — `resolvedLoggerTypeOverridesReceiverNameHeuristic`
  (`:262-277`) and `resolvedLoggerTypeOverridesImportGatedHeuristic`
  (`:279-295`) use `logger.info` and the import-gated `fake.info` with resolved
  `org.slf4j.Logger` types at distinct confidences (0.91 and 0.88). They assert
  `source = lsp` and the oracle confidence, so emitting the 0.6/0.5 heuristic
  fallback on a match would now fail, while the existing non-logger veto cases
  still cover the reject direction.
- Concurs: the new cases use the real frontends and the pack's default
  `LoggingConfig`, so `System.out` qualifier matching, the `methods` gate and
  the `imports` prefix gate are exercised through the same path as production
  detection, not a hand-built `View`.
- Concurs: the Round-1 conclusions on print/logger/state parity, import gating,
  config defaults, constants, and span/evidence construction are unchanged; the
  test-only edit does not alter rule behavior.
- Dissents: none.

### Residuals (not findings)

- The fixture snapshot still compares `NullOracle` only; the new targeted cases
  cover the oracle branches, so this remains a snapshot-scope residual.
- `loggerSymbols` still resolves duplicate call spans last-wins via
  `associateBy`, and the pack still trusts `CallFact.path`; neither is reachable
  from current core call extraction.

### Verified

- Static review only; no source edit, build, test or formatter command was run
  by this role, per `workflow.md`. Reviewed the test diff, the three new test
  bodies, the existing oracle/veto cases, and the unchanged logging rule.
