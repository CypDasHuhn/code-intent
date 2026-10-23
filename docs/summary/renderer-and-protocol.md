# Neovim Renderer and Live Protocol

## Layout

`nvim-renderer/` is a Lua plugin. Entry points are `plugin/cint.lua` and
`lua/cint/init.lua`; commands are registered in `lua/cint/commands.lua`.

| path | role |
|---|---|
| `lua/cint/core/api.lua` | published annotation lookups, position-aware lookups, provenance |
| `lua/cint/core/state.lua` | rendered/live state, profiles, candidates, review buffers |
| `lua/cint/core/config.lua` | defaults, root resolution, profile/shard resolution, language mapping |
| `lua/cint/core/capabilities.lua` | protocol/capability gating for discovery, restrictions, cache, and review operations |
| `lua/cint/core/profiles.lua` | profile file loading/merging/caching |
| `lua/cint/core/colors.lua` | highlight group names and fallback palette |
| `lua/cint/handlers/` | meaning handlers: color, conceal, highlight, ignore |
| `lua/cint/render/init.lua` | render plans, extmarks, active reveal, mixed-scaffold rules |
| `lua/cint/render/labels.lua` | optional virtual intent labels |
| `lua/cint/render/stripes.lua` | optional mixed-intent ABAB coloring |
| `lua/cint/render/candidates.lua` | passive/review candidate marks and AI styling |
| `lua/cint/hover.lua` | cursor intent hover |
| `lua/cint/ops/` | setup, live, render, annotations, profiles, config, suggestions |
| `lua/cint/transport/live.lua` | JSON-RPC/NDJSON Unix-socket transport |

## Commands

Core: `:CintRender`, `:CintClear`, `:CintToggle`, `:CintRefresh`, `:CintInfo`,
`:CintAnalyze`, `:CintLiveTags`.

Rendering: `:CintIntentLabels`, `:CintStripes`, `:CintHover`.

Profiles/shards: `:CintProfile`, `:CintProfile <name>`, `:CintProfile!`,
`:CintProfileEdit`, `:CintProfileReload`, `:CintShard`, `:CintShard!`,
`:CintShardUndo`, `:CintShardReset`, `:CintShardHistory`.

Annotations: `:CintAnnotate <INTENT>[,<INTENT>] [note]` (range-aware),
`:CintUnannotate`, `:CintAnnotations`, `:CintAnnotationsReload`.

AI candidates/review: `:CintSuggest`, `:CintSuggestCancel`, `:CintReview`,
`:CintReviewAccept [intent]`, `:CintReviewDeny`, `:CintReviewList`,
`:CintRestrictions`, `:CintAiLog`.

Config/live: `:CintConfigAdd`, `:CintConfigRemove`, `:CintServerStatus`,
`:CintServerDisconnect`, `:CintServerStop`, `:CintServerRestart`.

`<Plug>` mappings include `(CintProfilePick)`, `(CintProfileEdit)`,
`(CintShardPick)`, and `(CintHover)`.

## Meanings and handlers

Each annotation's intent set resolves to a meaning through `resolve.precedence`
(default `SHOW, HIGHLIGHT, IGNORE, CONCEAL`) and `resolve.default` (`SHOW`).
`rules[meaning]` selects handlers:

- `color`: background tint + gutter sign/icon.
- `highlight`: background from the chosen tag.
- `ignore`: dim foreground.
- `conceal`: replace text with a char or hide whole lines.

`tags[intent]` supplies `meaning`, `bg`/`color`, `sign`, `icon`, `priority`.
Unknown intents use the neutral fallback palette. Multi-intent scaffolds skip
color/highlight; `UNTAGGED` is ignored for visual meaning/tag when a real intent
is present, while `UNTAGGED`-only spans keep configured behavior.

Profiles are Lua files at `<root>/.cint/profiles.lua` (or global config).
Profiles extend/merge; shards are config patches applied on top. The client
caches resolved config per buffer.

## Intent hover

- `:CintHover` / `<Plug>(CintHover)`, and `CursorHold`/`CursorHoldI` when
  `hover.on_cursor_hold` is on (default).
- Non-focusable floating window near the caret; one line per intent, colored
  from the tag spec.
- Shows the **innermost** published span under the caret; coincident spans
  combine; `UNTAGGED` is omitted and untagged-only spans are skipped.
- Closes on cursor movement, buffer/window leave, text change, resize, and
  window scroll. It never takes focus.
- Non-current buffers anchor to the target window's visible caret via
  `screenpos`; not-visible targets do nothing.
- Config: `hover = { enabled = true, on_cursor_hold = true }`.

## Candidate and review UI

- Deterministic passive candidates: `?` sign, dotted undercurl.
- Protocol-3 passive AI candidates: `AI` sign, purple AI highlight, one ` AI`
  EOL badge per line, and an arrival notification per AI-count increase.
- Protocol-4 discovery passive mode is quiet (no AI marks); status text reports
  AI additions, restrictions, reuse, and coverage. Deterministic candidates are
  unchanged.
- Review mode: `+`/`−`/`− +` operation groups with confidence, source, rationale,
  and a virtual evidence line. Accept/deny act on the whole group under the
  caret; overlapping groups open a picker.
- `:CintReviewList` fills a cint-owned quickfix list; `]c`/`[c` navigate and
  accept/deny follow the quickfix cursor. Unrelated quickfix/location lists are
  ignored.
- `:CintRestrictions` lists and removes restriction records and triggers
  re-analysis. `:CintAiLog` shows session counts, coverage, reuse, tool calls,
  verdicts, budgets, and provider failure detail; it connects on demand.
- Progress is `grading N/M · reused R · activity`; `:CintSuggestCancel` cancels;
  completion notifies once.
- Candidate marks clear on buffer close or candidate republish.

## Live protocol

Transport: NDJSON JSON-RPC over a Unix socket (`cint-<uid>` directory), protocol
`4`. The client spawns/attaches to `cint-server` per project root.

Methods:

- `initialize {protocol, root, client}` -> protocol/root/languages/config
  descriptors (pack config + secret-free suggestions descriptor with
  `discovery`/`restrictions`/`cache` capability flags).
- `cint/publishTags {uri, version, hash, revision, analysisRevision,
  annotations}`.
- `cint/publishCandidates {uri, version, analysisRevision, suggestionRevision,
  candidates, groups}`.
- `cint/analyze`.
- `cint/suggest {uri, version, requestId}` and `cint/suggest/cancel`.
- `cint/suggest/progress {uri, version, graded, reused, total, activity}` during
  a discovery pass.
- `cint/suggest/log {uri}` -> last session counts, coverage, and events. Requires
  protocol 4.
- `cint/candidates/decide {uri, version, candidateId|proposalId|groupId,
  decision}`; proposal decisions apply the whole operation group atomically and
  return `{accepted, ...}` or a `missing`/`stale`/`disabled` result.
- `cint/restrictions/list {uri}` -> `{restrictions: [...]}` with
  `active`/`dormant`/`unknown` status; `cint/restrictions/remove {id}` ->
  `{accepted, removed}`. Both require protocol 4.
- `cint/config/read|append|remove`.
- `cint/error`.

Revision rules: stale document versions are dropped; `analysisRevision` is
monotonic per document and allocated with the analysis/config snapshot;
`suggestionRevision` orders candidate publishes. Candidate decisions republish
to every client holding the URI. Skip is client/session-only.

## Tests

`nvim-renderer/tests/run.sh` runs headless specs:

- `live_spec` — fake daemon, transport, revisions, errors.
- `render_spec` — extmarks, meanings/handlers, profiles, labels, untagged.
- `annotations_spec` — user annotation write/remove/lock/round-trip.
- `labels_spec` — virtual intent labels.
- `hover_spec` — position lookup, window/config/color/close behavior.
- `stripes_spec` — mixed-intent stripes.
- `config_spec` — config/profile/shard behavior.
- `candidates_spec` — passive/review candidates, revisions, decisions, AI UI,
  suggestion notifications.
- `review_spec` — protocol-4 capability gating, quiet passive mode, `+`/`−`
  groups, accept/deny/overlap picker, quickfix list/navigation, restrictions
  inspector, AI log, progress/cancel, and deduped completion notices.

Protocol 4 is additive: protocol-3 clients keep deterministic tags and the
existing candidate shapes and ignore unknown notifications.
