# Correctness review — ticket 007 (error-handling rulepack)

## Round 1

Verdict: Detection parity is exact. `ErrorHandlingConfig.accepts` reproduces the
legacy `IntentDefinition.has` receiver test (`value == q || value.endsWith(".$q")`),
the per-language call sets match `intents.yaml` (Kotlin checks, JS/TS
`assert`/`invariant`, C# `Assert` with `Debug`/`Trace`/`Contract`), `throws` is
always enabled for the shipped profiles, and `emit` reproduces the legacy
`raw` contribution exactly (call/throw site span, newline-flattened 160-char
evidence, `treesitter`, confidence 1.0, byte offsets). The rule is pure, reads
only core facts, declares `reads = {}`, and emits only its owned intent. One
pack/loading contract defect remains: the language-specific pack instances share
one pack id and one rule id, so the "all packs" helper returns a set the
scheduler cannot load.

### Findings

1. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/errorhandling/ErrorHandlingRulePack.kt:49, :54, :59, :66 — `all()` returns mutually exclusive packs with duplicate ids; passing it to the engine is a load error.**

   `all()` returns four `ErrorHandlingRulePack` instances (kotlin, javascript,
   typescript, csharp), all with `id = "error-handling"` and each with a rule
   `id = "error-handling:error-handling"`. Architecture §3 requires rule ids to
   be globally unique. `RuleScheduler.rulesOf` enforces that with
   `DuplicateRuleId`, so `RuleEngine.plan(ErrorHandlingRulePack.all())` throws
   before analysis. The helper therefore looks like a bundle of the supported
   packs but is not a legal pack list, and the pack id no longer identifies a
   single distribution unit. `packProvidesOneInstancePerSupportedLanguage`
   (`ErrorHandlingRulePackTest.kt:55-63`) actually asserts the duplicate
   (`distinct().size == 1`) instead of pinning the usable selection path.

   This is a contract/state issue rather than a detection-parity issue: only one
   error-handling rule may own `ERROR_HANDLING` in a plan, so the four
   language variants are alternatives, not co-loadable packs.
   Fix: make the language selection explicit and non-ambiguous, for example
   have `all()` return a language-keyed selection (or remove it in favour of
   `forLanguage`), and document that exactly one error-handling pack is loaded
   per analysis. Alternatively, if the loader is expected to register
   everything once, model this as one pack whose languages are declared
   together and select the config by the analyzed file's language. Add a test
   that what `all()`/the bundle exposes can actually be passed to
   `RuleEngine.plan` for a chosen language, and that a mixed co-load is rejected
   with a clear selection error rather than falling out as `DuplicateRuleId`.

### Concurs / Dissents

- Concurs: `accepts` is semantically identical to the legacy
  `IntentDefinition.has` check, including the `.`-suffix allowance that tags
  `System.Diagnostics.Debug.Assert` while leaving `c.Assert`/`obj.assert`
  untagged, and the empty-qualifier Kotlin/JS/TS configuration rejects every
  receiver-bearing configured call.
- Concurs: `ErrorHandlingConfig.forLanguage` matches the shipped `intents.yaml`
  overrides, including `throws: true` for all four shipped profiles and the C#
  qualifier set. Unknown languages fall back to the legacy Kotlin/base params,
  so the fallback also preserves legacy behavior.
- Concurs: call spans use `CallFact.site`, preserving trailing lambda arguments,
  and throw contributions use the same throw nodes as the legacy `ctx.throws`;
  ordering and spans therefore match `ErrorHandlingBlock`.
- Concurs: the rule is a pure `(View) -> List<Emit>` with no store access,
  `reads = emptySet()` is honest (only `calls()`/`throws()` are used), and every
  emit carries exactly `setOf(ERROR_HANDLING)`.
- Concurs: the parity tests compare intents, span, evidence, source, confidence
  and byte offsets against the legacy engine for all four fixtures, plus the
  targeted receiver and trailing-lambda cases; the new C# test covers both the
  qualified and arbitrary-receiver distinction.
- Dissents: the duplicate-id `all()` shape (finding 1).

Residual, not a finding: `detectThrows = false` is never exercised because every
shipped profile sets it true; the branch is simple, but a config-level test
would close the only unexercised path in the rule.

## Round 2

Verdict: F1 is addressed. Pack and rule ids are now language-scoped
(`ErrorHandlingRulePack.kt:49-55`), so `all()` produces distinct ids; the correct
selection path is explicit (`selection(language)` returns exactly one pack,
`:64`), and the tests pin both that path and the rejected mixed co-load
(`ErrorHandlingRulePackTest.kt:59-88`). Detection behavior is unchanged from the
round-1 version, so the parity and receiver/throw/lambda concurs still hold. No
new correctness finding.

### Findings

None.

### Concurs / Dissents

- Concurs on F1: `ErrorHandlingRulePack.all()` now returns four packs whose
  `id` and rule `id` are all distinct (`packProvidesOneInstancePerSupportedLanguageWithUniqueIds`,
  `ErrorHandlingRulePackTest.kt:59-69`), and `mixedLanguageCoLoadFailsAsDuplicateIntentOwner`
  (`:83-88`) documents that only one of the four may own `ERROR_HANDLING` in a
  plan. `selection(language)` (`ErrorHandlingRulePack.kt:64`) is the loadable
  path, and `languageSelectionSchedulesAndExecutesSuccessfully`
  (`ErrorHandlingRulePackTest.kt:71-81`) verifies each language yields one
  stratum containing that pack's rule and still matches legacy output. The
  previous `DuplicateRuleId` collapse is gone; the remaining rejection is the
  correct one-intent-owner constraint (§2/§3).
- Concurs: `forLanguage`/`all` still derive the config from the lowercased
  language (`ErrorHandlingRulePack.kt:46-49, :62-66`), so the language-scoped
  ids do not change detection for any shipped profile.
- Concurs: `ErrorHandlingRule` keeps the same receiver test, call/throw
  enumeration, span/evidence fields, owned intent and empty `reads`; the only
  change is the injected language-scoped rule id (`:70-82`). The Kotlin/JS/TS/C#
  fixture and focused parity tests remain meaningful.
- Concurs: the added `detectThrows = false`, Kotlin `assert`, `Catches.kt` and
  Vue-script cases exercise previously uncovered configured paths without
  changing the rule's contract.
- Dissents: none.

Residuals, not findings: `PACK_ID` (`ErrorHandlingRulePack.kt:58`) now names a
prefix rather than the actual pack id (`error-handling-<language>`), and the
public constructor still permits a caller to pair a language with a mismatched
config. Neither affects the shipped `forLanguage`/`selection` paths; a future
config keying or third-party caller should use the actual pack id and the
factory, or the prefix should be renamed.
