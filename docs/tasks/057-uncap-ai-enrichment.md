---
name: uncap ai enrichment
status: in-progress
depends-on: [055, 056]
reviewers: [correctness, detection-fidelity]
---

## Goal

Stop the AI enrichment pass from being artificially limited to a handful of
candidates. The pack still decides which spans are candidates, so removing the
caps does not tag every line; it only lets every AI-eligible candidate reach the
provider and every validated proposal survive.

## Scope

- Treat `maxCandidates` and `maxEnrichmentCandidates` values `<= 0` as
  unlimited; positive values keep their existing cap behavior.
- Default both settings to unlimited in `SuggestionOptions`, the server
  `SuggestionSettings`, and its YAML loader.
- Update `SuggestionService` and `CandidateValidator` so 0 does not mean "send
  nothing" or "return nothing".
- Keep `maxTokens`, timeout/worker bounds, privacy/redaction, intent vocabulary,
  span containment, confidence capping, dedupe, and ordering unchanged.
- Document that candidate generation is still pack-driven and that removing the
  caps can increase provider latency/cost; the provider timeout remains the
  safety valve.
- Add tests for unlimited per-response and per-pass enrichment, while positive
  caps still work.

## Acceptance

- With the default config, every AI-eligible deterministic candidate is sent to
  the provider and every validated proposal is retained (subject to dedupe and
  the existing timeout).
- Explicit positive caps still limit output exactly as before.
- Docs/config defaults describe `0`/unset as unlimited.
- `just build`, `just test-engine`, `just lint`, and `just test-nvim` pass.

## Out of scope

- Tagging arbitrary lines or scanning the whole file.
- Removing provider timeouts, worker bounds, or confidence caps.
