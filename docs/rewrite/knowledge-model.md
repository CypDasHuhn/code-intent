# V2 Knowledge and AI Model

Working notes for the V2 semantic knowledge layer. This supersedes the V1 model
where each rulepack owned a user-facing config shape and AI acted as a
per-expression classifier.

The central change: CodeIntent persists what symbols mean, derives the rest
deterministically, and lets users and agents write the same knowledge through
the same API. AI is a client, not the engine.

## Principles

1. **Semantic knowledge is persisted data.** Function-level intent and explicit
   span overrides are project data, not rulepack internals.
2. **Pack defaults are seed data.** A pack may suggest annotations, but it does
   not own a separate user-facing config shape.
3. **Derivation is deterministic.** Once a function contract exists, call-site
   propagation and composition do not call AI.
4. **All writers use the same mutation API.** User, editor, MCP agent, and
   fallback AI write through the same validation and provenance path.
5. **AI is not a privileged classifier.** It behaves like a user with its own
   provenance. Its output is not a separate storage model.
6. **In-house AI is fallback only.** It covers uninitialized repositories,
   recently changed code, and environments where MCP is unavailable.

## Data model

### `FunctionAnnotation`

The durable "this symbol is supposed to do X" record.

```text
FunctionAnnotation {
  selector
  intents
  scope
  provenance
  confidence
  status
  createdAt / updatedAt
}
```

A selector may describe:

- qualified name
- import
- receiver
- declaring type
- function signature
- structural pattern

The same record covers internal functions, external libraries, logging
frameworks, repository wrappers, and in-house helpers.

### `SpanOverride`

An explicit local annotation that wins over inference for a specific span.

```text
SpanOverride {
  path
  span
  contentHash
  intents
  provenance
  confidence
  rationale
  status
}
```

Overrides are the escape hatch for "the function is generally X, but this call is
Y" or "this span means something the contract cannot express."

### `PackDefault`

A seed `FunctionAnnotation` supplied by a pack.

```text
PackDefault {
  selector
  intents
  packId
  packVersion
  provenance = pack-default
}
```

There is no pack-specific user config document. Logging defaults, data-access
defaults, and future defaults all materialize into the same knowledge model.

### `PatternHypothesis`

An optional higher-level abstraction produced by an agent or AI:

```text
PatternHypothesis {
  scope
  evidence
  examples
  counterexamples
  proposedIntents
  provenance
  confidence
  status
}
```

A hypothesis can materialize into one or more `FunctionAnnotation` records, but
it is not used directly for derivation until it has been accepted by policy.

## Knowledge store

The project owns one materialized knowledge store. A possible shape:

```text
.cint/knowledge.yml
```

Exact file layout is open; the model is not. The store is versioned and entries
carry provenance. The deprecated `config.overlay.yml` is not the long-term shape.

Suggested statuses, with review policy designed later:

```text
suggested -> accepted -> established
         \-> rejected
```

The trust and promotion rules are deliberately deferred. The data model must
preserve provenance now so the policy can be added later.

## Derivation

Persisted knowledge feeds deterministic analysis:

```text
FunctionAnnotation(findUser) = LOOKUP
  -> findUser() at call site is LOOKUP
  -> repo.findUser().map(...) is LOOKUP + CHAINING + DATA_TRANSFORMATION
```

Precedence:

1. explicit `SpanOverride`
2. persisted `FunctionAnnotation`
3. materialized pack defaults, which are `FunctionAnnotation` records with
   `pack-default` provenance
4. deterministic rules and patterns that produce candidates

AI is not consulted during this derivation. The function contract is the
expensive inference; propagation is cheap and deterministic.

Function annotations also make underclassification less likely: one accepted
function contract can cover its declaration, call sites, and composed chains.

## Initialization

`init` materializes suggested defaults when no project knowledge exists:

1. Detect languages and relevant libraries.
2. Collect matching pack defaults.
3. Write a materialized knowledge store with provenance.
4. Never overwrite an existing store.
5. On later pack updates, propose a diff instead of silently changing knowledge.

This is the inversion: packs suggest, the project materializes, the editor and
agent edit the project data.

## Writers

All writers go through the same mutation surface:

```text
setFunctionAnnotation
removeFunctionAnnotation
setSpanOverride
removeSpanOverride
materializeDefaults
proposeKnowledgeChange
```

### User and editor

The user can attach an intent to a function, type, import, or span directly.
This is the non-AI value path and must work without any agent or provider.

### MCP agent

The intended AI workflow. An external agent uses repository documentation,
tests, code, and its own context, then calls CodeIntent tools to read analysis
and write knowledge.

Suggested MCP surface:

```text
repo.status / repo.index
analysis.run
knowledge.snapshot
function_annotations.list / set / remove
span_overrides.list / set / remove
patterns.list / propose / validate
knowledge.materializeDefaults / knowledge.suggestDefaults
changes.propose / changes.apply
```

The MCP agent handles the repo-wide init workflow. CodeIntent validates,
materializes, and exposes the data.

### In-house AI

Fallback only:

- uninitialized repo with no agent or user knowledge
- recently changed code not yet updated by the repo agent
- CI, tests, or offline environments

It returns proposals to the pipeline/runtime, which writes them through the same
mutation API. It does not get a separate pipeline or storage model.

## Analysis behavior

```text
indexed source
  + FunctionAnnotations
  + SpanOverrides
  + materialized pack defaults
        |
        v
deterministic rules, propagation, composition
        |
        v
annotations / candidates / published output
```

The deterministic engine remains authoritative. AI and MCP only improve the
knowledge that the engine consumes.

## V1 migration notes

- `core/suggest` is no longer the architectural center. Its discovery session
  and provider loop become the fallback AI path.
- `SuggestionService`, `AnnotationSession`, and DeepSeek provider code are
  candidates for reuse as fallback, not for the primary workflow.
- Per-pack config classes become sources of `PackDefault` records.
- `.cint/config.overlay.yml` is superseded by the materialized knowledge store.
- The V1 AI documents in `docs/ai-intents.md` and
  `docs/ai-annotation-session.md` describe implemented V1 behavior; treat them
  as historical where they conflict with this model.

## Open questions

- Exact knowledge file layout: one file, or split function annotations and
  overrides?
- Selector stability under rename, move, overload, and signature change.
- Trust and promotion policy for `suggested` records.
- Whether fallback AI writes accepted knowledge or only suggestions.
- Whether `PatternHypothesis` is persisted as knowledge or remains an agent
  artifact.
- How call-site propagation compounds confidence across chains.
- Per-repo, per-user, and per-agent knowledge scopes.
