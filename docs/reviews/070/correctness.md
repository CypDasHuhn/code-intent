## Round 1

Verdict: FINDINGS

### Findings

1. `analysis-engine-v2/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:91-101` — `Composition.settle` feeds derivative/scaffold output back and loops until `replaceRule` is unchanged, with no iteration cap, repeated-state check, or named error. A non-converging or oscillating interaction hangs analysis; only rule fixpoints get `NonConvergentRule`. Fix: apply the same bounded guard/repeated-state detection to both composition keys and test an oscillating producer.

2. `Composition.kt:398-421,469-524` — statement semantics are not a faithful V1 port. `bodyIntents(tryNode)` and `statementCoverage(tryNode)` walk `children()` (keywords, braces, catch clauses), and `bodyIntents`/`statementIntents` drop V1's `statements`/`statementExpression` fallbacks. The extra `statementCoverage >= scaffoldMinCoverage` gate is absent from V1 and can suppress whole-try scaffolds. Tests use only clean, fully-covered statement children, so parity is unproven. Fix: consume an explicit statement list or filter named statements, justify/remove the gate, and add partial, comment, and try fixtures.

3. `analysis-engine-v2/core/src/main/kotlin/dev/cint/core/view/RuleView.kt:55-65,108-117` — `covering`, `containing`, and `coverage` are public and expose every intent and contributor, ignoring `reads`/`optionalReads`; only `annotations(intent)` and `intentsAt` are gated. A rule can therefore read undeclared intents, bypassing the declared dependency graph. Fix: restrict these accessors to `visible` or make them internal, and route `coverage` through the visible-intent projection; add a hiding test.

4. `Composition.kt:683-692` — purity detection uses raw `annotation.contains(pattern)`, with no normalization or exact/qualified matching. Patterns such as `Pure` match unrelated substrings and miss whitespace/qualified forms that V1 matches, so function derivative promotion can fire for non-pure declarations or miss real ones. Fix: normalize annotation text and use V1's exact/qualified rules, or classify purity in the language adapter; test negative and qualified cases.

5. `analysis-engine-v2/core/src/main/kotlin/dev/cint/core/engine/RuleEngine.kt:147` — the self-fixpoint is entered when `owns` is in `optionalReads`, while V1 entered only when it was in required `reads`. This changes optional self-read rules from one evaluation to a bounded fixpoint, adding new non-convergence/non-monotone failure modes with no test or documented divergence. Fix: pin the intended semantics; for V1 parity use `owns in reads`, otherwise document and test optional self-read behavior.

6. `analysis-engine-v2/core/src/test/kotlin/dev/cint/core/engine/RuleEngineTest.kt:133-151` — tests prove upstream settling but not same-stratum isolation, required-versus-optional read gating, or candidate provenance. `CompositionTest.kt:17-156` covers only one property, one return, and one function. Fix: add two-root isolation, optional-read absence/presence, provenance-replacement, partial/derivative-function, and composition-convergence tests.

### Summary

The scheduler, strata ordering, rule-level fixpoint, annotation replacement, and established/candidate split are sound and deterministic, and the synthetic tests pin the main scheduler errors and basic fixpoint behavior. The composition terminal is the weak point: its settlement loop is unbounded, its statement model diverges from V1, and purity matching is loose. The capability view also has ungated read paths, and optional self-read semantics changed without a test or documented decision. Further synthetic coverage is needed before V1 parity can be trusted.

## Round 2

Verdict: FINDINGS

- F1 CONCUR — `Composition.kt:106-126` adds `settleComposition` with repeated-state and derived-limit detection plus `NonConvergentComposition`; `CompositionTest.kt:371-388` proves the oscillating path. Both keys use it (`:71,:77`).
- F2 CONCUR — `StatementFact`/`ValueFact` are explicit, `FactsIndex.kt:61-71` resolves them, try now uses `bodyId` (`Composition.kt:429-430`), the coverage gate is gone (`:438-445`), and comment/partial/expression tests were added (`CompositionTest.kt:117-293`).
- F3 CONCUR — `RuleView.kt:52-62` makes `covering`/`containing` private; `coverage` projects through `restrictedTo(visible)` (`:105-117`); `RuleViewTest.kt:53-59` pins hidden spans.
- F4 CONCUR — `pureAnnotationMatches` normalizes and uses exact/qualified matching (`Composition.kt:689-707`); `CompositionTest.kt:362-368` covers negatives and qualified names.
- F5 CONCUR — `RuleEngine.kt:152` again keys the fixpoint only on required reads; `RuleEngineTest.kt:97-109` pins one run.
- F6 CONCUR — same-stratum isolation (`RuleEngineTest.kt:112-128`), optional self-read (`:97-109`), candidate provenance (`:131-161`), and broader composition tests now pin the mechanics.
- F7 NEW — the neutral model is not internally complete for exits. `Composition.kt:523-534` ignores `ValueFact.rejection`/constant in `exitIntents`; V1 neutralized exactly `return false` and non-return exits (`continue`/`break`), but `ValueFact` collapses all rejection sentinels and `StatementFact` has no exit/neutrality field. So `return false` and `break`/`continue` become `UNTAGGED` and may block scaffolds. Also `StatementFact.containerId` is nullable (`SemanticFacts.kt:58-62`) while `FactsIndex.statements` (`:61-65`) matches only non-null ids, leaving null-container statements unreachable, and `ValueFact` kind/flags can contradict. Fix: add an exit/neutral flag or exact boolean-false distinction, handle non-return exits, make `containerId` non-null (or support a root path), and validate value flags; add fixtures.
- Deferral: moving environment-specific population to 072/084 is legitimate only if 072 explicitly produces `StatementFact`, `ValueFact`, and `WriteFact.valueId`/`targetName`, and 084 asserts V1 parity. Core fallbacks currently return `UNTAGGED` when these facts are absent, so an incomplete adapter silently loses scaffolds.

Summary: F1-F6 are addressed and their tests exercise the fixed paths. F7 is a residual neutral-fact/exit gap, and the population deferral is sound only with explicit 072/084 ownership. No other new regressions found.

## Round 3

Verdict: PASS (F7 CONCUR)

- F7 CONCUR — `StatementKind` plus non-null `containerId` and `ValueFact.booleanValue` are coherent and validated (`SemanticFacts.kt:49-79`). `exitIntents` now returns neutral for exact boolean `false` and for `CONTINUE`/`BREAK`, matching V1 (`Composition.kt:544-564`); `statements()` no longer has a null-container hole (`FactsIndex.kt:61-65`).
- Tests are meaningful: removing neutrality would add `UNTAGGED` and suppress the function scaffold asserted at `CompositionTest.kt:294,335`; the contradictory-value test exercises the new invariants (`:339-346`).
- Residual: the `CONTINUE`/`BREAK` branch in `exitIntents` is unreachable from current callers, and adapter population remains legitimately deferred to 072/084.

No new regressions.
