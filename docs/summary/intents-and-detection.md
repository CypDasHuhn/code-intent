# Intents and Detection

## Model

- An **intent** is an open string name attached to a span.
- A span may carry several intents (multi-label, not an enum).
- `UNTAGGED` is reserved: it means unclassified/neutral and is never owned by a
  rule. Rendering and hover treat it as neutral rather than a normal tag.
- One rule owns exactly one intent; no two rules may own the same name.
- Rules may read other intents (`reads`) or optionally read them
  (`optionalReads`). The scheduler resolves names through ownership.
- Rulepacks emit established `Emit` or review-only `CandidateEmit`; only `Emit`
  enters composition.
- Confidence is part of the annotation but candidate-only output is structural:
  AI candidates never become established without a user decision.

## Roles

| role | producer | meaning |
|---|---|---|
| `SOURCE_INTENT` | rulepack or user layer | detected or user-authored intent |
| `DERIVATIVE_USE` | core composition | value carries an intent because of read-only flow |
| `SCAFFOLD` | core composition | enclosing block/function/return region covered by intents |

Plugins may only emit `SOURCE_INTENT`; composition roles are core-only.

## Bundled intents

Enabled in the bundled service descriptor:

| intent | owner | detection shape |
|---|---|---|
| `LOGGING` | `logging` | configured print/logger calls, receiver names, imports, qualified receivers, logger types, logging-state guards |
| `ERROR_HANDLING` | `error-handling-<language>` | `throw`, configured failure calls, throws inside coalesce expressions |
| `BOOKKEEPING` | `bookkeeping` | explicit `// cint:bookkeeping` magic comment; declared-once/write inheritance |
| `VALIDATION` | `validation` | rejection-shaped guards (`return`/`throw`/`continue`/`break`/rejection sentinel) over configured predicates, or `// cint:validation`; reads bookkeeping and mutation intents |
| `NORMALIZATION` | `normalization` | configured normalization/parse/format calls and types |
| `SEARCH` | `data-access:search` | query a collection/repository by criteria where the result may be absent |
| `LOOKUP` | `data-access:lookup` | retrieve a known-to-exist value from a static/preset source: map, table, enum, config, registry |
| `SERVICE_INIT` | `service:init` | construction/DI wiring: constructors, `@Inject`, TS parameter properties, factory construction, init methods |
| `SERVICE_DELEGATION` | `service:delegation` | thin forwarding/delegation to a service; optionally reads `DATA_MODIFY` |
| `DATA_MODIFY` | `mutation:data-modify` | durable/persistence writes; configured compound mutations; durable callbacks |
| `LOCAL_VALUE_MODIFY` | `mutation:local-value-modify` | local mutable writes, loop counters, optional mutable-local declarations, local atomic `getAnd*` |
| `DATA_TRANSFORMATION` | `data-transformation` | configured/typed transform chains over SEARCH evidence; self-read fixpoint |
| `RETURN_VALUE` | `return-value` | the whole return statement, from `return`/label through the returned expression |
| `LOCAL_COPY` | `local-copy` | exact receiver copy chains / local copies; suppresses pure-copy local mutation |

Present but disabled in the current bundled descriptor:

| intent | owner | notes |
|---|---|---|
| `BOOL_COMBINATORICS` | `bool-combinatorics` | boolean token composition pack; disabled |
| `CHAINING` | `chaining` | structural call-chain pack; disabled |

User-layer directives (not pack-owned):

| name | meaning |
|---|---|
| `PURE` | function has no side effects and is deterministic; drives pure-function derivative promotion |
| `READONLY` | function does not write state but may read external state; side-effect-free read for derivative/validation |

`PURE` and `READONLY` stay in the annotation intent set and provenance.

## SEARCH vs LOOKUP

This distinction is about **the kind of data**, not whether the key was
validated earlier.

- **LOOKUP**: the value is known to exist and is retrieved from a static or
  effectively static source — known maps, tables, enums, config, registries.
  Examples: `statusById[id]`, `Color.valueOf(code)`, `config.getValue(key)`.
- **SEARCH**: a criteria query against a collection/repository where the result
  may be absent. A prior guard or validation does not make it a lookup.
  Examples: `repository.findById(id)`, `repo.getById(id)`,
  `repo.loadById(id)`, `findAll`, `findByStatus`, `query`.
- `findBy*`, `getBy*`, and `loadBy*` are SEARCH by default. The default LOOKUP
  boundary candidate is gone; `boundaryPrefixes` remains available as an
  explicit opt-in.
- Prefix matching is token-boundary aware: `getBy` matches `getById` but not
  `getBytes`. Explicit `lookupCalls` take precedence: `getByKey` stays LOOKUP.
- Index writes (`counts[i]++`, `map[key] = value`) are mutation targets, not
  LOOKUP; reads inside the target and RHS reads can still be LOOKUP.

## Scaffolds and rendering

- A scaffold carries the covered intents for an enclosing region.
- Multi-intent scaffolds skip color/highlight by default.
- `UNTAGGED` is ignored when choosing the visual meaning/tag **only when a real
  intent remains**. An `{ERROR_HANDLING, UNTAGGED}` scaffold renders as
  ERROR_HANDLING; an `UNTAGGED`-only span keeps its configured meaning
  (including `IGNORE`/`CONCEAL`).
- Hover and labels omit `UNTAGGED`; hover selects the innermost annotation span
  under the caret and combines intents only for exactly coincident spans.

## Candidates and AI

Rulepacks may emit candidates where they are uncertain (`GAP`,
`LOW_CONFIDENCE`, `PACK_DOMAIN`). Candidates never affect composition or
established tags. AI enrichment only sees pack-emitted, `aiEligible`
candidates; it cannot scan arbitrary lines. AI defaults are now uncapped:
every eligible candidate is sent and every validated proposal is kept, with the
provider timeout as the safety valve.

See [annotations-and-ai.md](annotations-and-ai.md) for the full candidate/AI
contract.
