# AI Annotation Session, Restrictions, and Reuse

Status: proposed, 2026-10-22.

This document designs the next AI layer above the deterministic pipeline:
expression-level discovery, reviewable restrictions, and reuse across document
versions. It does not change the rule that AI output is candidate-only.

## Goal

Make AI useful for code the packs do not recognize, let it challenge wrong
annotations, and avoid repeating work after edits.

Today the AI pass only enriches candidates that a rulepack already emitted. If a
file produces no pack candidate, the provider is never called. `Facts.kt:216`
(`Regex(...).find(text)`) is an obvious `SEARCH` by human reading, but no pack
candidate exists there, so no AI annotation appears for the whole file.

The second gap is direction: AI can only add. It cannot say that an established
intent is wrong, too broad, or should be replaced.

The third gap is reuse: every `didChange` re-runs the full analysis, and every
`:CintSuggest` re-runs the provider, even when the content hash is unchanged or
only one linted line moved.

## Non-goals

- AI-created rules, packs, or established annotations.
- AI inside the scheduler, rule graph, or composition.
- Replacing the deterministic pipeline as the source of truth.
- Cross-project learning from confirmations.
- External MCP tooling in the first slice; the internal tool loop comes first.
- Arbitrary project-file, shell, or network access for the agent.
- AI code completion or code edits.

## Architecture

```
source → deterministic pipeline → annotations + candidates
                                        │
                                        ├─ expression worklist
                                        │      ↓
                                        │  annotation session
                                        │   tools ⇄ agent
                                        │      ↓
                                        │  add/remove verdicts
                                        │      ↓
                                        └─ candidate groups → review → confirm
                                                               ├─ add  → user-annotations.json
                                                               └─ group → ai-restrictions.json
                                                                             ↓
                                                                     next analysis:
                                                               user → restrictions → composition
```

The deterministic pipeline remains authoritative. The session runs after
terminal composition and user annotations, reads the current document and the
oracle, and produces candidates. Confirmed restrictions are applied as source
input on the next analysis, before composition.

## Expression units

The server enumerates every expression node in the document, including leaves.
The enumeration is a core syntax fact derived from the language profile; it is
not a rulepack concern and not a domain heuristic.

Each unit carries:

- `unitId`
- `path` and `language`
- `startByte` / `endByte` and span
- expression text
- parent expression reference and enclosing statement/function references
- existing intent spans overlapping the unit, with source/provenance
- known intent vocabulary

The worklist is authoritative. The agent must return an explicit verdict for
every unit:

- `none`
- `add` on any sub-span of the unit
- `remove` of an existing intent
- `add` and `remove` as one grouped reclassification

Silence is not a verdict. If the session budget is exhausted, the server
publishes the graded units and reports coverage, for example
`graded 143/212, budget exhausted`. Deterministic annotations are unaffected.

The model is not required to annotate the whole expression. A verdict may target
a narrower sub-span, which is how the `Regex.find` call can be tagged without
tagging the enclosing function.

## Session and tools

An annotation pass is a session over one document version. The server owns the
worklist, tool execution, budgets, validation, and publication. The agent owns
the decisions.

The first implementation uses an internal tool loop (provider function/tool
calling). The tool schema is MCP-shaped so an external MCP endpoint can expose
the same surface later without changing the model of the pass.

Tools are read-only except `submit_verdicts`:

| tool | result |
| --- | --- |
| `list_expression_units` | unit ids, spans, existing intents |
| `get_expression` | text, span, existing intent spans for one unit |
| `get_parent` | enclosing expression at N levels |
| `get_enclosing_statement` | statement context |
| `get_enclosing_function` | function context |
| `read_range` | bounded range in the current document |
| `lookup_type` | oracle type facts for a range |
| `get_annotations` | established annotations and provenance in a range |
| `submit_verdicts` | validated add/remove operations |

Scope is the current document plus LSP facts for that document. The agent does
not read other project files, run commands, or access the network. Every text
result passes through `allowGlobs` and `redactPatterns` before reaching the
model.

The session is cancellable and discarded when the document version changes.

### Budgets

The session has a whole-pass timeout, a per-request timeout, turn and tool-call
limits, and a token budget. Suggested defaults, adjusted during implementation:

- session timeout: 120 s
- per-request timeout: 10 s
- max turns: 50
- max tool calls: 200
- max units per request: 24
- max tokens per request: 2048

A session that exhausts a budget publishes the graded units. A provider error
publishes what was graded and marks the session incomplete. Invalid verdicts are
dropped per unit rather than failing the pass.

## Verdicts and validation

A verdict is a list of operations:

```json
{
  "unitId": "u42",
  "add": [
    {
      "intent": "SEARCH",
      "startByte": 10120,
      "endByte": 10175,
      "confidence": 0.71,
      "rationale": "regex find searches the text"
    }
  ],
  "remove": []
}
```

Validation rules:

- Every add span lies inside its unit.
- Every remove target matches an established annotation at that span and
  source, and is not a user annotation or `PURE`/`READONLY`.
- A remove or add may not invent an intent outside the known vocabulary.
- Duplicates of an existing `span + intent` are dropped.
- Same-intent proposals on nested spans collapse to the more complete
  expression span; different intents may nest freely.
- A group that loses all operations after validation is dropped.
- Confidence is diagnostic and capped at the existing ceiling; it never
  promotes anything to established.

Add and remove operations from one unit form one proposal group. Confirming the
group applies every operation atomically; denying it suppresses the proposal at
that anchor.

## Restrictions

A restriction group removes one or more established source intents and may add
replacements. Narrowing and reclassification are the same mechanism:

- remove `LOGGING` from the call span
- add `SEARCH` on the call or a narrower sub-span
- remove on an outer span plus add on an inner span = narrowing

Restrictions target source intents only. Removing a source intent before
composition also removes derivative and scaffold intents that depended on it.
Direct restriction of composition-only annotations is out of scope for the first
slice.

### Application order

1. parse and build facts
2. run the scheduler
3. resolve user annotations
4. apply AI restrictions to non-user contributions
5. add the confirmed AI replacement contributions
6. run composition
7. publish established annotations and candidates

The confirmed replacement contribution uses the `ai-confirmed` source tier and
keeps provider/model/prompt provenance.

### Persistence

Restriction groups live in `.cint/ai-restrictions.json`, separate from user
annotations. The file uses the same lock protocol as
`.cint/user-annotations.json`. Plain additive AI confirms continue to write user
annotations; only restriction groups use this file.

```json
{
  "version": 1,
  "restrictions": [
    {
      "id": "stable-id",
      "path": "src/main/kotlin/Facts.kt",
      "symbol": "magicCommentIn",
      "contentHash": "sha256:...",
      "unitFingerprint": "...",
      "anchor": {
        "startLine": 215,
        "startCol": 8,
        "endLine": 215,
        "endCol": 55
      },
      "remove": [{ "intent": "LOGGING", "startOffset": 0, "endOffset": 47 }],
      "add": [{ "intent": "SEARCH", "startOffset": 0, "endOffset": 47 }],
      "provider": "deepseek",
      "model": "deepseek-chat",
      "promptVersion": "deepseek-intent-v3",
      "createdAt": "2026-10-22T12:00:00Z"
    }
  ]
}
```

Offsets are relative to the anchored unit so the record survives moves. The
record resolves through the same path/symbol/content-hash machinery as user
annotations. If the target no longer exists, the restriction is dormant, not
deleted; an inspector lets the user remove it.

A restriction never suppresses a user annotation. If the user later annotates
the same span with the removed intent, the user wins.

## Reuse

Reuse works at two granularities: the document and the expression unit. File
hashes are too coarse for AI, and a lint on one line must not throw away most
verdicts.

### Environment identity

Split deterministic identity from AI identity:

```
deterministicEnv = H(
  engineVersion,
  packFingerprint,
  configRevision,
  userAnnotationsHash,
  aiRestrictionsHash,
  languageProfileVersion,
  oracleMode
)

aiEnv = H(
  deterministicEnv,
  provider,
  model,
  promptVersion,
  vocabularyVersion
)
```

- `documentKey = H(path, contentHash, deterministicEnv)`
- `aiCandidateKey = H(documentKey, aiEnv)`

### Document short circuit

If the content hash and `deterministicEnv` match, reuse the parsed facts and
`AnalysisOutput`. Do not parse, query LSP, run rules, or call AI. Publish for the
new buffer version with a fresh publish revision; only the analysis work is
reused.

### Unit verdict cache

```
unitKey = H(
  language,
  importSetHash,
  nodeKind,
  normalizedText,
  enclosingSymbol(kind, name, normalizedSignature),
  sortedReferencedNames,
  existingIntentFingerprint,
  aiEnv
)
```

- Whitespace is collapsed and comments are stripped where safe, so formatting
  and most lint edits preserve the key.
- Imports are included so identical text in different files does not share a
  verdict blindly.
- Negative verdicts are cached; most expression leaves are `none`.
- Operation offsets are stored relative to the unit start and remapped on reuse.

### Invalidation

After a text change:

1. diff old and new text to get changed ranges
2. map old units to new units through the diff
3. reuse units whose key matches and that do not overlap a changed range
4. invalidate overlapping units, their ancestors up to the enclosing
   statement/function, and units referencing changed declarations when the
   oracle can identify them
5. invalidate all units when `aiEnv` changes

A one-line lint should produce `reused 211, grading 1`. A pure reformat can
produce `reused 212, grading 0`.

### Similar-unit hints

When a unit changed but is highly similar to a cached unit (default threshold
0.8), do not publish the old verdict directly. Seed the agent with it:

> `prior: SEARCH (0.71) — confirm, adjust, or discard`

Prior restrictions near the same symbol are carried the same way, so edits do
not silently lose a previously reviewed decision.

### Decision reuse

Rejections, confirmations, and restriction records are keyed by the normalized
anchor fingerprint rather than only the file content hash. A formatting edit
must not resurrect a rejected suggestion or drop an accepted restriction.

### Persistence

- `.cint/cache/ai-verdicts.json`, versioned and project-scoped.
- Store fingerprints, operations, spans, intents, confidence, and env hashes.
- Do not store source text.
- Omit rationale from disk by default; after a restart, review shows a cached
  verdict without the old prose.
- Bound entries with an LRU/TTL policy and clear the cache on engine, prompt,
  provider, or vocabulary changes.

## UX

### Passive

Review-first. By default AI proposals add no undercurl, badge, or conceal to the
buffer. Awareness comes from the status count and one arrival notification per
completed pass:

`CodeIntent: 31 unsure (31 AI) · 3 restrictions`

Deterministic candidates keep their current passive rendering.

### Running a pass

- `:CintSuggest` grades every expression in the buffer.
- `:'<,'>CintSuggest` grades expressions intersecting the selection.
- `:CintSuggestCancel` aborts the session.
- Progress: `CodeIntent: grading 48/212 · reused 180 · reading Facts.kt:216`.
- Completion: `graded 212/212 · 9 additions · 3 restrictions · 41 tool calls`.
- No misses: `AI: no changes since last pass (212 reused)`.

### Review

`:CintReview` keeps the inline overlay and shows operation groups:

```
+ SEARCH
  ai:deepseek · 0.71
  Regex find searches the text.

− LOGGING
  established by logging · ai 0.66
  This regex find is not a log call.

− LOGGING + SEARCH
  ai:deepseek · 0.68
  Reclassify: remove logging, add search on the call.
```

- `:CintReviewAccept` applies the group under the caret; overlapping groups
  open a picker labelled with their `+`/`−` signature.
- `:CintReviewDeny` rejects the proposal at that anchor.
- `:CintReviewList` opens a quickfix/panel of all groups sorted by path and
  line, labelled with operations, confidence, and rationale.
- `]c` / `[c` and `<Plug>` mappings jump between proposals.
- `:CintRestrictions` lists active restriction groups; selecting one removes the
  record and re-analyzes, restoring the original annotation.
- `:CintAiLog` shows the session's tool calls, reuse decisions, and verdicts.

After an accepted group: `removed LOGGING, added SEARCH (AI)`.

## Protocol and API

The first slice requires new candidate shapes and progress reporting.

- Protocol 4 gates discovery and restriction features.
- `initialize.suggestions` advertises `discovery`, `restrictions`, `cache`, and
  the usual provider/model/knownIntents fields.
- `cint/suggest/progress` reports `{uri, version, graded, reused, total,
  activity}` during a pass.
- `cint/suggest` returns coverage and counts alongside `accepted`/`cancelled`.
- `cint/publishCandidates` groups carry explicit add/remove operations instead
  of only an intent set.
- `cint/candidates/decide` accepts proposal-group ids for confirm/deny/skip.
- `cint/suggest/log` returns the last session's tool/reuse events for
  `:CintAiLog`.

The v5 `CandidateEmit` cannot express removal. The preferred API change is a v6
proposal model with explicit operations (`ADD` / `REMOVE`), while established
`Emit` and the frozen `Annotation` shape remain unchanged. `toContribution`
still accepts only established emits.

## Configuration

New settings live in `.cint/suggestions.yml`, flat to match the existing loader:

```yaml
suggestions:
  enabled: true
  provider: deepseek
  discoveryEnabled: true
  discoveryIncludeLeaves: true
  discoveryMaxUnitsPerRequest: 24
  sessionTimeoutMs: 120000
  maxTurns: 50
  maxToolCalls: 200
  maxTokensPerRequest: 2048
  similarHintThreshold: 0.8
  restrictionsEnabled: true
  cacheEnabled: true
  cachePersist: true
  cacheMaxEntries: 5000
  cacheTtlHours: 720
  passiveCandidates: status
```

`allowGlobs` and `redactPatterns` continue to gate text sent to the provider and
apply to tool results as well.

## Privacy and failure behavior

- Scope is the current document plus LSP facts.
- No source text in the disk cache.
- No provider/response/key logging by default.
- `allowGlobs` and `redactPatterns` apply to excerpts, tool results, and hints.
- A missing key, provider failure, timeout, or invalid output leaves
  deterministic annotations and candidates untouched.
- A stale document version discards the session; a new version starts from the
  reuse worklist.
- Budget exhaustion publishes graded units and reports coverage.
- Cancellation publishes nothing beyond what was already committed.

## Testing

- Scripted fake provider that exercises the tool loop, verdict validation, and
  budget exhaustion.
- A fixture modeled on `Facts.kt:216` where no pack candidate exists and the
  session proposes `SEARCH`.
- Leaf-unit grading and negative-verdict caching.
- Restriction validation: user annotations are never removed; composed
  annotations recompute after a source removal.
- Reuse: same hash short-circuits; a one-line lint reuses most units; prompt
  changes invalidate; the disk cache survives restart; rejected suggestions do
  not resurrect after formatting.
- Renderer: passive minimal display, review groups, accept/deny, restrictions
  inspector, progress, cancellation.

## Phasing

- **058** — expression-level annotation session and discovery, internal tool
  loop, protocol progress, add-only verdicts.
- **059** — restriction proposals, grouped remove+add, `.cint/ai-restrictions.json`,
  engine application, protocol/or API operation model.
- **060** — document and unit verdict reuse, diff invalidation, hints, disk
  cache.
- **061** — Neovim review UX for discovery, restrictions, reuse, and high-volume
  review.
- Follow-up — external MCP endpoint over the same tool schema.
