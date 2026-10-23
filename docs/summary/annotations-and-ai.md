# Annotations, User Directives, and AI Candidates

## User annotations

The client writes `.cint/user-annotations.json` (schema version 1) per project.
Each record has:

```json
{
  "path": "relative/path.kt",
  "symbol": "optionalName",
  "contentHash": "sha256",
  "startLine": 0,
  "startCol": 0,
  "endLine": 0,
  "endCol": 0,
  "intents": ["PURE"],
  "note": "optional",
  "createdAt": "ISO-8601"
}
```

Resolution (`core/user`):

- Path matching accepts project-relative or absolute source paths.
- Symbol anchors bind to named declarations/functions/properties and require a
  matching content hash or a stored range inside the candidate.
- Range anchors use UTF-8 byte columns; a present hash must match the current
  range or the record is stale.
- Resolved records become `SOURCE_INTENT` contributions with rule id
  `core:user-annotations`, source tier `user`, confidence `1.0`, and
  `nodeAnchor = null`.
- Records merge with pack contributions by role+byte span; removal removes only
  the user provenance.
- `UNTAGGED` is stripped from user contributions.
- Unknown user intent names are preserved as open intents.

Directives:

- `PURE` — no side effects, deterministic. Participates in the annotated
  pure-function derivative path.
- `READONLY` — no writes, may read external state. Treated as a
  side-effect-free read for derivative use and validation composition.
- Both remain visible in the intent set and provenance; neither is pack-owned.

### Lock protocol

The Kotlin writer and Neovim writer share
`.cint/user-annotations.json.lock/`:

- The lock directory carries `owner` with a random token, pid, and Linux
  `/proc/<pid>/stat` start marker.
- Staleness is liveness-based; a live or suspended holder is never stolen.
- A `.lock.steal` claim serializes stale transitions. A claimant rechecks the
  canonical lock under the claim before moving/deleting.
- Release is a verified rename-to-tombstone (`.stale-<token>`) then delete.
  Unexpected restore failures hard-fail. Both writers fence before the data
  rename.
- Ownerless/legacy paths are age-reaped; acquire timeout is 15 s.
- `CrossWriterAnnotationLockTest` runs the JUnit writer and real headless
  Neovim against one temp root, records enter/exit intervals, and rejects
  overlap. It is part of `just test-engine` and `just test-cross-writer`.
- Accepted residual: the claim-reaping check-then-act window can move a newly
  published live claim aside under compound concurrent reapers; normal live
  holders are never stale, and partial outcomes hard-fail. A strict guarantee
  would need an OS advisory lock or unique-generation CAS.

## Candidate channel

- Rules return established `Emit` and review-only `CandidateEmit`.
- Candidate sources: `candidate`, `rule:<id>`, or `ai:<provider>`. AI output is
  structurally candidate-only.
- Candidate identity: path + content hash + byte span + sorted intents + reason.
- Alternatives at one anchor form a group. Confirming any alternative resolves
  the group; siblings stop being published.
- Decisions: confirm writes a user annotation and re-analyzes; reject persists
  suppression in `.cint/suggestions.json`; skip is per-client/session.
- Grouped remove+add proposals use `proposalId`/`groupId`; confirm writes one
  `.cint/ai-restrictions.json` record instead of a user annotation.
- Candidates never enter reads, composition, or annotation stores.

## AI discovery session

Ticket 058 replaces pack-candidate-only enrichment with an expression-level
session by default; ticket 059 adds grouped removal proposals:

```text
cached analysis + established annotations
  -> core expression units (including leaves)
  -> batched worklist + known vocabulary
  -> annotation agent (internal tool loop; current document + LSP facts)
  -> add/remove verdicts (none/add/remove per unit)
  -> validation (unit containment, exact removal target, vocabulary, duplicates, confidence)
  -> grouped remove+add proposal or add-only candidate
  -> merge with deterministic candidates -> group + publish
```

Tools are `list_expression_units`, `get_expression`, `get_parent`,
`get_enclosing_statement`, `get_enclosing_function`, `read_range`,
`lookup_type`, `get_annotations`, and `submit_verdicts`. All text results pass
through `redactPatterns` (`allowGlobs` gates the pass). The session is asynchronous, one pass per
document version, cancellable, and discarded when the version changes.

Budget and coverage behavior is unchanged: `sessionTimeoutMs` (120 s),
per-request `timeoutMs` (10 s), `maxTurns` (50), `maxToolCalls` (200),
`maxUnitsPerRequest` (24), `maxTokensPerRequest` (2048), plus the candidate and
confidence caps. Budget exhaustion publishes the graded units and reports
coverage; deterministic annotations are unaffected. `restrictionsEnabled`
(default true) gates grouped removal proposals and the restriction inspector.

Protocol 4 adds `discovery`/`restrictions`/`cache` capabilities,
`cint/suggest/progress`, coverage/counts on the `cint/suggest` result, and
`cint/suggest/log`. `restrictionsEnabled: false` disables proposals and the
inspector while leaving deterministic analysis and add-only proposals unaffected.
`cint/suggest.restrictions` is the post-filter published proposal count, and
protocol-3 clients receive no `operations` or `restrictions` fields.
`discoveryEnabled: false` restores the legacy enrichment pipeline.

## AI restriction proposals

A verdict that removes established intents becomes one proposal group anchored
on its expression unit. The group carries explicit `ADD` and `REMOVE`
operations; add-only verdicts keep the existing candidate path. Removal targets
must match an established `SOURCE_INTENT` at the exact byte span. User
annotations, `ai-confirmed` contributions (including protected provenance inside
a `mixed` span), and `PURE`/`READONLY` are rejected. A removal may target an
enclosing span from a child unit and carry a negative relative start offset. A
group that loses all operations after validation is dropped. All candidate and
proposal decisions share a decision lock, and proposal confirm revalidates
removals and replacement adds. A decision republish
is scoped per client: it preserves that client's unrelated pending add candidates
and proposals, revalidates carried proposals, drops groups whose removal target
or replacement add is already established, and does not send proposal groups to
protocol-3 clients.

`cint/publishCandidates` groups expose `proposalId`/`groupId` and `operations`.
`cint/candidates/decide` accepts those ids for grouped confirm/deny/skip.
Confirm applies the whole group atomically and writes
`.cint/ai-restrictions.json`; deny suppresses the proposal in
`.cint/suggestions.json`; skip is session-only. Add-only confirms still write
`.cint/user-annotations.json`.

```json
{
  "version": 1,
  "restrictions": [
    {
      "id": "stable-proposal-id",
      "path": "src/main/kotlin/Facts.kt",
      "symbol": "magicCommentIn",
      "contentHash": "3f0c...",
      "unitFingerprint": "b41d...",
      "anchor": {
        "startLine": 215,
        "startCol": 8,
        "endLine": 215,
        "endCol": 55
      },
      "remove": [{ "intent": "LOGGING", "startOffset": -12, "endOffset": 47 }],
      "add": [{ "intent": "SEARCH", "startOffset": 0, "endOffset": 47 }],
      "provider": "deepseek",
      "model": "deepseek-chat",
      "promptVersion": "deepseek-intent-v3",
      "createdAt": "2026-10-23T12:00:00Z"
    }
  ]
}
```

Offsets are relative to the anchored expression unit; adds are contained in it,
while a removal may start before it when targeting an enclosing span. Analysis
resolves a record by path, anchor content hash, stored symbol, and unit
fingerprint (including adjacent sibling context); duplicate, ambiguous, or
missing identity is dormant and harmless. It applies removals after user
annotations and before composition, and adds replacements with
`source = ai-confirmed` and contributor `core:ai-confirmed`; structured
provider/model/prompt provenance is carried on the contribution fields and on
the restriction record/list, not a concatenated rule id. Derivative and scaffold
annotations are recomputed from the filtered source set. Removing a record restores the original annotation on
re-analysis. User annotations always win over a restriction.
`cint/restrictions/list` returns `{restrictions: [...]}` with
`active`/`dormant`/`unknown` status and the operation/provider fields;
`cint/restrictions/remove` takes `{id}` and returns `{accepted, removed}`. Both
require protocol 4 and `restrictionsEnabled`.

## Legacy pack-candidate enrichment

Pipeline:

```text
cached analysis candidates
  -> filter aiEligible
  -> bounded excerpt + metadata + known/neighboring intents
  -> IntentSuggestionProvider (fake or DeepSeek)
  -> CandidateValidator (vocabulary, span containment, confidence range/cap, dedupe)
  -> merge with deterministic candidates
  -> group + publish
```

DeepSeek defaults (`core/suggest`, current):

- provider `deepseek`, model `deepseek-chat`
- prompt version `deepseek-intent-v3`
- temperature `0.3`, max tokens `1024`
- `maxCandidates: 0` and `maxEnrichmentCandidates: 0` mean **unlimited**
  (positive values cap)
- timeout `10000 ms`, worker threads `2`
- max confidence `0.84` (diagnostic cap)
- excerpt radius `400` bytes per side
- one attempt per candidate; no retries

Privacy and validation:

- Discovery sends the current batch of expression units plus tool results for the
  current document. Legacy enrichment sends the candidate span plus bounded
  excerpt, path/language, candidate metadata, known/neighboring intents, and
  prompt version. Neither path reads other project files or sends whole files.
- `allowGlobs` gates the pass; `redactPatterns` star out matches in every text
  result before sending while preserving byte length/newlines.
- The API key comes from `DEEPSEEK_CHAT_KEY` (or `apiKeyEnv`), is used in the
  Authorization header, and is never logged or published.
- Provider failures, timeouts, and malformed output leave deterministic tags and
  candidates untouched. A timeout cancels the pass without partial results.
- AI candidates carry provider/model/prompt/rationale diagnostics. Confidence is
  diagnostic, not an establishment threshold.

Server config lives in `.cint/suggestions.yml` under `suggestions:`. A missing
file means disabled/manual with the defaults above. Pack candidates still
publish when AI is disabled. `initialize.suggestions` is a secret-free
capability descriptor.

## Client candidate UI

- Passive deterministic candidates: muted `?` sign + dotted undercurl.
- Passive AI candidates: `AI` sign, purple AI highlight/undercurl, and one ` AI`
  end-of-line badge per line.
- New AI candidates notify once per AI-count increase:
  `AI added N candidate(s); :CintReview to inspect`.
- `status_text` includes the AI count:
  `CodeIntent: N unsure (M AI)`.
- `:CintSuggest` reports start, busy, disabled, no-new-candidates, and errors.
- Review mode adds a visible highlight and a virtual evidence line with intent,
  confidence, source, reason, and rationale. `:CintReviewAccept [intent]` and
  `:CintReviewDeny` act on the candidate group under the caret.
