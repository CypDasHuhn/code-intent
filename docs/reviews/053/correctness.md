# Correctness review — ticket 053 (hover innermost span)

## Round 1

Verdict: The nested-scaffold suppression, annotation-order preservation, and
unchanged `annotations_at_position` are implemented correctly, and the new
tests are non-vacuous for the line/column fallback path. Two issues remain: the
selection treats equal *size* as coincidence, so same-length spans at different
positions are combined, and the tests never exercise the byte-based path that
the server actually publishes.

### Findings

1. **`nvim-renderer/lua/cint/core/api.lua:75-80,125-145` — equal-size spans are treated as coincident, so non-coincident spans can be combined.**

   `size_matches` compares only the computed size (`bytes` when both have them,
   otherwise `lines`/`cols`), not the span's start/end. Two annotations of equal
   length at different positions both count as the "same" minimum and their
   intents are merged. For example, with no byte fields:

   ```lua
   { intents = { "A" }, startLine = 0, startCol = 0, endLine = 0, endCol = 10 },
   { intents = { "B" }, startLine = 0, startCol = 2, endLine = 0, endCol = 12 },
   ```

   a caret at column 5 yields both `A` and `B`, although neither span contains
   the other and they do not coincide. The same happens when both have bytes:
   spans `[100,110)` and `[105,115)` have equal byte length. This contradicts
   the ticket goal ("combining intents only when spans coincide") and the docs
   added in this diff (`nvim-renderer/README.md:158-160`,
   `nvim-renderer/doc/cint.txt:124-127,327-331`). Equal-size multi-line spans
   are affected through the same fallback whenever their line counts and
   `endCol - startCol` happen to agree despite starting on different lines.

   Suggested fix: keep the size comparison only for choosing the minimum, then
   combine annotations whose actual bounds equal the winning annotation's
   bounds — compare `startByte`/`endByte` when both carry bytes, otherwise
   compare `startLine`/`startCol`/`endLine`/`endCol`. If two distinct spans tie
   for the minimum size non-coincidentally, pick one deterministically (first
   in annotation order) or add an explicit containment rule; do not union them.

2. **`nvim-renderer/tests/hover_spec.lua:54-97,110-134` — the tests never exercise the byte-based selection path.**

   Every published annotation in the hover fixture lacks `startByte`/`endByte`,
   so nested suppression, coincident combination, and scaffold-only selection
   are all pinned only through the line/column fallback. The production server
   always emits byte spans (`AnnotationJson.kt:15-30`), so the primary path
   described in the ticket is untested. The tests also have no equal-size
   non-coincident case (finding 1) and no `intents_at_position` check at the
   zero-width point.

   Suggested fix: give the fixture annotations byte spans (including one
   nested case, one coincident case, and one mixed byte/no-byte case) so the
   byte branch in `span_size`/`size_is_smaller`/`size_matches` runs; add a
   same-length shifted-span assertion once finding 1 is fixed; optionally
   assert the zero-width point through `intents_at_position`.

### Verified

- `annotations_at_position` is unchanged: the diff adds helpers before it and
  `intents_at_position` after it; its body is byte-for-byte the HEAD version.
  The updated count assertions remain consistent with the added fixture spans.
- Nested suppression works for the fallback path: the smaller source span wins
  over the enclosing scaffold at `(0,5)`, and a scaffold-only point still
  returns the scaffold intent.
- Coincident same-position spans combine in annotation order, and duplicate
  intents are still emitted once. The `(1,12)` case also confirms that a
  multi-line scaffold with fewer columns is still ranked larger than a
  single-line span because line count is compared first.
- Zero-width same-line spans yield size `(0,0)` (or `0` bytes) and are selected
  as innermost; position coverage for them is unchanged.
- The no-covering path returns `{}` early and the hover test at `(3,10)`
  confirms no empty hover is opened.

Summary: The change meets the stated acceptance behavior for nested, coincident
and scaffold-only cases, and `annotations_at_position` is untouched, but the
"coincide" contract is implemented as "equal size", which merges distinct
same-length spans, and the test suite only covers the line/column fallback even
though production spans carry bytes.

Verdict: dissents.

## Round 2

Verdict: Both round-1 findings are resolved. No new or unresolved findings.

### Resolved

1. **Equal-size non-coincident spans are no longer combined — resolved.**
   `intents_at_position` now keeps the first minimum-size annotation as the
   winner (`api.lua:130-138`, strict `<` so ties keep annotation order) and
   combines only annotations for which `same_span` matches the winner's exact
   bounds, using bytes when both sides carry them and line/column otherwise
   (`api.lua:77-85,141-150`). The shifted byte and fallback fixtures assert a
   single winner and no union, so the tie-break is pinned as documented.

2. **The byte-based path is now covered — resolved.**
   `hover_spec.lua:138-222` adds byte-bearing nested, coincident, and
   equal-size shifted spans, plus the fallback shifted case and the zero-width
   point through `intents_at_position`. These exercise `span_size`,
   `size_is_smaller`, and `same_span` on both byte and line/column paths. The
   README and `doc/cint.txt` now document coincidence plus the earliest-wins
   tie-break.

### Verified

- `annotations_at_position` and `annotations_at` remain untouched; the fixture
  count changes still match their new spans.
- Nested suppression, coincident combination with source-before-derivative
  ordering, scaffold-only behavior, zero-width points, and the no-covering
  early return are all still correctly pinned.
- The mixed byte/no-byte coincidence fallback in `same_span` is consistent with
  the size-comparison fallback: it compares line/column only when either side
  lacks a byte span.

Summary: The revised selection picks a deterministic earliest minimum-size
winner and unions intents only for exact-span matches, fixing the equal-size
over-combination, while the expanded tests now exercise the production byte
path, both fallback paths, shifted ties, and zero-width points; documentation
matches the implemented tie-break. No further correctness issues found.

Verdict: concurs.
