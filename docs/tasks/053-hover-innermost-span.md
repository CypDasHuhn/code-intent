---
name: hover innermost span
status: in-progress
depends-on: [052]
reviewers: [correctness]
---

## Goal

The intent hover currently unions every annotation covering the caret, so a
function/block scaffold contributes its intents together with the actual
source-intent span. Narrow the hover to the innermost annotation span under the
caret, combining intents only when spans coincide.

## Scope

- Make `intents_at_position` select the smallest annotation span covering the
  caret (byte span when available, otherwise line/column extent), then collect
  intents from annotations with that same size.
- Keep `annotations_at_position` unchanged (all covering annotations).
- Preserve annotation order when combining coincident spans.
- Document the innermost-span behavior in the renderer README and `doc/cint.txt`.
- Add tests for a nested scaffold plus source span, coincident spans, and the
  existing position edges/colors.

## Acceptance

- Hovering a source-intent span inside a larger scaffold shows only the source
  intent(s), not the scaffold’s intents.
- Coincident source/derivative spans still combine their intents.
- Hovering a region covered only by a scaffold shows the scaffold intents.
- `just test-nvim`, `just lint`, `just build` pass.

## Out of scope

- Changing published annotation spans or composition.
- Filtering by role other than choosing the innermost span.
