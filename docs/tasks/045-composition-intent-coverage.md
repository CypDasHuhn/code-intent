---
name: composition intent coverage and read-only semantics
status: done
depends-on: [037, 038, 040]
reviewers: [correctness, detection-fidelity]
---

## Goal

Cover intents in mixed expressions instead of losing them at composition
boundaries, add `BOOL_COMBINATORICS` and `DATA_TRANSFORMATION`, propagate
initializer intents to the declaration/assignment, and replace the
purity-for-derivative-use rule with read-only semantics.

The motivating fixture is
`analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/composition/DerivativeComposition.kt`.

## Scope

- `BOOL_COMBINATORICS`: **complex** boolean composition only, not every `if`.
  Established when the expression has multiple boolean operators, mixed
  `&&`/`||`, nested/negated compounds, `all`/`any`/`none` over predicate
  collections, or combines several distinct classified predicates. Simple
  `if (x != null)`, single comparisons, and trivial `a && b` do not qualify.
  Borderline forms are low-confidence `CandidateEmit`s (AI-eligible), not
  established tags.
- `DATA_TRANSFORMATION`: shaping an existing value/collection (`map`, `filter`,
  `flatMap`, `fold`, `reduce`, `collect`, `distinct`, `sorted`, `groupBy`, ...),
  distinct from `SEARCH` (retrieving data) and `NORMALIZATION` (canonicalizing one
  value). Overlap is allowed and should be documented.
- Declaration propagation: when an initializer has intents, the declaration /
  assignment span inherits them (as derivative use or equivalent) so
  `val uses = <SEARCH expression>` paints the assignment, not only the RHS.
- Validation coverage:
  - elvis/early-continue forms such as `val x = y ?: continue` and
    `if (uses.isEmpty()) continue` are `VALIDATION`, not neutral;
  - read-only but externally-reading guards remain `VALIDATION` with lower
    confidence;
  - a guard like `if (receiverNode(call) != null) return false` must not be
    dropped merely because purity is unknown.
- Read-only instead of pure:
  - derivative-use promotion requires read-only (no writes/mutations), not
    purity/no external reads;
  - unknown read-only lowers confidence rather than blocking;
  - known writes/mutations still block.
- Tests over a fixture matching the motivating file, plus focused
  bool/data-transformation/guard/read-only cases.
- Docs: intent definitions, overlap/precedence, and the read-only confidence
  policy.

## Acceptance

- In the motivating file, the elvis guard and `uses.isEmpty()` guard are
  `VALIDATION`; the `val uses = ...` assignment carries `SEARCH`; the
  `.map`/`.all` expressions carry `DATA_TRANSFORMATION`; the
  `isConstant || isIdentifier || ...` expression carries `BOOL_COMBINATORICS`.
- Simple conditions (`if (x != null)`, one comparison, trivial `a && b`) do not
  get `BOOL_COMBINATORICS`; borderline boolean expressions stay candidates.
- A guard with unknown read-only status is still `VALIDATION` with reduced
  confidence.
- Derivative-use promotion accepts read-only initializers that read external
  sources; writes/mutations still block promotion.
- Overlaps with `SEARCH`, `NORMALIZATION`, `VALIDATION`, and `BOOKKEEPING` are
  pinned by tests.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Whole-program dataflow, alias analysis, or a real purity engine.
- New roles or a breaking changes to the v5 emit API.
- Auto-establishing AI candidates.

## Notes

- `DerivativeComposition.isPure` currently requires constant/identifier/annotated
  pure calls. The requested policy is read-only, where external reads are fine.
- Keep confidence diagnostic: unknown read-only lowers it; it does not promote a
  candidate to established by itself.
- Deterministic pack detection first; emit candidates for ambiguous boolean or
  transformation helpers.
- `BOOL_COMBINATORICS` is deliberately selective: prefer missing a simple
  condition over painting every `if`. AI can raise borderline cases later.

## Implementation notes (in progress)

Status stays in progress until the reviewers close the Round 4 docs finding; the
code findings from Rounds 1–4 are addressed.

- Registered `bool-combinatorics:bool-combinatorics` (`BOOL_COMBINATORICS`) and
  `data-transformation:data-transformation` (`DATA_TRANSFORMATION`) in the
  bundled descriptor, host selection, migration/loading lists, and client
  defaults.
- `BOOL_COMBINATORICS` scans guard conditions, return expressions, and
  initializers; establishes on multiple/mixed operators, negated compounds,
  configured quantifiers, or two distinct classified predicates; keeps simple
  `a && b`, negation-only, prefix-collision, and nested non-quantifier forms
  untagged or candidate.
- `DATA_TRANSFORMATION` reads `SEARCH` and its own intent for chained
  transforms. Established output requires a configured qualifier, a known
  collection type, or a `SEARCH`/transformation-classified receiver; bare calls,
  unknown/unclassified receivers, and null-oracle offline receivers stay
  candidates. In-place JS mutators may overlap `LOCAL_VALUE_MODIFY`.
- Derivative local promotion is read-only rather than explicitly pure: known
  read-only `0.7`, unknown read-only `0.55`, external reads allowed, and known
  writes/mutations (`DATA_MODIFY`, `LOCAL_VALUE_MODIFY`, `BOOKKEEPING`) block.
  Property initializers and assignment RHS values propagate value intents to the
  whole declaration/assignment; lambda/argument effect intents are filtered and
  propagation/merge use the minimum confidence.
- Validation now requires a rejecting exit for the read-only path (`throw`,
  bare/sentinel `return`, `continue`, `break`), handles unbraced bodies, and
  excludes jumps inside nested loops/switches/functions. Predicate,
  `BOOKKEEPING`, and explicit-comment paths remain rejection-independent.
- Regression tests pin the motivating `testing-grounds/kotlin/Composition.kt`
  fixture, read-only/unknown guards, writes and mutation-call initializers,
  bool/data-transformation overlaps, unbraced cross-language rejection, nested
  loop/switch scoping, and transformation receiver/chain evidence.
- Docs updated: `docs/intent-model.md`, `docs/engine.md`,
  `docs/architecture.md`. The nvim README does not enumerate the default tag set.


## Outcome (2026-10-21)

Implemented `BOOL_COMBINATORICS` (complex boolean expressions only, borderline
forms candidate-only) and `DATA_TRANSFORMATION` (receiver/type/qualifier
evidence, SEARCH-aware, own-intent fixpoint). Derivative use now promotes
read-only initializers (external reads allowed; known writes/mutations block;
unknown read-only lowers confidence) and propagates initializer intents to
declaration/assignment spans. Validation now covers elvis/continue/break/throw
rejection forms, unbraced guards, and read-only guards at reduced confidence,
with nested loop/switch exits scoped correctly. Both packs are registered and
snapshot/default-tag integrated. Reviews closed after five rounds; build,
test-engine, lint, and test-nvim pass.
