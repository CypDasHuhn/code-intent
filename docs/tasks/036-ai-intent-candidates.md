---
name: ai intent candidates via secondary emits
status: todo
depends-on: [035]
reviewers: [correctness, api-fidelity]
---

## Goal

Introduce the candidate channel for AI-assisted intent inference. Rules return
established `Emit`s and secondary `CandidateEmit`s in one list; the engine
partitions them, publishes candidates separately, and supports review without
changing established annotations or composition.

See [../ai-intents.md](../ai-intents.md).

## Scope

- `api`: v5 rule output split (`RuleEmit`, `Emit`, `CandidateEmit`), candidate
  reasons, `CandidateEmit` builders; `Matcher.run` returns `List<RuleEmit>`.
- `core`: partition rule output in `settle`; return `AnalysisOutput` with
  established annotations and candidates; candidates never enter
  `AnnotationStore`, composition, fixpoint convergence, or `reads`.
- `host`/`server`: carry candidates through analysis; protocol 3
  `initialize.suggestions`, `cint/publishCandidates`, `cint/suggest`,
  `cint/suggest/cancel`, `cint/candidates/decide`.
- Fake provider: deterministic AI secondary enrichment for tests and the first
  vertical slice; no real network provider in this ticket.
- Neovim: publish/render candidates non-destructively; `:CintSuggest`,
  `:CintReview`, confirm/reject/skip; status count.
- Persistence: `.cint/suggestions.json` decisions; confirm writes the existing
  user-annotation record; reject writes suppression.
- Tests: v5 contract, scheduler partition/fixpoint, candidate stale-version
  handling, protocol round-trip, confirm/reject end-to-end, nvim headless spec.
- Docs: architecture, protocol, renderer commands, manual test if live.

## Acceptance

- A rule can return established and candidate emits in one `List<RuleEmit>`.
- Candidates never appear as established annotations, contributions, or
  composition inputs.
- A validation-like gap can be emitted as a candidate by the validation pack and
  is published for review.
- Confirming a candidate writes a user annotation and the next analysis shows
  the intent as established; rejecting suppresses the same occurrence.
- AI disabled still publishes pack candidates; fake provider exercises the AI
  path.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Real remote/local model integration and prompt tuning.
- New intent packs (037-040) beyond a validation-gap fixture.
- Core structural-gap candidates that no pack owns.
- Auto-idle trigger by default; manual and fake-triggered passes only.
- Cross-project learning or automatic established intents.

## Notes

- Bump `CINT_API_VERSION` to 5 and update bundled/test packs.
- `toContribution` must accept only `Emit`, so candidates cannot leak.
- Fixpoint rules keep candidates from the final settled iteration only.
- Candidate identity uses path + anchor/content hash + intent + reason.
- Keep the frozen `Annotation` shape unchanged.
