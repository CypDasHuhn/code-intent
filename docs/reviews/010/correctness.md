# Correctness review — ticket 010 (core composition terminal)

## Round 1

Verdict: Composition is wired correctly as the graph terminal: it runs after all
strata, receives only the settled source contributions plus `TerminalNode`, and
reproduces the legacy scaffold and derivative logic without naming a producer.
The fixture parity and pack-order tests are green, return categories come from
the CFG, and every composed annotation is core-owned `SCAFFOLD`/`DERIVATIVE_USE`.
One behavioural gap remains against the ticket's scaffold contract: because
return categorization is materialised as a `SCAFFOLD` annotation before the
scaffold settlement, every non-validation return presents `UNTAGGED` to
`exitIntents`, so bare returns are never neutral and `return expr` never inherits
the expression's intents. Test coverage also leaves the other half of the return
contract and pack removal unexercised.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:100-105,131-144,447-462` — the synthetic return-category annotation makes the ticket's return scaffold semantics unreachable.**
   `returnContributions` emits a `SCAFFOLD` `UNTAGGED` annotation at every
   non-validation return (`:137-143`), and it is stored before the scaffold
   settlement (`:100-107`). `exitIntents` then sees exact coverage
   `{UNTAGGED}` and returns it at `:454-455`, so the
   `exitExpression`/`intentsAt` fallback at `:458-462` is dead for all returns.
   Consequently a function such as
   `fun f(x: String?) { if (x != null) use(x); return }` treats the bare return
   as `UNTAGGED` (blocking a function scaffold) instead of neutral, and
   `return log.warn(...)` / `return requireNotNull(...)` never carries
   `LOGGING`/`ERROR_HANDLING` into an enclosing scaffold. That contradicts ticket
   010's scaffold bullets ("bare `return`/`continue`/`break` neutral; `return expr`
   carries the expression's intents"). The fixture parity does not catch it
   because no fixture has an otherwise-covered body ending in a bare return or a
   classified return expression.
   Fix: make `exitIntents` distinguish the synthetic return category from source
   evidence — keep `VALIDATION` for real validation returns, but for untagged
   returns fall through to the bare-return-neutral or `return expr` expression
   path — or record an explicit, approved divergence from the ticket and pin it
   with a test.

2. **`analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/CompositionTerminalTest.kt:395-442` — the return contract is only half pinned.**
   The test covers a validation return on the true edge and a non-rejection
   value on the false edge, but not the explicitly required false-edge rejection
   sentinel (`null`, `false`, `0`, bare return) and not the unguarded final
   return. A regression in `isRejection` or in the "everything else is
   `UNTAGGED`" branch would leave the suite green.
   Fix: add `if (!valid) return null` / bare `return` cases on the false edge,
   plus an unguarded final `return value`, asserting both the category intents
   and the effect on any enclosing scaffold.

3. **`analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/CompositionTerminalTest.kt:236-311` — pack independence only tests addition.**
   Acceptance says adding *or removing* a pack may change composition only by
   its source intents. The test adds pack B to A and compares against the
   distinct union, but never removes a pack and never loads a pack that
   contributes no emissions. Those cases also pin the
   `terminal.sourceIntents` filter at `Composition.kt:88-92` and the
   no-composition-source path.
   Fix: run `both`, then `both - B`, and compare the latter with the original
   A result; add a third pack that owns an intent but emits nothing and assert
   composition is unchanged.

4. **`analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/CompositionTerminalTest.kt:146-209,313-391` — derivative behaviour beyond simple property promotion is not tested in the new pipeline.**
   `PurityAssist.kt` exercises literal and annotated-pure property promotion only.
   Function promotion, impure-local rejection, transitive declaration→declaration
   promotion, and smallest-containing-annotation resolution were copied into
   `Composition.kt` but have no new-pipeline test; a copy/paste regression in the
   function loop or `derivativePlaceIntents` would still pass parity.
   Fix: add targeted new-pipeline tests for each of those cases (or extend the
   parity fixtures so the legacy oracle exercises them).

### Concurs / Dissents

- Concurs: composition is invoked after the strata and receives only
  `plan.terminal` plus the settled source contributions
  (`RuleEngine.kt:88-107`); it does not receive or branch on rule/pack identity.
- Concurs: the source filter keeps only `Role.SOURCE_INTENT` contributions whose
  intent is in `TerminalNode.sourceIntents` (`Composition.kt:88-92`), and
  `TerminalNode` names no rule or pack.
- Concurs: all composed contributions use `Role.SCAFFOLD` or
  `Role.DERIVATIVE_USE`; `Rule.toContribution` hard-codes `SOURCE_INTENT`, so a
  plugin cannot produce or remove composition roles, and the plugin-id collision
  test confirms the role boundary.
- Concurs: the if/else split, catch span, whole-try vs try-header, function
  scaffold, and derivative code match the legacy blocks, and the fixture
  snapshots (including cross-language) plus pack split/order tests pass.
- Concurs: return categorization is derived from `ControlFlow.returns` and
  validation source intents, and an empty pack set still classifies returns
  without producing scaffolds or derivatives.
- Dissents: the ticket's neutral bare-return and expression-carrying return
  scaffold rules are not implemented (finding 1).
- Does not re-report the architecture reviewer's contributor-id namespace and
  return-provenance-identity findings.

## Round 2

Verdict: All four round-1 findings are addressed, and the architecture findings
are addressed as well. `exitIntents` now treats an untagged bare return as
neutral and falls back to the return expression, so the ticket's return-scaffold
semantics are reachable; the new tests pin bare returns, return expressions,
false-edge rejection sentinels, pack removal, no-op packs, and the derivative
cases that were previously unexercised. The scheduler reserves the `core:`
contributor prefix with a named `RESERVED_CONTRIBUTOR_ID` error, and return
categorization now uses the stable `core:return-category` contributor with only
the intent set varying. No new correctness defects were found.

### Findings

- None. All round-1 findings (F1-F4) are fixed and verified by the added tests.
  The stable contributor and namespace fixes also resolve the architecture
  reviewer's F1/F2 without weakening the terminal contract.

### Concurs / Dissents

- Concurs: F1 — `analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:446-468` returns
  `VALIDATION` only for a real category intent, treats a bare return as
  `emptySet()` (neutral), and uses `intentsAt(expression)` for return
  expressions; `returnScaffoldsTreatBareReturnsAsNeutralAndCarryExpressionIntents`
  exercises both paths on the new pipeline.
- Concurs: F2 — `returnCategoryCoversRejectionSentinelsOnFalseEdgesAndFinalReturns`
  covers `return null`, bare `return`, `return value`, and the final-return
  no-scaffold case, in addition to the original true-edge/false-value test.
- Concurs: F3 — `addingAPackOnlyAddsItsSourceIntentsToComposition` now removes
  pack B again and loads a no-op pack, asserting composition is unchanged except
  for B's source intents.
- Concurs: F4 — the new derivative tests exercise annotated-pure function
  promotion, transitive declaration promotion, impure-local rejection, the
  function-reference containment rule, and smallest-containing-annotation
  resolution.
- Concurs: `RuleScheduler` rejects plugin rule ids using `CoreContributors.PREFIX`
  with `ReservedContributorId`/`SchedulerError.RESERVED_CONTRIBUTOR_ID`
  (`RuleScheduler.kt:105-115`), and `Composition` emits the stable
  `core:return-category` contributor (`CoreContributors.kt`,
  `Composition.kt:30,131-143`); the tests assert the reserved error and the
  core-only contributor set.
- Concurs: composition still runs as the terminal after all strata, consumes
  only source-intent contributions, emits only `SCAFFOLD`/`DERIVATIVE_USE`, and
  the fixture parity, cross-language parity, pack-order/split tests remain green.
- Residual (not a finding): a return categorized as `VALIDATION` still returns
  only the category intents even when its expression also carries source
  intents; the ticket's explicit return-expression rule is now exercised for
  untagged returns. If validation returns are also expected to union their
  expression intents, that should be pinned in 009/017 rather than left
  implicit.
