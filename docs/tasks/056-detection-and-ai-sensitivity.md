---
name: detection and AI sensitivity bundle
status: done
depends-on: [042, 048, 055]
reviewers: [correctness, detection-fidelity]
---

## Goal

Bundle four related fidelity fixes: make scaffolds with a real intent render as
that intent instead of falling back to UNTAGGED-mixed styling, paint the whole
`return` statement and propagate return-value intents through nested local
values, state the SEARCH/LOOKUP contract clearly and stop treating repository
criteria calls as lookup alternatives, and make AI enrichment less
conservative.

## Scope

### Scaffold rendering

- Ignore the reserved `UNTAGGED` marker when resolving a scaffold's visual
  meaning/tag and when deciding whether a scaffold is mixed-intent.
- A scaffold carrying `ERROR_HANDLING,UNTAGGED` must render like an
  ERROR_HANDLING scaffold; it must not skip color/highlight just because
  UNTAGGED is present.
- Add a renderer test for this exact shape.

### RETURN_VALUE coverage

- Emit the RETURN_VALUE source span over the whole return statement (from the
  `return` keyword through the expression), not only the expression.
- Propagate RETURN_VALUE to local declarations whose value is used in a
  return-value expression even when the local has other unannotated reads or
  writes (for example `val result = ...; if (result.errors...) throw; return
  result.value` and `val updated = ...; return LanguageConfig(updated)`).
- Keep other derivative-use intents on the existing all-uses-classified policy.
- Update tests/snapshots to the new return-statement spans and add nested
  argument/property-flow cases.

### SEARCH vs LOOKUP contract

- Document explicitly: LOOKUP retrieves a value known to exist from a
  static/preset source (map/table/enum/config/registry); SEARCH queries a
  collection/repository where the result may be absent, and a prior validation
  or guard does not turn a search into a lookup.
- Stop emitting the default LOOKUP boundary candidate for `findBy*`/`getBy*`/
  `loadBy*` repository calls. Empty the default `boundaryPrefixes` (keep the
  config field for explicit overrides) and update docs/tests.
- Keep known-map/enum/config retrieval as LOOKUP.

### AI sensitivity

- Increase AI recall defaults: more candidates per pass and more deterministic
  candidates eligible for enrichment; raise the completion token budget.
- Use a nonzero temperature and a prompt that asks for multiple plausible
  alternatives, permits lower-confidence guesses, and does not require
  certainty. Keep the known-intent vocabulary, byte containment, candidate-only
  output, and confidence cap unchanged.
- Update provider/server config defaults, tests, and docs.

## Acceptance

- The Config-like throw scaffold is visibly ERROR_HANDLING (no UNTAGGED-mixed
  render suppression).
- `return result.value` paints the full return statement; `val result = ...` and
  `val updated = ...` receive derivative RETURN_VALUE in the motivating shapes.
- `repository.findById(id)` remains SEARCH with no default LOOKUP alternative;
  map/enum retrieval remains LOOKUP.
- AI defaults produce more candidates/alternatives with a nonzero temperature
  while preserving all validation/privacy rules.
- `just build`, `just test-engine`, `just lint`, and `just test-nvim` pass.

## Out of scope

- New intents, new packs, or scheduler/API version changes.
- Whole-program dataflow for return propagation.
- Letting AI leave the bounded candidate span or invent intent names.
