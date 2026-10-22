---
name: ai restriction proposals
status: todo
depends-on: [058]
reviewers: [correctness, api-fidelity]
---

## Goal

Let AI propose removing established intents and adding replacements as one
reviewable group. Confirmed groups persist separately from user annotations and
are applied before composition on the next analysis.

See [../ai-annotation-session.md](../ai-annotation-session.md).

## Scope

- Add an operation model for candidates: `ADD` and `REMOVE` operations, grouped
  per expression unit and anchored for review. Preferred shape is API v6 with
  explicit proposal operations; `toContribution` must still accept only
  established emits.
- Extend the session verdict schema to return `remove` alongside `add`.
- Validate removals against established annotations at the exact span/source,
  reject user annotations and `PURE`/`READONLY`, and drop operations that no
  longer resolve.
- Implement grouped confirm/deny/skip for proposals: one group applies all its
  operations atomically.
- Persist confirmed groups in `.cint/ai-restrictions.json` under the shared
  annotation lock, with anchored path/symbol/content hash and relative offsets.
- Resolve restrictions during analysis and apply them after user annotations,
  before composition; recompute derivative/scaffold output from the filtered
  source set.
- Add confirmed replacement intents as an `ai-confirmed` source contribution
  with provider/model/prompt provenance.
- Handle dormant restrictions (target gone) and user overrides (user wins).
- Extend protocol 4 candidate payloads/groups and `cint/candidates/decide` for
  operation groups; keep plain additive confirms writing
  `.cint/user-annotations.json`.
- Add an inspection/removal path for active restrictions and re-analysis after
  removal.
- Tests for reclassification, narrowing, user-wins precedence, derived
  recomposition, dormant records, and restart persistence.
- Docs: restriction semantics, file format, protocol, and config.

## Acceptance

- An AI group can propose `− LOGGING + SEARCH` on a span where `LOGGING` is
  established, and review displays both operations.
- Accepting the group removes `LOGGING`, adds `SEARCH` as `ai-confirmed`, and
  composition recomputes dependent annotations.
- Denying the group suppresses the proposal; removing the persisted record
  restores the original annotation on re-analysis.
- AI cannot remove user annotations or `PURE`/`READONLY`.
- A restriction whose target disappears becomes dormant and does not crash the
  pass.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Direct restriction of composition-only annotations; source removal plus
  recomposition is the supported path.
- Cross-file or project-wide restrictions.
- Removing AI restrictions from the engine through an AI proposal.
- Reuse cache (060) and full renderer review UX (061).

## Notes

- The restriction file is not a user-overrides file; keep it separate and
  engine-owned.
- Relative offsets keep records stable across line moves.
- Do not change the frozen `Annotation` shape.
- A group with no surviving operations after validation is dropped silently.
