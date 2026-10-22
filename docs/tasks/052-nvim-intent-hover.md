---
name: nvim intent hover
status: in-progress
depends-on: [016]
reviewers: [correctness]
---

## Goal

Add an LSP-hover-like intent inspector to the Neovim client. The client opens a
small floating window near the cursor showing the intents of the span under the
caret, each intent color-coded with its tag highlight. Like an LSP hover, the
window is non-blocking and disappears as soon as the caret moves.

## Scope

- Add a position-aware lookup over the published annotations (line and column,
  not only line).
- Render a scratch floating buffer below the cursor with one line per distinct
  intent; each intent uses the configured tag highlight color.
- Close the window on cursor movement, buffer/window leave, text change, and
  resize. Do not take focus.
- Add `:CintHover` and `<Plug>(CintHover)` for an explicit hover.
- Add a `hover` config section: `enabled` and `on_cursor_hold` (default on), so
  the hover can also appear after `CursorHold`/`CursorHoldI`.
- Document the command, config, and behavior in the renderer README and
  `doc/cint.txt`.
- Add headless tests for position lookup, color-coded content, non-blocking
  window config, close-on-cursor-move, no-intent behavior, and the command.

## Acceptance

- Hovering a span with intents shows those intents adjacent to the caret.
- Intent text uses the tag's configured highlight color; unconfigured intents
  use the fallback palette.
- Moving the caret closes the window; the window never receives focus.
- No intents under the caret produces no window.
- `:CintHover` and `<Plug>(CintHover)` work; `hover.on_cursor_hold = false`
  disables the automatic trigger.
- `just test-nvim` and `just lint` pass, plus the existing engine gate.

## Out of scope

- Engine/protocol changes or additional annotation fields.
- Async LSP-style markdown, scrolling, or pinning the hover.
- Replacing the existing intent labels/stripes.

## Notes

Reuse the published annotation store (`state.live_tags`/`state.rendered`), the
resolved profile tags, and `core.colors` groups. The hover is client-local and
must not change rendering or analysis.
