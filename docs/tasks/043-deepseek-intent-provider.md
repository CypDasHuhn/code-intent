---
name: deepseek intent provider
status: done
depends-on: [036]
reviewers: [correctness, api-fidelity]
---

## Goal

Replace the fake suggestion provider with a real, hardcoded DeepSeek provider.
Low-confidence `CandidateEmit`s are sent to DeepSeek; it may paint its own
candidate annotations with a slightly higher confidence, but the output remains
confirmable and never becomes established without user confirmation.

The Neovim review flow from 036 is reused: candidates are rendered
non-destructively, `:CintReview` offers Confirm / Reject / Skip (and alternative
selection), confirm writes a user annotation, reject suppresses the occurrence.

## Scope

- New `DeepSeekIntentSuggestionProvider` behind the existing
  `IntentSuggestionProvider` SPI. OpenAI-compatible chat-completions endpoint,
  default model `deepseek-chat`, default base URL
  `https://api.deepseek.com/chat/completions`, and env-based key
  (`DEEPSEEK_CHAT_KEY`, with `apiKeyEnv` override).
- `:suggest` owns the provider and the remote transport: the JDK `HttpClient`
  call, `Authorization` header, per-request timeout, and token budget. The server
  resolves `.cint/suggestions.yml`, injects the config and key lookup, constructs
  the provider registry, and owns pass orchestration and cancellation. There is
  no provider SDK in `core`/`api`, and no retry loop.
- Credentials: the key is read through the injected lookup, used only in the
  `Authorization` header, and never logged or published. The
  `initialize.suggestions` descriptor stays secret-free.
- Input: `aiEligible` `CandidateEmit`s only, with a bounded source excerpt
  (candidate span plus `excerptRadiusBytes` per side, default 400, adjusted to
  UTF-8 boundaries) and candidate metadata (path, language, candidate
  intent/reason/confidence, known and neighboring intents, prompt version). Do
  not send imports, enclosing functions, or whole files.
- Output: JSON-constrained candidate intents with byte span offsets, rationale,
  and confidence. Strict validation: span containment (narrower sub-spans are
  allowed), document bounds, known intents, dedupe, max count, and raw
  confidence range. Raw out-of-range or non-finite confidence is dropped; valid
  values are capped.
- Confidence policy: `maxConfidence` defaults to `0.84` and is clamped to at most
  `0.84` (a lower value is allowed). Confidence is diagnostic only;
  candidate-only is structural because AI output is a `CandidateEmit` and never
  enters the annotation store. All AI output requires Confirm.
- Privacy: `allowGlobs` gates the pass (empty allows all); `redactPatterns` are
  regexes applied to the excerpt before sending while preserving byte length and
  line breaks.
- Config: `.cint/suggestions.yml` adds provider `deepseek`, `model`, `baseUrl`,
  `apiKeyEnv`, `maxTokens`, `maxConfidence`, `excerptRadiusBytes`, `allowGlobs`,
  and `redactPatterns`, alongside the existing suggestion keys.
- Diagnostics: AI candidates carry `source = ai:deepseek`, `provider`, `model`,
  `promptVersion`, `rationale`, and the input candidate's reason; confidence is
  retained as a diagnostic value, not an auto-establish threshold.
- Tests: local fake HTTP endpoint for request/response shape, malformed JSON,
  out-of-bounds and unknown intents, sub-span/multiline/UTF-8 mapping, timeout,
  confidence cap and range rejection, privacy allow/redact/radius, key
  redaction, disabled/missing-key degradation, and end-to-end `:CintSuggest` →
  `:CintReview` → confirm.
- Docs: setup/env key, config, confidence semantics, diagnostics, privacy,
  transport ownership, and the manual live gate with real DeepSeek.

## Acceptance

- With `.cint/suggestions.yml` enabled and `DEEPSEEK_CHAT_KEY` set,
  `:CintSuggest` sends eligible low-confidence candidates to DeepSeek and
  publishes AI candidates.
- AI candidates may carry higher confidence than the input where appropriate,
  up to `maxConfidence` (at most `0.84`), but remain confirmable; nothing is
  established automatically.
- Confirm turns the chosen intent into an established user annotation on the
  next analysis; reject suppresses that occurrence; skip remains client-local.
- Disabled provider or missing key degrades cleanly: deterministic tags still
  publish, no candidate error spam, and no network call is made.
- A provider failure is a single attempt (no retries); deterministic candidates
  are published unchanged.
- CI uses only a local fake HTTP server; no real network or key required.
- Build, engine tests, lint, and nvim tests pass.
- The real-key editor/daemon path is recorded as manual gate m014; until it is
  run it stays unverified.

## Out of scope

- Model picker, multiple providers, local models, fine-tuning.
- Auto-confirm or auto-established AI intents.
- Code completion.
- Sending whole-project context or arbitrary files.

## Notes

- 036 already provides candidate rendering, `:CintSuggest`, `:CintReview`,
  Confirm/Reject/Skip, grouping, persistence, and protocol 3 transport, so no
  new nvim review UI is needed.
- Keep the provider and its HTTP transport in `:suggest`; the server resolves
  config, injects the key lookup, and orchestrates the pass. This is the
  implemented module boundary, not a server-side HTTP adapter.
- DeepSeek is the only remote provider in this slice; keep the SPI stable for
  later providers. The model and base URL are overridable but there is no
  provider picker.
- No retries: one request is attempted, and missing keys, non-200 responses,
  timeouts, and malformed output fall back to the deterministic candidates.
- Context is the bounded excerpt plus metadata; imports and enclosing functions
  are intentionally not collected or sent.
- The confidence cap is at most `0.84`, and `maxConfidence` may only lower it.
  Confidence is diagnostic, not a threshold that promotes candidates;
  candidate-only is enforced by the `CandidateEmit` channel.


## Outcome (2026-10-21)

Implemented the hardcoded DeepSeek provider behind the `:suggest` SPI. It reads
`DEEPSEEK_CHAT_KEY`, sends a bounded redacted excerpt plus candidate metadata to
DeepSeek, validates JSON output against containment/vocabulary/confidence/count,
and emits candidate-only annotations with provenance and a `<=0.84` diagnostic
cap. Missing key, non-200, timeout, cancellation, and malformed output fall back
to deterministic candidates. Config supports provider/model/baseUrl/apiKeyEnv/
maxTokens/maxConfidence/excerptRadiusBytes/allowGlobs/redactPatterns, and the
capability descriptor is secret-free. The existing nvim `:CintSuggest` /
`:CintReview` Confirm/Reject/Skip flow is reused. A provider-level live smoke
test against DeepSeek succeeded; the full live editor/daemon path remains manual
as m014. Correctness and api-fidelity reviews closed after two rounds; build,
test-engine, lint, and test-nvim pass.
