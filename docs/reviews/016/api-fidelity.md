# API-fidelity review — ticket 016 (renderer annotation contract)

## Round 1

Verdict: The published shapes are faithful to the contract. Both serializers now
carry the reserved nullable `nodeAnchor`, neither emits a top-level `+`-joined
rule, `provenance` remains the per-contributor structured array with every
contract field, unknown intent names fall through to `resolve.default`, and the
renderer handles all three core roles. The remaining problems are at the new
client boundary: `annotations_at` uses an inclusive end line and a current-window
cursor even for another buffer, and the new tests bypass the JSON transport, so
the nullable anchor is never exercised through the decode path the live client
uses.

### Findings

1. **`nvim-renderer/lua/cint/init.lua:571` — `annotations_at` counts the exclusive end line as covered.**

   `Span` line/column values come from tree-sitter's `endPoint`
   (`analysis-engine/treesitter/src/main/kotlin/dev/cint/treesitter/KotlinParser.kt:64`)
   and byte offsets are half-open, while `annotations_at` checks
   `line <= annotation.endLine`. A multi-line annotation ending at
   `(startLine = 1, endLine = 2, endCol = 0)` is therefore returned for line 2
   even though its last covered character is on line 1 and the renderer paints
   no text on line 2 (`render.lua:202-217`). The new test only uses
   single-line spans, so this is unpinned.

   Fix: use a half-open check (`line < endLine`, plus `line == endLine and
   endCol > 0`, with a same-line `endCol > startCol` guard), or explicitly
   document that span lines are inclusive and make the engine/serializer agree.
   Add a test with a span ending at column 0 of the following line.

2. **`nvim-renderer/lua/cint/init.lua:565-568` — the omitted-line default uses the current window's cursor regardless of `buf`.**

   `cint.annotations_at(other_buf)` (and therefore `provenance_at(other_buf)`)
   takes the cursor row from whatever window is current, not from a window
   displaying `other_buf`. The documented `[buf], [line]` signature makes this
   easy to call against a non-current buffer and get answers for the wrong line.
   The help entry also does not state that `line` is 0-based, while the default
   is derived from the 1-based cursor.

   Fix: derive the default line from a window showing `buf` (or require `line`
   when `buf` is not current), and document the 0-based convention in
   `doc/cint.txt`. Add a test that queries a non-current buffer with an explicit
   line.

3. **`nvim-renderer/tests/render_spec.lua:11-17` and `:578-587` — the transport test injects a Lua table, so the nullable anchor is never decoded from the new payload.**

   The test helper writes directly into `cint._live_tags`, bypassing
   `vim.json.decode` in `nvim-renderer/lua/cint/live.lua:206` and the server's
   `nodeAnchor` serialization. The actual payload contains
   `"nodeAnchor": null` when the anchor is absent; Neovim decodes JSON null to
   `vim.NIL`, not `nil`. The test's assertion that the received anchor is the
   literal `"node-1"` therefore proves only table pass-through, and a future
   truthiness check (`if annotation.nodeAnchor then`) would treat absent as
   present. This leaves the "renderer paints from the new payload" and reserved
   anchor parts of the acceptance untested at the boundary where they matter.

   Fix: add a test that builds the exact `cint/publishTags` params, encodes and
   decodes them with `vim.json` (including null and present anchors), and feeds
   the decoded message through `live_on_tags`; normalize `vim.NIL` to `nil` in
   the client (or omit the field when absent) and assert both cases. Keep the
   live-socket row in `manual-test.md` for the daemon end-to-end check.

### Concurs / Dissents

- Concurs: `Artifact.kt:42-44` and `AnnotationPayload.kt:20` both publish the
  reserved anchor as a nullable string, and `Annotation` still defines it as an
  optional `NodeAnchor` (`api/Model.kt:49`). The field is additive in both
  serializers and consistent in name and representation.
- Concurs: the top-level joined `rule` string is gone from both the artifact and
  the live payload; `ArtifactTest.renderPublishesEveryContributorInProvenance`
  and `AnnotationPayloadTest.publishesEveryContributorInProvenance` pin its
  absence and the per-contributor chain, including byte offsets.
- Concurs: unknown intent names resolve through `resolve_meaning` to the
  configured default, and the new SHOW/HIGHLIGHT cases confirm no client tag
  entry is required. The fallback highlight group is derived from the unknown
  intent name, so no client table change is needed.
- Concurs: source-intent, derivative-use and scaffold annotations all reach the
  normal intent policy; the plugin API remains source-only (`Emit` has no role,
  `Rule.toContribution` stamps `SOURCE_INTENT`), so the renderer cannot receive
  a plugin-produced core role.
- Concurs: the new `provenance_at`/`annotations_at` surface exposes the
  structured chain from the published payload without rebuilding it.
- Dissents: `annotations_at` does not yet have a trustworthy line-range contract
  (finding 1) or a buffer-aware default (finding 2).
- Dissents: the automated renderer tests do not cross the JSON transport, so the
  nullable anchor contract is only verified in-process (finding 3).

### Verified

- No changes to the frozen `api` module; `NodeAnchor` was already reserved there.
- The live payload and the artifact use the same annotation field names
  (`role`, `intents`, `evidence`, `source`, `confidence`, `startLine`,
  `startCol`, `endLine`, `endCol`, `startByte`, `endByte`, `nodeAnchor`,
  `provenance`), so the renderer's read path is compatible with both.
- `provenance` entries retain rule, role, intents, evidence, source, confidence,
  line/column and byte offsets in both serializers; no field was flattened or
  dropped.
- Reviewer performed read/reason only; no build, test or formatter command was
  run, and the green handover is assumed per `docs/workflow.md`.

## Round 2

Verdict: Round-1 findings 1 and 3 are addressed. `annotation_covers` now applies
the half-open end-line rule and the range test pins the end-column-zero case;
`live_on_tags` normalizes `vim.NIL` anchors and `publish_wire` exercises the
JSON encode/decode boundary for both null and present anchors. Finding 2's
non-current-buffer path is addressed, but the new default-line resolver has one
remaining case: when the queried buffer is current and displayed in more than
one window, it can read a non-current window's cursor. One new api-fidelity
finding remains.

### Findings

1. **`nvim-renderer/lua/cint/init.lua:586-591` — current-buffer default line can come from another window.**

   `cursor_line_for` always uses `vim.fn.win_findbuf(buf)[1]`. When `buf` is the
   current buffer displayed in multiple windows, the first window showing it is
   not necessarily the current window, so `cint.annotations_at()` with no
   explicit line returns annotations for another split's cursor. The previous
   implementation used the current window; the round-1 fix removed that
   preference for all buffers, not just non-current ones.

   Fix: use window `0` when `buf == vim.api.nvim_get_current_buf()`, and fall
   back to `win_findbuf(buf)[1]` only for a non-current buffer. Add a test with
   the same buffer in two splits at different cursor lines and assert that the
   current split's line is used.

### Concurs / Dissents

- Concurs: F1 is addressed — `annotation_covers`
  (`nvim-renderer/lua/cint/init.lua:571-584`) checks `line < endLine` and treats
  `line == endLine` as covered only when the end column is non-zero, and the new
  range test (`render_spec.lua:633-644`) pins a span ending at `(2, 0)` as not
  covering line 2. The zero-width same-line case is also explicitly pinned.
- Concurs: F2 is addressed for non-current buffers — `cursor_line_for` takes a
  window displaying the queried buffer, `annotations_at` returns empty when no
  such window exists, and `doc/cint.txt:273-278` documents the 0-based line and
  window-derived default.
- Concurs: F3 is addressed — `live_on_tags` normalizes `vim.NIL` to nil
  (`init.lua:510-515`), and `publish_wire` round-trips a server-shaped payload
  through `vim.json.encode`/`vim.json.decode` and asserts both null and present
  anchors plus the decoded provenance chain (`render_spec.lua:590-629`).
- Dissents: the default-line resolver still prefers the first window showing the
  buffer over the current window when both refer to the current buffer (new
  finding above).

### Residual observations (not filed)

- `annotation_covers` returns no coverage for a same-line span with
  `endCol == startCol`; zero-length spans are valid at the `api`/`Emit` layer
  (see `RuleEngineTest`), so if the renderer can paint a zero-width sign the
  accessor will not be able to explain it. The new test currently pins the
  exclusion, but the contract does not say zero-width annotations are
  unqueryable.
- `publish_wire` exercises `live_on_tags` rendering but does not assert the
  resulting extmarks, so the wire test proves decoded accessors/provenance rather
  than painting from a decoded payload; the ordinary in-process render tests
  still cover the handler path.

## Round 3

Verdict: The Round-2 finding is addressed. `cursor_line_for` now prefers the
current window when the queried buffer is the current buffer and only falls back
to `win_findbuf` for another buffer, and the multi-split regression pins that
distinction. `annotation_covers` now includes zero-width same-line spans, and
the range test asserts they remain explainable. No new api-fidelity findings.

### Findings

None.

### Concurs / Dissents

- Concurs: `cursor_line_for` (`nvim-renderer/lua/cint/init.lua:586-597`) uses
  `nvim_get_current_win()` for the current buffer and `win_findbuf(buf)[1]`
  otherwise; the new split test (`render_spec.lua:666-678`) puts the same buffer
  in two windows at different cursor lines and confirms the current split's line
  wins.
- Concurs: zero-width same-line coverage is restored by
  `endCol >= startCol` (`init.lua:577-579`), with the range test now asserting
  that the zero-width annotation at line 3 is returned
  (`render_spec.lua:644-646`). The half-open end-line behavior for multi-line
  spans is unchanged and still pinned.
- Concurs: Round-1 F1/F3 remain addressed; the wire payload still normalizes
  `vim.NIL` anchors, preserves decoded provenance, and the payload/artifact
  field shapes are unchanged.
- Dissents: none.
