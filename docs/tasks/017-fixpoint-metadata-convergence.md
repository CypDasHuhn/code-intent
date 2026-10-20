---
name: fixpoint metadata convergence
status: blocked
depends-on: [003]
reviewers: [correctness]
---

## Goal

Define a bounded convergence rule for self-reading rules whose annotation
identity (`role`, byte span, intents) is stable but whose metadata (`source`,
`confidence`) keeps changing. Ticket 003 bounds the identity lattice by the
inclusive byte-interval universe; metadata is not currently bounded by the
contract, so an arbitrary metadata-only sequence can exhaust the derived safety
cap before producing a later identity. Annotation `evidence` was removed in
ticket 032 and no longer participates in fixpoint convergence.

## Scope

- Decide whether `source`/`confidence` participate in convergence, or whether
  metadata-only changes are defined as non-convergent.
- Derive a bound from the chosen finite domain, or make the safety limit
  explicitly configured and document the non-termination tradeoff.
- Add regression tests for metadata changing across several iterations before a
  new identity, and for metadata that never stabilizes.

## Acceptance

- The convergence domain is explicit and finite (or the absence of a finite
  bound is documented with a configured safety valve).
- A rule that changes metadata and then emits a later identity is not truncated.
- A rule whose metadata never stabilizes fails with a named error.
- `just test-engine` green.

## Out of scope

- Composition behavior (010), pack loading (011), scheduler stratification (003).

## Notes

- Blocked: derived from ticket 003 review finding F7. Evidence no longer
  participates in convergence (ticket 032); the open question is whether
  `source`/`confidence` are part of convergence state.

## Update (2026-10-20)

Ticket 032 removed `evidence` from the contract and from fixpoint stability, so
only `source` and `confidence` remain in the metadata-convergence question.
