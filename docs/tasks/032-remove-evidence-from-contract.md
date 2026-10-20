---
name: remove evidence from annotation contract
status: done
depends-on: [019, 025]
reviewers: [correctness, api-fidelity]
---

## Goal

Remove `evidence` from the annotation contract, provenance, artifact/live
payloads, renderer payload, and tests. Evidence is currently write-only
diagnostic metadata and is not used for detection or rendering.

## Scope

- Remove `evidence` from `Emit`, `Contribution`, `Annotation`, emit builders,
  store aggregation/order, composition, rulepacks, JSON serializers, and
  renderer test payloads.
- Tests should identify spans by source text via byte offsets, not evidence.
- Update snapshots to the new shape.
- Update current docs/contract notes. Historical review files may remain
  historical.
- Bump `CINT_API_VERSION` to 4 and pin the breaking removal.
- Update ticket 017: evidence no longer participates in fixpoint convergence;
  confidence/source remain the open metadata question.

## Acceptance

- No production/test/serialization reference to annotation evidence remains.
- Artifact/live payload has no `evidence` field at annotation or provenance
  level.
- `just build`, `just test-engine`, `just lint`, `just test-nvim` pass.
- Size/dir-size remain clean.

## Outcome (2026-10-20)

`evidence` is removed from `Emit`, `Contribution`, `Annotation`, emit builders,
store aggregation/order, composition, rulepacks, artifact/live JSON, renderer
fixtures, and current docs. API is v4 with a strict v3 rejection test. Tests
identify spans via a shared UTF-8 byte-safe `Annotation.text(source)` helper.
Historical pre-018 snapshots retain their original evidence internally and are
stripped only for comparison; `pipeline-current.json` is v4-shaped. Reviewers
concur after two rounds. `just build`, `just test-engine`, `just lint`,
`just test-nvim` pass.
