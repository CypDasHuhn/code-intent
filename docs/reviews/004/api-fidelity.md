# API-fidelity review — ticket 004 (capability-scoped view)

## Round 1

Verdict: The frozen `api` surface is untouched and `CapabilityView` implements every
`View` member without adding detection logic; core facts and derived helpers delegate
to the existing syntax/CFG model, `annotations`/`intentsAt` gate on `reads + owns`, and
the annotation copy keeps the renderer contract (role, span, byte offsets, provenance,
reserved anchor) intact. The gating is not yet faithful in two places: `intentsAt`
computes the innermost annotation over the full store before applying visibility, so an
undeclared annotation can mask a declared one, and the plugin-facing annotation reads
pass through core-only roles (`SCAFFOLD`, `DERIVATIVE_USE`) that architecture.md §1
excludes from the plugin API.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/view/CapabilityView.kt:64-65` — `intentsAt` filters after the innermost-annotation selection, so invisible annotations can hide declared ones.**
   `context.intentsAt(node)` (`core/engine/Engine.kt:201-220`) returns only the intents
   of the smallest covering annotation(s). `CapabilityView` then removes anything not in
   `visible`. If an undeclared annotation (say `LOGGING`) covers a sub-range of the node
   and a declared annotation (say `BOOKKEEPING`) covers a larger range, the validation
   view gets the small `LOGGING` set and returns empty even though `BOOKKEEPING` covers
   the node. The result is both a loss of a declared read and a shape leak: the presence
   and size of undeclared annotations changes a rule's answer. The existing test seeds
   all contributions on one node, so it does not exercise overlap.
   Fix: perform visibility filtering before the innermost/union selection (e.g. add a
   `RuleContext.intentsAt(node, visible)` or filter the covering annotations to those
   carrying a visible intent, then choose the smallest), and add a test with a smaller
   undeclared annotation nested inside a larger declared one, asserting the declared
   intent is returned and the undeclared one is never surfaced.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/view/CapabilityView.kt:52-66` with `:68-81` — the View exposes core-only annotation roles to plugins.**
   `annotations`/`intentsAt` filter by intent only, and `restrictedTo` preserves
   `Annotation.role`. A `Role.SCAFFOLD` or `Role.DERIVATIVE_USE` annotation carrying a
   declared intent therefore reaches the rule through the plugin-facing `View` with its
   role and restricted provenance. Architecture.md §1 says those roles are produced
   exclusively by core composition and are "not part of the plugin API"; ticket 004 only
   promises intent gating, so the read-side role policy is currently unstated and
   unpinned. No test seeds a non-`SOURCE_INTENT` contribution, so this is not covered.
   Fix: decide and enforce the read policy here — filter plugin-facing annotations to
   `Role.SOURCE_INTENT` (consistent with §1), or record in the ticket/architecture that
   plugins may observe composed roles — and add a test seeding `SCAFFOLD`/`DERIVATIVE_USE`
   at a visible intent to pin the chosen behavior.

### Concurs / Dissents

- Concurs: `api` is unchanged by this diff; `View` in `api/Contract.kt:31-50` still
  matches architecture.md §5 member-for-member, and `CapabilityView` implements all
  sixteen accessors with the frozen signatures.
- Concurs: `calls`, `properties`, `functions`, `ifs`, `tries`, `catches`, `imports`,
  `references`, `throws` read the existing `SyntaxModel` facts, and `coverage`,
  `enclosingFunction`, `enclosingDeclaration`, `writes`, `isAnnotatedPure` delegate to
  the existing model/config. No new fact layer, no detector, and no rule-specific logic
  is introduced in the core view.
- Concurs: `annotation` gating for the rule's own intent plus declared `reads` works at
  annotation granularity; `restrictedTo` keeps provenance non-empty, strips undeclared
  intents from both the top-level set and each contribution, recomputes evidence/source/
  confidence, and preserves span, byte offsets and the reserved `nodeAnchor`, so the
  artifact/annotation shape consumed by the renderer is unchanged.
- Concurs: with `failOnUndeclaredRead = true`, requesting an undeclared intent throws
  rather than returning another rule's data; the defensive empty-list path returns no
  cross-rule data.
- Dissents: `intentsAt` is not capability-faithful for overlapping annotations (finding 1).
- Dissents: read-side role scoping at the plugin boundary is unspecified and unenforced
  (finding 2).

### Verified

- `git diff` contains no `api` changes; `AnalysisContext.view(owns, reads, ...)` returns
  the frozen `View` type and `CapabilityView` is a core implementation, not a competing
  contract. No second `View`/fact shape was added.
- `Rule.toContribution` and `api.Annotation` are untouched; the store still produces the
  same role/intents/evidence/span/source/confidence/byte/provenance fields, and
  `Artifact.render` is unchanged, so no artifact/annotation contract drift.
- The capability view contains only delegation and filtering; the only core-specific
  input is `IntentConfig.pureAnnotations`, which is framework config, not detection.

### Residuals (not filed as findings)

- Legacy `RuleContext` still exposes unscoped `annotations()`/`intentsAt()` to
  `RuleBlock`, so the `View` gating is enforced only for views built directly (as in the
  tests) until 003/006 wire `Rule.run` to `View`. That is the scheduler/rulepack cutover,
  not a 004 defect.
- `AnalysisContext.view` accepts caller-supplied `owns`/`reads` instead of deriving them
  from a `Rule`; 003 should construct it from the running rule and may want to narrow the
  raw factory so the invariant is not re-established by every caller.
- `coverage` deliberately remains ungated and returns intents from other rules; the
  ticket and §5 list it under always-available derived facts, and the test pins that
  behavior. It is not treated as a leak.

## Round 2

Verdict: Both round-1 findings are addressed. `intentsAt` now reads all containing
annotations, filters out core-only roles, computes the visible intent set per
annotation, and only then applies the innermost/union rule, so an undeclared smaller
annotation can no longer mask a declared larger one. `annotations` and `intentsAt` also
drop `SCAFFOLD`/`DERIVATIVE_USE`, pinning the §1 read boundary. The frozen `api` surface
and the annotation/artifact contract are unchanged. No new api-fidelity findings.

### Findings

- F1: concurs — `CapabilityView.intentsAt` (`analysis-engine/core/src/main/kotlin/dev/cint/core/view/CapabilityView.kt:69-86`) obtains `context.containingAnnotations(node)`, filters to `Role.SOURCE_INTENT`, maps each annotation through `visibleIntents`, and then selects the smallest among the visible ones. The added `intentsAtSelectsVisibleAnnotationBeforeInnermost` test (`analysis-engine/treesitter/src/test/kotlin/dev/cint/treesitter/CapabilityViewTest.kt:181-194`) nests a smaller `LOGGING` annotation inside a larger `BOOKKEEPING` one and asserts the declared intent is returned with no `LOGGING` leak, which is the round-1 repro.
- F2: concurs — `annotations` filters `Role.SOURCE_INTENT` before gating (`CapabilityView.kt:54-67`), and `intentsAt` filters source roles before computing visibility (`:69-71`), so composed `SCAFFOLD`/`DERIVATIVE_USE` annotations no longer reach the plugin-facing `View`. The added `pluginFacingReadsIgnoreCoreOnlyRoles` test (`CapabilityViewTest.kt:211-229`) seeds both core-only roles at a visible intent and asserts `annotations` and `intentsAt` (loose and strict) return empty.
- New findings: none.

### Concurs / Dissents

- Concurs: `AnalysisContext.containingAnnotations` (`analysis-engine/core/src/main/kotlin/dev/cint/core/engine/Engine.kt:202-208`) reuses the existing interval index, so the fix adds no second fact layer and does not alter stored annotations.
- Concurs: `restrictedTo` now rebuilds `intents` from the surviving provenance (`CapabilityView.kt:98-107`), so a hand-built annotation advertising an intent no contributor supports cannot surface it; the new core test (`analysis-engine/core/src/test/kotlin/dev/cint/core/view/CapabilityViewTest.kt:58-94`) pins that, and the annotation/artifact shape is unchanged.
- Concurs: strict mode is now coherent for both gated methods: `annotations` still throws for an undeclared requested intent, and `intentsAt` throws only when source coverage exists but none of it is visible (`CapabilityView.kt:76-80`); core-only coverage stays invisible rather than failing.
- Dissents: none.

### Verified

- `git diff` still contains no `api` changes; `View`, `Rule`, `Emit`, `Annotation`,
  `Contribution` and `Artifact` are untouched, and `CapabilityView` remains a core
  implementation of the frozen interface with no competing shapes.
- The view still contains only delegation and filtering; no detector, fact builder or
  rule-specific logic moved into core.
- The legacy `RuleContext.annotations()`/`intentsAt()` path is still unscoped, so the
  residuals from round 1 about the 003/006 wiring and caller-supplied `owns`/`reads`
  remain true.

### Residuals (not filed as findings)

- `containingAnnotations` was added to the rule-facing `RuleContext` interface
  (`Engine.kt:52`) but is only used by `CapabilityView`. When 003 replaces the legacy
  `RuleBlock` path, consider making it an internal/helper method rather than widening
  the context every legacy rule receives; it adds no information beyond the already
  unscoped `annotations()`.
- The source-only read policy is enforced in `CapabilityView` and covered by a test but
  is not recorded in the ticket or architecture. 010/016 should treat that as the
  intended plugin read boundary when core composition starts writing non-source roles.
