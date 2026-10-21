---
name: deepseek intent provider
status: todo
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
  `IntentSuggestionProvider` SPI. OpenAI-compatible chat completion endpoint,
  hardcoded model (e.g. `deepseek-chat`) with env-based key
  (`DEEPSEEK_API_KEY`) and optional base URL override.
- Server owns the HTTP call, credentials, timeouts, retries, and token budget;
  no key or prompt logging.
- Input: `aiEligible` `CandidateEmit`s only (low-confidence pack gaps), bounded
  context (candidate span, enclosing function, neighboring intents, imports,
  allowed intent vocabulary). Do not send whole files by default.
- Output: JSON-constrained candidate intents with span offsets, rationale,
  confidence, provider/model/promptVersion; strict validation (span containment,
  known intents, max count, confidence range).
- Confidence policy: AI candidates may be higher than the input candidate's
  confidence but stay candidate-only and below any auto-establish path. All AI
  output requires Confirm.
- Config: extend `.cint/suggestions.yml` with provider `deepseek`, model,
  endpoint/base URL, timeout, max tokens/candidates, privacy allow/redact.
- Tests: local fake HTTP endpoint for request/response, malformed JSON,
  out-of-bounds span, unknown intent, timeout, confidence cap, key redaction,
  disabled provider, and end-to-end `:CintSuggest` → `:CintReview` → confirm.
- Docs: setup/env key, config, confidence semantics, privacy, manual test with
  real DeepSeek.

## Acceptance

- With `.cint/suggestions.yml` enabled and `DEEPSEEK_API_KEY` set,
  `:CintSuggest` sends eligible low-confidence candidates to DeepSeek and
  publishes AI candidates.
- AI candidates carry higher confidence than the input where appropriate but
  remain confirmable; nothing is established automatically.
- Confirm turns the chosen intent into an established user annotation on the
  next analysis; reject suppresses that occurrence; skip remains client-local.
- Disabled provider or missing key degrades cleanly: deterministic tags still
  publish, no candidate error spam.
- CI uses only a local fake HTTP server; no real network or key required.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Model picker, multiple providers, local models, fine-tuning.
- Auto-confirm or auto-established AI intents.
- Code completion.
- Sending whole-project context or arbitrary files.

## Notes

- 036 already provides candidate rendering, `:CintSuggest`, `:CintReview`,
  Confirm/Reject/Skip, grouping, persistence, and protocol 3 transport.
- Keep the provider behind `:suggest`; the server resolves config and injects it.
- Hardcode DeepSeek for now; keep the SPI stable for later providers.
- Confidence is diagnostic, not a threshold that promotes candidates.
