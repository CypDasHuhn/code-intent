---
name: capability-scoped view
status: done
depends-on: [001]
reviewers: [correctness, api-fidelity]
---

## Goal

Implement the `View` (architecture.md §5) over the existing syntax/CFG facts, and
enforce `reads`: `annotations(intent)` and `intentsAt(node)` surface only the
intent a rule owns plus the intents it declared, so `reads` is enforceable rather
than decorative.

## Scope

- Core facts, always available: `calls`, `properties`, `functions`, `ifs`,
  `tries`, `catches`, `imports`, `references`, `throws`.
- Derived, always available: `coverage`, `enclosingFunction`,
  `enclosingDeclaration`, `writes`, `isAnnotatedPure`.
- Gated by declared `reads`: `annotations(intent)`, `intentsAt(node)` — only the
  rule's own intent plus declared intents; an undeclared read yields empty
  (defensive) or fails loudly in dev; it never leaks another rule's data.
- Back the facts with the existing `SyntaxModel`/CFG rather than inventing a
  second fact layer.

## Acceptance

- Every fact accessor is implemented and covered by tests over the existing
  fixtures.
- A rule reading an undeclared intent gets empty/`devAssert` failure, never
  another rule's data; the rule's own owned intent is always visible.
- No detection logic in the `core` view implementation.
- `just test-engine` green.

## Out of scope

- The DSL (005), the scheduler (003).

## Notes

- If the fact shapes must move onto the frozen `api` surface, do it here and
  record the decision.

## Outcome (2026-10-20)

`CapabilityView` implements the frozen `View` over existing syntax/CFG facts.
Annotation reads are capability-gated to the rule's owned intent plus declared
`reads`; containment is filtered visibility-first so undeclared nested spans
cannot mask declared intents; restricted annotations rebuild intents from
surviving provenance; plugin-facing reads expose only `SOURCE_INTENT` roles per
architecture §1. Round-1 findings from both reviewers fixed and round 2 concurs.
`just build` and `just test-engine` green.
