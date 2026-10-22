# Correctness review — ticket 052 (nvim intent hover)

## Round 1

Verdict: The range lookup, color resolution, focusability, no-intent path, and
autocmd deduplication are sound, and the new tests are non-vacuous for those
paths. Two behavior bugs remain in the hover's window/config handling: a
non-current-buffer hover samples one window's caret but anchors the float to the
current window's caret, and automatic-hover gating reads `base_config` while the
open path reads the resolved profile config. The tests do not cover either path.

### Findings

1. **`nvim-renderer/lua/cint/hover.lua:94-105` and `:50-61` — `cint.hover(buf)` samples `buf`'s window cursor but anchors the float to the current window/cursor.**

   For a non-current `buf`, `M.hover` correctly reads `line, col` from
   `win_findbuf(buf)[1]`, but `open_window` always passes
   `relative = "cursor"`, so the float is placed at the current window's caret,
   not the queried window's caret. A caller that invokes `cint.hover(other_buf)`
   while another buffer is current gets intents for `other_buf`'s cursor
   position displayed next to the wrong caret (and potentially in the wrong
   tab/window). This contradicts the help text at `doc/cint.txt:331-334`, which
   advertises "`buf`'s first showing window". `:CintHover` only uses the current
   buffer, so the command path is fine, but the API path is not.

   Suggested fix: keep the target window returned by `win_findbuf` (or the
   current window when `buf` is current) and pass it into `open_window`; for a
   non-current target use `relative = "win", win = target_win`,
   `row = cursor[1]` (0-based row below the caret), `col = cursor[2]`, and
   `relative = "cursor"` only for the current window. Alternatively, drop the
   `buf` parameter/claim and always use the current buffer and cursor. Add a
   two-window/two-cursor test that would fail today.

2. **`nvim-renderer/lua/cint/hover.lua:146-155` versus `:89-91` — the automatic trigger uses the base config, while the open path uses the resolved profile config.**

   `setup_autocmds` decides whether to register `CursorHold`/`CursorHoldI` from
   `config.base_config.hover`, and the callback re-checks `config.base_config`
   only. `M.hover` instead resolves `config.config_for(buf)` and honors that
   `hover.enabled`. Consequently a profile that sets
   `hover = { on_cursor_hold = false }` still opens the hover on `CursorHold`
   because the callback's base config says `on_cursor_hold = true` and `M.hover`
   never checks `on_cursor_hold`; conversely a profile cannot enable the trigger
   when the base config has it off. A profile-level `hover.enabled = false` only
   makes the callback's `M.hover` call return false after the trigger has
   already fired. The two halves of the feature therefore disagree about whether
   hover settings are profile-aware.

   Suggested fix: pick one contract and apply it consistently. For
   profile-aware settings, register the `CursorHold`/`CursorHoldI` autocmd
   unconditionally (or whenever any level can enable it), resolve
   `config.config_for(vim.api.nvim_get_current_buf())` inside the callback, and
   gate on both `enabled` and `on_cursor_hold` there. For a client-global
   contract, use `config.base_config.hover` for `M.hover`'s enable gate as well
   (while continuing to resolve `tags` per buffer) and document that these two
   keys are not profile-scoped. Add a test with a profile overriding
   `on_cursor_hold`.

3. **`nvim-renderer/tests/hover_spec.lua:83-111,153-179` — the tests leave the advertised API and several documented close triggers unpinned, so findings 1 and 2 pass green.**

   The spec exercises only the current-buffer command, the `CursorMoved` close,
   and setup-level hover gates. It never calls `cint.hover(buf)` for a
   non-current buffer, never resolves a profile-level `hover` setting, never
   verifies `<Plug>(CintHover)` exists or invokes it (an explicit acceptance
   criterion), and never exercises the documented `BufLeave`/`WinLeave`/
   `TextChanged`/`TextChangedI`/`VimResized` close paths. The assertions that do
   exist are meaningful for position edges, distinct-intent order, colors,
   no-intent behavior, and window flags; the `width <= max_width` and
   `height <= max_height` checks are effectively unconditional for the 3-line
   fixture, so the caps themselves are not actually tested.

   Suggested fix: add a non-current-window hover test (assert the target window
   or caret anchor, not just the content), a profile-level `on_cursor_hold`
   test, a `<Plug>` mapping existence/invocation check, and at least one
   non-cursor close event. Keep the existing range and color assertions; they
   are not vacuous.

### Verified

- Position matching is half-open at the end on both same-line and multi-line
  spans, inclusive at the start, and treats a zero-width same-line span as a
  point at `(startLine, startCol)`; the new tests pin all of those edges.
- `intents_at_position` deduplicates in annotation order, and the hover uses
  published (`live_tags`/`rendered`) annotations only.
- Intents are colored from resolved `tags` with `bg`/`color` for background and
  `sign` for foreground, falling back per field to `core.colors.fallback`;
  configured, partially configured, and unknown intents are all covered.
- The scratch buffer is non-focusable, non-wrapping, cursorline-free, and
  `noautocmd`; the float does not take focus. `close` clears module state first,
  tolerates an already-closed window/buffer, and deletes the scratch buffer, so
  double-close and externally closed windows are safe (a stale `active` is
  harmless because `active()` checks window validity).
- `setup_autocmds` uses `augroup(..., { clear = true })`, and both the plugin
  registration and `setup.run` call it, so repeated `cint.setup` calls do not
  accumulate close/hold autocmds. `setup.run` also closes an existing hover.
- `hover.enabled = false` makes the command/mapping no-ops and suppresses the
  automatic trigger; `hover.on_cursor_hold = false` leaves the explicit command
  working. No source, build, test, or formatter command was run by this role.

### Residual observations (not filed)

- `open_window` caps height at `M.max_height`, but the scratch buffer keeps every
  line; with more distinct intents than the cap the extra lines are not visible
  and the window is non-focusable, so there is no keyboard scrolling or
  overflow indicator. The same applies to names wider than `M.max_width`. A
  truncation marker (or scrollable height) would make the cap explicit.
- `hover.enabled = false` leaves `:CintHover` and `<Plug>(CintHover)` registered
  as no-ops; the docs' "disables both" is accurate behaviorally but not about
  the command's existence.
- The spec injects annotations directly into `_live_tags` (bypassing the JSON
  transport). The numeric span fields and intents are unaffected, so this does
  not weaken the position lookup, but it does not exercise the live decode path.

### Summary

The core hover behavior is correct: the position matcher respects start/end
column edges, multi-line spans, zero-width points, and overlap ordering; the
window is genuinely non-focusable and anchored to the caret for the common
current-buffer command; tag/fallback colors resolve as documented; and close
cleanup plus augroup clearing avoid duplication and double-close errors. The two
real defects are at the boundaries the tests do not touch: `cint.hover(buf)` for
a non-current buffer anchors the float to the current caret while sampling the
target buffer's caret, and the `CursorHold` trigger is gated by `base_config`
while the open path resolves the profile config, so profile-level
`on_cursor_hold` is silently ignored. Those should be fixed (or the non-current
API/global-config contract narrowed and documented), and the test coverage
extended to pin the non-current window, resolved-profile gate, `<Plug>` mapping,
and the remaining documented close events.

Concurs / Dissents: **Dissent** on findings 1 and 2 (the non-current anchoring
and base/resolved config split), and on finding 3 because the missing tests let
both bugs pass; I concur that the position lookup, color resolution,
focusability, no-intent behavior, and autocmd lifecycle are otherwise correct.

## Round 2

Verdict: The round-1 findings are addressed. Non-current hover now resolves a
current-tab target window and uses `relative = "win"` to that window instead of
the current cursor; the `CursorHold`/`CursorHoldI` autocmd is registered
unconditionally and both `enabled` and `on_cursor_hold` are resolved through
`config.config_for(current_buf)`; tests now cover a two-window non-current
hover, profile-level `on_cursor_hold`/`enabled` overrides, `<Plug>(CintHover)`
existence/invocation, and `VimResized`/`BufLeave` close; and the docs describe
the current-tab window and profile-scoped hover settings. One genuinely new
anchoring defect remains in the non-current path.

### Findings

1. **`nvim-renderer/lua/cint/hover.lua:76-80` — the non-current float passes buffer coordinates where `relative = "win"` expects screen coordinates, so it is still not anchored to the target caret under scrolling, wrapping, tabs, or horizontal scroll.**

   For the non-current branch, `open_window` uses
   `relative = "win", win = target, row = cursor[1], col = cursor[2]`.
   `nvim_open_win` defines `row`/`col` for a win-relative float in screen-cell
   units relative to the target window, while `cursor[1]` is a 1-based *buffer
   line* and `cursor[2]` is a *byte column*. The new test happens to use an
   unscrolled, unwrapped, tab-free ASCII target at line 2, where the buffer line
   and byte column coincide with the screen row/column, so it passes. If the
   target window is scrolled (`topline > 1`), has wrapped lines above the caret,
   or the caret is after tabs/wide characters or horizontally scrolled, the
   float is placed at the wrong screen position (potentially far below the
   viewport), so the advertised "next to the caret" behavior is still not
   correct for `cint.hover(buf)`.

   Suggested fix: express the anchor in buffer-relative text coordinates with
   `bufpos = { line, col }` (and `relative = "win", win = target`, with the
   usual `row = 1, col = 0` offset), or compute the caret's screen position with
   `vim.fn.screenpos(target, cursor[1], cursor[2])` and convert it to
   window-relative screen cells before opening the float. Add a test with a
   scrolled or wrapped target window (or a tab before the caret) that asserts
   the float row/col track the caret, not the buffer indices.

### Finding status

- Round-1 finding 1 (wrong window/cursor for `cint.hover(buf)`): resolved—the
  target window is now selected and used; the remaining issue is the coordinate
  system within that window, filed above.
- Round-1 finding 2 (base vs resolved hover gating): resolved—the trigger is
  unconditional and the callback resolves both keys from the current buffer's
  config; profile tests now pin this.
- Round-1 finding 3 (test gaps): resolved—the non-current window, profile
  overrides, `<Plug>` mapping, and resize/leave close paths are covered. The
  width/height cap assertions remain boundary-free, but that is the residual
  observation from round 1, not a new finding.

### Verified

- The round-2 changes do not disturb the position matcher, color resolution,
  focusability, no-intent behavior, or cleanup/double-close handling; the
  current-buffer `relative = "cursor"` path is unchanged and correct.
- `target_window` now rejects buffers with no current-tab window and documents
  that limitation consistently in `doc/cint.txt`.
- The new tests are non-vacuous for the addressed paths: the non-current test
  distinguishes the target window from the current window, the profile tests
  exercise both resolving `on_cursor_hold = false` and re-enabling via `base`,
  and the `<Plug>` test invokes the stored callback rather than only checking a
  command.
- No source edit, build, test, or formatter command was run by this role.

### Summary

Round 1 is substantially resolved: the non-current hover now selects the target
window and the automatic trigger consistently uses resolved profile settings,
with tests and documentation covering both. The remaining defect is narrower:
the non-current float anchors with buffer line/byte-column values in a
screen-coordinate API, so it tracks the caret only in the simple unscrolled,
unwrapped, ASCII case the new test uses; scrolled, wrapped, tabbed, or
horizontally scrolled target windows will misplace it. That should be fixed with
`bufpos` or `screenpos`-derived coordinates and pinned by a test before the
ticket closes.

Concurs / Dissents: **Dissent** on the one new non-current coordinate finding; I
concur that round-1 findings 1-3 are addressed and that the rest of the hover
behavior remains correct.

## Round 3

Verdict: The round-2 finding is resolved and I found no new correctness issue.
The non-current path now uses `vim.fn.screenpos(target, cursor[1], cursor[2] + 1)`
to obtain the visible caret cell, rejects the zero/zero not-visible result, and
opens an editor-relative float at `row = anchor.row`, `col = anchor.col - 1`.
Those are the correct 0-based editor coordinates for "one screen row below the
caret, left-aligned with the caret cell", so scrolling, wrapping, tabs, wide
characters, and horizontal scroll are handled by Neovim's screen mapping rather
than by raw buffer indices. The current-buffer path still uses
`relative = "cursor"` and is unchanged. `WinScrolled` is now a close trigger, so
a non-current hover cannot silently drift when the target window scrolls.

### Finding status

- Round-2 finding (non-current float used buffer line/byte column in a
  screen-coordinate API): resolved. The implementation uses `screenpos` and
  `relative = "editor"`, and the zero/zero not-visible guard returns `false`
  instead of opening an invalid float.
- Round-1 findings 1-3 remain resolved; their fixes are unchanged in this round.

### Verified

- The new editor-anchor test compares the float row/column against the target
  window's `screenpos` and asserts that the current window's caret differs, so
  it would catch a regression back to current-window or buffer-index anchoring.
- The scrolled-target test moves the cursor to buffer line 30 but restores
  `topline = 28`, making the visible caret sit near screen row 3; the test
  asserts the float tracks the screen row and explicitly rejects the raw buffer
  row, and then verifies that `WinScrolled` closes the hover.
- The not-visible guard, statusline/window-relative conversion, and
  `relative = "editor"` placement are consistent with the `nvim_open_win`
  and `screenpos` contracts (`screenpos` returns 1-based screen cells; the
  float API takes 0-based `row`/`col`).
- The docs now state the scroll close trigger and the current-tab window
  limitation consistently with the code. The earlier height/width-cap residual
  observation is unchanged and remains non-blocking.
- No source edit, build, test, or formatter command was run by this role; the
  green handover is assumed per `docs/workflow.md`.

### Summary

The round-3 fix closes the last anchoring gap: non-current hovers are now placed
from the target window's visible screen position through `screenpos` and
editor-relative coordinates, with a not-visible fallback and a `WinScrolled`
close event, and the new two-window and scrolled-target tests pin both the
screen mapping and the distinction from the current caret. The position
matcher, color resolution, focusability, no-intent behavior, resolved-profile
gating, cleanup, and command/`<Plug>` wiring remain correct, and I found no new
or unresolved defect.

Concurs / Dissents: **Concurs** — the round-2 finding is resolved and round 3
introduces no new correctness issue; zero findings remain, so the ticket may
close.
