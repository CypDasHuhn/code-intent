---
name: composition intent coverage and read-only semantics
status: todo
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
