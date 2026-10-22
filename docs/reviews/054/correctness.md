# Correctness review — ticket 054 (hover omit untagged)

## Round 1

Verdict: The filter removes `UNTAGGED` from the returned intent list, the
standalone untagged-only and mixed fixtures behave as accepted, and
`annotations_at_position`/provenance are untouched. One semantic issue remains:
the filter runs only after the innermost/tie-break winner is chosen, so an
`UNTAGGED`-only annotation can win and suppress real intents from another
covering annotation. The new tests do not exercise that case.

### Findings

1. **`nvim-renderer/lua/cint/core/api.lua:132-153` — an `UNTAGGED`-only winner hides real intents from a broader or equal-size covering annotation.**

   `intents_at_position` chooses the minimum-size annotation at `:134-140`
   before excluding `UNTAGGED` at `:146`. If the winner carries only
   `UNTAGGED`, the result is `{}` even when `annotations` also contains a
   covering annotation with a real intent. Two concrete shapes:

   ```lua
   -- broader real span; caret at (3, 3)
   { intents = { "UNTAGGED" }, startLine = 3, startCol = 2, endLine = 3, endCol = 4 },
   { intents = { "SEARCH" },   startLine = 3, startCol = 0, endLine = 3, endCol = 8 },
   -- returns {} today; the real annotation is hidden by the placeholder

   -- equal-size non-coincident spans; caret at (3, 3)
   { intents = { "UNTAGGED" }, startLine = 3, startCol = 2, endLine = 3, endCol = 4 },
   { intents = { "SEARCH" },   startLine = 3, startCol = 3, endLine = 3, endCol = 5 },
   -- the earliest annotation wins the size tie, then filters to {}, hiding SEARCH
   ```

   The first shape is the interaction with 053's innermost rule: an
   untagged-only nested span suppressing a larger scaffold is consistent with
   "a larger scaffold does not contribute", but the ticket's premise that
   `UNTAGGED` is an engine placeholder rather than a user-facing intent is at
   odds with letting it occlude a real annotation under the same caret. The
   second shape is not required by 053: when two spans are equally innermost,
   the earliest-wins tie-break should not let a placeholder beat a real intent.
   The added fixture only has a standalone untagged span
   (`nvim-renderer/tests/hover_spec.lua:97,113,313-315`), so neither shape is
   pinned.

   Suggested fix: remove annotations whose entire intent set is `UNTAGGED`
   from the candidate set before selecting the winner, while keeping mixed
   annotations and still filtering `UNTAGGED` from the collected output. Add
   tests for (a) an untagged-only span nested inside a real-intent span and
   (b) an equal-size shifted `UNTAGGED`/real pair with the untagged annotation
   first. If suppressing the enclosing intents is the intended contract,
   document that explicitly and add those tests asserting no hover for both
   shapes; then the current code can stay.

### Verified

- Standalone untagged-only behavior matches the acceptance: `(3,2)` returns
  `{}` and the hover at `(4,2)` returns false and creates no window; the
  fixture positions match the added span.
- Mixed filtering works per intent: `{UNTAGGED, SEARCH}` returns `SEARCH`, and
  an untagged-only annotation coincident with a real annotation contributes
  nothing without removing the real annotation's intents. The mixed assertion
  is non-vacuous because dropping the whole annotation would fail it.
- `annotations_at_position`, `annotations_at`, `provenance_at_position`, and
  `provenance_at` are unchanged; the diff only adds the constant and the
  condition inside `intents_at_position`, so `UNTAGGED` remains visible through
  those APIs as required.
- Intent order and duplicate suppression are unchanged, and the docs in
  `nvim-renderer/README.md`, `nvim-renderer/doc/cint.txt`, and
  `docs/tasks/README.md` describe the omission accurately for the standalone
  and mixed cases. They do not state whether an untagged-only innermost span
  suppresses real intents from another covering span, which is the decision
  finding 1 asks to settle.
- No source, build, test, or formatter command was run by this role.

### Summary

The change is correct for the paths the ticket names: a standalone untagged
span produces no hover, a mixed span returns only its real intents, intent order
and deduplication are preserved, and the annotation/provenance APIs still expose
`UNTAGGED`. The unresolved correctness question is the placement of the filter:
because the winner is selected before `UNTAGGED` is removed, an untagged-only
placeholder can win over a broader or equal-size real annotation and make the
hover disappear when it should show the real intent; the tests only cover a
standalone untagged span, so this passes green. The implementor should either
move the exclusion into candidate selection and add nested/equal-size tests, or
explicitly document that an innermost untagged span suppresses enclosing
intents and pin that behavior with tests.

Verdict: dissents.

## Round 2

Verdict: The round-1 behavior bug is fixed: UNTAGGED-only annotations are now
removed from the candidate set before winner selection, so they no longer
suppress a real broader or equal-size span; mixed annotations still contribute
their real intents, and standalone untagged-only positions still yield no hover.
The nested regression test is non-vacuous. However, the new equal-size test is
vacuous — it also passes against the old filter-after-selection code — so the
equal-size tie case named in round 1 is still unpinned. The public
`intents_at_position` help entry also still describes the old selection rule.

### Resolved

1. **Round-1 finding — an UNTAGGED-only winner suppressed real intents — resolved in code.**
   `intents_at_position` now builds `candidates` from annotations that have at
   least one non-`UNTAGGED` intent (`nvim-renderer/lua/cint/core/api.lua:132-145`)
   and uses that list for both winner selection (`:148-155`) and collection
   (`:157-167`). An untagged-only annotation therefore cannot win, while a mixed
   annotation remains a candidate and still has `UNTAGGED` filtered per intent.
   The nested fixture (`nvim-renderer/tests/hover_spec.lua:122-123,127-131`)
   would fail against the old code (the size-2 `UNTAGGED` span won and yielded
   `{}`), so it pins the fix. Standalone untagged-only still returns `{}` and
   the mixed assertion still returns only `SEARCH`.
   `annotations_at_position`, `annotations_at`, `provenance_at_position`, and
   `provenance_at` are still untouched.

### Findings

1. **`nvim-renderer/tests/hover_spec.lua:124-136` — the equal-size test passes against the old filter-after-selection implementation, so it does not pin the equal-size/earliest-tie defect.**

   The equal-size fixture lists `REAL_EQUAL` (line 124, cols 10-14) before the
   `UNTAGGED` span (line 125, cols 12-16). Both have size 0 lines / 4 columns
   and both cover caret `(1,13)`. The old code selected the first minimum-size
   annotation, which is `REAL_EQUAL`, and its `same_span` collection also
   returned only `REAL_EQUAL`; the new assertion therefore succeeds on both
   implementations. It never exercises the round-1 shape where the untagged
   annotation is earlier in annotation order and wins the existing earliest-wins
   tie-break.

   Suggested fix: publish the equal-size `UNTAGGED` annotation before
   `REAL_EQUAL` (or otherwise make the untagged span the first equal-size
   candidate in annotation order) so the old code would select the placeholder
   and return `{}`. Keep the assertion that the result is `REAL_EQUAL`.
   Optionally add a byte-span equivalent, though the candidate filter is
   independent of span shape.

2. **`nvim-renderer/doc/cint.txt:330-337` — the `intents_at_position` API entry still says "innermost published span" and does not mention that UNTAGGED-only spans are skipped during selection.**

   The `:CintHover` section (`nvim-renderer/doc/cint.txt:124-127`) and the
   README (`nvim-renderer/README.md:158-160`) document the exclusion, but the
   public API reference for the function whose behavior changed does not. With
   the new code, a caret on an untagged-only nested span returns the enclosing
   real span's intents, so "innermost published span" is no longer literally
   accurate.

   Suggested fix: add "UNTAGGED-only spans are ignored when choosing the
   innermost span" (or "the innermost span with a non-UNTAGGED intent") to the
   `intents_at_position` help entry so it matches `:CintHover`.

### Verified

- Candidate filtering preserves annotation order, deduplication, and coincident
  span combination. A coincident untagged-only annotation is simply absent from
  the candidate list; a coincident real or mixed annotation still contributes.
- Standalone untagged-only positions return `{}` and create no hover window;
  the mixed `{UNTAGGED, SEARCH}` assertion returns only `SEARCH`.
- The new nested test is non-vacuous; the equal-size test is not (finding 1).
- The README and `:CintHover` help now match the implemented selection rule;
  the API entry is stale (finding 2).
- No source, build, test, or formatter command was run by this role.

### Summary

The implementation change is correct: excluding UNTAGGED-only annotations
before winner selection removes the occlusion bug while preserving mixed-intent
output, standalone no-hover behavior, ordering, and the annotation/provenance
contracts. The nested regression test genuinely exercises the fix, but the
equal-size test is ordered so that it would have passed before the fix and thus
does not protect the tie-break case; it needs the untagged annotation placed
first in annotation order. Separately, the `intents_at_position` API help still
describes the old "innermost published span" behavior and should mention that
UNTAGGED-only spans are ignored, matching `:CintHover` and the README. With
those two small changes the ticket is ready to close.

Verdict: dissents.

## Round 3

Verdict: Both round-2 findings are resolved and no new issues were found.

### Resolved

1. **Round-2 finding — the equal-size test was vacuous — resolved.**
   The fixture now publishes the `UNTAGGED` span (`nvim-renderer/tests/hover_spec.lua:124`,
   cols 12-16) before `REAL_EQUAL` (`:125`, cols 10-14). Both spans have size 0
   lines / 4 columns and both cover caret `(1,13)`, so the old
   filter-after-selection code would select the first minimum-size annotation
   (`UNTAGGED`), filter it to `{}`, and fail the assertion. The new candidate
   exclusion selects `REAL_EQUAL`, so the test now pins the equal-size tie case.

2. **Round-2 finding — the `intents_at_position` API help was stale — resolved.**
   `nvim-renderer/doc/cint.txt:331-337` now says `UNTAGGED` is omitted and
   `UNTAGGED`-only spans are skipped, matching the `:CintHover` section
   (`:124-127`) and the README (`nvim-renderer/README.md:158-160`).

### Findings

None.

### Verified

- The candidate filter removes annotations with no non-`UNTAGGED` intent before
  winner selection while keeping mixed annotations, so nested and equal-size
  untagged-only spans no longer suppress real intents.
- Standalone untagged-only positions still return `{}` and create no hover;
  mixed `{UNTAGGED, SEARCH}` still returns only `SEARCH`; intent order,
  deduplication, coincident-span combination, and the
  `annotations_at_position`/provenance contracts remain unchanged.
- The nested and equal-size tests are both non-vacuous, and the README,
  `:CintHover` help, and API entry now agree on the selection rule.
- No source, build, test, or formatter command was run by this role.

### Summary

The implementation correctly excludes UNTAGGED-only annotations from the
candidate set before selecting the innermost span, which resolves the original
occlusion defect without changing mixed-intent output, standalone no-hover
behavior, ordering, or the annotation/provenance APIs. The equal-size fixture
now places the untagged span first in annotation order and therefore genuinely
fails the old implementation, and the `intents_at_position` help now documents
that UNTAGGED-only spans are skipped, consistent with the hover help and README.
No new findings remain; the ticket is ready to close.

Verdict: concurs.
