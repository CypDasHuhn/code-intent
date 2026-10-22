---
name: hover omit untagged
status: done
depends-on: [052, 053]
reviewers: [correctness]
---

## Goal

The intent hover should never show the reserved `UNTAGGED` marker. It is an
engine placeholder, not a user-facing intent.

## Scope

- Filter `UNTAGGED` from `intents_at_position` (the hover's source of truth).
- Keep `annotations_at_position` and provenance APIs unchanged.
- Add tests for an untagged-only span (no hover) and a mixed
  `UNTAGGED`+intent span (only the real intent).
- Document that `UNTAGGED` is omitted from the hover.

## Acceptance

- An untagged-only span shows no hover window.
- A mixed span shows only its real intents.
- `just test-nvim`, `just lint`, and `just build` pass.
