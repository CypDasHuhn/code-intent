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
    override val source: String,
    override val confidence: Double,
    val reason: CandidateReason,         // GAP, LOW_CONFIDENCE, PACK_DOMAIN
    val aiEligible: Boolean = true,
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
      - candidate source span + enclosing function
      - neighboring established intents
      - language/imports
      - allowed intent vocabulary
  → IntentSuggestionProvider (SPI)
  → CandidateValidator
      - span inside the input candidate
      - intent in allowed vocabulary
      - confidence range; max candidates
  → AI CandidateEmit(s)
```

- Provider SPI is one-shot and provider-agnostic: fake/local/remote providers.
- Prompt is versioned and structured; code and comments are untrusted data.
- Invalid or out-of-bounds output is dropped. No tool calls, no edits.
- Async, cancellable, debounced, one in-flight request per document.
- Cache by `(file, version, analysisRevision, candidateHash, provider, model,
  promptVersion)`.
- AI disabled by default. Pack candidates still work without AI.

## Candidate lifecycle

- `candidate` — proposed, shown non-destructively.
- `confirmed` — user accepted; written as a user annotation and re-analyzed.
- `rejected` — persisted as suppression keyed by path + anchor/content hash +
  intent.
- `stale` — anchor no longer matches; drop or re-propose.

Decisions live in `.cint/suggestions.json`. Established annotations and the
frozen `Annotation` API are unchanged.

## Protocol

Protocol 3 additions:

- `initialize.suggestions` — capability descriptor: enabled, trigger policy,
  provider/model, known intents. No secrets.
- `cint/publishCandidates` — `uri`, `version`, `analysisRevision`,
  `suggestionRevision`, candidate groups.
- `cint/suggest` / `cint/suggest/cancel` — manual or idle pass.
- `cint/candidates/decide` — confirm/reject/skip; server persists, re-analyzes
  on confirm, republishes.

Candidates carry `source` (`rule:<id>` or `ai:<provider>`), confidence,
rationale, prompt version, reason, and a stable anchor. Stale versions and
revisions are discarded.

## Renderer UX

- Candidate style is non-destructive: dotted underline, muted sign/text, never
  conceal.
- Status count, e.g. `CodeIntent: 3 unsure`.
- `:CintSuggest` runs a pass; `:CintReview` lists candidates with evidence,
  rationale, and provenance.
- `[Confirm] [Reject] [Skip]`, plus choosing among alternative intents for one
  span.
- AI unavailable or over budget → no candidates, deterministic tags unaffected.

## Config and privacy

`.cint/suggestions.yml`:

```yaml
suggestions:
  enabled: false
  trigger: manual        # manual | idle
  provider: openai-compatible
  model: ...
  apiKeyEnv: CINT_SUGGESTIONS_API_KEY
  timeoutMs: 4000
  idleMs: 750
  maxCandidates: 5
  allowNewIntents: false
  privacy:
    allowGlobs: ["src/**"]
    redactPatterns: [...]
```

Secrets stay server-side. Only bounded candidate context is sent, never the whole
project by default. Local providers are first-class. Prompt/response logging is
off by default.

## Modules

- `api` — `RuleEmit`, `CandidateEmit`, v5 rule contract.
- `core` — scheduler partition and `AnalysisOutput`; no AI.
- `rulepacks` — deterministic secondaries where domain-specific.
- `suggest` — context builder, provider SPI, validator, candidate merging,
  policy. No server/editor/provider SDK.
- provider modules — optional local/remote implementations.
- `server` — async orchestration, config, credentials, protocol, persistence.
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
- Follow-up — real provider, evaluation corpus, cost/latency budget, manual test.
