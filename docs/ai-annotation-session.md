# AI Annotation Session, Restrictions, and Reuse

Status: partially implemented, 2026-10-23. Ticket 058 implements expression-level
discovery, the internal tool loop, add-only verdicts, budgets/coverage, protocol 4
progress/log, and the recall-oriented prompt. Ticket 059 implements grouped
remove+add restriction proposals, `.cint/ai-restrictions.json`, pre-composition
application, and the restriction inspector API. Ticket 060 implements the
deterministic document short circuit, per-unit verdict reuse with diff
invalidation and hints, normalized decision anchors, and the disk verdict cache.
The full review UX (061) remains proposed.

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
`allowGlobs` gates the pass, and `redactPatterns` sanitizes every text result
before it reaches the model.

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
the stable `core:ai-confirmed` contributor id. Provider/model/prompt provenance
is structured both on the contribution (`provider`, `model`, `promptVersion`
defaulted fields) and in `.cint/ai-restrictions.json` / `cint/restrictions/list`.
The tag provenance no longer uses concatenated metadata.

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
      "contentHash": "3f0c...",
      "unitFingerprint": "b41d...",
      "fingerprintVersion": 2,
      "anchor": {
        "startLine": 215,
        "startCol": 8,
        "endLine": 215,
        "endCol": 55
      },
      "remove": [{ "intent": "LOGGING", "startOffset": -12, "endOffset": 47, "startToken": -4, "endToken": 9 }],
      "add": [{ "intent": "SEARCH", "startOffset": 0, "endOffset": 47, "startToken": 0, "endToken": 9 }],
      "provider": "deepseek",
      "model": "deepseek-chat",
      "promptVersion": "deepseek-intent-v3",
      "createdAt": "2026-10-22T12:00:00Z"
    }
  ]
}
```

Offsets are relative to the anchored unit so the record survives moves. Adds
must lie inside the anchor (non-negative offsets). A removal may start before a
child-unit anchor when it targets an enclosing established span, so its
`startOffset` can be negative; the operation must still overlap the anchor. The
record resolves through path, anchor content hash, stored symbol, and the unit
fingerprint (node kind, normalized text, enclosing symbol, previous and following
siblings, and ordinal). An adjacent insertion invalidates that identity, and
ambiguous or missing anchor identity is dormant rather than bound to a different
identical expression. If the target no longer exists, the restriction is
dormant, not deleted; an inspector lets the user remove it.

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

If the content hash and `deterministicEnv` match, reuse the analyzed
`AnalysisOutput`; do not parse, query LSP, run rules, or call AI. Publish for the
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
- Positive operations store token offsets relative to the unit anchor (and raw
  offsets for legacy/out-of-anchor operations); they are remapped on reuse and
  revalidated against the current text. Existing-intent fingerprints use token
  positions when the annotation is token-aligned.

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

Rejections, confirmations, and restriction records are keyed by versioned
normalized anchor fingerprints rather than only the file content hash. Candidate
and proposal fingerprints include the containing unit identity and normalized
operation shape so duplicate expressions stay independent. Confirmed add-only
annotations persist the containing unit fingerprint, its version, token range,
and normalized offsets, so a formatting edit does not drop them. A formatting
edit must not resurrect a rejected suggestion or drop an accepted annotation or
restriction.

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
- `:CintAiLog` shows the session's tool calls, reuse decisions, and verdicts
  (renderer command planned for 061).

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
- `cint/suggest/log` returns the last session's structured tool/reuse events;
  `:CintAiLog` is planned for 061.

The v5 `CandidateEmit` cannot express removal. The operation model therefore
stays at the `suggest`/protocol boundary: `ProposalOperation` /
`CandidateProposal` carry explicit `ADD` / `REMOVE` operations, while established
`Emit`, the frozen `Annotation` shape, and `toContribution` are unchanged. This
is the chosen v5-compatible shape; a v6 API bump is not needed because
restrictions are AI review data, not rulepack emits.

One additive API model change accompanies the provenance requirement:
`Contribution` gains defaulted `provider`, `model`, and `promptVersion`
diagnostic fields (the `certifiesReturn` precedent). Normal pack contributions
leave them null, so existing rulepack sources remain source-compatible and
`CINT_API_VERSION` stays 5. The API contract test pins the defaults. Consumers
that construct `Contribution` positionally should add the new arguments only when
they need AI provenance; wire consumers can ignore the optional JSON keys.

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

`allowGlobs` continues to gate the pass, and `redactPatterns` applies to every
text result sent to the provider, including tool results.

## Privacy and failure behavior

- Scope is the current document plus LSP facts.
- No source text in the disk cache.
- No provider/response/key logging by default.
- `allowGlobs` gates the pass; `redactPatterns` applies to excerpts, tool
  results (including type lookups), and hints.
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

## Implementation snapshot (058)

- Core exposes expression units through `SyntaxModel.expressionUnits`, including
  leaf nodes. The server builds the worklist with overlapping established intents.
- `core/suggest/session` owns the provider-neutral tool loop, tool schema,
  verdict validation, batching, budgets, progress callbacks, and session events.
  `AnnotationSessionProvider` is implemented by the fake and DeepSeek providers;
  the DeepSeek prompt version is `deepseek-intent-v3`.
- Tool text, including `lookup_type` results, is sanitized with `redactPatterns`;
  `allowGlobs` gates the pass. Batch prompts bound per-unit text and existing
  annotation lists to the same session byte budget as tool results. Scope is
  the current document plus oracle type lookups. No shell, file, or network
  tools exist.
- The live server runs one cancellable pass per document version, publishes
  `cint/suggest/progress`, returns coverage/counts, and stores the last session
  for `cint/suggest/log`. Discovery defaults on; disabling it preserves the
  legacy pack-candidate enrichment path.
- Protocol is 4. `initialize.suggestions` advertises `discovery` (true),
  `restrictions`/`cache` (false), and `maxUnitsPerRequest`. Add-only candidates
  keep the v5 `CandidateEmit` shape.
- Tests cover core unit enumeration (including leaves), tool-loop verdicts,
  sub-span adds, validation, duplicate/nested collapse, budget exhaustion,
  sanitization, DeepSeek tool-call parsing, server progress/log/coverage, and the
  regex-find fixture with no pack candidate producing `SEARCH`.

## Implementation snapshot (059)

- A verdict may carry `remove` operations beside `add`. Removes must match an
  established `SOURCE_INTENT` at the exact byte span; user annotations,
  `ai-confirmed` contributions (including a `mixed` span with protected
  provenance), and `PURE`/`READONLY` are rejected. Unresolved operations are
  dropped per unit. A removal may target an enclosing span from a child unit;
  its relative offset can be negative and round-trips through the file.
- One verdict with removals becomes one proposal group anchored on its expression
  unit. The group carries explicit `ADD`/`REMOVE` operations and is published in
  `cint/publishCandidates` groups with `proposalId`/`groupId`; its additions are
  not independently confirmable candidates. `maxCandidates` caps add candidates
  first, then proposal groups with the remaining slots.
- `cint/candidates/decide` accepts `proposalId`/`groupId` (or a matching
  `candidateId`) and applies confirm/deny/skip to the whole group. All candidate
  and proposal decisions share a decision lock; proposal confirm revalidates
  removals and replacement adds against the current analysis under that lock. A
  decision republish is scoped per writer: it preserves that writer's unrelated
  pending add candidates and proposals, revalidates carried proposals against the
  new analysis, drops groups whose removal targets are gone or whose adds are
  already established, and never publishes proposal groups to protocol-3
  clients. Add-only verdicts keep the existing candidate path and write
  `.cint/user-annotations.json` on confirm.
- Confirm writes one record to `.cint/ai-restrictions.json` under the shared
  `.cint/user-annotations.json.lock` protocol. Records store path, symbol,
  anchor span, bare lowercase SHA-256 content hash, a real unit fingerprint,
  relative `remove`/`add` offsets, and provider/model/prompt provenance. Writers
  reject add-only records so no file is written that the reader would reject.
  Deny persists suppression in `.cint/suggestions.json`; skip is session-only.
- Analysis resolves restrictions by path, anchor content hash, stored symbol, and
  unit fingerprint, so line moves keep the anchor while duplicate text and
  deleted targets go dormant instead of rebinding. It applies removals after user
  annotations and before composition, adds confirmed replacements as
  `source = ai-confirmed` with contributor `core:ai-confirmed` and structured
  provider/model/prompt contribution fields, and recomputes derivative/scaffold
  output from the filtered source set.
- `cint/restrictions/list` reports stored records with `active`/`dormant`/`unknown`
  status (a record whose removal no longer applies and whose add is not present
  is dormant); `cint/restrictions/remove` removes a record and re-analyzes open
  documents while preserving pending review state. Both require protocol 4 and
  `restrictionsEnabled`; the capability is advertised for protocol-4 clients
  whenever `restrictionsEnabled` is set.
- `cint/suggest.restrictions` reports the post-filter published proposal count.
  Protocol-3 clients receive neither that field nor `operations` group fields.
- The restriction file `version` is checked; unknown versions are not applied and
  writes refuse to rewrite them, preserving existing records.

## Implementation snapshot (060)

- `DeterministicEnvironment` hashes engine code fingerprint, pack/rule code
  fingerprint, config fingerprint, the user-annotation file and AI-restriction
  file contents, the language-profile code/resource fingerprint, and the oracle
  mode. `AiEnvironment` adds provider, model, prompt version, and a hash of the
  known intent vocabulary.
- A `didChange`/`cint/analyze` whose content hash, language, and
  `deterministicEnv` match the cached analysis does not parse, query LSP, run
  rules, compose, or call the provider. It allocates a fresh analysis revision
  and republishes the cached annotations and deterministic candidates for the
  new buffer version. A short circuit republish filters decisions by their
  stored candidate/anchor ids instead of building anchor contexts, so it performs
  no parse even when the project has stored decisions.
- Unit verdicts are keyed by language, import-set digest, node kind, normalized
  text (comments stripped, whitespace tokenized), enclosing symbol and signature,
  sorted referenced names, existing-intent token fingerprint, and `aiEnv`.
  Negative verdicts are cached. Positive operations store token offsets relative
  to the unit anchor (and raw offsets for legacy/out-of-anchor operations); they
  are remapped to the current text and revalidated before use. Formatting inside
  a unit therefore keeps the same token span, including annotations that are
  part of the existing-intent fingerprint. An existing annotation that is not
  token-aligned falls back to relative offsets and may miss a reformat; that is a
  deliberate no-stale-reuse fallback.
- The suggestion pass keeps the text of the last completed grading pass per
  document and diffs that against the new text. Units that overlap a changed
  range or reference a declaration whose normalized body changed are graded
  again. Pure formatting produces no changed range because the normalized
  document is unchanged. Insertions and deletions are represented as point
  ranges so they still invalidate adjacent/containing units. The baseline is
  process-local: after a daemon restart or a budget-exhausted pass the first
  suggestion has no diff basis and falls back to exact cache keys.
- Similar changed units (token similarity at or above `similarHintThreshold`)
  are carried into the batch prompt as `prior:` hypotheses even when
  invalidation forces the unit to be regraded. Prior restrictions are collected
  independently of the verdict-cache toggle. Hints are never published directly.
- Reject/confirm decisions and restriction records use a versioned normalized
  anchor fingerprint built from the unit identity (symbol, siblings, ordinal)
  plus the normalized operation shape. Identical expressions in the same symbol
  and different proposal shapes therefore stay independent, while formatting
  keeps the decision. Confirmed proposals are also matched against the stored
  restriction unit fingerprint, not only the raw `proposalId`. Confirmed
  add-only annotations persist the containing unit fingerprint and its version,
  normalized anchor fingerprint, token range, and normalized offsets, which
  `UserAnnotationResolver` prefers over the raw span/hash path. A non-token-
  aligned confirmation falls back from token offsets to normalized offsets, and
  only a record with no token or normalized anchors uses the raw-range/content-
  hash check. A versioned anchor that no longer matches is dormant rather than
  rebinding to an enclosing declaration or a duplicate expression.
- Restriction fingerprints are versioned. Version 1 records use the 059
  whitespace-collapse normalization; new records use token normalization with
  comments stripped from the anchor and preserved in sibling context. Records
  without a version field are read as version 1, and an unmatched legacy
  fingerprint falls back to the stored span when its content hash still matches.
  New restriction operations store relative token offsets so formatting or
  moving the anchor remaps removals and replacements instead of dropping them.
  Version-1 records cannot survive an intra-anchor formatting edit because they
  carry no token offsets; reconfirming upgrades them to version 2.
- Split-document decisions compute their unit/token/normalized anchors against
  the containing script region (using that region's language frontend) while the
  persisted span/content hash stays in host coordinates. They persist a stable
  region identity derived from region language plus normalized region text.
  Region ordinal/language are legacy fallback fields only; duplicate region
  identities are dormant, not resolved by ordinal. Ambiguity is computed over
  the flattened leaf set shared by scoping, status, and suppression. Scoping,
  status, and proposal
  suppression all use that identity and require the same region; if two regions
  normalize to the same identity, the record is treated as ambiguous and dormant
  rather than applying or suppressing across regions. Line insertions,
  region insertion/removal, and reordering therefore preserve unique region
  identities. The scoping step passes region-relative anchors through and the
  region resolver matches them; legacy unanchored records keep the raw
  host-span/hash clip. Split analysis also runs the host frontend over host-level
  source outside leaf regions and merges those annotations/candidates, so
  confirmed host-level user annotations and restrictions are applied rather than
  stored only. Status and proposal suppression share the cached applicability
  check (`restrictionApplies`) instead of treating any non-empty resolution as
  applicable. Host analysis receives only host-level records, while region
  analysis receives only records owned by that leaf; host-level restrictions
  suppress only host-level proposals and region records only same-region
  proposals. The restriction inspector and proposal suppression resolve region
  records against their region source, not the host Vue document, and use the same
  recursive leaf-region enumeration as discovery.
  `SuggestionDecision` stores an anchor-fingerprint version alongside restriction
  records, with unversioned round-2 records read at version 2.
- Split discovery recursively enumerates nested leaf regions and also includes
  host-level expression units outside regions. Batch prompts carry the document
  language plus per-unit `language` when it differs and a deduplicated sorted
  `languages` set when units introduce a non-host language, so providers see
  JavaScript/TypeScript region languages rather than only the host language.
  `lookup_type` routes each requested host span through the containing region's
  frontend and oracle; declaration invalidation diffs declarations from every
  split region with its own frontend. Nested leaf selection is recursive for
  discovery, anchoring, status, and suppression; overlapping split regions and
  legacy ordinal-only nested records remain the region-selection limitations.
- Engine/language-profile identity is package-derived: engine identity hashes
  `api`, `core`, `suggest`, `host`, `server`, `lsp`, and `rulepacks` class files;
  language identity hashes `frontend`/`treesitter` classes plus every syntax YAML
  for the registered languages. Rulepack shared helpers are hashed through the
  rulepack package as well as the pack/rule class digests. Missing code,
  resources, or unreadable code roots make the fingerprint fail loudly, and the
  guard test asserts prefix/entry coverage as well as the Gradle module list.
- `.cint/cache/ai-verdicts.json` is versioned with the full deterministic and AI
  environment, not only provider/model/prompt/vocabulary. Entries store key
  digests, raw-text digest, token operations, intents, confidence, spans, and
  timestamps; they never store unit source text or rationale. Rationale is kept
  for an in-memory reuse but omitted at the disk boundary. The file is bounded by
  `cacheMaxEntries` and `cacheTtlHours`; a changed environment discards it, and
  loaded entries are filtered by the current `aiEnv`. `cachePersist: false` keeps
  reuse in memory only. The file has one environment header, so alternating
  languages in one project replace the persisted entries for the previous
  language; in-memory reuse is unaffected until the daemon restarts.
- `cint/suggest` reports `reused`, `graded`, `total`, and `overhead` (cache
  bookkeeping milliseconds); progress reports `reused`. `graded` counts units the
  provider actually graded, so `reused + graded == total` when coverage is full.
- Accepted staleness limit: an external type change with unchanged text does not
  bump the environment, so a reused verdict can be stale until the next text or
  environment change. This is intentional for the current slice because the LSP
  document version is not part of the persisted identity.
- Accepted migration/scope notes: pre-060 suggestions decisions without an
  `anchorFingerprint` still use their content-hash ids; old restriction records
  are read through the version-1 fingerprint. Similar-unit hints are memory-only
  because normalized text is not persisted. The unit key intentionally omits
  path, so files with matching language/imports/symbol/text share verdicts.
- Stability contract: `AnchorFingerprint.of` and the raw/normalized offset
  mapping are part of the version-2 unit identity. They are frozen for version 2;
  changing either recipe requires a new `UnitIdentity` version and a versioned
  read path for existing decisions/user annotations. They do not carry an
  independent version field.
- Region identity is content-addressed: changing a region's normalized content
  changes its key, so old decisions become dormant until reconfirmed. If the
  original region disappears while an identical-content region remains, that
  region can adopt the key; duplicate identical regions are ambiguous and
  dormant. There is no automatic migration across content changes.
- Shared external rulepack helpers are not covered by the bundled package
  fingerprint. The bundled `dev/cint/rulepacks` package is covered; third-party
  pack artifacts must bump their pack code/version to invalidate their entries.
  Follow-up: hash each external pack artifact/checksum when third-party pack
  loading is supported. Splitting a restriction whose remove/add target extends
  outside the region is treated as inapplicable/dormant in the region, not
  partially applied.

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
