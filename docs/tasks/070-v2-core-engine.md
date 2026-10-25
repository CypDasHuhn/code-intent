---
name: V2 deterministic core engine
status: done
depends-on: [065]
reviewers: [correctness, architecture]
---

## Goal

Reimplement the deterministic engine on V2 contracts: scheduler, strata, fixpoint, annotation store, composition, and candidate partition.

## Scope

- Rule graph, capability-scoped view, strata, and fixpoint semantics.
- Annotation store and provenance.
- Terminal composition: coverage, derivative use, scaffolds, return category.
- Established emit versus candidate separation.

## Acceptance

- Core behavior matches the frozen V1 corpus where V2 semantics are unchanged.
- Core does not obtain facts or query knowledge.
- No language, parser, LSP, filesystem, or AI types in core.

## Out of scope

- Pack content, index, adapters, pipeline, and runtime.

## Notes

- Port semantics, not the V1 module layout.
- This is a high-risk ticket; schedule it early.
