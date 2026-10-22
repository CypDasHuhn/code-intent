---
name: user readonly directive
status: done
depends-on: [030]
reviewers: [correctness, api-fidelity]
---

## Goal

Add a second built-in user directive, `READONLY`, alongside `PURE`. A call to a
function annotated `READONLY` does not write state and may be treated as a
side-effect-free read by derivative-use and validation composition, while it is
not asserted to be deterministic the way `PURE` is.

## Scope

- Accept `READONLY` as an open user annotation intent and keep it in the
  published intent set and provenance like `PURE`.
- Resolve symbol/range records carrying `READONLY` through the existing user
  annotation path.
- Treat calls to `READONLY` functions as side-effect-free reads in
  `CapabilityView` and derivative read-only confidence.
- Keep the PURE-only annotated pure-function derivative path unchanged; user
  `PURE` calls are also explicitly side-effect-free for validation composition.
- Complete `READONLY` in the renderer annotate command and document the
  directive in `intent-model.md` and the renderer README.
- Cover persistence, merge, derivative promotion, and the non-pure distinction.

## Acceptance

- `READONLY` persists through the client and host round trip, is visible in the
  annotation intent set and provenance, and is removed with the record.
- A call to a `READONLY` function promotes derivative use and qualifies as a
  side-effect-free read for validation composition.
- `READONLY` does not mark a function as annotated pure or otherwise claim
  determinism. `PURE`'s pure-function derivative path is unchanged, while its
  side-effect-free validation status is explicit and pinned.
- Renderer completion offers `PURE` and `READONLY`, and the shipped default
  policy still marks user annotations.
- `just build`, `just test-engine`, `just lint`, and `just test-nvim` pass.

## Out of scope

- A dedicated directive field in the persisted or published contract.
- Propagating directives to receivers, members, or qualified calls.
- New pack-owned intents or scheduler/API version changes.
- Automatic AI-applied directives.

## Notes

- The working tree already carried the first slice of this change; this ticket
  records the unit of work and pins its contract.
- The directive names stay open (not reserved), matching the ticket 030
  resolution that user intent names are not a new pack namespace.
- Manual editor/daemon restart coverage remains under m009/m010; automated
  round-trip coverage is expected here.
- User `PURE` and `READONLY` calls both use the side-effect-free validation
  confidence. Treating user `PURE` calls as side-effect-free is a deliberate
  clarification, not a change to the PURE-only derivative path. `READONLY`
  never asserts determinism.
