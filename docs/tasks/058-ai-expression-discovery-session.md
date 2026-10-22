---
name: ai expression discovery session
status: in-progress
depends-on: [057]
reviewers: [correctness, architecture]
---

## Goal

Replace pack-candidate-only AI enrichment with an expression-level annotation
session. Every expression in the document becomes a unit the agent must grade,
so code that no rulepack recognizes can still receive AI candidates. Add-only
verdicts land in this ticket; removals follow in 059.

See [../ai-annotation-session.md](../ai-annotation-session.md).

## Scope

- Enumerate expression units from the language syntax model, including leaf
  expressions; expose them through a core fact rather than a rulepack.
- Implement an internal tool loop in `suggest`/`server` with the read-only tool
  surface from the design: unit list, expression, parent, enclosing
  statement/function, bounded range read, type lookup, annotations, and
  `submit_verdicts`.
- Scope tools to the current document plus LSP facts; apply `allowGlobs` and
  `redactPatterns` to every text result.
- Require an explicit verdict per unit: `none` or `add` in this ticket; `remove`
  arrives with 059. A missing verdict is not success; track coverage.
- Validate adds inside the unit, known intent vocabulary, duplicates, and
  confidence; drop invalid verdicts without failing the pass.
- Run the session asynchronously, one pass per document version, cancellable,
  discarded on version change.
- Enforce session timeout, per-request timeout, turn, tool-call, token, and
  units-per-request budgets. Publish graded units and report coverage on budget
  exhaustion.
- Protocol 4: advertise discovery/capabilities in `initialize.suggestions`, add
  `cint/suggest/progress`, and return coverage/counts from `cint/suggest`.
- Update the prompt version and make the prompt recall-oriented: grade every
  unit, allow sub-spans, propose plausible low-confidence intents, never repeat
  an existing intent.
- Publish session events for `:CintAiLog` and support `cint/suggest/log`.
- Tests with a scripted fake provider/tool loop, including a `Facts.kt:216`-like
  regex find that has no pack candidate and must produce `SEARCH`.
- Docs: design, protocol, config, and test coverage.

## Acceptance

- With AI enabled, `:CintSuggest` grades expressions that have no pack
  candidate, and a regex find fixture yields a `SEARCH` candidate.
- Every unit ends as graded or explicitly uncovered; coverage is reported.
- The agent cannot propose outside its unit, invent intents, or write state.
- Cancellation, stale versions, provider failure, and budget exhaustion never
  disturb deterministic annotations.
- Protocol 4 clients receive progress and coverage; older clients keep protocol
  3 behavior.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Remove verdicts, restriction groups, and `.cint/ai-restrictions.json` (059).
- Reuse caches and similarity hints (060).
- Full Neovim review UX for high-volume discovery (061).
- External MCP endpoint; the tool schema should be reusable for it later.
- Arbitrary project-file, shell, or network tools.
- AI-established annotations or composition participation.

## Notes

- Existing additive candidates remain candidate-only and unchanged in shape
  until 059 introduces the operation model.
- The worklist is authoritative; do not let the model decide which units exist.
- Keep the deterministic pipeline, rule graph, and composition untouched.
- Prefer batching by locality window, but each unit is judged independently.
- The provider timeout is no longer the only safety valve: the session budget is.
