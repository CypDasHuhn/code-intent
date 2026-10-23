# Correctness review — ticket 061 (AI review UX for discovery and restrictions)

## Round 1

Verdict: The main shape is right. Capability gating is centralized in
`core/capabilities.lua` and the protocol-3 path still renders passive AI
candidates and keeps the old status/notification behavior; discovery passive mode
suppresses AI undercurl/sign/badge/virtual lines while keeping deterministic
candidates; review mode renders `+`, `−`, and `− +` groups with confidence,
source, and rationale; proposal decisions send `proposalId` and add-only
decisions send `candidateId`; `:CintReviewList`, the restrictions inspector, the
AI log, progress, and cancellation all go through the real transport. Six
renderer/UX defects remain, however: the AI log and inspector print `vim.NIL` for
nullable engine fields, a removal operation outside the proposal anchor cannot be
acted on from its own highlight, accept/deny report success before the server
answers and ignore `missing`/`disabled` decisions, deny can silently choose a
candidate group over an overlapping restriction proposal, the quickfix window
opened by `:CintReviewList` has no navigation, and the headless spec never
exercises the disabled-capability branches and uses a log fixture that hides the
`vim.NIL` case. Not ready to close.

### Findings

1. **`nvim-renderer/lua/cint/ops/suggestion_ops.lua:858-879` and `:942-955` — `:CintAiLog` and `:CintRestrictions` print `vim.NIL` for absent engine fields.**

   The engine's Jackson mapper (`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:67`,
   default `jacksonObjectMapper`) writes nullable fields as JSON `null`. The
   transport decodes with plain `vim.json.decode`
   (`nvim-renderer/lua/cint/transport/live.lua:258`), so `null` becomes
   `vim.NIL`, which is truthy and not equal to `""` in Lua. `event_line` tests
   `if event.tool and event.tool ~= ""` and the same for `unitId`; a real reuse
   event (where `tool` is null) therefore renders
   `reuse · vim.NIL · u1: reused u1`, and a tool event (where `unitId` is null)
   renders `tool · list_expression_units · vim.NIL: ...`. The same pattern in
   `restriction_label` (`record.symbol` and `record.provider` are nullable in
   `AiRestrictionRecord`) turns a top-level restriction without a symbol or
   provider into `... · vim.NIL · ai:vim.NIL`. The new headless fake omits these
   keys entirely, so the spec passes while a real daemon does not. Reproduction:
   run a discovery pass against the real server and `:CintAiLog`, or make the
   fake send `tool = vim.NIL` / `unitId = vim.NIL`; inspect a restriction record
   written without `symbol`/`provider`.

   Suggested fix: use a `type(value) == "string" and value ~= ""` guard (as
   `live_on_progress` already does for `activity`) everywhere a nullable server
   string is optional, and add a spec event with `tool`/`unitId` set to
   `vim.NIL` plus a restriction record with null `symbol`/`provider`.

2. **`nvim-renderer/lua/cint/ops/suggestion_ops.lua:396-440` with `nvim-renderer/lua/cint/render/candidates.lua:267-305` — a removal span that extends outside the proposal anchor is highlighted but cannot be selected for accept/deny.**

   `render_operations` highlights every operation span, including a `REMOVE`
   whose span is larger than the group anchor (059 explicitly supports negative
   start offsets for a removal of an enclosing annotation from a child unit).
   `matches_under_cursor` only tests `group_covers`, which uses
   `group.startLine/startCol/endLine/endCol` (the unit anchor). Reproduction:
   publish a proposal anchored on an inner expression at lines 2-2, columns 4-10
   with a `REMOVE` operation at lines 1-3, columns 0-20, enter review mode and put
   the caret on the highlighted `−` text at line 1 (outside the anchor);
   `:CintReviewAccept` and `:CintReviewDeny` both report "no candidate under the
   cursor" even though the caret is on the removal highlight.

   Suggested fix: treat a proposal group as matching when either its anchor or
   any of its operations covers the caret, and deduplicate by group. Add a
   proposal fixture whose remove span is wider than the anchor.

3. **`nvim-renderer/lua/cint/ops/suggestion_ops.lua:312-343` with `:596-630` and `:723-770` — accept/deny report success before the server answers and ignore rejected decisions.**

   `accept_proposal`/`accept_match` notify `removed ... added ... (AI)` or
   `accepted ...` immediately after `M.decide` sends the request. The decide
   callback only handles `request_err` and `result.stale`; `accepted == false`
   with `missing`, `disabled`, or another reason is ignored, and the optimistic
   success message has already been shown. Reproduction: press
   `:CintReviewAccept` twice on the same proposal before the republish arrives.
   The daemon locks the first decision and the second is answered
   `{accepted:false, missing:true}` (the engine's own test expects
   `missing`/`stale` for the loser), yet the renderer prints the success message
   twice and says nothing about the rejected second decision. The same happens
   with two clients or with a proposal whose server-side restriction
   revalidation returned `stale`.

   Suggested fix: return the decision result through a callback, notify success
   only when `result.accepted == true`, map `missing`/`disabled`/`stale` to
   distinct messages, and suppress a second decision for the same
   proposal/candidate while one is in flight (per buffer, version, and id). Do
   not print the applied-group message from the send path.

4. **`nvim-renderer/lua/cint/ops/suggestion_ops.lua:723-746` with `:449-457` — deny can reject the wrong group when a proposal and a candidate overlap.**

   `review_deny` calls `best_match`, which picks the highest-confidence match
   across both candidate groups and proposal groups and does not offer the
   picker used by accept. Candidate groups are published before proposal groups
   (`LiveServerCandidates.kt` appends proposals after candidate groups), so a
   tie selects the candidate, and a higher-confidence add candidate always wins.
   Reproduction: seed an add candidate at line 2, columns 0-10 with confidence
   0.9 and a restriction proposal at line 2, columns 0-10 with confidence 0.6
   (both cover the caret); `:CintReviewDeny` sends a `reject` for the candidate
   id, persists suppression for that candidate, and leaves the proposal pending,
   although the command is documented as rejecting the proposal at the anchor.

   Suggested fix: when more than one group covers the caret, open the same
   group picker for deny (labelled with the signature), or prefer an overlapping
   proposal group; never silently choose a candidate group over the proposal.
   Add a spec with overlapping candidate and proposal groups.

5. **`nvim-renderer/lua/cint/ops/suggestion_ops.lua:799-856` with `nvim-renderer/lua/cint/commands.lua:366-367` — `]c`/`[c` (and accept/deny) do not work in the quickfix window opened by `:CintReviewList`.**

   `:CintReviewList` ends with `copen`, which makes the quickfix window current.
   The global `]c`/`[c` mappings then call `review_navigate` with the quickfix
   buffer as the current buffer; `M.review_entries(qf_buf)` finds no candidate
   state and reports "no review groups for this buffer" instead of moving to the
   next source group. `:CintReviewAccept`/`:CintReviewDeny` from the quickfix
   window similarly report "no candidate under the cursor". Reproduction:
   `:CintSuggest`, `:CintReview`, `:CintReviewList`, then press `]c` while the
   quickfix window is focused. The new spec hides this by executing `cclose` and
   `nvim_set_current_buf(buf)` before testing navigation
   (`tests/review_spec.lua:609-617`).

   Suggested fix: make navigation fall back to the quickfix list when the
   current buffer is the quickfix window (resolve the entry's source buffer, or
   use `cnext`/`cprev`), or keep focus in the source window after `copen`, or
   install buffer-local quickfix mappings. Update the spec to exercise `]c`
   and accept/deny with the quickfix window focused.

6. **`nvim-renderer/tests/review_spec.lua:219-239`, `:466-469`, `:685-703` — the spec does not exercise the gated-off capability branches and its restriction-removal assertion cannot fail for a missing re-analysis.**

   The review fake advertises `discovery`, `restrictions`, and `cache` all true
   and the spec only asserts those descriptor values; `candidates_spec` covers
   protocol 3 passive candidates but never calls the restriction commands or
   checks that a protocol-3/protocol-4-without-`restrictions` client refuses
   `:CintRestrictions`. A regression such as removing the
   `capabilities.restrictions()` guard in `M.restrictions`/`M.restriction_remove`
   or counting `restrictions` in the completion message on a server that does not
   advertise it would leave the suite green. The removal test also has the fake
   publish the restored `LOGGING` tags from its own
   `cint/restrictions/remove` handler; the renderer's subsequent
   `live_request_analysis` makes the fake publish empty annotations, and the
   assertion only waits for *any* non-empty tags, so it passes whether or not
   the renderer triggers re-analysis and can race the empty republish.
   Reproduction: delete the `capabilities.restrictions()` check from
   `M.restrictions`, or delete the `live_request_analysis` call from
   `restriction_remove`, and run `tests/run.sh`; both mutations still pass.

   Suggested fix: add protocol-4 cases with `restrictions = false`,
   `cache = false`, and `discovery = false`, and a protocol-3 case that asserts
   `:CintRestrictions` is rejected and proposal groups are not counted or
   rendered. Make the remove fake publish the restored tags only from a
   `cint/analyze`/`didChange` path (or make it assert that request), and assert
   the restored annotation is rendered after the re-analysis settles. Also send
   `tool`/`unitId` as `vim.NIL` in the log fixture to cover finding 1.

### Concurs / Dissents

- Concurs with the capability split and the protocol-3 fallback: `discovery`,
  `restrictions`, `cache`, and `operations` all require protocol 4 plus their
  advertised flag; `ai_passive` is the inverse of `discovery`. By inspection,
  protocol 3 keeps the old `CintCandidate`/`CintAiCandidate` marks, `AI` badge,
  status shape, and arrival/`no new candidates` notifications, and
  `candidates_spec` still exercises that path.
- Concurs with passive mode: AI candidates are skipped before any undercurl,
  sign, badge, or virtual line is created; deterministic candidates still render,
  and the status counts AI add operations plus pending restriction groups.
- Concurs with the review presentation: `+`, `−`, and `− +` signatures,
  confidence, `ai:<provider>` source, rationale, review highlight, and virtual
  evidence lines are all produced; established annotations are still rendered by
  `render_ops` and proposals are not applied optimistically to the tag layer.
- Concurs with accept/deny identity in the normal case: proposals send one
  `confirm`/`reject` with `proposalId` (as well as `candidateId`), add-only
  decisions send `candidateId`, a single group is applied as a whole, and
  overlapping groups open a picker on accept.
- Concurs with the restrictions inspector: it lists `active`/`dormant`/`unknown`
  records with path, symbol, and `−`/`+` operations, removes by `id`, and lets
  the engine re-analysis reach `live_on_tags` so the restored annotation is
  repainted.
- Concurs with `:CintAiLog`: it uses `cint/suggest/log`, renders structured
  coverage/counts and events (no source text or provider prose), opens a scratch
  buffer, and handles absent/erroring results without a command error; the
  residual `vim.NIL` issue is finding 1.
- Concurs with progress/cancel: progress is stored per buffer, validated by
  document version, cleared on completion/cancellation, and does not repaint the
  tag layer; deterministic annotations are untouched. Completion notifications
  are emitted from the `cint/suggest` response, not from repeated candidate
  republishes.
- Dissents: findings 1-6.

### Residuals

- `:CintReviewAccept <intent>` matches both add and remove operation intents
  (`group_has_intent`), so `:CintReviewAccept LOGGING` on a `− LOGGING + SEARCH`
  proposal confirms the whole proposal. The README says the optional intent
  selects an add candidate alternative; this is a wording/behavior mismatch
  rather than a partial confirmation, because the proposal group is still atomic.
- The status branch is keyed on `discovery`: a protocol-4 server with
  `discovery = false` but `restrictions = true` (or `cache = true`) uses the
  legacy status text and omits the pending-restriction or reuse/coverage parts.
  The server advertises `restrictions` independently of `discovery`, so this
  combination is representable; no proposal groups are generated in the legacy
  path, but stored restrictions would not be counted. Decide whether the status
  should key on `restrictions`/`cache` instead of `discovery`.
- `restriction_remove` sends its own `didChange` after the removal even though
  the server already republishes open documents. It is harmless in the normal
  path but causes a second analysis and is why the removal spec's fake sees an
  empty republish.
- Review navigation/list is buffer-scoped: quickfix entries are built only from
  the current buffer's groups, so a project-wide review list would need an
  explicit multi-buffer command. The current single-buffer behavior matches the
  fact that candidates are published per URI.
- `M.review_toggle` reports the render count as "unsure candidate(s)"; in
  protocol-4 review the render count includes restriction groups, so the wording
  is approximate.
- `live_ops.on_error` clears tags, candidates, and progress but leaves
  `live_suggest_results`, `live_suggest_log`, and `live_restrictions` in place;
  a same-root reconnect can show stale status/log data until the next publish or
  command.
- The AI log has no event cap. Rendering all events is intentional per the
  README and is fine for the stated tool-call ceiling, but a much larger session
  would produce a large scratch buffer.
- Review remains read-only: no source, test, build, lint, or formatter command was
  run.

## Round 2

Verdict: All six Round 1 findings are addressed in the renderer and covered by
the new spec: null normalization happens at the transport boundary and the fake
now sends real-shaped nulls; proposal operations outside the anchor are part of
the hit test; decisions are awaited, failures are reported, and duplicate
accepts are blocked; deny uses the overlap picker; basic quickfix navigation and
accept/deny work from the quickfix window; and the capability-off, client
re-analysis, and null-log cases are tested. One new defect remains. The quickfix
path identifies the current entry with `getqflist({idx=0}).idx`, which Neovim
updates only when a `:cc`/`:cnext` jump occurs, not when the cursor moves in the
quickfix window. A user who moves to another review entry and then accepts,
denies, or navigates can therefore act on a different group than the one under
the cursor. Not ready to close.

### Per-finding status

1. **Concur — addressed.** `transport/live.lua:101-118` recursively replaces
   `vim.NIL` with nil (dropping null keys) before dispatch, and
   `suggestion_ops.lua:14-16` adds a `nonempty_string` guard used by
   `restriction_label` and `event_line`. The fake now sends explicit
   `tool = vim.NIL` / `unitId = vim.NIL` event fields and `symbol`/`provider`
   nulls, and asserts neither the log nor the inspector contains `vim.NIL`
   (`tests/review_spec.lua:352-356, 366-376, 907, 932`). The boundary fix also
   covers the real Jackson payload, which writes null keys.
2. **Concur — addressed.** `proposal_covers`
   (`suggestion_ops.lua:476-495`) tests every operation span in addition to the
   group anchor, and `matches_under_cursor` uses it for proposals. The wide
   removal fixtures (`tests/review_spec.lua:750-803`) select and deny from a
   caret outside the anchor.
3. **Concur — addressed.** `M.decide` now takes a callback, guards an in-flight
   key per buffer/version/target/id, and reports `missing`, `disabled`, and
   `stale` failures while only treating `accepted == true` as success
   (`suggestion_ops.lua:319-383`). `accept_proposal`, `accept_match`, and
   `deny_group` notify success from the response callback, not the send path.
   The delay/duplicate, disabled, and missing tests exercise each path
   (`tests/review_spec.lua:675-748`).
4. **Concur — addressed.** `review_deny` now opens the same group picker when
   more than one group covers the caret, and the selected entry is denied
   explicitly (`suggestion_ops.lua:723-736, 884-900`). The overlapping
   candidate/proposal test selects the `− LOGGING` proposal and verifies the
   proposal id is rejected (`tests/review_spec.lua:832-890`).
5. **Concur for the reported reproduction; new finding 7.** Basic quickfix
   navigation, accept, and deny from the quickfix window work and are tested
   without closing it first (`tests/review_spec.lua:622-661`). The remaining
   defect is which entry is selected after the qf cursor moves.
6. **Concur — addressed.** The spec covers protocol 3, protocol 4 with
   `discovery=false`/`restrictions=true`/`cache=false`, protocol 4 with
   `restrictions=false`/`cache=false`, and rejection of
   `restrictions/list`/`restrictions/remove` without the capability
   (`tests/review_spec.lua:934-1009`). The remove fake now withholds the
   restored tags until the client sends `didChange`, and the test asserts
   `restored_after_analysis` and the LOGGING tag
   (`tests/review_spec.lua:394-398, 243-262, 897-919`). The fake also mirrors the
   real nullable event/record payloads.

### New findings

7. **`nvim-renderer/lua/cint/ops/suggestion_ops.lua:419-431`, `:929-934`, and
   `:957-981` — quickfix accept/deny/list/navigation use the last `:cc` entry,
   not the entry under the quickfix cursor, and any quickfix list is treated as
   a review list.**

   `quickfix_entry` reads `vim.fn.getqflist({ idx = 0 }).idx`; `review_navigate`
   does the same in its quickfix branch. Neovim updates that index on
   `:cc`/`:cnext`/`:cprev`, not when the cursor moves within the quickfix
   window. Reproduction: run `:CintSuggest`, `:CintReview`, `:CintReviewList`,
   then press `j` twice and `:CintReviewAccept`. The qf cursor is on the third
   entry but `quickfix_entry` returns entry 1, so the decision carries the first
   entry's `candidateId` (or proposal id) and can confirm or reject the wrong
   group. Likewise, `]c` from the third qf line jumps to entry 2, and `[c` can
   move forward. Because `review_target` and `review_navigate` only check
   `buftype == "quickfix"`, the same branch also consumes an unrelated
   grep/quickfix list and can resolve a cint group at that hit's source
   position.

   Suggested fix: derive the entry index from the quickfix window cursor line
   (`nvim_win_get_cursor(0)[1]`, clamped to `#items`) in both `quickfix_entry`
   and the `review_navigate` quickfix branch, and mark the cint list (title or
   context, or group ids in `user_data`) so a non-cint quickfix window is left
   alone. Add a test that moves the qf cursor with `j`/`G` before
   accept, deny, and `]c`/`[c`, plus a test that an unrelated quickfix list is
   not navigated or consumed.

### Concurs / Dissents

- Concurs with findings 1, 2, 3, 4, and 6; concurs with finding 5 for the
  reported "quickfix window has no navigation" defect.
- Dissents: new finding 7 (wrong quickfix entry selection after cursor movement,
  and the generic quickfix-list ownership).

### Residuals

- The decision in-flight key is a concatenated string
  (`buf:version:target:id`); ids containing `:` could in principle collide.
  Candidate/proposal ids are server-generated identifiers, so this is a
  robustness note rather than a reachable defect.
- `review_entries` still lists proposal groups from whatever is in
  `state.live_candidates` even when the `restrictions` capability is off. A
  conforming server filters those groups; a stale client state can list a group
  whose confirm/deny the server will reject as disabled. The commands remain
  safe because the server is the gate.
- `restriction_remove` still sends its own `didChange` after the server's
  removal republish, so the restriction is analyzed twice. This is harmless and
  is what lets the spec require a client-triggered re-analysis.
- `live_close` clears the buffer's progress/results/log/restrictions but not
  `state.live_decisions` entries for that buffer; an unanswered decision keeps
  its in-flight key until a reconnect/error clears the table.
- The intent filter in `:CintReviewAccept <intent>` still matches remove
  operations as well as adds; this is unchanged from Round 1 and remains a
  wording/behavior mismatch rather than a partial group confirmation.
- The AI log still has no event cap; long sessions are rendered in full by
  design.
- Review remains read-only: no source, test, build, lint, or formatter command was
  run.

## Round 3 (finding 7 verification)

Verdict: Finding 7 is resolved, and there are zero open findings. `quickfix_entry`
(`suggestion_ops.lua:422-450`) now rejects windows with a location list
(`getloclist(0).winid ~= 0`), requires the cint title (`"cint review"`) and
`context.cint_review == true` on the quickfix list, checks that the list's
`winid` matches the window showing the quickfix buffer, and derives the current
entry from the quickfix window's cursor row clamped to the item count instead of
`getqflist({idx = 0}).idx`. `review_target` uses that helper, `review_accept` and
`review_deny` use `review_target`, `review_list` rebuilds from its source buffer,
and the quickfix branch of `review_navigate` calls `quickfix_entry` and computes
next/previous from its index, so navigation, accept, and deny all agree on the
entry under the cursor. `review_list` marks the list with the cint title and
context after `setqflist(..., "r")` (the empty append changes only metadata and
adds no item). The spec now moves the quickfix cursor with `3G`/`G` while
asserting the qf index is still stale (`idx == 1`), expects `]c` to target the
cursor entry (lnum 4), accepts `prop-1` and denies `prop-2` from the moved rows,
and verifies that a replacement grep quickfix list and a location-list window
are rejected without sending a decision or jumping (`tests/review_spec.lua:622-698`).
The replacement quickfix list loses the cint title, and a location-list window
reports a non-zero list `winid`, so unrelated lists are no longer consumed. All
six Round 1 findings and Round 2 finding 7 are now closed; only residuals remain.

### Residuals

- A global `quickfixtextfunc` that renders one quickfix entry over multiple
  display lines would break the cursor-row-to-item-index mapping. The plugin does
  not install one, so this is a configuration edge rather than a defect in the
  current surface.
- When accept/deny is invoked from a non-cint quickfix list, the fallback reports
  "no candidate under the cursor"; only navigation reports "not a cint review
  list". Both refuse to act, so this is wording only.
- The cint quickfix list is a snapshot. After a decision republish the list can
  contain entries for groups that no longer exist; the server's version/missing/
  stale guards prevent a wrong apply, and `:CintReviewList` refreshes it.
- Review remains read-only: no source, test, build, lint, or formatter command was
  run.
