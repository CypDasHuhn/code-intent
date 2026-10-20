# Correctness review — ticket 016 (renderer annotation contract)

## Round 1

Verdict: The serializer and client changes are directionally correct. Structured
provenance is preserved per contributor in both the artifact and the live
`cint/publishTags` payload, the `+`-joined rule string is absent, a nullable
`nodeAnchor` is now present in both wire shapes, unknown intent names still
resolve through `resolve.default`, and the renderer paints all three core roles.
The remaining correctness problem is in the new client-side view of the payload:
JSON `null` does not arrive as Lua `nil`, so the accessor cannot distinguish a
reserved/unset anchor from a populated one, and the current test bypasses that
wire decode.

### Findings

1. **`nvim-renderer/lua/cint/init.lua:561-576` (with `live.lua:206`) — a null
   `nodeAnchor` decodes as `vim.NIL`, not `nil`, so `annotations_at()` presents
   every reserved anchor as present.**
   `live.lua` decodes each payload with `vim.json.decode`; JSON `null` becomes
   the truthy sentinel `vim.NIL`. The server always emits `"nodeAnchor": null`
   today because `Annotation.nodeAnchor` is never populated, so in the real live
   path every annotation returned by `annotations_at()` has a non-`nil`
   `nodeAnchor`. The new test constructs a Lua table with the string `"node-1"`
   directly in `_live_tags`, so it never exercises the null-decoding case; a
   consumer checking `if annotation.nodeAnchor then` would treat the reserved
   field as a real anchor. This is a representation mismatch against the public
   `Annotation.nodeAnchor: NodeAnchor?` contract, not just a test gap.
   Fix: normalise the decoded payload when storing live tags (for example, map
   `nodeAnchor == vim.NIL` to `nil` per annotation), or document that the client
   exposes `vim.NIL`; add a test that decodes a real payload with
   `"nodeAnchor":null` and one with a string and asserts the two values differ.

### Concurs / Dissents

- Concurs: `Artifact.kt:42-44` emits `"nodeAnchor":null` or an escaped anchor id
  and keeps the field before the structured `provenance` array;
  `AnnotationPayload.kt:20` does the same for the live payload. `ArtifactTest`
  and `AnnotationPayloadTest` pin the present and null cases respectively.
- Concurs: provenance remains per-contributor (`rule`, `role`, `intents`,
  `evidence`, `source`, `confidence`, line/column and byte offsets), and the
  server test asserts the old top-level `rule` field is gone.
- Concurs: unknown intent names do not error. `render.resolve_meaning` uses only
  known `tags`/`intentMeanings` labels and falls back to `resolve.default`; the
  new tests cover default `SHOW` (no paint) and default `HIGHLIGHT` (painted
  through `Cint_HL_HIGHLIGHT_PACK_NEW_INTENT`) without a client tag entry.
- Concurs: source-intent, derivative-use and scaffold annotations all reach the
  renderer and paint through the normal intent policy. The plugin/core role
  boundary remains enforced in core (`Rule.toContribution` hardcodes
  `SOURCE_INTENT`; composition emits the other roles), so this client test does
  not weaken it.
- Concurs: `docs/manual-test.md` m004 exists, names ticket 016, is marked
  unverified for the live socket, and references the new accessor and
  `:CintLiveTags`.
- Dissents: finding 1 above.

### Residuals (not findings)

- `annotations_at(buf)` without a `line` reads the current window's cursor even
  when `buf` is not the current buffer; the returned line can therefore refer to
  a different buffer. The signature is documented as `[buf], [line]`, so callers
  can avoid it by passing a line, but the default is easy to misuse.
- Line matching is purely `startLine <= line <= endLine`; columns are ignored, so
  a span whose `endCol` is `0` (zero-width at the start of `endLine`) is reported
  as covering that line.
- `stored_annotations` prefers `_live_tags` over `_rendered`, so after
  `cint.clear()` (or when an unchanged-hash publish updates tags without
  re-rendering) the accessor can describe annotations that are not what is
  currently painted. That is consistent with "published annotations" but not with
  "covering rendered annotations"; the public docs do not disambiguate it.
- The anchor is still never produced end-to-end: `Contribution` has no anchor
  field and `Rule.toContribution` drops `Emit.nodeAnchor`, so only hand-built
  `Annotation` values in tests can carry one. This matches ticket 001/§10's
  "reserved, unused" wording, but the live contract will need the propagation
  path when anchors become real.
- The nvim contract test injects Lua tables into `_live_tags` instead of decoding
  a server-shaped JSON payload, so the payload spelling (`nodeAnchor`,
  `provenance`) and null handling are only indirectly covered on the client side;
  m004 is the live backstop.

No source edit or build/test/formatter command was run by this role; the
handover was treated as green per `workflow.md`.

## Round 2

Verdict: Finding 1 is addressed. `live_on_tags` normalises a decoded
`nodeAnchor == vim.NIL` to `nil` before storing or rendering, so the reserved
anchor is distinguishable from a populated string, and the new `publish_wire`
test round-trips a server-shaped payload through `vim.json.encode`/`decode` and
asserts both the null and string cases. The same pass fixes the round-1 line and
buffer residuals: `annotation_covers` treats the end line as half-open unless
`endCol > 0`, rejects zero-width same-line spans, and `annotations_at` derives a
missing line from a window that actually shows the queried buffer (returning
empty when there is none), with regression tests for the start line, end line,
zero-width span, non-current buffer, and hidden buffer. No new correctness
finding was found.

### Findings

- None. The round-1 finding (JSON-null anchor represented as truthy `vim.NIL`)
  is fixed at the live ingest boundary, and the new wire-decoded tests exercise
  the real decode path rather than injecting Lua tables.

### Concurs / Dissents

- Concurs: F1 — `init.lua` normalises `nodeAnchor` for every decoded annotation,
  and `_live_tags`/`_rendered` receive the same normalised table. The null case
  now yields `nil` and a present anchor remains `"node-1"`; the earlier direct
  table-only test no longer masks the wire behaviour.
- Concurs: the half-open range change — a multi-line span starting at line 1 and
  ending at line 2 col 0 covers only line 1, a same-line nonzero span covers its
  line, and a zero-width same-line span covers none; the tests pin each case.
- Concurs: the buffer/window change — an explicit line works for a non-current
  buffer, an omitted line comes from a window showing that buffer rather than the
  unrelated current window, and a buffer with no window returns no annotations.
  `doc/cint.txt` documents the 0-based line and window-derived default.
- Concurs: the serializers, per-contributor provenance, absent `+`-joined rule
  string, unknown-intent default policy, role painting, and m004 manual entry
  remain correct and unchanged.
- Dissents: none.

### Residuals (Round 2)

- The anchor is still not produced end-to-end (`Contribution` has no anchor and
  `Rule.toContribution` drops `Emit.nodeAnchor`), consistent with 001/§10's
  "reserved, unused" wording but needed when anchors become real.
- `stored_annotations` still prefers `_live_tags` over `_rendered`, so a
  same-hash publish that changes annotations without re-rendering can make the
  accessors describe newer tags than the painted extmarks; this is pre-existing
  dedupe behaviour and is consistent with "published" rather than "rendered".
- `annotation_covers` now deliberately excludes zero-width same-line spans and
  spans ending at column 0, which is correct for half-open text ranges; a future
  point/anchor use would need an explicit exception.

No source edit or build/test/formatter command was run by this role; the
handover was treated as green per `workflow.md`.
