---
name: ai verdict reuse cache
status: done
depends-on: [058, 059]
reviewers: [correctness, architecture]
---

## Goal

Stop redoing identical work. Reuse analysis for unchanged content, reuse AI
verdicts for unchanged expression units after edits, and seed the agent with
near-miss verdicts instead of starting from nothing.

See [../ai-annotation-session.md](../ai-annotation-session.md).

## Scope

- Introduce `deterministicEnv` and `aiEnv` identities covering engine/pack
  versions, config, user annotations, AI restrictions, language profile, oracle
  mode, provider/model, prompt version, and vocabulary.
- Short-circuit document analysis when `contentHash` and `deterministicEnv`
  match: no parse, LSP, rules, composition, or AI call; republish for the new
  buffer version.
- Add a per-unit verdict cache keyed by language, import set, node kind,
  normalized text, enclosing symbol identity/signature, referenced names,
  existing intent fingerprint, and `aiEnv`.
- Cache negative verdicts and store operation offsets relative to the unit.
- Invalidated by text diff: reuse non-overlapping matching units, invalidate
  changed units, ancestors up to the enclosing statement/function, and units
  referencing changed declarations when the oracle can identify them.
- Carry prior verdicts and prior restrictions as hints for similar but changed
  units; do not publish hints directly.
- Re-key rejection/confirmation/restriction decisions by normalized anchor
  fingerprint so formatting edits do not resurrect or drop them.
- Persist fingerprints and verdicts under `.cint/cache/ai-verdicts.json`
  without source text; omit rationale from disk by default. Add LRU/TTL bounds
  and version invalidation.
- Report reuse in the session result: reused, graded, total, and overhead.
- Tests: same-hash short circuit, lint-only edit reuse, reformat reuse,
  prompt/provider invalidation, annotation/restriction invalidation, disk
  restart reuse, decision survival, and bounded eviction.
- Docs: cache identity, invalidation rules, persistence, and config.

## Acceptance

- A `didChange` with identical text does not re-analyze or call the provider.
- A one-line lint edit reuses the unaffected expression verdicts and grades only
  the affected units.
- A reformat reuses units whose normalized fingerprints are unchanged.
- Changing the prompt, provider, model, vocabulary, packs, config, user
  annotations, or restrictions invalidates the relevant cache layer.
- The disk cache survives a daemon restart and contains no source text.
- Rejected suggestions do not reappear after a formatting edit.
- External type changes without text changes are documented as an accepted
  staleness limit or handled explicitly.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Cross-project learning or sharing rejections across repositories.
- Hard cross-file verdict reuse without matching context.
- Persisting source excerpts or full provider transcripts.
- External MCP/cache integration.
- UI for cache statistics beyond the session reuse counts (061 owns the UX).

## Notes

- Correctness first: a cache hit must be verified against the current text and
  environment; never trust a stored span without remapping.
- AI verdicts are candidates only, so similarity hints are safe, but they must
  be clearly marked as prior hypotheses in the session log.
- Deterministic reuse must be exact; do not apply hint-style reuse there.

## Deferred

Round 13 closed the cache scope. The remaining split/legacy ownership findings
are deferred to ticket 062 with the review evidence:

- Legacy records without a `unitFingerprint` bypass the split ownership
  classifier, so status, scoping, host analysis, and suppression can disagree
  (`docs/reviews/060/correctness.md` round 13 finding 31;
  `docs/reviews/060/architecture.md` round 13 finding 31).
- Straddling/host-context suppression lacks the shared ownership check.
- Short-circuit republishing bypasses suppression.
- Residual coverage and known limits: overlapping split regions, legacy
  ordinal-only nested records, content-addressed region-key migration, external
  pack helper fingerprints, process-local diff baselines, memory-only hints,
  one cache header per language, and `aiEnv` not covering every suggestion-layer
  parameter.
