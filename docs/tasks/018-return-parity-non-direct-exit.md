---
name: return parity for non-direct-exit guards
status: done
depends-on: [009, 010]
reviewers: [correctness, detection-fidelity]
---

## Goal

Restore legacy `ReturnBlock` parity for nested/else-edge validation returns when
the guard has no direct then-exit and therefore no `ValidationBlock` source span.
Ticket 009 approved a narrower behavior: return categorization requires an emitted
VALIDATION annotation (so no-op packs and missing packs cannot alter composition),
and only guards tagged by the validation rule certify returns.

## Scope

- Define a core-owned validation-guard certificate that does not fabricate plugin
  source spans and does not depend on pack presence or pack emissions.
- Cover nested-loop returns, else-edge rejection returns, and lambda/local-function
  boundaries.
- Preserve the protections from 009: no nested-function/lambda fabrication, no
  empty-intent scaffolds, pack independence, and bare return/`false` neutrality.

## Acceptance

- Return categories match legacy `ReturnBlock` for the affected shapes.
- Source `ValidationBlock` tags remain unchanged.
- No plugin source span is invented for return-only certification.
- `just test-engine` green.

## Out of scope

- Composition scaffolding (010), validation detection parity (009).

## Notes

- Blocked: needs a design decision on where the guard certificate lives (core
  composition vs a pack-neutral derived fact) and how it is bounded without
  re-coupling composition to pack identity.

## Outcome (2026-10-20)

Resolved by a generic return certificate instead of validation-specific core
logic. `Emit`/`Contribution` gained an additive `certifiesReturn` flag; the
validation pack sets it on its guard annotations and now emits them for every
qualifying guard, including nested/else-edge returns. Core `ReturnComposition`
uses only certified annotations and inherits their intents; `VALIDATION` and its
config are gone from core, as are `HostConfig`'s validation intent mapping and
the unused validation intent-params compatibility helpers. Validation tags widen
to non-direct-exit guards, which is the intentional tradeoff for a pack-driven
certificate. Rulepack detection tests moved to `:rulepacks-tests`; per-pack
packages were normalized to logging/errorhandling/bookkeeping/validation/support.
`just build`, `just test-engine`, and `just lint` pass.
