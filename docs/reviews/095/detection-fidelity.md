# Detection Fidelity Review — 095 Language-neutral default rulepacks

Verdict: the covered frozen corpus is preserved, but the refactor changes two
unexercised detection paths and does not yet prove the data-only claim.

## Verified

- `tests/parity/v2-parity-report.json` was regenerated after the commit:
  211/211 annotations, 45/45 candidates, 16/16 files, 0 defects, 0 IPC defects.
  `defect-baseline.json` is empty, the 59 intentional differences are unchanged,
  and no new allowlist entries were added. No covered regression is hidden.
- All moved knowledge is faithful: every pack's old `bundled-packs.json` seeds
  and playbook rules match the new per-pack `defaults.json`/`playbook.json`
  exactly (49 logging, 47 data-access, 121 mutation, etc.), and every moved
  constant table matches its `rules.json` counterpart.
- Fact extraction now supplies exits, coalesces, creations, classes, parameters,
  writes-with-value, directives, and return/guard metadata; the parity and
  `PacksBoundaryTest` suites pass.

## Findings

1. **Return literals changed (high).** `RuleEvaluators.kt:657` replaces V1's
   text classification with `ValueKind.LITERAL`. V1 emitted `RETURN_VALUE` for
   `42L`, `0xFF`, `3.14f`, and similar values; those are now suppressed, and the
   corpus contains none of them. Preserve the old buckets via neutral fact
   fields, or record this as an intentional difference and add fixtures.

2. **Descending loop counters changed (medium).** `RuleEvaluators.kt:477` skips
   every increment in a loop header; V1 skipped only `++`
   (`write.kind == INCREMENT && node.text.contains("++")`). `i--` now loses
   `LOCAL_VALUE_MODIFY`. Restore the old condition in
   `SyntaxFactExtractor.kt:1178` or document and baseline the change.

3. **DataOnlyLanguageTest does not prove detection (medium).**
   `DataOnlyLanguageTest.kt:14-43` copies the logging definition, adds `rust` to
   its manifest/seed, and resolves a spec; it registers no descriptor/profile,
   builds no Rust facts, and runs no pipeline, so it passes even if Rust
   detection requires code. Add descriptor + fact batch + pipeline emission.

4. **Guards remain a denylist (medium).** `PacksBoundaryTest.kt:41-89` checks
   hardcoded quoted words only, missing raw-text/regex parsing (e.g.
   `RuleEvaluators.kt:1000,1010`), and its registered-language list
   (`:115-123`) omits `c`. Derive ids from authoritative descriptors and reject
   `SyntaxNode.text`/regex use in pack sources.

## Round 2

Verdict: DISSENT (narrow); requested fixes are present, but new unbaselined
matching deltas remain.

Verified:
- Report was regenerated at 23:06 with the same 211/211 annotations, 45/45
  candidates, 0 defects, 59 intentional differences; defect baseline is still
  empty. `LanguageNeutralDetectionTest` (2/2) and `CorpusParityTest` pass.
- `ReturnValuePack.kt:31` uses `ValueFact.returnLiteral`; the new artifact test
  covers `42L`, `0xFF`, `3.14f`. `MutationPack.kt:158` uses
  `WriteFact.loopIncrement`, which is `++`-only and loop-parent based, and the
  test covers `i--`.
- `LanguageNeutralDetectionTest` registers a Rust descriptor, builds facts, and
  asserts LOGGING plus LOOKUP/SEARCH through `DefaultIntentPipeline`.
- Guards are now an import allowlist + raw-parsing denylist with descriptor-
  derived language ids.

Residual regressions:
1. `returnLiteral` (`SyntaxFactExtractor.kt:664`) uses `PLAIN_NUMBER`, narrower
   than V1 `toLongOrNull`/`toDoubleOrNull`: `1e3`, `+1`, `.5` now emit
   RETURN_VALUE where V1 suppressed. Extend the helper and regression test.
2. Guard matching became exact-token (`RuleMatching.kt:109`) instead of V1
   `text.contains`, so `isLoggingEnabled` no longer emits LOGGING. Add variants
   to defaults or retain substring semantics.
3. Token-prefix matching (`RuleMatching.kt:38`) changes V1 `startsWith`
   (`formatted` no longer matches `format`); verify intentional or restore.

## Round 3

Verdict: DISSENT (narrow); two residuals are fixed, but the `3.14f` expectation
is itself a V1 regression.

Verified: report regenerated at 23:11 still shows 211/211, 45/45, 0 defects;
baseline empty and 59 intentional differences unchanged. `LanguageNeutralDetectionTest`
passes 4/4, `CorpusParityTest` 1/1. `1e3`/`+1`/`.5` are suppressed and
`42L`/`0xFF` emit; guard containment now matches `isLoggingEnabled`; prefix
matching is `startsWith` again. No raw parsing remains in `default-rulepacks`.

Correction: Round 1 was wrong about `3.14f`. V1's `toDoubleOrNull` accepts
`f/F/d/D`, so V1 suppressed `3.14f` (and C# `1D`/`1F`). The new
`TYPE_SUFFIXES` gate (`SyntaxFactExtractor.kt:460,671`) now emits RETURN_VALUE
for them, a new false positive. Drop `f/F/d/D` from the gate (or use V1 parse
semantics directly) and change the regression test to expect suppression;
keep emitting `42L`/`0xFF`.

## Round 4

Verdict: CONCUR.

`returnLiteral` (`SyntaxFactExtractor.kt:663-668`) now uses V1's
`toLongOrNull`/`toDoubleOrNull` directly, with no suffix gate. Tests assert
`42L` and `0xFF` emit, while `3.14f`, `1D`, `1F`, `1e3`, `+1`, and `.5` are
suppressed. The guard-containment and `startsWith` prefix fixes remain intact.
`LanguageNeutralDetectionTest` passes 5/5 and `CorpusParityTest` 1/1 from the
latest artifacts; the regenerated report remains 211/211 annotations, 45/45
candidates, 0 defects, with the empty baseline and 59 unchanged intentional
differences. No raw parsing is present in `default-rulepacks` main sources.
The residual detection deltas from Rounds 1-3 are resolved.
