---
name: annotation store and provenance
status: done
depends-on: [001]
reviewers: [correctness]
---

## Goal

Implement the annotation store: rules are pure `(View) -> List<Emit>` and never
mutate it; the framework replaces each rule's entire previous contribution every
iteration; provenance is structured and per-contributor (architecture.md §6).

## Scope

- Merge/replacement semantics: a span may carry multiple intents — the union of
  contributions from different rules, plus further union from core composition.
- Per-rule contribution replacement: each iteration the framework swaps a rule's
  *whole* previous output for its new output. The scaffold case that emits one
  span when `then == else` and two ranges when they differ simply returns its
  current answer; there is no `ctx.remove(...)`.
- Structured provenance: keep the per-contributor entries (rule, intents,
  evidence, source tier, confidence, span) so the client can answer "why did this
  fire" with the full chain; drop the concatenated `rule` string.
- Monotone: no removal, hiding, or reclassification API exists.
- Deterministic ordering so identical inputs produce identical output.

## Acceptance

- Two rules contributing to one span yield the union and both provenance entries.
- A rule that changes its output shape between iterations leaves no stale
  fragments.
- No removal API; a test asserts one rule cannot shrink another's contribution.
- Provenance round-trips per contributor; the old `+`-joined rule field is gone
  from the emitted shape.
- `just test-engine` green.

## Out of scope

- Stratification (003), capability gating (004), composition (010).

## Notes

- The store keys contributions by rule id (`§3`).
- This is why the current `ctx.remove(...)` calls disappear (`§6`).

## Outcome (2026-10-20)

Store implemented as `core/store/AnnotationStore.kt`, keyed per configured rule
instance; whole-output replacement, union of intents across contributors,
structured provenance, deterministic ordering, and no removal API. Rules now
receive a read-only `RuleContext`; store mutation and dirty tracking stay in
`AnalysisEngine`. Round-1 findings (rule-facing mutation surface, non-unique
replacement key) fixed and round 2 concurs. The legacy `validation-guard-block`
scaffold emission was removed; final scaffold behavior is owned by 010 and
renderer confirmation by 016. `just build` and `just test-engine` green.
