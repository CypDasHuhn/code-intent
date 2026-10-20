---
name: renderer annotation contract
status: done
depends-on: [002, 011]
reviewers: [correctness, api-fidelity]
---

## Goal

Update the live annotation contract the renderer consumes so it carries
structured provenance and tolerates open intent names (architecture.md §2, §6).

## Scope

- Per-contributor provenance in the published payload (drop the `+`-joined rule
  string) while preserving "why did this fire".
- The client resolves unknown intent names through its existing resolve/default
  policy, so a pack can introduce new names without a client change.
- Reserve (do not yet use) the node anchor in the live contract (`§10`).
- Roles stay core-only at the plugin boundary: the renderer never sees
  plugin-produced `SCAFFOLD`/`DERIVATIVE_USE`.

## Acceptance

- The renderer paints from the new payload; an unknown intent name resolves via
  the default policy instead of erroring.
- Structured provenance is available to the client.
- `just test-nvim` green.
- Any live-socket verification is recorded in `manual-test.md`.

## Out of scope

- Occurrence persistence and candidate UX (`§10`).

## Notes

- Lua side: `nvim-renderer/lua/cint/`; the emitted field shape changes in 002.

## Outcome (2026-10-20)

The live payload and artifact carry per-contributor `provenance` (no joined rule
string), an optional `nodeAnchor` (null until occurrence persistence), and open
intent names. The nvim renderer paints source/derived/scaffold roles from
role+intents, resolves unknown intents through `resolve.default`, normalizes
JSON-null anchors, and exposes `annotations_at`/`provenance_at` with half-open
line semantics and buffer-aware cursor selection. Wire-decoded tests pin nullable
anchors and provenance. `just test-nvim` passes; live-socket verification is
m005 (unverified).
