---
name: ai review ux for discovery and restrictions
status: todo
depends-on: [058, 059, 060]
reviewers: [correctness]
---

## Goal

Make expression-level discovery and restriction proposals usable in Neovim
without flooding the buffer. Passive mode stays quiet; review mode exposes
adds, removals, reuse, coverage, and a way to undo restrictions.

See [../ai-annotation-session.md](../ai-annotation-session.md).

## Scope

- Gate protocol-4 discovery/restriction/cache features on the returned
  capability descriptor; keep protocol-3 behavior.
- Passive mode: no AI undercurl, badge, or conceal by default; status count
  includes AI additions, restrictions, reuse, and coverage. Deterministic
  candidates keep their current passive rendering.
- Review mode: render add (`+`) and remove (`−`) operations, grouped
  reclassification (`− +`), confidence, source, and rationale.
- `:CintReviewAccept` applies the whole group under the caret, with a picker
  when groups overlap; `:CintReviewDeny` rejects the group.
- Add `:CintReviewList` (quickfix/panel), `]c`/`[c` navigation, and `<Plug>`
  mappings.
- Add `:CintRestrictions` to inspect and remove active restriction groups,
  triggering a re-analysis that restores the original annotation.
- Add `:CintAiLog` for session tool calls, reuse decisions, coverage, and
  verdicts; support `cint/suggest/log`.
- Surface progress (`grading N/M · reused R · activity`) and
  `:CintSuggestCancel`.
- Notify once on completion with additions/restrictions counts; do not spam
  repeated republishes.
- Update README and `doc/cint.txt`; extend headless specs for passive/review,
  accept/deny groups, restrictions inspector, progress, cancellation, and reuse
  display.

## Acceptance

- Passive mode adds no AI marks to the buffer and still reports counts/coverage.
- Review mode shows `+`, `−`, and `− +` groups with rationale and supports
  accept/deny.
- `:CintReviewList` navigates high-volume results; `]c`/`[c` work.
- `:CintRestrictions` lists and removes records; the restored annotation
  reappears after re-analysis.
- `:CintAiLog` is readable and shows reuse/hints without crashing on long
  sessions.
- Progress and cancellation are visible and do not disturb deterministic tags.
- Protocol-3 daemons keep the existing candidate UX.
- `just test-nvim`, `just lint`, and `just build` pass.

## Out of scope

- Engine-side discovery, validation, persistence, or cache changes.
- An external MCP client UI.
- Reworking deterministic candidate rendering.
- Auto-entering review mode on arrival.

## Notes

- High-volume sessions are the norm now; the review list/navigation is the
  primary tool, not inline-only acceptance.
- Keep notifications deduped and rate-limited.
- Review UIs must never hide established annotations before confirmation.
