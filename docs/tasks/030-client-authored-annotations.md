---
name: client-authored annotations
status: done
depends-on: []
reviewers: [correctness, api-fidelity]
---

## Goal

Let the Neovim client add user-authored annotations at runtime and have the
engine treat them as durable source facts, for example “this symbol is pure” or
“this span is logging”.

This is an exploratory feature; prototype it on an experimental branch before
merging.

## Scope

- Client commands/keymaps to annotate the current symbol or visual range with an
  open intent name, remove an annotation, and list annotations.
- Per-project persistence under `.cint/` with stable identity (path + symbol /
  content hash or range fallback), created timestamp, intent(s), optional note.
- Host/server loading of the persisted file; user annotations participate in
  analysis as source contributions with a user-specific contributor id, without
  violating the rulepack 1:1 ownership rule (decide: separate user layer vs
  pseudo-pack).
- Pure annotations feed `isAnnotatedPure` / composition.
- Live refresh strategy: file watch, daemon restart, or explicit client reload.
- Renderer visual distinction for user-authored annotations.
- Round-trip, persistence, stale-anchor, merge and delete tests.

## Acceptance

- Annotating a symbol in nvim survives editor restart and appears in the next
  analysis/render.
- Removing it removes the contribution.
- Pure annotations promote derivative use as expected.
- Unknown user intent names resolve through the client default policy.
- Experimental branch builds and tests; no main-queue behavior regresses.

## Out of scope

- Candidate/established occurrence persistence and a full annotation UI.
- Cross-machine sync.
- Replacing rulepack detection.

## Notes

- Start on `experiment/client-authored-annotations`; decide the stable-anchor
  format before merging.
- Keep the design compatible with the reserved `nodeAnchor` in the API.
