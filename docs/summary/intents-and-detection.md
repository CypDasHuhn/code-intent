# Intents and Detection

## Model

- An **intent** is an open string name attached to a span.
- A span may carry several intents (multi-label, not an enum).
- `UNTAGGED` is reserved: it means unclassified/neutral and is never owned by a
  rule. Rendering and hover treat it as neutral rather than a normal tag.
- One rule owns exactly one intent; no two rules may own the same name.
- Rules may read other intents (`reads`) or optionally read them
  (`optionalReads`). The scheduler resolves names through ownership.
- Packs emit established annotations or review-only candidates. Only
  established annotations enter composition and knowledge.
- Confidence is diagnostic; candidate-only output is structural, so AI results
  never become established without a user or agent decision.

## Roles

| role | producer | meaning |
|---|---|---|
| `SOURCE_INTENT` | pack or user/knowledge layer | detected or authored intent |
| `DERIVATIVE_USE` | core composition | value carries an intent through read-only flow |
| `SCAFFOLD` | core composition | enclosing block/function/return region covered by intents |

Packs may only emit `SOURCE_INTENT`; composition roles are core-only.

## Bundled intents

| intent | owner | detection shape |
|---|---|---|
| `LOGGING` | `logging` | configured print/logger calls, receiver names, imports, qualified receivers, logger types, logging-state guards |
| `ERROR_HANDLING` | `error-handling` | `throw`, configured failure calls, throws inside coalesce expressions |
| `BOOKKEEPING` | `bookkeeping` | explicit `// cint:bookkeeping` magic comment; declared-once/write inheritance |
| `VALIDATION` | `validation` | rejection-shaped guards over configured predicates, or `// cint:validation`; reads bookkeeping and mutation intents |
| `NORMALIZATION` | `normalization` | configured normalization/parse/format calls and types |
| `SEARCH` | `data-access` | query a collection/repository by criteria where the result may be absent |
| `LOOKUP` | `data-access` | retrieve a known-to-exist value from a static/preset source |
| `SERVICE_INIT` | `service` | construction/DI wiring: constructors, `@Inject`, TS parameter properties, factories, init methods |
| `SERVICE_DELEGATION` | `service` | thin forwarding/delegation to a service; optionally reads `DATA_MODIFY` |
| `DATA_MODIFY` | `mutation` | durable/persistence writes, compound mutations, durable callbacks |
| `LOCAL_VALUE_MODIFY` | `mutation` | local mutable writes, loop counters, mutable-local declarations, local atomic `getAnd*` |
| `DATA_TRANSFORMATION` | `data-transformation` | configured/typed transform chains over `SEARCH` evidence |
| `RETURN_VALUE` | `return-value` | the whole return statement, from `return` through the returned expression |
| `LOCAL_COPY` | `local-copy` | exact receiver copy chains / local copies; suppresses pure-copy local mutation |
| `BOOL_COMBINATORICS` | `bool-combinatorics` | boolean token composition pack; disabled by default |
| `CHAINING` | `chaining` | structural call-chain pack; disabled by default |

`USER_FEEDBACK` is reserved for the user/knowledge layer. `PURE` and `READONLY`
remain user directives in the knowledge model and carry their composition
semantics.

## SEARCH vs LOOKUP

This distinction is about **the kind of data**, not whether the key was
validated earlier.

- **LOOKUP**: the value is known to exist and is retrieved from a static or
  effectively static source — known maps, tables, enums, config, registries.
- **SEARCH**: a criteria query against a collection/repository where the result
  may be absent. A prior guard or validation does not make it a lookup.
- `findBy*`, `getBy*`, and `loadBy*` are `SEARCH` by default. Explicit lookup
  calls take precedence: `getByKey` stays `LOOKUP`. Prefix matching is
  token-boundary aware, so `getBy` matches `getById` but not `getBytes`.

## Scaffolds and rendering

- A scaffold carries the covered intents for an enclosing region.
- Multi-intent scaffolds skip color/highlight by default.
- `UNTAGGED` is ignored when choosing the visual meaning/tag only when a real
  intent remains. An `{ERROR_HANDLING, UNTAGGED}` scaffold renders as
  `ERROR_HANDLING`; an `UNTAGGED`-only span keeps its configured behavior.
- Hover and labels omit `UNTAGGED`; hover selects the innermost annotation span
  under the caret and combines intents only for exactly coincident spans.

## Knowledge and candidates

Pack seed defaults are materialized into the project knowledge store once.
Function annotations then resolve and propagate deterministically to call sites
and chains. Candidates remain review-only. Fallback AI and MCP agents write
proposals or knowledge through the same mutation API used by users.

See [annotations-and-ai.md](annotations-and-ai.md) for the full
knowledge/candidate/AI contract.
