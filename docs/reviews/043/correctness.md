# Correctness review — ticket 043 (DeepSeek intent provider)

## Round 1

Verdict: Not ready. The core path is sound: the request is OpenAI-compatible, the
excerpt is bounded, output stays candidate-only, and missing key / non-200 /
timeout / malformed responses degrade to the deterministic candidates. Two
correctness findings and the still-missing live manual gate remain. Read-only
review of the uncommitted tree; no build, test, formatter, or source edit was run.

### Findings

1. **Model confidence outside `0..1` bypasses `CandidateValidator`'s range rule.**

   `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/DeepSeekIntentSuggestionProvider.kt:142-154`
   with `:79-85` and
   `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/CandidateValidator.kt:18`.

   `parse` applies `maxOf(input.confidence, model.confidence).coerceIn(0.0, cap)`
   before the proposal reaches `CandidateValidator`. A model value of `2.0`
   becomes `0.84` and a negative value becomes the input confidence, so the
   validator's `confidence in 0.0..1.0` check never sees the raw value. The
   ticket requires strict confidence-range validation, so malformed confidence is
   silently normalized instead of dropped. The cap itself works and the result is
   still candidate-only, but the validator contract is not actually enforced on
   model output.

   Fix: reject non-finite or out-of-range raw confidence before applying the cap,
   then apply `maxOf`/`coerceAtMost` to valid values. Add tests for `> 1`, `< 0`,
   and non-finite values; if normalization is intentional, document it and pin it
   with a test instead.

2. **The new excerpt/span mapping is untested for sub-spans, multiline spans, and
   UTF-8 boundaries, and cancellation is only tested provider-side.**

   `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/SuggestionContext.kt:34-58,63-89`
   with
   `analysis-engine/core/suggest/src/test/kotlin/dev/cint/suggest/DeepSeekIntentSuggestionProviderTest.kt:46-73,161-183`
   and
   `analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/LiveSuggestionsTest.kt:917-991`.

   `SourceExcerpt.spanFor` maps model byte offsets to line/column positions, and
   the AI is explicitly allowed to return a span narrower than the input
   candidate. Every positive test uses the exact input span (`0..4` or the
   candidate's own start/end); only out-of-bounds rejection is tested. There is no
   test for a valid sub-span, a multiline excerpt, a non-ASCII boundary, or a
   candidate at the document edge. Because confirmation writes `candidate.span`
   into the user annotation, a mapping regression would persist a wrong span.
   Timeout is covered by a provider-unit test, but no DeepSeek-specific
   cancellation test exists; the server cancellation tests use stub providers.

   Fix: add positive tests for a narrower span and for multiline/non-ASCII text,
   assert the published line/column and byte span, and add a server-level test
   that cancels or times out a blocking DeepSeek endpoint and asserts no partial
   candidates are published.

3. **The required live DeepSeek manual check is absent (overlaps api-fidelity
   finding 3).**

   `docs/manual-test.md:6-16` with
   `docs/tasks/043-deepseek-intent-provider.md:41-42` and
   `docs/ai-intents.md:278-279`.

   There is no `043` row in the manual gate, although the ticket requires a
   manual test with real DeepSeek and the workflow requires non-automatable
   acceptance checks to be recorded there. All committed tests use a loopback
   fake endpoint, which is correct for CI. No live smoke test source exists in the
   tree (only a stale empty build-report directory), so there is no current
   key-leak path to audit. If one is added, it must read `DEEPSEEK_CHAT_KEY` from
   the environment and must not echo the key, enable shell tracing, print request
   headers/body, or commit a config containing the key.

   Fix: add a manual-test row naming `043`, with key-safe steps for
   `:CintSuggest` → AI provenance → confirm/reject and for missing-key
   degradation.

### Concurs / Dissents

- Concurs: the request shape is OpenAI-compatible (`model`, `temperature`,
  `max_tokens`, `messages`, Bearer auth) and the response path reads
  `choices[0].message.content`, tolerates standard markdown fences/prose, and
  returns no proposals for non-200, malformed JSON, or a non-array shape.
- Concurs: the excerpt is bounded to the candidate plus 400 bytes per side,
  clamped to the document, and adjusted off UTF-8 continuation bytes; only the
  excerpt, candidate metadata, known/neighboring intents, path/language, and
  prompt version are sent, not the whole file by default.
- Concurs: `CandidateValidator` integration enforces containment in the input
  candidate, document bounds, known intents, dedupe, and the configured
  `maxCandidates` at the service layer; AI output has `source = "ai:deepseek"`,
  `aiEligible = false`, and remains a `CandidateEmit`.
- Concurs: confidence is capped at the configured `maxConfidence` and no
  auto-establish path exists; confirmation still goes through the existing
  user-annotation writer.
- Concurs: a missing key, non-200 response, HTTP timeout, or malformed content
  returns no proposals and leaves deterministic candidates untouched; the
  provider catches `InterruptedException`, restores the interrupt flag, and the
  server's outer `timeoutMs` cancels the pass without publishing partial results.
- Concurs: config gating and provider selection are correct. `enabled: false`
  refuses `cint/suggest`, an enabled file without a provider resolves to
  `deepseek`/`deepseek-chat`, and `fake` remains available. `LiveServer` wires the
  registry from `SuggestionSettings`; the disabled and missing-key paths make no
  network call.
- Concurs: no secret or prompt logging was added. The key appears only in the
  `Authorization` header, the capability descriptor remains secret-free, and the
  tests assert the key is not in the request body. Tests use only loopback
  endpoints.
- Concurs: no established annotation path is touched by the diff; the e2e test
  confirms through the existing user-annotation path.
- Dissents: findings 1-3.

### Residual observations (not filed)

- `promptVersion` is caller-overridable, but `SYSTEM_PROMPT` is a fixed constant;
  an override records a version that does not match the prompt actually sent.
- A single-line fenced response with no newline after the opening fence is not
  unwrapped and degrades to no proposals; this is safe but should be covered if
  fence handling is meant to be general.
- Project-local `.cint/suggestions.yml` controls both `baseUrl` and `apiKeyEnv`,
  so an untrusted project could direct the configured key to an arbitrary host
  when suggestions are enabled. Consider documenting that trust boundary or
  restricting the endpoint.
- The ticket mentions server-owned retries, but no retry path exists; failures
  fall back after one attempt.

### Summary

The DeepSeek provider is directionally correct and preserves the important safety
properties: bounded excerpt, strict candidate containment, candidate-only output,
no auto-establishment, clean fallback on missing key/HTTP failure/timeout/malformed
output, secret-free capability reporting, and no change to the established
annotation path. It still needs work on confidence-range enforcement (raw
out-of-range model values are clamped before validation), on tests for the new
sub-span/UTF-8 span mapping and DeepSeek-specific cancellation, and on the
required real-DeepSeek manual gate; the live smoke must be documented and must not
print or log the key.

## Round 2

Verdict: the loop can close. Read-only review of the updated uncommitted tree; no
build, test, formatter, or source edit was run. All round-1 findings are addressed
and no new blocking correctness finding remains.

Verified:

- Raw confidence rejection: `DeepSeekIntentSuggestionProvider.kt:147-148` rejects
  non-finite and out-of-`0..1` model values before the cap; the new test covers
  `1.1`, `-0.1`, `1e999`, `-1e999`, and `NaN`.
- Span mapping: the unit tests now cover a narrower sub-span, a multiline
  excerpt, a non-ASCII excerpt boundary, and candidates at the document start and
  end; the server e2e test publishes absolute bytes and line/column for a
  sub-span in a source containing a non-ASCII character.
- Timeout/cancel: the provider timeout unit test and the new blocking-endpoint
  server test both produce a cancelled/timeout response with no AI candidates
  published. The provider restores the interrupt flag on `InterruptedException`;
  the existing blocking-provider test still covers explicit client cancellation.
- Prompt version default: `CandidateContext.promptVersion` is now nullable,
  `SuggestionService` resolves the provider's default, and both providers fall
  back to `defaultPromptVersion`; the test asserts `deepseek-intent-v1` is both
  sent and recorded when the context omits it.
- Cap clamp: the settings loader clamps `maxConfidence` to `0..0.84` and the
  provider re-clamps its cap to the same ceiling; tests cover both a config value
  of `1.0` and a directly constructed provider config of `1.0`.
- Capability provider/model: the server now resolves the selected provider's
  model for `initialize.suggestions`; tests cover enabled-without-provider
  (`deepseek`/`deepseek-chat`) and explicit `fake`/`fake-intent-v1`.
- Privacy: `allowGlobs` gates the service pass (empty allows all) and invalid
  globs fail closed; `redactPatterns` stars matches while preserving byte length
  and line breaks. Service tests cover gating, redaction, and excerpt radius, and
  the server passes all three settings through.
- Manual gate and key safety: `m014` now exists for 043 with explicit instructions
  not to print or log the key, enable shell tracing, echo request headers/bodies,
  or commit a config containing it. No live smoke source or key material is
  committed; the provider logs nothing and the tests assert the key is absent from
  the request body. The historical provider-level smoke cannot be audited from
  the tree, but the documented procedure is key-safe.
- No established annotation path is touched; the e2e confirm test still writes a
  user annotation through the existing path.

Concurs/dissents:

- Concurs: round-1 correctness findings 1-3 are addressed, and the updated docs
  match the implemented provider, privacy, and manual-gate behavior.
- Dissents: none.

Residual observations (not filed):

- A YAML `maxConfidence: .nan` survives `coerceIn` (NaN comparisons are false),
  so the diagnostic cap can be bypassed by a non-finite config value. Normal
  finite values, including `1.0`, are clamped correctly; candidate-only remains
  structural regardless.
- Invalid `redactPatterns` regexes are silently skipped (fail-open), while invalid
  `allowGlobs` fail closed. Validating or rejecting bad redaction patterns would
  make the privacy boundary fail closed.
- The docs say whole files are never sent; a small file or a large
  `excerptRadiusBytes` can make the excerpt equal the whole file. "Not by default"
  would be more accurate, or the radius could be capped.
- The provider-level live smoke is not reproducible from the tree; `m014` records
  it as unverified, which is the correct manual-gate state.

### Summary

All round-1 correctness findings are resolved: raw confidence is rejected before
validation, the new excerpt/span mapping is tested for sub-spans, multiline,
UTF-8, and document edges, DeepSeek timeout is exercised end to end without
partial publishes, prompt-version and capability defaults are provider-correct,
the cap is clamped for finite configs, and allow/redact privacy controls are wired
through the service. The documented manual gate is present and explicitly
key-safe, with no live smoke source or key material committed, and the established
annotation path is unchanged. The loop can close; the remaining items are
non-blocking hardening observations.
