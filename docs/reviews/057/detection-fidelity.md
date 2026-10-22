# Detection fidelity review — ticket 057 (uncap ai enrichment)

## Round 1

Verdict: The uncapping logic is correct by inspection. `maxCandidates <= 0` and
`maxEnrichmentCandidates <= 0` are unlimited, positive values keep the old
per-response/per-pass `take(n)` behavior, candidate generation stays pack-driven,
and validation, privacy, redaction, confidence capping, dedupe, ordering, and the
timeout path are unchanged. One test-fidelity gap remains: the new unlimited
service test passes explicit zeros instead of exercising the changed
`SuggestionOptions` defaults, so a regression of those defaults would leave the
suite green. The docs describe the pack-driven and timeout/cost behavior
accurately, with one wording ambiguity noted below.

### Findings

1. **`analysis-engine/core/suggest/src/test/kotlin/dev/cint/suggest/SuggestionServiceEnrichmentTest.kt:24-29` with `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/model/SuggestionModel.kt:18-19` — the unlimited test does not pin the new default values.**

   `nonPositiveEnrichmentAndValidationMaximumsAreUnlimited` constructs
   `SuggestionOptions(providerId = provider.id, maxCandidates = 0,
   maxEnrichmentCandidates = 0)`, so it exercises the explicit-zero branch but
   never the defaults the ticket changed. If `SuggestionOptions` reverted to
   `10`/`12`, or if a future caller relied on the defaults, this test would still
   pass. The production server path is indirectly covered because
   `LiveSuggestionSettingsTest` pins the loader's `0` defaults and the service
   test pins zero behavior, but the `SuggestionOptions` default change itself is
   currently unverified.

   Suggested fix: add a default-options case, for example
   `SuggestionOptions(providerId = provider.id)` with the same 15-candidate
   assertions, or assert `SuggestionOptions().maxCandidates == 0` and
   `SuggestionOptions().maxEnrichmentCandidates == 0`. Keep the explicit-zero or
   negative case as a separate test for the `<= 0` contract.

### Verified

- `SuggestionService` filters `aiEligible` candidates, sorts by reason then byte
  span, and with `maxEnrichmentCandidates <= 0` calls the provider for every
  eligible candidate. No upstream candidate cap was found in the pack/runtime
  path; the AI pass only receives `cached.output.candidates`.
- The DeepSeek provider does not reintroduce a hidden cap: it validates with
  `maxCandidates = proposals.size.coerceAtLeast(1)`, which retains every parsed
  proposal, and `SuggestionService` then applies the configured unlimited or
  positive cap.
- `CandidateValidator.validate` applies the existing vocabulary, confidence,
  byte-bound, containment, and `distinctBy` filters before the cap. `0` and
  negative values return the full deduped list; positive values still use
  `take(n)`. `valid` now follows the same unlimited semantics.
- `SuggestionSettingsLoader` defaults both keys to `0`, preserves explicit `0`
  and negative values, and `LiveServerSuggestions` forwards them into
  `SuggestionOptions`.
- Privacy and redaction run before the provider call and are unchanged;
  provider exceptions still degrade to deterministic candidates. Timeout,
  `workerThreads`, `maxTokens`, and `maxConfidence` handling is untouched.
- Candidate merging and final ordering are unchanged; AI proposals at the same
  span survive alongside the deterministic candidate and are grouped by anchor
  downstream. No rulepack or snapshot changes are part of this ticket.
- `docs/ai-intents.md`, `docs/engine.md`, `docs/notes.md`, and
  `nvim-renderer/README.md` show the `0` defaults, `<= 0` unlimited behavior,
  pack-driven candidate generation, and the increased latency/cost with the
  timeout as the safety valve. No stale `10`/`12` default remains in current
  docs.
- The zero tests are non-vacuous against the old implementation: old
  `maxEnrichmentCandidates = 0` produced `take(0)` and no provider calls, and
  old `maxCandidates = 0` returned no validated proposals.
- Read-only review: no source edit, build, test, lint, or formatter command was
  run.

### Residuals (not findings)

- `maxCandidates` is applied once per provider response (per input candidate),
  not once per pass. The config comments say positive values "cap validated
  proposals" without naming that scope; a user setting a positive
  `maxCandidates` with unlimited enrichment can receive up to that many AI
  proposals per enriched candidate. The code matches the ticket's "exactly as
  before" requirement, but the docs could say "per provider response" for cost
  expectations.
- `CandidateValidator`'s fallback defaults remain `10`; this is already finding 1
  in `correctness.md` and is not repeated here.
- The existing all-or-nothing timeout now matters more with unlimited
  enrichment: a pack-heavy file can exhaust the pass timeout and publish zero AI
  proposals even though earlier provider calls completed. The docs already state
  that a timed-out pass yields no candidates, so this is documented behavior
  rather than a regression.
- The new `SuggestionServiceEnrichmentTest.kt` is untracked in the worktree and
  must be staged with the change (also noted in `correctness.md`).

### Concurs / Dissents

- Concurs: zero and negative values uncap both the per-response validation and
  the per-pass enrichment, and every validated AI proposal is retained subject to
  dedupe and the timeout.
- Concurs: explicit positive caps still limit enrichment calls and validated
  proposals in the same priority order as before.
- Concurs: pack-driven candidate generation, validation, privacy/redaction,
  confidence capping, ordering, and timeout behavior remain effective.
- Concurs: the docs accurately describe the new defaults, pack-driven scope, and
  cost/timeout tradeoff.
- Dissents: finding 1, the unlimited service test does not exercise or assert
  the changed `SuggestionOptions` defaults.

### Summary

The implementation meets the ticket's behavioral contract: uncapping lets every
AI-eligible pack candidate reach the provider and every validated proposal
survive, explicit positive caps keep their old semantics, and the timeouts,
privacy gates, and validation filters are untouched. Candidate generation is
still pack-driven, and the docs say so along with the cost/latency tradeoff. The
only detection-fidelity gap is that the new unlimited test hard-codes zero
instead of pinning the default `SuggestionOptions` values, so the default change
is not regression-protected; add a default-options case before closing. I
otherwise concur, with the per-response cap wording as a non-blocking
clarification.

## Round 2

Verdict: Finding 1 is resolved, no new findings were identified, and the
detection-fidelity dimension concurs. The ticket can close once the new test file
is staged.

### Finding status

1. Resolved — `SuggestionServiceEnrichmentTest.defaultEnrichmentAndValidationMaximumsAreUnlimited`
   now constructs `SuggestionOptions()`, asserts both candidate defaults are
   `0`, and then enriches 15 candidates using `defaults.copy(providerId = ...)`.
   The assertions would fail against the old `10`/`12` defaults, so the default
   contract is now regression-protected. `LiveSuggestionSettingsTest` also
   asserts `SuggestionSettings()` itself defaults both keys to `0`, and
   `CandidateValidator.validate`/`valid` now use `maxCandidates: Int = 0`, which
   also clears the round-1 validator-default residual (and the corresponding
   correctness finding).

### New findings

None. Changing the validator fallback defaults does not alter any reviewed
production path: `SuggestionService` still forwards `options.maxCandidates`,
the DeepSeek provider still passes an explicit `proposals.size` cap, and
positive values still use `take(n)`. The service, loader, docs, ordering,
dedupe, confidence/containment validation, privacy/redaction, and timeout paths
are unchanged from round 1.

### Verified

- The default test is non-vacuous: with the old `SuggestionOptions` defaults it
  would see only 12 provider calls and cap validation, and the explicit default
  assertions would fail.
- The server default assertions would fail against the old `10`/`12` settings
  defaults and loader coercion.
- Docs still match the implementation: `0`/negative is unlimited, positive caps
  retain the old per-response/per-pass behavior, candidate generation remains
  pack-driven, and the cost/timeout tradeoff is stated.
- The new `SuggestionServiceEnrichmentTest.kt` remains untracked; this is a
  staging note, not a detection finding.
- Read-only review: no source edit, build, test, lint, or formatter command was
  run.

### Concurs / Dissents

- Concurs: finding 1 is resolved by using and asserting the real defaults.
- Concurs: validator, options, and server defaults all agree on unlimited (`0`),
  while positive caps keep their previous behavior.
- Concurs: no detection, privacy, validation, or timeout regression was
  introduced by the round-2 changes.
- Dissents: none.

### Summary

Round 2 resolves the sole detection-fidelity finding: the unlimited service test
now exercises `SuggestionOptions` defaults and asserts both are `0`, the server
settings defaults are pinned, and the validator fallback now matches the
unlimited contract. No new findings were found; this dimension concurs and the
ticket can close once the untracked test file is included in the commit.
