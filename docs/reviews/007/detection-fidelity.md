# Detection-fidelity review — ticket 007 (error-handling rulepack)

## Round 1

Verdict: Detection parity is exact for the reviewed paths. The new rule uses the
same syntax facts, `call.site` span resolution, receiver/qualifier test, throw
enumeration, evidence formatting, source, and confidence as the legacy
`ErrorHandlingBlock`, and its pack-local config reproduces the
`intents.yaml` values for Kotlin, JavaScript, TypeScript, and C#. The rule is
pure and its `reads` are honest. No detection-fidelity findings.

### Findings

None.

### Concurs / Dissents

- Concurs: `ErrorHandlingConfig.KOTLIN_CALLS`, `JAVASCRIPT_CALLS`,
  `CSHARP_CALLS`, and `CSHARP_QUALIFIERS`
  (`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/errorhandling/ErrorHandlingRulePack.kt:27-32`)
  match the corresponding `intents.yaml` `params`/`overrides` exactly:
  `require, requireNotNull, check, checkNotNull, error, TODO, assert`;
  `assert, invariant`; and `Assert` with `Debug, Trace, Contract`.
- Concurs: qualifier gating is parity-faithful. `accepts` returns true for an
  unqualified call, and for a received call only when the receiver text equals a
  qualifier or ends with `".$qualifier"`
  (`ErrorHandlingRulePack.kt:18-25`), exactly mirroring
  `IntentDefinition.has` (`core/config/IntentConfig.kt:13-17`). Kotlin/JS/TS
  have empty qualifier sets, so `c.require(...)`, `obj.assert(...)`, and
  `c.Assert(...)` stay untagged, while C# `Debug.Assert`, `Trace.Assert`,
  `Contract.Assert`, and `System.Diagnostics.Debug.Assert` tag.
- Concurs: throw detection uses `view.throws()` when `detectThrows` is true
  (`ErrorHandlingRulePack.kt:72-75`), which is the same
  `syntax.isThrow` enumeration used by the legacy engine
  (`core/engine/Engine.kt` `AnalysisContext.throws`); all four legacy override
  configs have `throws: true`.
- Concurs: trailing-lambda spans are inherited from `CallFact.site`
  (`ErrorHandlingRulePack.kt:73`), the same site that legacy
  `ErrorHandlingBlock` annotates. `ProfileSyntaxModel.callSite` extends through
  call parents whose suffix contains a lambda
  (`core/syntax/ProfileSyntaxModel.kt:476-487`), and the new test
  `trailingLambdaSpanIsIncluded` pins a multi-line lambda span and evidence.
- Concurs: span, evidence, source, confidence, and byte offsets match legacy
  `SyntaxNode.raw`: `node.span()`, `node.text.replace("\n", " ").take(160)`,
  `"treesitter"`, `1.0`, and `startByte`/`endByte`
  (`ErrorHandlingRulePack.kt:78-87`). The tests compare the complete
  `ErrorHandlingHit` record, including all of these fields, against the legacy
  engine for the Kotlin, JavaScript, TypeScript, and C# fixtures.
- Concurs: detection semantics are exercised beyond the fixture happy path:
  the C# test covers all three qualifiers, a fully qualified `Debug.Assert`, and
  an arbitrary receiver; the Kotlin test covers a received `require` against an
  unqualified one plus a trailing lambda; the JavaScript test covers
  unqualified `assert`/`invariant` against a received `assert`.
- Concurs: honest reads and purity. `reads` is empty (`:70`), the rule reads
  only always-available core facts (`calls`, `throws`), never calls
  `annotations`/`intentsAt`, mutates no store, and returns `List<Emit>`.
  `RuleEngine.contributionsOf` accepts its exact owned-intent emit set.
- Dissents: none.

### Residuals (not filed as findings)

- The parity test iterates Kotlin `ErrorHandling.kt`, the JS/TS/C# `Sample`
  files, and focused snippets, but not every fixture containing an error-shaped
  construct (`Catches.kt` rethrows and `vue/Sample.vue` throws). Those paths use
  the already-covered `throw` shape; Vue host-coordinate parity belongs to the
  split/host cutover rather than this rulepack.
- Kotlin `assert` is in the configured call set and matches `intents.yaml`, but
  no current fixture contains it. The code path is the same as the other
  configured calls, so this is fixture coverage, not a behavior difference.
- `ErrorHandlingRulePack.all()` returns four language-specific packs that share
  the pack id and rule id. They must be selected/filtered by language before
  scheduling; passing the whole list to `RuleEngine.plan` would hit the
  scheduler's duplicate-rule-id load error. Multi-language pack registration is
  for 011/013/015, not a ticket-007 detection mismatch.

### Verified

- No build, test, or formatter command was run by this role; the handover is
  treated as green per `docs/workflow.md`.
- The tracked diff contains only `treesitter/build.gradle.kts` adding the
  test-only `:rulepacks` dependency; the reviewed detection source and test are
  untracked additions.

## Round 2

Verdict: Concur. The round-1 residuals are addressed without changing detection
semantics, and no new detection-fidelity finding was introduced.

### Findings

None.

### Concurs / Dissents

- Concurs: Vue throw coverage is added. `vueScriptThrowMatchesLegacy` extracts
  the `<script>` body from `testing-grounds/vue/Sample.vue`, analyzes it as
  TypeScript, and asserts full legacy parity for the `throw`. This covers the
  miss from round 1; host-coordinate span shifting remains the split/host
  cutover's concern, not this rulepack's.
- Concurs: Kotlin `assert` coverage is added. `kotlinAssertMatchesLegacy`
  exercises the one configured call missing from `ErrorHandling.kt`.
  `kotlinCatchesFixtureMatchesLegacy` also checks the rethrow fixture, so the
  remaining throw fixture is covered against the legacy engine.
- Concurs: the `detectThrows` branch is now pinned. With
  `ErrorHandlingConfig(detectThrows = false)`, the test confirms the same source
  yields one legacy hit and no new hit. The default remains `true`, so configured
  language behavior is unchanged.
- Concurs: the round-1 multi-language residual is resolved in the API and
  tests. Pack and rule ids now include the language
  (`error-handling-kotlin:error-handling`, etc.), `selection(language)` returns
  one schedulable pack, and `languageSelectionSchedulesAndExecutesSuccessfully`
  plans and runs each language variant. The test that `all()` fails as
  `DuplicateIntentOwner` pins the remaining restriction: variants are selected
  per language rather than co-loaded. That is consistent with one owner per
  intent and does not affect per-file detection parity.
- Concurs: the detection body is unchanged. Calls are still filtered by the
  same language config and `accepts` receiver rule, throws still come from
  `view.throws()`, and span/evidence/source/confidence/byte fields are still the
  legacy `raw` equivalent. The id rename only changes provenance identifiers.
- Dissents: none.

### Residuals (not filed as findings)

- The Vue test verifies detection parity on the extracted script, not shifted
  host spans or descriptor/CRLF edge cases. Those remain host/split cutover
  verification, not a rulepack detection difference.
- Multi-language analysis must invoke `selection`/`forLanguage` per file or
  otherwise filter the variant packs; loading every variant together is still a
  duplicate-intent load error by design.
