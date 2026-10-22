# Correctness review — ticket 057 (uncap ai enrichment)

## Round 1

Verdict: The unlimited behavior is implemented correctly for validated
proposals and for the enrichment pass: `0` and negative values retain every
validated proposal, every AI-eligible candidate is sent, and positive caps keep
the old `take(n)` behavior after the same sort/dedupe. The loader and defaults
now agree with the docs, and the new tests are non-vacuous. One dormant default
remains inconsistent with the new contract: `CandidateValidator` still defaults
to `10` even though the configured defaults are now unlimited (`0`).

### Findings

1. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/CandidateValidator.kt:11,31` — the validator fallback defaults still cap at `10`, while `SuggestionOptions` and the server settings now default to unlimited (`0`).**

   `SuggestionOptions.maxCandidates` and `SuggestionSettings.maxCandidates` are
   now `0` (unlimited), and `SuggestionService` forwards that value explicitly.
   The DeepSeek provider also passes an explicit `proposals.size` cap. No
   current production caller therefore relies on the validator defaults, so
   there is no live behavior regression. However, `validate(...)` and
   `valid(...)` still advertise `maxCandidates = 10`; a future direct caller
   gets the old capped behavior, and the module's own default no longer matches
   the documented "0 or less is unlimited" contract. This is the same kind of
   dormant default mismatch that was called out and fixed in ticket 056, now
   inverted by the new defaults.

   Suggested fix: change both defaults to `0` so the validator's fallback
   matches `SuggestionOptions` and the documented unlimited default (direct
   `valid()` calls are unaffected because they validate a single candidate), or
   remove the defaults so callers must choose a budget. If `10` is intentionally
   retained as a last-resort safety cap, document that intent and pin it with a
   test rather than leaving it implicit.

### Verified

- `CandidateValidator.validate` now returns the full deduped validated list for
  `maxCandidates <= 0` and only applies `take(n)` for positive values. The
  filter (non-empty intents, known vocabulary, finite/in-range confidence, byte
  bounds, containment) and `distinctBy(::identity)` ordering are unchanged, and
  an empty proposal list still yields an empty result.
- `SuggestionService` sends all `aiEligible` candidates for
  `maxEnrichmentCandidates <= 0` and keeps the same reason/start/end ordering;
  positive values still take from that same sorted list. Proposal merging,
  dedupe, excerpt/redaction construction, privacy globs, provider exception
  handling, and the server timeout path are untouched.
- The server loader defaults both keys to `0`, preserves explicit `0`, and no
  longer coerces negative values; `LiveServerSuggestions` forwards both values
  into `SuggestionOptions`, so negative/zero reach the unlimited branches. Other
  bounded settings (`timeoutMs`, `workerThreads`, `maxTokens`, `maxConfidence`,
  `excerptRadiusBytes`) keep their existing clamps.
- `docs/ai-intents.md`, `docs/engine.md`, `docs/notes.md`, and
  `nvim-renderer/README.md` describe the `0` defaults and `<= 0` usage, and
  retain the pack-driven/no-arbitrary-scan and timeout safety-valve wording. No
  stale `10`/`12` candidate defaults remain outside historical review files.
- The new validator test is non-vacuous: the pre-change code returned an empty
  list for both `0` and `-3`, while the test asserts all 15 proposals in order.
  The new enrichment test is also non-vacuous: pre-change `take(0)` produced zero
  provider calls, while it now asserts 15 calls/results, and the positive-cap
  case pins three calls so the pass cap is not simply ignored.
- Note (not a correctness finding): the new
  `analysis-engine/core/suggest/src/test/kotlin/dev/cint/suggest/SuggestionServiceEnrichmentTest.kt`
  is currently untracked; it must be included in the commit.
- Read-only review: no source edit, build, test, lint, or formatter command was
  run.

### Concurs / Dissents

- Concurs: `maxCandidates <= 0` means unlimited in the validator, including
  negative values, while positive caps are unchanged.
- Concurs: `maxEnrichmentCandidates <= 0` sends every AI-eligible candidate and
  positive caps still limit provider calls in the same priority order.
- Concurs: loader defaults/zero handling, server pass-through, and default
  config documentation match the ticket.
- Concurs: empty inputs, dedupe, confidence/vocabulary/containment validation,
  privacy/redaction, and timeout behavior are preserved.
- Dissents: finding 1, the dormant validator fallback default mismatch.

### Summary

The ticket's behavioral contract is met: zero and negative caps are unlimited
for both validation and enrichment, positive caps are unchanged, the server
loader preserves `0`/negative values and defaults to unlimited, docs describe
the new defaults, and the new tests would fail against the old `take(0)`/early-
return code. The only outstanding item is that `CandidateValidator`'s fallback
defaults remain `10` instead of the new unlimited default; current callers pass
an explicit budget, so it is dormant, but it should be aligned (or explicitly
documented and pinned) before closing.

## Round 2

Verdict: Finding 1 is resolved. `CandidateValidator` now defaults to unlimited
(`0`) and the new test assertions pin the `SuggestionOptions` and
`SuggestionSettings` defaults, so the documented contract and all fallbacks
agree. No new findings; ready to close.

### Finding status

1. Resolved — `CandidateValidator.kt:11,31` now uses `maxCandidates: Int = 0`,
   matching `SuggestionOptions` and `SuggestionSettings`. `validate` still
   applies `take(n)` only for positive values, so the unlimited branch and the
   positive-cap behavior are unchanged. The renamed enrichment test now takes
   `SuggestionOptions()` defaults, asserts both are `0`, and then verifies 15
   enrichment calls/results, which would still fail against the old `take(0)`
   behavior. The server test also asserts `SuggestionSettings()` itself defaults
   both keys to `0` before checking the loader, which would fail the old
   `10`/`12` defaults.

### Verified

- No new source behavior changed beyond the validator defaults; the service,
  loader, docs, ordering/dedupe/confidence/containment/privacy/timeout, and
  positive-cap paths remain as reviewed in round 1.
- The added default assertions are non-vacuous: the pre-round-2 validator
  defaults were `10`, and the pre-change server defaults were `10`/`12`.
- The new `SuggestionServiceEnrichmentTest.kt` is still untracked in the working
  tree; it must be included in the commit. This remains a staging note, not a
  correctness finding.
- Read-only review: no source edit, build, test, lint, or formatter command was
  run.

### Concurs / Dissents

- Concurs: the validator fallback now matches the unlimited default contract.
- Concurs: defaults are pinned at both the options/settings and loader levels,
  and unlimited/positive-cap tests remain non-vacuous.
- Dissents: none.

### Summary

Round 2 resolves the sole finding: the validator, options, and server settings
all default to unlimited (`0`), and the added tests assert those defaults and
still exercise the actual unlimited and positive-cap behavior. No new issues
were found; this review concurs and the ticket can close once the untracked test
file is included in the commit.
