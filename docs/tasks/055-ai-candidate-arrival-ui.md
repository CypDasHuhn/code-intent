---
name: ai candidate arrival ui
status: done
depends-on: [052]
reviewers: [correctness]
---

## Goal

Make an arriving AI suggestion visibly change the client UI. Today deterministic
and AI candidates share the same passive `?`/undercurl treatment, so when an AI
enrichment pass replaces or adds a candidate nothing looks different.

## Scope

- Detect AI candidates by their `ai:` source in the candidate renderer.
- Render passive AI candidates distinctly: an `AI` sign, a purple AI highlight,
  and a small ` AI` badge at the end of the candidate line. Deduplicate the badge
  per line.
- Notify when new AI candidates arrive (`AI added N candidate(s); :CintReview`)
  and give `:CintSuggest` a start/finish signal without duplicate notifications.
- Include the AI candidate count in `status_text` (for example
  `CodeIntent: 2 unsure (1 AI)`) and expose `ai_candidate_count`.
- Keep review-mode rendering and candidate decisions unchanged.
- Update README and `doc/cint.txt`.
- Extend `candidates_spec.lua` with passive AI sign/highlight/badge, status
  count, and arrival notification coverage.

## Acceptance

- A deterministic candidate keeps the muted `?` passive rendering.
- Once an AI candidate arrives, its span shows the `AI` sign/badge and AI
  highlight without entering review mode.
- New AI candidates produce a visible notification; repeated republishes do not
  spam it.
- `:CintSuggest` reports start and no-result outcomes.
- `just test-nvim`, `just lint`, and `just build` pass.

## Out of scope

- Auto-entering review mode.
- Changing candidate generation, decisions, or the engine protocol.
- A persistent candidate panel.
