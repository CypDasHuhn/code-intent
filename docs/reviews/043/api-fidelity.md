# API-fidelity review — ticket 043 (DeepSeek intent provider)

## Round 1

Verdict: The candidate-only contract is preserved: DeepSeek output is still a
`CandidateEmit`, never an `Annotation`, `aiEligible` is cleared, and confidence
remains diagnostic. The `initialize.suggestions` descriptor stays secret-free
and protocol 3 is unchanged; no frozen `api`/`Annotation` file is touched. The
main gaps are contract/documentation fidelity: the network provider is placed in
`:suggest` despite the ticket assigning the HTTP call to the server, the
ticket's context and privacy scope is only partially implemented while
`docs/ai-intents.md` still describes the fuller context, the required real
DeepSeek manual test is absent, and two SPI/settings defaults are not
provider-neutral.

### Findings

1. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/DeepSeekIntentSuggestionProvider.kt:30-86`
   with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/SuggestionProviders.kt:11-32`,
   `docs/tasks/043-deepseek-intent-provider.md:25`, and
   `docs/architecture.md:270-288,313-314` — provider placement contradicts the
   ticket scope and leaves the architecture doc stale.**

   The ticket says the server owns the HTTP call, credentials, timeouts, retries
   and token budget, but the implementation performs the `HttpClient` call,
   Authorization header construction and request timeout inside `:suggest`; the
   server builds the provider, injects config/key lookup, and applies its own
   future timeout. Ticket Notes also say
   “keep the provider behind `:suggest`”, so the ticket is internally
   inconsistent. `docs/architecture.md` was not updated: it still says `suggest`
   contains only the fake provider and that the server owns credentials and the
   fake provider instance, and it still lists real remote providers as deferred.
   That makes the module boundary undocumented and the ticket acceptance
   ambiguous. Keeping the SPI/provider in `:suggest` is defensible because
   `:suggest` is the AI module, but the network egress and credential handling
   need to be an explicit, documented decision.

   Suggested fix: either move only the HTTP transport into a server-side adapter
   while leaving the SPI and fake provider in `:suggest`, or amend the ticket
   scope and `docs/architecture.md` to state that `:suggest` owns the remote
   transport and that the server owns configuration/key injection and
   orchestration. Update the module/dependency table to match the chosen
   boundary.

2. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/SuggestionContext.kt:11,15-16`
   with `SuggestionService.kt:49-65`, `DeepSeekIntentSuggestionProvider.kt:88-127`,
   `docs/tasks/043-deepseek-intent-provider.md:27-29,36-37`, and
   `docs/ai-intents.md:118-131,220-247` — the advertised context and privacy
   contract is only partially implemented.**

   The ticket and the AI-service diagram promise an enclosing function, imports,
   neighboring intents, candidate span and allowed vocabulary, plus privacy
   allow/redact settings. `CandidateContext.enclosingFunction` is never
   populated, imports are never collected or sent, and `SuggestionSettings` has
   no privacy allow/redact keys. The request does send the excerpt, path,
   language, known intents, neighboring intents and candidate metadata, but the
   docs diagram still lists the missing fields, and the “Config and privacy”
   section lists no privacy controls at all. This is a contract/ticket mismatch,
   not just an implementation detail: callers cannot rely on the documented
   context model and users cannot configure the promised privacy boundary.

   Suggested fix: either populate `enclosingFunction` and imports (and add
   allow/redact settings) or narrow the ticket and `docs/ai-intents.md` to the
   actually sent excerpt model. Whichever is chosen, make the config key list and
   the context diagram match the request payload.

3. **`docs/manual-test.md:6-16` with `docs/tasks/043-deepseek-intent-provider.md:41-42`
   and `docs/workflow.md:97-103` — the required live DeepSeek manual test is
   missing.**

   The ticket explicitly requires a manual test with real DeepSeek, and the
   workflow requires non-automatable acceptance checks to be recorded in
   `manual-test.md` with the ticket id. There is no entry for 043, so the live
   key/endpoint/candidate round trip is not tracked. CI correctly uses only a
   local fake endpoint, but that does not replace the manual gate.

   Suggested fix: add a manual-test row (for example m014) naming 043 and
   covering a real `DEEPSEEK_CHAT_KEY`, a project-local `.cint/suggestions.yml`,
   `:CintSuggest`, AI candidate provenance, confirm/reject, and clean degradation
   with a missing key.

4. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/SuggestionSettings.kt:53-55`
   with `DeepSeekIntentSuggestionProvider.kt:136-154`, `docs/engine.md:276-280`,
   and `docs/ai-intents.md:235` — the confidence cap documentation and the
   configurable range disagree.**

   The loader clamps `maxConfidence` to `0..1`, so a user can set `1.0`, while
   the docs say the default `0.84` keeps AI candidates “below auto-establish”
   and the ticket says AI confidence stays below any auto-establish path. The
   provider also applies `maxOf(input confidence, model confidence)` and then
   coerces down to the cap, so an input candidate above the cap can get a lower
   AI confidence. Candidate-only is still structurally enforced by the
   `CandidateEmit` channel, but the cap is presented as a safety boundary when
   it is user-raisable and partly a display clamp.

   Suggested fix: either clamp `maxConfidence` to the safe ceiling (for example
   `0..0.84`) and test the clamp, or document that confidence is diagnostic only,
   that candidate-only is enforced by the channel rather than the number, and
   that the cap can only lower the reported value. Avoid describing the cap as an
   auto-establish guard until such a path exists.

5. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/SuggestionContext.kt:14`
   with `DeepSeekIntentSuggestionProvider.kt:93,158` and
   `SuggestionService.kt:40` — the provider-neutral SPI still defaults
   `promptVersion` to the fake provider's version.**

   `CandidateContext.promptVersion` defaults to `FAKE_PROMPT_VERSION`. The server
   path masks this by always supplying `options.promptVersion ?:
   provider.defaultPromptVersion`, but a direct or future caller of the DeepSeek
   provider that constructs a context without a prompt version sends and records
   `fake-prompt-v1`. The new `defaultPromptVersion` hook exists precisely to make
   this provider-specific, so the context default undermines the SPI contract.

   Suggested fix: make `CandidateContext.promptVersion` nullable or required and
   have `SuggestionService`/the provider resolve the provider default; add a test
   that a DeepSeek context without an explicit prompt version carries
   `deepseek-intent-v1`.

### Concurs / Dissents

- Concurs: no frozen `api` or `Annotation` change. `git status`/`git diff` show
  no changes under `analysis-engine/core/api`; `CandidateEmit` already carried
  the optional provenance fields, and the provider only populates them.
- Concurs: AI output remains candidate-only. AI proposals are `CandidateEmit`s
  with `source = "ai:deepseek"`, `aiEligible = false`, and the input `reason`;
  they are validated against the input candidate, known intents, document
  bounds, confidence range and configured count before publishing. Confirmation
  still goes through the existing user-annotation path and no AI path writes an
  established annotation.
- Concurs: the `initialize.suggestions` descriptor is secret-free. It still
  exposes only `{enabled, trigger, provider, model, knownIntents}` from
  `effectiveProvider`/`effectiveModel`; `apiKeyEnv`, the key value and
  `baseUrl` are not published, and the existing capability test pins the key
  set. The new settings/loader tests also do not print or inspect the key value.
- Concurs: the live protocol remains compatible. Protocol stays 3, the
  descriptor keys and candidate/decision messages are unchanged, and only the
  provider/model values become `deepseek`/`deepseek-chat` by default when
  enabled without an explicit provider. The change is additive for old clients
  and the disabled path still gates `cint/suggest`.
- Concurs: candidate provenance is complete on the wire for the normal path:
  `source`, `provider`, `model`, `promptVersion`, `rationale`, and the copied
  `reason` are populated, and the server already serializes optional provenance
  fields without changing the candidate identity contract.
- Concurs: config defaults match the docs for `enabled`, `trigger`, provider,
  model, `maxCandidates`, `timeoutMs`, `workerThreads`, `apiKeyEnv`, `baseUrl`
  and `maxTokens`; a missing file stays disabled with a null provider/model, and
  an enabled file without a provider resolves to DeepSeek.
- Dissents: findings 1-5.

### Residuals (not filed)

- When `provider: fake` is enabled without a model, `initialize.suggestions.model`
  is null even though the active fake provider's model is `fake-intent-v1`,
  because `effectiveModel` only resolves DeepSeek defaults. The descriptor should
  be derived from the selected provider rather than from a DeepSeek-specific
  helper if it is meant to describe the active provider.
- `SuggestionOptions.maxCandidates` remains per input candidate, not a total per
  suggest pass; DeepSeek proposals are limited by the outer service validation,
  so a document with many candidates can exceed the configured total.
- AI proposals with the same intent/span/reason as a deterministic candidate
  share the same candidate identity and replace its source/provenance in the
  merged list. This pre-dates 043 but becomes more likely with a model that can
  echo the pack intent.
- The ticket mentions server-owned retries, but no retry path or configuration
  exists; failures fall back to deterministic candidates after one attempt.
- There is no test for an enabled, default-provider (`deepseek`) capability
  descriptor; the existing secret-free test covers disabled and explicit `fake`.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.

### Summary

The DeepSeek implementation is directionally sound and preserves the most
important contracts: no frozen API/Annotation change, no secret in the
capability descriptor or request body, candidate-only confidence semantics, and
additive protocol-3 compatibility. It is not yet contract-complete because the
provider's network placement conflicts with the ticket and the stale
architecture doc, the documented context/privacy scope is only partly
implemented, the real-DeepSeek manual test is missing, and the configurable
confidence cap and SPI prompt-version default need to be reconciled with the
documented contract.

## Round 2

Verdict: concurs; all five Round 1 findings are resolved and the API-fidelity
loop can close. The ticket, architecture/engine/ai-intents/notes/nvim docs, and
the code now agree that `:suggest` owns the DeepSeek provider and HTTP transport
while the server resolves config, injects the key lookup, constructs the
registry, and orchestrates; the context/privacy contract is narrowed to a
bounded excerpt plus metadata and is backed by `excerptRadiusBytes`,
`allowGlobs`, `redactPatterns`, UTF-8-safe span mapping, and tests; m014 is
recorded as the unverified real-key editor/daemon gate; `maxConfidence` defaults
to and is clamped at `0.84` with diagnostic-only semantics and range/cap tests;
`CandidateContext.promptVersion` is nullable with provider
`defaultPromptVersion` resolution and a DeepSeek-default test; and the capability
descriptor derives provider/model from the selected registry entry (default
DeepSeek and explicit fake) while staying secret-free. I concur that no frozen
API/`Annotation` shape or protocol-3 wire contract changed and AI output remains
candidate-only. No blocking dissent; only non-blocking nits remain: the
architecture dependency line still says `:suggest` depends on `api`/`core` only
although its build now adds Jackson (not a provider-SDK leak), the ai-intents
diagram attributes the confidence cap to `CandidateValidator` although the
provider applies it, and a non-finite configured `maxConfidence` is not
explicitly rejected (diagnostic-only, suitable for a follow-up).
