# AI Intent Candidates — Architecture

AI here means **intent inference**, not code completion. The deterministic
pipeline remains the source of truth; AI only fills structural gaps and produces
reviewable candidates.

## Boundary

```
source → deterministic pipeline → established annotations → publish
                                        │
                                        └→ rule secondary emits
                                             → optional AI enrichment
                                             → candidates
                                             → review → confirm/reject
```

- AI runs after terminal composition and user annotations.
- AI never runs inside the scheduler, owns an intent, or changes a rulepack.
- AI output is candidate-only. It never affects composition, purity, or
  established tags.
- Confirmed candidates enter through the existing user-annotation path and are
  re-evaluated deterministically on the next analysis.

## Rule output: one list, two emit types

Rules return established emits and secondary candidates in one pass. The split
is type-level, not a boolean flag.

```kotlin
sealed interface RuleEmit {
    val intents: Set<Intent>
    val span: Span
    val startByte: Int
    val endByte: Int
    val source: String
    val confidence: Double
}

data class Emit(
    override val intents: Set<Intent>,
    override val span: Span,
    override val startByte: Int,
    override val endByte: Int,
    override val source: String,
    override val confidence: Double,
    val certifiesReturn: Boolean = false,
) : RuleEmit

data class CandidateEmit(
    override val intents: Set<Intent>,   // normally setOf(rule.owns)
    override val span: Span,
    override val startByte: Int,
    override val endByte: Int,
    override val source: String,         // "candidate", "rule:<id>", or "ai:<provider>"
    override val confidence: Double,
    val reason: CandidateReason,         // GAP, LOW_CONFIDENCE, PACK_DOMAIN
    val aiEligible: Boolean = true,
    val rationale: String? = null,
    val promptVersion: String? = null,
    val provider: String? = null,
    val model: String? = null,
) : RuleEmit

interface Rule {
    fun run(view: View): List<RuleEmit>
}
```

`Emit` stays the established type. `Matcher` returns `List<RuleEmit>`;
`Emit.of`/`Emit.declaration` stay established-only, and `CandidateEmit.of`
builds candidates. `toContribution` accepts only `Emit`, so candidates cannot
leak into the annotation store.

This changes the frozen rule contract: bump `CINT_API_VERSION` to 5 and update
the bundled packs, fixtures, and contract tests.

## Scheduler behavior

```kotlin
data class RuleOutcome(
    val established: List<Emit>,
    val candidates: List<CandidateEmit>,
)

data class AnalysisOutput(
    val annotations: List<Annotation>,
    val candidates: List<CandidateEmit>,
)
```

- `settle` partitions the rule output. Established emits become contributions;
  candidates are collected separately.
- Candidates do not enter `AnnotationStore`, composition, purity, or the `reads`
  graph.
- A rule may only emit its own intent as a candidate.
- For fixpoint rules (`owns in reads`), only established output feeds
  convergence; keep candidates from the final settled iteration.
- User-confirmed candidates are user contributions and participate as
  established source facts on the next analysis.

## Candidate sources

1. **Rulepack secondaries** — deterministic, domain-aware candidates. A pack
   marks spans where it stopped being certain, e.g. an unclassified block inside
   a validation-like guard. Packs decide `aiEligible`.
2. **AI secondaries** — async enrichment of `aiEligible` candidates, optionally
   plus structural gaps produced by core. AI may propose alternatives from the
   known vocabulary.
3. **Core structural gaps** (later) — empty-intent candidates for untagged
   regions that no pack owns.

AI does not scan arbitrary code. It only processes candidates supplied to it,
which keeps cost and privacy bounded and leaves trigger semantics with the pack.

## AI service

```
CandidateEmit (aiEligible)
  → ContextBuilder
      - candidate source span + bounded source excerpt (excerptRadiusBytes)
      - candidate metadata: intents, reason, confidence, path, language
      - known + neighboring intent vocabulary
      - prompt version
  → IntentSuggestionProvider (SPI)
  → CandidateValidator
      - span inside the input candidate and document
      - intent in allowed vocabulary
      - raw confidence range; confidence cap <= 0.84; max candidates
  → AI CandidateEmit(s)
```

- Provider SPI is one-shot and provider-agnostic: fake/local/remote providers.
  The deterministic fake provider stays available for tests and offline config;
  the DeepSeek provider performs a JDK `HttpClient` chat-completions call.
  `:suggest` owns the provider and its remote transport; the server resolves
  config, injects the key lookup, and orchestrates the pass. There are no
  retries: one attempt per eligible candidate, then fallback to the
  deterministic candidates.
- Context is a bounded excerpt plus metadata. Imports and enclosing functions
  are not collected or sent, and whole files are never sent.
- Prompt is versioned and structured; code and comments are untrusted data. It
  asks for several plausible alternatives, including lower-confidence guesses,
  within the candidate span rather than requiring certainty. DeepSeek completion
  uses a nonzero temperature (`0.3`) and a `1024`-token default budget.
- `CandidateValidator` enforces intent vocabulary, confidence range, byte bounds
  and containment inside the input candidate, dedupes, and applies
  `maxCandidates` when it is positive. A value of `0` or less means unlimited,
  so every validated proposal survives. Only the top
  `maxEnrichmentCandidates` deterministic candidates (GAP first, then
  LOW_CONFIDENCE, then PACK_DOMAIN) are sent to the provider per pass when that
  setting is positive; `0` or less sends every AI-eligible deterministic
  candidate. Candidate generation is still pack-driven: AI enrichment never
  scans arbitrary lines. Raw out-of-range or non-finite model confidence is
  dropped before the cap. Invalid output is dropped. No tool calls, no edits.
- AI output is a `CandidateEmit` (`source = ai:deepseek`), so candidate-only is
  structural. It never becomes an established annotation without Confirm, and
  its confidence is diagnostic rather than a promotion threshold.
- `SuggestionService` merges AI proposals with the deterministic candidate
  instead of replacing it, groups candidates by anchor (content hash + byte
  span), and keeps alternatives for one span. Confirming any alternative
  resolves the entire anchor group.
- Async and cancellable with one in-flight request per document. The provider
  call and suggestion pass use bounded `workerThreads` pools and are interrupted
  after `timeoutMs`; both pools shut down with the server. The trigger is manual
  in this slice; idle triggering is a follow-up.
- AI disabled by default. Pack candidates still work without AI.

## Candidate lifecycle

- `candidate` — proposed, shown non-destructively.
- `confirmed` — user accepted; written to `.cint/user-annotations.json` under
  the shared `.cint/user-annotations.json.lock` directory (also used by the nvim
  annotation writer) and re-analyzed as an established `user` source. Confirming
  one alternative resolves the whole anchor group, so the sibling alternatives
  stop being published and are no longer confirmable. A confirm record is kept
  in `.cint/suggestions.json` as audit history. If the user annotation is
  removed, the next analysis re-proposes the candidate unless it was rejected.
- `rejected` — persisted as suppression in `.cint/suggestions.json`, keyed by
  path + anchor/content hash + byte span + sorted intents + reason.
- `skipped` — hidden for the requesting client/session only; not persisted.
- `stale` — anchor no longer matches; the decision is refused and the candidate
  is dropped.

Each `.cint/suggestions.json` decision stores `candidateId`, `path`,
`contentHash`, `intents`, `reason`, `decision`, and `createdAt`, plus optional
`anchorId` and a `startLine`/`startCol`/`endLine`/`endCol` span snapshot. Older
records without those optional fields still load, with the span defaulting to
zeros. Established annotations and the frozen `Annotation` API are unchanged.

## Protocol

Protocol 3 additions:

- `initialize.suggestions` — secret-free capability descriptor
  `{enabled, trigger, provider, model, knownIntents}`. `enabled` comes from
  `.cint/suggestions.yml` and defaults to false.
- `cint/publishCandidates` — `uri`, `version`, `analysisRevision`,
  `suggestionRevision`, flat `candidates`, and `groups`. A group is one anchor
  (`anchorId`, `path`, `contentHash`, span/positions) with its alternative
  candidates. The message is also emitted with empty `candidates`/`groups` after
  a decision resolves everything.
- `cint/suggest` — `{uri, version, requestId}`; asynchronous, one pass per
  document, returns `{accepted, cancelled, count}` or a disabled/stale/busy
  result. A provider timeout returns `cancelled: true` with `timeout: true` and
  publishes nothing. `cint/suggest/cancel` takes `{uri?, requestId?}`.
- `cint/candidates/decide` — `{uri, version, candidateId, decision}` with
  confirm/reject/skip; the server validates version and anchor hash, persists,
  re-analyzes on confirm, and republishes. Confirming one alternative resolves
  the whole anchor group, so siblings are not published afterward. Confirm and
  reject are project-global and republish the URI to every client that has it
  open, so all owners of the same URI drop the resolved group or suppressed
  candidate; skip remains per-client/session and is not persisted.

Candidates carry `candidateId`, `path`, `contentHash`, `source`
(`rule:<id>` or `ai:<provider>`), confidence, reason, `aiEligible`, span/bytes,
and optional `rationale`/`promptVersion`/`provider`/`model`. Stale document
versions and older revisions are discarded. `analysisRevision` is allocated with
the analysis and config snapshot under the same lock, so it stays monotonic with
config revision; candidate publishes older than the cached analysis or the last
published candidates are dropped, and a suggest pass is discarded if candidate
state changes before publish.

## Renderer UX

- Outside review mode, candidate style is non-destructive: dotted underline,
  muted sign/text, never conceal. Status count, e.g. `CodeIntent: 3 unsure`.
- `:CintSuggest` runs a pass. `:CintReview` toggles buffer-local review mode: each
  candidate range gets a visible review highlight and a true virtual line above
  it with intent name(s), confidence, source/reason, and a concise rationale.
  Toggling off restores the passive rendering.
- `:CintReviewAccept [intent]` confirms the candidate range under the caret. With
  alternatives, the intent argument picks the matching alternative; without it,
  a picker lists the candidates under the caret (highest confidence first). `:CintReviewDeny` rejects every
  alternative in that candidate's group so the group clears. Both report a
  missing candidate, keep review mode active, and rely on the server republish to
  update marks.
- Confirming an alternative resolves the whole anchor group; the siblings
  disappear with the republished (possibly empty) candidate list.
- `:CintSuggest` gate errors distinguish a protocol below 3, a missing
  suggestions descriptor, and `suggestions.enabled: false` in
  `.cint/suggestions.yml`. The last case is never auto-enabled.
- AI unavailable, timed out, or over budget → no candidates, deterministic tags
  unaffected. A timeout cancels the pass without publishing partial results.

## Config and privacy

`.cint/suggestions.yml` (implemented keys):

```yaml
suggestions:
  enabled: false
  trigger: manual        # manual; idle is a follow-up
  provider: deepseek     # "deepseek" by default when enabled and unset; "fake" stays available
  model: deepseek-chat
  maxCandidates: 0                  # 0 or less is unlimited; positive caps validated proposals
  maxEnrichmentCandidates: 0        # 0 or less sends every AI-eligible candidate per pass
  promptVersion: deepseek-intent-v2
  timeoutMs: 10000       # whole pass/provider interrupt/HTTP timeout, minimum 1
  workerThreads: 2       # bounded suggestion/provider pools, clamped 1..8
  apiKeyEnv: DEEPSEEK_CHAT_KEY
  baseUrl: https://api.deepseek.com/chat/completions
  maxTokens: 1024
  maxConfidence: 0.84    # diagnostic cap, clamped to at most 0.84
  excerptRadiusBytes: 400
  allowGlobs: []         # empty allows every path; globs gate the AI pass
  redactPatterns: []     # regexes applied to the excerpt before sending
```

A missing file defaults to disabled/manual, unlimited candidates, unlimited
enrichment candidates, a 10000 ms timeout, and 2 worker threads. Uncapping
enrichment can increase provider latency and cost because every AI-eligible
pack candidate reaches the provider; the provider timeout remains the safety
valve. When disabled,
`initialize.suggestions.enabled` is false and
`cint/suggest` refuses, but pack candidates still publish. Both executor pools
are bounded by `workerThreads` and stop on server/process close, not on the
protocol `shutdown` request.

The server resolves `.cint/suggestions.yml` and injects the config and key lookup;
`:suggest` owns the provider and the HTTP call. The key is read from
`DEEPSEEK_CHAT_KEY` (or `apiKeyEnv`), appears only in the `Authorization` header,
and is never published in the secret-free capability descriptor or logged.
`baseUrl` and `apiKeyEnv` are project-controlled, so enabling suggestions trusts
the project config. A missing key, non-200 response, HTTP timeout, or malformed
output produces no AI proposals and leaves deterministic candidates untouched;
the provider makes one attempt and does not retry.

Only `excerptRadiusBytes` per side of the candidate (default 400, adjusted to
UTF-8 boundaries) is sent, plus candidate metadata, known/neighboring intents,
path/language, and the prompt version. `allowGlobs` gates the pass; an empty list
allows all paths. `redactPatterns` regex matches are starred out in the excerpt
before it is sent while preserving byte length and line breaks. Imports and
enclosing functions are not collected or sent. Prompt/response/key logging is
off by default.

AI candidates expose diagnostics on the wire: `source = ai:deepseek`,
`provider`, `model`, `promptVersion`, `rationale`, and the input candidate's
reason. `maxConfidence` defaults to `0.84`, can only lower that ceiling, and is
diagnostic; candidate-only is enforced because AI output is a `CandidateEmit`.

## Modules

- `api` — `RuleEmit`, `Emit`, `CandidateEmit`, v5 rule contract.
- `core` — scheduler partition, `AnalysisOutput`, candidate identity; no AI.
- `rulepacks` — deterministic secondaries where domain-specific.
- `suggest` — context model, provider SPI/registry, fake and DeepSeek providers,
  validator, merge, and grouping. Depends on `api` and `core`; the remote
  provider uses the JDK `HttpClient` only.
- `server` — config/key injection, async orchestration, settings, protocol,
  persistence, and provider construction from settings.
- `nvim-renderer` — candidate rendering and review UI.
- `cli` — deterministic candidates only; no AI by default.

## Non-goals

- AI-created rules or automatic established intents.
- AI inside the scheduler or rulepack dependency graph.
- Cross-project learning from confirmations.
- Adding candidate state to the frozen `Annotation` API.
- AI code completion.

## Phasing

- **036** — v5 `RuleEmit` split, scheduler partition, candidate channel, fake AI
  provider, `:CintReview`.
- **037** — `NORMALIZATION` pack.
- **038** — `SEARCH` and `LOOKUP` data-access pack.
- **039** — `SERVICE_DELEGATION` and `SERVICE_INIT` service pack.
- **040** — `DATA_MODIFY` and `LOCAL_VALUE_MODIFY` mutation pack.
- **043** — DeepSeek-backed provider with bounded excerpt context, privacy
  allow/redact controls, confirmable candidates, local fake-endpoint tests, and
  manual gate m014 for the real-key editor/daemon path.
- Follow-up — evaluation corpus, cost/latency budget, additional providers.
