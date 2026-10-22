# Correctness review — ticket 055 (AI candidate arrival UI)

## Round 1

Verdict: Passive AI detection and rendering are correct, review mode keeps
precedence, the badge is deduplicated per line, and the status count is
consistent. The suggest/arrival notification pair is not correct for a repeated
`:CintSuggest`: the analysis republish that `suggest` triggers clears the
existing AI candidates first, so the publish re-adds them (arrival
notification) while the callback compares against the pre-analysis count and
reports "found no new candidates". Busy suggestion replies are also misreported
as no-result, and the tests model neither path.

### Findings

1. **`nvim-renderer/lua/cint/ops/suggestion_ops.lua:141-170` and `nvim-renderer/lua/cint/ops/live_ops.lua:260-267` — a repeated `:CintSuggest` emits contradictory "AI added" and "found no new candidates" messages.**

   `suggest` calls `live_request_analysis(buf)` (a `textDocument/didChange`)
   and only then snapshots `ai_before` at `:144`. The daemon handles that
   didChange synchronously by re-analysing and republishing the deterministic
   candidate set, and that publish has a higher `analysisRevision` and
   `suggestionRevision` than the previous suggestion publish. The client
   therefore applies it and drops the AI count from `N` to `0`. The subsequent
   suggestion publish re-adds the same `N` AI candidates, so
   `live_on_candidates` sees `0 < N` and notifies
   `cint: AI added N candidate(s); :CintReview to inspect`. When the
   `cint/suggest` reply arrives, the callback still holds the pre-didChange
   `ai_before == N`, sees `N <= N`, and notifies
   `AI suggestions found no new candidates`. The user gets both messages for a
   pass that, from their point of view, added nothing new; this contradicts the
   README/help statement that a pass finding no new candidates reports that
   *instead of* repeating the arrival notification. The AI badges are also
   briefly cleared and re-added on every repeat suggest. The first suggest in
   the spec does not hit this because `ai_before` is `0`, and the no-result
   block does not because the fake didChange publish uses a lower revision and
   is discarded (finding 3).

   Suggested fix: make the finish signal request-scoped instead of comparing
   against a baseline captured before `live_request_analysis` can publish.
   Either skip the didChange when the buffer already has a publish for the
   current version (so repeat suggest does not clear AI candidates), or record
   in `state` that the outstanding request observed an AI increase in
   `live_on_candidates` and suppress the callback's no-result when it did
   (emitting exactly one finish message). Add a test that runs two consecutive
   successful suggests with AI candidates already present.

2. **`nvim-renderer/lua/cint/ops/suggestion_ops.lua:153-170` — a `busy` suggestion reply is reported as "AI suggestions found no new candidates".**

   The callback only special-cases `stale` and `cancelled`; it never checks
   `result.accepted`. A second quick `:CintSuggest` while the first provider
   call is in flight gets `{ accepted = false, busy = true, count = 0 }` from
   `handleSuggest` (`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:74-80`).
   Because the first pass has not published yet, `M.ai_candidate_count(buf)`
   is still `<= ai_before`, so the client tells the user the pass found no new
   candidates even though no pass ran. The same applies to an `accepted = false`
   disabled reply if the gate raced a config change.

   Suggested fix: only emit the no-result message for an accepted, completed
   pass (`result.accepted == true`, not `stale`, not `cancelled`, not
   `busy`); give `busy` its own message (for example "AI suggestion pass
   already running") and return silently for `disabled`. Correlate the callback
   with `state.live_suggest_request[buf]` so a superseded callback cannot emit
   a finish message for an older request.

3. **`nvim-renderer/tests/candidates_spec.lua:130-135` — the fake daemon reuses fixed revisions, so the new tests do not model the production didChange/suggest ordering.**

   `didOpen` always publishes revisions `1` and `didChange` always publishes
   revisions `2`, while `cint/suggest` publishes `3`. After a successful
   suggestion the client is at revision `3`/`3` (and `4`/`4` after the
   republish block), so the second suggest's didChange publish at `2` is
   discarded as stale. That is why the no-result block
   (`nvim-renderer/tests/candidates_spec.lua:533-546`) keeps the AI count at
   `1` and passes; in production the analysis revision is monotonically
   increasing and the publish *does* clear the AI candidates before the
   suggestion re-adds them (finding 1).

   Suggested fix: allocate monotonic analysis/suggestion revisions in the fake
   daemon (as `next_decision_revision` already does for decisions), including
   publishes for `didChange`, and add a second-suggest-after-AI assertion that
   pins a single coherent finish outcome (however finding 1 is resolved, not
   one "added" plus one "no result").

4. **`nvim-renderer/tests/candidates_spec.lua:311-365` — the notification tests do not pin the "no duplicate" acceptance and the republish uses the same table reference.**

   The successful-pass block asserts that the start and the arrival messages
   each occur once but never asserts that
   `AI suggestions found no new candidates` is absent, so an implementation
   that unconditionally emitted the no-result after a successful pass would
   still pass (that is exactly the shape of finding 1). The republish test
   passes `candidates = ai_record.candidates`, the same table already stored in
   `state.live_candidates[buf]`, so it would also pass an identity-based
   dedupe (`candidates ~= previous.candidates`) and does not exercise a real
   republish, which arrives as a fresh table with the same count.

   Suggested fix: in the successful-pass block assert
   `notification_count(suggest_notifications, "AI suggestions found no new candidates") == 0`;
   republish a distinct/deep-copied candidate list and assert no arrival
   notification; add a busy reply case. While in this area, pin the badge
   placement (`virt_text_pos == "eol"`) and its highlight group, which the
   current badge assertions do not check.

### Verified

- AI detection matches the wire contract: AI proposals carry `source =
  ai:<provider>` (`docs/ai-intents.md`, `SuggestionService`/providers) and
  `is_ai` checks the prefix defensively (non-string/nil source is not AI).
- Passive rendering is distinct and correct: passive AI candidates get the
  purple `CintAiCandidate` span highlight, the `AI` sign with
  `CintAiCandidateSign`, and one ` AI` badge at end of line; deterministic
  candidates keep `CintCandidate`/`?`. `review` takes precedence over
  `passive_ai`, so review-mode marks and evidence lines are unchanged, and
  exiting review restores the AI passive marks. Badges are deduplicated by
  start row within each render and the namespace clear prevents stale badges.
- All candidate extmarks keep the existing `priority = 1100`, above spans
  (1000), stripes (1001), and labels (900), so the AI highlight/sign/badge are
  visible and do not change review evidence priority.
- `ai_candidate_count` is exposed and `status_text` is consistent: total
  candidates include AI, and the `(M AI)` suffix appears only when `M > 0`
  (`CodeIntent: 2 unsure (1 AI)`).
- Stale candidate publishes are discarded before the arrival notification, and
  a same-count republish on a fresh table would be quiet with the current
  count comparison. The disabled/protocol/descriptor gates run before the
  start notification, so a gated `:CintSuggest` sends no request and prints no
  start message.
- No source, build, test, or formatter command was run by this role.

### Summary

The passive-AI rendering, review precedence, per-line badge dedupe, status
count, and stale-publish ordering are implemented consistently with the ticket.
The remaining correctness problems are in the suggest notification lifecycle:
because `live_request_analysis` republishes deterministic candidates before the
suggestion pass, a repeat `:CintSuggest` clears and re-adds the displayed AI
candidates, producing both an arrival notification and a contradictory "found
no new candidates" from the callback's stale `ai_before` snapshot; busy replies
are likewise misreported as no-result. The tests pass green because the fake
daemon's fixed revisions cause the production didChange republish to be
discarded, and because the successful-pass and republish assertions do not pin
the no-duplicate and fresh-table cases. Fixing the finish signal to be
request-scoped (or avoiding the redundant re-analysis), handling
`accepted`/`busy`, and updating the fake daemon revisions plus the notification
assertions would close the ticket.

Verdict: dissents.

## Round 2

Verdict: All four round-1 findings are resolved and no new correctness issue was
found. The repeat-suggest path no longer republishes the deterministic analysis,
so AI candidates are not cleared and re-added, and the finish signal is now
request-scoped and does not contradict the arrival notification.

### Resolved

1. **Round-1 finding — repeat `:CintSuggest` produced contradictory arrival and no-result messages — resolved.**
   `analysis_current` (`nvim-renderer/lua/cint/ops/suggestion_ops.lua:21-30`) skips the
   didChange when `live_docs` and `live_tags` already match the buffer version
   and path (`:152-158`), so an existing AI publish is not cleared before the
   suggestion pass. When a pass does publish AI (including the first pass or the
   fallback path where the analysis is not current), `live_on_candidates` sets
   `state.live_suggest_ai_seen[buf] = true` on the increase
   (`nvim-renderer/lua/cint/ops/live_ops.lua:265-266`), and the matching callback
   suppresses the no-result message (`suggestion_ops.lua:198-200`). The repeat
   suggest test (`nvim-renderer/tests/candidates_spec.lua:368-388`) pins
   `didChange` not being sent, exactly one no-result, and no arrival; it would
   fail without the reuse path. The successful first pass still pins the arrival
   with no accompanying no-result (`:334-342`). The fallback branch that still
   calls `live_request_analysis` is unchanged from round 1 and correct by
   inspection.

2. **Round-1 finding — busy/non-accepted replies were reported as no-result — resolved.**
   The callback now ignores superseded requests via the stored request id
   (`suggestion_ops.lua:169-171`) and handles `stale`, `cancelled`, `busy`,
   `disabled`, and `accepted ~= true` before deciding a pass completed with no
   result (`:179-200`). The busy and disabled tests
   (`nvim-renderer/tests/candidates_spec.lua:409-445`) assert the dedicated
   messages and the absence of both no-result and arrival notifications.

3. **Round-1 finding — the fake daemon reused fixed revisions, so production
   didChange/suggest ordering was not modeled — resolved.**
   The fake daemon now allocates monotonic revisions through `next_revision` for
   didOpen, didChange, suggest, and decisions
   (`nvim-renderer/tests/candidates_spec.lua:27-34,130-160`). Combined with the
   repeat-suggest assertions above, a regression that republished the analysis
   before enrich would raise the revision and fail the `didChange` count,
   no-result, and arrival assertions, so the test is not vacuous.

4. **Round-1 finding — notification tests did not pin no-duplicate, fresh-table
   republish, or badge details — resolved.**
   The successful-pass block asserts the no-result message is absent
   (`nvim-renderer/tests/candidates_spec.lua:339-342`); the republish uses
   `vim.deepcopy(ai_record.candidates)` (`:396-407`) so a fresh identical list is
   exercised; and the badge assertions now check `virt_text_pos == "eol"` and the
   `CintAiCandidateSign` highlight (`:355-362`).

### Findings

None.

### Verified

- The reuse check compares path and version for both the live document and the
  published tags, so it only skips the didChange when the server's analysis for
  the current text version has arrived; a pending debounce (newer `changedtick`)
  or missing tags falls back to the existing `live_request_analysis` path.
- `live_suggest_ai_seen` is reset at each request start and on root change,
  error, close, shutdown, stop-server, and `state.reset`, so it cannot leak
  across buffers or reconnects. The request-id guard means a superseded callback
  cannot emit a finish message.
- Passive AI rendering, review precedence, per-line badge dedupe, extmark
  priorities, `ai_candidate_count`, and `status_text` are unchanged from round 1
  and remain correct; the new tests assert the badge position and highlight.
- Disabled/protocol/descriptor gating still runs before the start notification
  and before any request is sent, and the updated README and `doc/cint.txt`
  describe the reuse, busy, disabled, and no-result behavior accurately.
- No source, build, test, or formatter command was run by this role.

### Summary

The round-2 changes resolve the notification lifecycle defects without
disturbing the rendering, status, or gating behavior: a suggest on an already
analyzed document reuses that analysis, so AI marks stay in place; a successful
pass that publishes AI records the increase per buffer so the requested pass
does not also report "no new candidates"; and superseded, busy, disabled, or
otherwise unaccepted replies get their own handling instead of a misleading
no-result. The tests now exercise the reuse path, the no-duplicate successful
pass, a fresh equal-count republish, busy and disabled replies, and the badge
position/highlight, and the fake daemon's monotonic revisions make the
republish assertions meaningful. No new findings remain; the ticket is ready to
close.

Verdict: concurs.
