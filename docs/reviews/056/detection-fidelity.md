# Detection fidelity review — 056 detection and AI sensitivity bundle

## Verdict

Changes requested. The scaffold `UNTAGGED` filtering, the whole-statement
`RETURN_VALUE` span, and the AI sensitivity defaults are implemented and mostly
pinned by tests and the regenerated snapshot. Two behavioral gaps remain: the
`RETURN_VALUE` bypass in derivative promotion also promotes sibling non-return
intents despite unclassified reads, and `getBy*`/`loadBy*` repository criteria
calls still receive a `LOOKUP` candidate while not becoming established
`SEARCH`. The prompt text also changed without a prompt-version bump.

## Baseline

Reviewed the uncommitted diff on `ticket/056-fidelity` against the ticket,
`docs/workflow.md`, `docs/engine.md`, `docs/intent-model.md`,
`docs/data-access-intents.md`, `docs/ai-intents.md`, the rulepack sources, the
renderer sources, the rulepack/integration tests, and the testing-grounds
fixtures. The snapshot was compared annotation-by-annotation against `HEAD`.
Static review only; no source edit, build, test, or formatter command was run.

## Findings

### 1. `RETURN_VALUE` bypass also promotes sibling intents past the all-classified policy

Location:
`analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/composition/DerivativeComposition.kt:50-58`
(`allClassified`/`carriesReturnValue` gate and the union at line 58), with the
documented behavior at `docs/engine.md:242-245` and
`docs/intent-model.md:352-356`.

Problem: When a read carries `RETURN_VALUE` but a sibling read is unclassified,
the code bypasses the all-classified gate and then emits the union of every real
intent from every read. This relaxes the all-uses-classified policy for intents
other than `RETURN_VALUE`, which the ticket explicitly says to keep. The
regenerated snapshot shows the regression: C# `value = Compute(id)`
(`startByte` 461-480) and JS `value = compute(id)` (`startByte` 162-181) now
carry `{LOGGING, RETURN_VALUE}` on the declaration even though the `value < 0`
read is unclassified; only one read (`Console.WriteLine(value)` /
`console.log("done", value)`) is logging. The new integration tests cover only
shapes where no sibling read is classified, so they assert
`setOf("RETURN_VALUE")` and do not pin this mixed case.

Suggested fix: In the `!allClassified && carriesReturnValue` branch, emit only
`RETURN_VALUE` (or retain other intents only when `allClassified`); keep the
current union for fully classified locals. Regenerate the snapshot and add a
test with a returned local that has one classified sibling read (for example
logging) plus one unclassified read, asserting the derivative intents are only
`RETURN_VALUE`.

### 2. `getBy*`/`loadBy*` repository criteria calls are still lookup alternatives and are not established search

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/config/DataAccessConfigDefaults.kt:33,138,171`
(`SEARCH_PREFIXES`, `AMBIGUOUS_PREFIXES`, empty `boundaryPrefixes`),
`.../rules/DataAccessMatching.kt:6-15`, `.../rules/DataAccessLookupMatcher.kt:30-34`,
and the coverage gap in
`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/dataaccess/overlap/DataAccessOverlapTest.kt:38-72`.

Problem: Emptying `boundaryPrefixes` removes the boundary candidate, but it does
not remove the lookup candidate for `getBy*`/`loadBy*` calls. `repo.getById(id)`
and `repo.loadById(id)` still match `ambiguousPrefixes` (`get`, `load`), so
`isAmbiguous` is true: the lookup rule emits a `PACK_DOMAIN` `LOOKUP` candidate
and the search rule only emits a `SEARCH` candidate (they are not in
`searchPrefixes`, so no established `SEARCH`). The ticket goal and scope name
`findBy*`/`getBy*`/`loadBy*` repository calls, but the added tests only cover
`findById`; `getBy*`/`loadBy*` are untested. Known map/enum retrieval remains
correct.

Suggested fix: Treat `getBy*`/`loadBy*` as repository criteria like `findBy*`
(for example add them to `SEARCH_PREFIXES` and let the existing search-prefix
guard in `isAmbiguous` disable the lookup candidate), or explicitly exclude
repository-qualified/typed `getBy*`/`loadBy*` calls from ambiguity. Add tests
for `repo.getById`/`repo.loadById` asserting established `SEARCH` and no
`LOOKUP` candidate, and update the SEARCH/LOOKUP docs accordingly.

### 3. Prompt content changed without bumping the prompt version

Location:
`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/provider/DeepSeekIntentSuggestionProvider.kt:15,176-186`,
with `docs/ai-intents.md:142-144`.

Problem: The system prompt now asks for several alternatives and lower-confidence
guesses, but `DEEPSEEK_PROMPT_VERSION` remains `deepseek-intent-v1`. Persisted
`CandidateEmit` provenance therefore cannot distinguish proposals produced by the
old certainty-oriented prompt from the new recall-oriented prompt, even though
the docs describe the prompt as versioned and `promptVersion` is carried into the
published candidates. The new test only checks the request temperature/token
budget and the presence of the new prompt wording, so the version regression is
not caught.

Suggested fix: Bump the version (for example `deepseek-intent-v2`) and update
the docs/tests that assert the default prompt version, or explicitly document
that the version tracks only the response schema and not prompt semantics.

## Residuals (not findings)

- Return-value propagation still resolves a read through `intentsAt`, which
  selects the smallest covering annotation. A smaller source annotation on the
  returned expression can shadow the enclosing `RETURN_VALUE` for an argument
  read, so the "nested flow" behavior is reliable for the two acceptance shapes
  but not general. This is within the ticket's whole-program-dataflow exclusion;
  add a case only if that shape is expected.
- `SuggestionOptions`' own 10/12 defaults are not directly asserted; the server
  loader defaults are pinned by `LiveSuggestionSettingsTest`.
- The regenerated pipeline snapshot otherwise contains only the expected
  `RETURN_VALUE` statement-span widening; the only unrelated-looking additions
  are the derivative annotations described in finding 1.
- Static review only; the green handover is assumed per `docs/workflow.md`.

## Concurs / Dissents

- Concurs: `RETURN_VALUE` source spans now cover the whole return statement
  including labeled returns, the additive tests are updated, and the snapshot
  widening is consistent across Kotlin, C#, JS, and TS.
- Concurs: scaffold rendering ignores `UNTAGGED` for meaning/tag resolution,
  mixed-intent detection, labels, and stripes; the added renderer case pins
  `{ERROR_HANDLING, UNTAGGED}` rendering as `ERROR_HANDLING`.
- Concurs: AI defaults are raised (10 candidates, 12 enrichment candidates,
  1024 tokens), temperature is nonzero (`0.3`), the prompt asks for
  alternatives, and byte containment, candidate-only output, confidence capping,
  and privacy/redaction behavior are unchanged.
- Concurs: `findBy*` no longer receives a default lookup boundary candidate, and
  known map/enum/config retrieval remains `LOOKUP`.
- Dissents: the derivative `RETURN_VALUE` bypass over-promotes sibling intents;
  `getBy*`/`loadBy*` are not brought under the SEARCH contract; the prompt
  version was not advanced after the prompt change.

Summary: the bundle lands the intended span widening and renderer/AI sensitivity
changes, but it does not fully honor the "keep other derivative intents on the
all-uses-classified policy" constraint and only fixes the `findBy*` half of the
named repository-criteria set. I would fix or explicitly defer those two
behavioral points (and decide on the prompt-version bump) before closing.

## Round 2

### Verdict

Concurs — zero findings; close. All three round-1 findings are resolved, and the
additional scaffold/untagged/default fixes do not introduce a detection
regression in the reviewed surfaces.

### Status of round-1 findings

1. Resolved: `DerivativeComposition.kt:55-64` now keeps the full union only when
   `allClassified`; the `RETURN_VALUE` bypass emits exactly
   `setOf(RETURN_VALUE_INTENT)`. The new
   `returnedLocalKeepsSiblingIntentsBehindTheAllClassifiedGate` test covers a
   local with a logging read plus an unclassified read, and the regenerated C#/JS
   snapshot derivative entries now carry only `RETURN_VALUE` (C# 461-480, JS
   162-181). Docs now say other intents still require every read to be
   classified.
2. Resolved: `SEARCH_PREFIXES` now includes `getBy`/`loadBy`
   (`DataAccessConfigDefaults.kt:33`), so the existing `isAmbiguous` search-prefix
   guard makes repository `getBy*`/`loadBy*` calls established `SEARCH` with no
   lookup candidate. `getByAndLoadByRepositoryCriteriaRemainSearchWithoutLookupAlternative`
   and the config test pin the behavior; `findById` and the configured
   `boundaryPrefixes` override tests remain green by construction. Docs are
   updated consistently.
3. Resolved: `DEEPSEEK_PROMPT_VERSION` is now `deepseek-intent-v2`, with the
   provider test asserting the constant, `LiveSuggestionDeepSeekTest` updated,
   and the docs/notes showing v2.

### Additional changes reviewed

- `ScaffoldComposition.kt:214-224` now subtracts `RETURN_VALUE` from the exact
  return-statement coverage and from the expression fallback. This is the
  correct counterpart to the wider span: `return requireNotNull(...)` keeps its
  `ERROR_HANDLING` function/branch scaffold instead of being relabelled
  `RETURN_VALUE`. The new `ReturnCategoryCompositionTest` cases pin both the
  function and branch shapes, and the snapshot shows no scaffold churn.
- The renderer's `real_intents` now falls back to the original list when every
  intent is `UNTAGGED` (`init.lua:11-23`, `labels.lua`, `stripes.lua`), so
  untagged-only spans retain their configured meaning/tag while
  `{real, UNTAGGED}` still resolves to the real intent and is not treated as
  mixed. Both shapes are explicitly tested.
- `CandidateValidator` defaults now match the raised `maxCandidates` (10), and
  the stale `512`/`4000` defaults in notes/README/engine docs are corrected to
  `1024`/`10000`.

### New / unresolved findings

None blocking. Residual observation only: adding `getBy`/`loadBy` globally to
`SEARCH_PREFIXES` makes the ambiguity guard suppress the lookup path for any
receiver with such a callee. A lookup-qualified receiver calling an
unconfigured method such as `cache.loadByX(...)` or
`statusById.getByCode(...)` no longer receives a `LOOKUP` candidate (the prior
ambiguous-prefix path did); `findBy*` already had this receiver-insensitive
shape, and the tested map index/getValue/getOrDefault/lookup and enum paths are
unaffected. If that receiver shape matters later, make prefix-based ambiguity
suppression receiver-aware. The round-1 residual about
smallest-annotation shadowing also remains, but is outside this ticket.

### Concurs / Dissents

- Concurs: finding 1 is fixed and pinned by unit, integration, and snapshot
  evidence.
- Concurs: finding 2 is fixed for `findBy*`/`getBy*`/`loadBy*`, with the
  established-SEARCH/no-LOOKUP contract tested.
- Concurs: finding 3 is fixed with a v2 prompt version and matching docs/tests.
- Concurs: scaffold domain recovery, untagged-only rendering, and the stale
  default cleanup are correct and covered.
- Dissents: none.

Summary: round 2 resolves all three findings without introducing a reviewed
detection regression; the derivative bypass is now limited to `RETURN_VALUE`,
repository criteria prefixes establish `SEARCH` without default lookup
alternatives, the prompt version matches the changed prompt, and the extra
scaffold/untagged/default fixes are consistent with the updated docs and
snapshots. I concur and would close the ticket; the only note is the non-blocking
receiver-insensitive `getBy*`/`loadBy*` prefix behavior for lookup-qualified
receivers, which is a possible follow-up rather than a finding.
