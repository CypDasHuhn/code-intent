# Intent Model — Design Notes

Snapshot of the current model and the design directions discussed for CodeIntent.
This is intentionally broader than the implementation: it separates what the
engine does today from what we want to support later.

## 1. Core model

The engine emits **roles + intents + spans + provenance**; the client resolves
intents into visual meanings.

- Roles: `SOURCE_INTENT`, `DERIVATIVE_USE`, `SCAFFOLD`.
- Intents are a set, e.g. `{VALIDATION, BOOKKEEPING}`.
- `UNTAGGED` is the reserved "unclassified effect" intent. Clients ignore it
  when resolving a scaffold's visual meaning/tag and when deciding whether a
  scaffold is mixed-intent, so a scaffold carrying one real intent plus
  `UNTAGGED` renders as that intent.
- Tag/intent names are screaming snake case: `LOGGING`, `ERROR_HANDLING`,
  `BOOKKEEPING`, `VALIDATION`, `NORMALIZATION`, `SEARCH`, `LOOKUP`,
  `SERVICE_DELEGATION`, `SERVICE_INIT`, `DATA_MODIFY`, `LOCAL_VALUE_MODIFY`,
  `BOOL_COMBINATORICS` (currently disabled in the bundled selection), `DATA_TRANSFORMATION`, `RETURN_VALUE`, `CHAINING` (currently disabled),
  `LOCAL_COPY`, `USER_FEEDBACK` (planned), `UNTAGGED`.
- Source annotations remain code-shaped: `@Bookkeeping`, `@UserFeedback`,
  `// cint:bookkeeping`, `// cint:validation`.
- The engine has no colors or visual policy. `tags[intent].meaning` and
  `rules[meaning]` live on the client.

Each annotation carries `rule`, `source`, `confidence`, a span, and contributor
`provenance`. That provenance is part of the product: it is what makes
review/explain possible.

## User directives

User annotations may carry any intent name, and the engine preserves unknown
names as user intent facts. Two names have built-in directive semantics:

- `PURE` — the function has no side effects and is deterministic.
- `READONLY` — the function does not write state, but may read external state.

`PURE` therefore implies both determinism and no side effects; `READONLY`
implies only that the function does not write state. Calls to a function
annotated with either directive are treated as side-effect-free reads by
derivative-use and validation composition. `PURE` also participates in the
pure-function derivative path. Both directives remain part of the annotation's
intent set and provenance; they are not pack-owned.

## 2. Current sources

### `LOGGING`

- Logger calls via typed receiver, factory-initialized property, qualifier, or
  import-gated/receiver-name heuristics.
- Strong heuristics (factory property, qualifier) skip LSP.
- Weak heuristics (`receiver-name`, import-gated) consult the oracle when
  available; a resolved non-logger type vetoes them.
- Live LSP hover timeout is capped; failures fall back to heuristics.
- Receiver types are memoized per analysis, and the live oracle caches positive
  hovers per document version. Empty hovers use a bounded negative TTL and are
  revalidated after it instead of becoming durable negatives. Timeouts and
  failures are memoized only within one analysis and retried by later analyses;
  they are not durable cache hits. A per-analysis timeout, failure and
  elapsed-time budget stops further oracle queries so repeated unresolved
  receivers degrade to text/config evidence.
- Trailing lambda arguments are included in the call span.

### `ERROR_HANDLING`

Elvis/coalesce throws (`x ?: throw ...`, `x ?? throw ...`) are tagged from
the `?:`/`??` operator through the throw; the left operand and the enclosing
declaration are not error handling.

- `throw` and configured checks: `require`, `requireNotNull`, `check`,
  `checkNotNull`, `error`, `TODO`, `assert`.
- Trailing lambda spans are included.
- Error handling is for failure/throw semantics; not every early exit.

### `BOOKKEEPING` (implemented)

- Declared once on a property with `@Bookkeeping` or
  `// cint:bookkeeping`.
- The declaration and all writes (`x++`, `x += 1`, assignment) inherit
  `BOOKKEEPING`.
- Reads are not tagged as bookkeeping.
- A preceding magic comment is part of the declaration span.
- The annotation layer is preferred over user-source configuration for now.
- No automatic inference from variable names. Explicit declaration is the
  source of truth.

### `VALIDATION` (implemented, rejection-shaped)

Validation means **rejecting/skipping input**, not every early return.

Current behavior:

- establishes when a then/else branch contains a rejecting exit and the
  condition is not a known write;
- a rejecting exit is bare `return`/`continue`/`break`, or a `return`
  whose expression is a configured rejection sentinel (`null`, `false`, `0`,
  empty literals/collections, ...); a normal-value return does not reject;
- `throw` guards are `ERROR_HANDLING`, not `VALIDATION`, unless explicitly certified;
  unbraced single-statement bodies count in Kotlin, JavaScript,
  TypeScript, and C#;
- `continue`/`break` only reject when they belong to the guard's own loop or
  switch. A jump inside a nested loop, nested switch, or nested
  function/lambda does not certify the outer guard;
- elvis/coalesce forms (`x ?: continue`, `x ?: return sentinel`) use the same
  rejection and known-write gates; `throw` is not a validation certificate;
- these paths are rejection-independent by design:
  - a validation predicate (`isEmpty`, `isBlank`, `none`, `any`, `all`,
    `isValid*`, `has*`, ...),
  - a `BOOKKEEPING` write in the branch,
  - an explicit `// cint:validation` comment;
- a constant-only condition still needs explicit or bookkeeping evidence;
- a read-only condition with a rejecting exit is established at lower
  confidence. Unknown read-only status stays validation with reduced confidence
  instead of being dropped. Known writes and mutation intents (`DATA_MODIFY`,
  `LOCAL_VALUE_MODIFY`, `BOOKKEEPING`) block the read-only path;
- contributes an ambient `VALIDATION` scaffold over the guard so plumbing
  locals read inside the guard become `DERIVATIVE_USE` with `VALIDATION`;
- bare early exits are neutral; `return false` and bare `return` do not make a
  scaffold `UNTAGGED`;
- the enclosing `if` composes e.g. `{VALIDATION, BOOKKEEPING}`.

Confidence is diagnostic: explicit comment `1.0`, bookkeeping `0.9`, predicate
`0.7`, side-effect-free condition `0.6`, read-only fallback `0.5`, elvis `0.6`.

Known problem:

- local shape still cannot fully separate a genuine rejection from a cache-hit,
  found-value, or ordinary early return. The rejection and nested-scope gates
  remove the broadest false positives, but a guard that returns a rejection
  sentinel for a business reason can still be over-tagged.
- There is a gradient: validation early return, cache-hit return, simple value
  return, found-value return. The line cannot be drawn reliably with local
  shape heuristics.

The design direction is to stop treating shape as a certificate and to use
candidate/confirmed states (see §7). Possible final rules:

- explicit annotation/comment;
- a genuinely validating predicate plus a rejection effect;
- `BOOKKEEPING` plus a direct exit;
- domain analyzers/user annotations for project-specific cases.

### `USER_FEEDBACK` (planned)

`player.msg(...)` is a user-facing app effect, not diagnostic logging. It should
be its own intent, not folded into `LOGGING`.

Proposed source:

```kotlin
@UserFeedback
fun Player.msg(...)
```

or

```kotlin
// cint:user-feedback
fun Player.msg(...)
```

Calls to the annotated function inherit `USER_FEEDBACK`. A validation guard that
emits feedback then exits becomes:

```text
if (target == null)          {VALIDATION}
    player.msg(...)          {USER_FEEDBACK}
    return                   neutral
```

so the `if` becomes `{VALIDATION, USER_FEEDBACK}`.

`APP_EFFECT` is considered too broad: messages, sounds, inventory mutations,
etc. have different semantics. Generic effects can be a separate taxonomy later.

### Implemented intents (2026-10-21)

Definitions for the current taxonomy batch. Deterministic detection where
config/explicit patterns exist; candidates for the ambiguous remainder.

| intent | meaning | include | exclude / boundary |
|---|---|---|---|
| `NORMALIZATION` | canonicalize a value for downstream use | trim, case fold, parse/coerce, date/time format, path/unit normalization | rejecting input (`VALIDATION`), persistence (`DATA_MODIFY`), display-only formatting until configured |
| `SEARCH` | retrieve data by criteria from a source | repository queries, `findBy*`/`getBy*`/`loadBy*`/`search*`/`query*`, filters, external search APIs; empty/many results | known-key single-value mapping (`LOOKUP`), pure transformation (`NORMALIZATION`) |
| `LOOKUP` | resolve one known value by key/constant | map/dictionary get, enum/constant maps, config keys, static tables, enum-like value to DB id | criteria-based retrieval (`SEARCH`), service forwarding (`SERVICE_DELEGATION`) |
| `SERVICE_DELEGATION` | transfer responsibility to another service/component | controller→service, facade, adapter, thin call-through/argument adaptation | constructing/wiring (`SERVICE_INIT`), local business logic, the inner call's own intent |
| `SERVICE_INIT` | build/configure/wire a service or resource | construction, client creation, connection setup, DI/bootstrap | initializing business data, delegation, every constructor call |
| `DATA_MODIFY` | change that persists beyond the process | repository save/update/delete, ORM writes, durable file writes, queue/event publication, durable compound writes such as `findOneAndUpdate`/`findAndModify` | in-memory mutation (`LOCAL_VALUE_MODIFY`), bookkeeping counters |
| `LOCAL_VALUE_MODIFY` | mutate a local/field/in-memory object | assignment, increment/decrement, atomic `getAndIncrement`/`getAndUpdate`/`getAndDecrement`, configured mutable collection operations; opt-in mutable local declarations with later writes | persistence (`DATA_MODIFY`), bookkeeping writes unless configured, implicit `for`-loop counters, unwritten/`val` declarations, declarations used only for pure copies |
| `BOOL_COMBINATORICS` | complex boolean composition | multiple/mixed `&&`/`||`, nested/negated compounds, `all`/`any`/`none` (and `every`/`some`) over predicates, two or more distinct classified predicates | simple `if (x != null)`, single comparisons, trivial `a && b`, negation-only guards, plain literals, `is`/`has` prefix collisions such as `issue`/`hashCode` |
| `DATA_TRANSFORMATION` | shape/transform an existing value or collection | `map`, `filter`, `flatMap`, `fold`, `reduce`, `collect`, `distinct`, `sorted`, `groupBy`, `all`/`any`/`none`; in-place JS mutators such as `sort`/`reverse` may co-occur with `LOCAL_VALUE_MODIFY` | fetching data (`SEARCH`), single-value canonicalization (`NORMALIZATION`), persistence (`DATA_MODIFY`); unclassified/unknown receivers stay candidates |
| `RETURN_VALUE` | explicitly return a computed value | `return expr`, including value-bearing labeled returns (`return@map expr`); emitted over the whole return statement from the `return` keyword through the expression | bare `return`, bare `return@label`, literal/constant-only returns unless `includeLiterals` is enabled; never suppresses other intents |
| `CHAINING` | fluent/chained call shape | at least two exact call links, e.g. `Factory.For().Use().Build()`; the outer call is the primary emit | boolean `||` operands and parenthesized OR receivers (`BOOL_COMBINATORICS`), single calls; ambiguous conditional/ternary receivers are candidates |
| `LOCAL_COPY` | copy a parameter into a field/local | field-like target (`_db`, `m_`/`s_`/`f_` or configured prefix, or owner-qualified) from a bare non-field identifier; assignments and declaration/property initializers, including class/constructor copies | general local mutation (`LOCAL_VALUE_MODIFY`), persistence (`DATA_MODIFY`); field/self copies, declared-property sources, and case variants do not establish; non-established simple shapes (including field/self and ambiguous owner-qualified targets) are candidates when enabled |

Bundled ownership and deployment:

- `normalization:normalization` owns `NORMALIZATION`; configured calls and
  prefixes establish only with qualifier/type evidence, while display formatting
  and unconfigured chain tails are candidates.
- `data-access:search` and `data-access:lookup` own `SEARCH` and `LOOKUP`; both
  are source-only rules registered in bundled discovery. Ambiguous retrieval
  calls and SEARCH/LOOKUP boundaries are candidates.
- `service:delegation` and `service:init` own `SERVICE_DELEGATION` and
  `SERVICE_INIT`; thin forwarding is judged independently of the delegate's
  intent, and factory/connection/DI work belongs to init.
- `mutation:data-modify` and `mutation:local-value-modify` own `DATA_MODIFY` and
  `LOCAL_VALUE_MODIFY`; persistence needs durable receiver/path evidence, and
  local writes exclude bookkeeping and implicit `for`/`foreach` counters;
  explicit `while`-loop increments/decrements and atomic `getAnd*` operations
  are local modifications. `local-value-modify` also
  reads `LOCAL_COPY` (so the local-copy owner must be in the plan) and
  suppresses a write's local mutation by default when that write is an
  established `LOCAL_COPY`. Mutable local declaration tagging is opt-in via
  `mutableLocalDeclarations` (default off) and covers mutable Kotlin `var`,
  JavaScript/TypeScript `let`/`var`, and mutable C# locals, emitting on each
  written declarator rather than the shared statement wrapper. The default
  established set stays write-only and does not add noise for
  `val`/`const`/`readonly` or unwritten multi-declarator siblings.
- Callback calls to function-typed parameters are `SERVICE_DELEGATION`
  candidates when generic. If the parameter's default/known implementation
  references a qualified configured durable path/call such as `Files::move` or
  `fs.rename`, the call establishes `DATA_MODIFY` instead; this uses
  declaration-local callback evidence, not whole-program dataflow, and the call
  is not treated as a local mutation. The service rule consumes the mutation
  pack's `DATA_MODIFY` classification as an optional read, so configured target
  additions/removals stay coherent instead of relying on a separate hardcoded
  service list.
- `bool-combinatorics:bool-combinatorics` owns `BOOL_COMBINATORICS`. It is
  deliberately selective: simple conditions stay untagged, and borderline
  compound booleans (`a && b`) are low-confidence candidates. Nested booleans
  inside a non-quantifier call are not roots.
- `data-transformation:data-transformation` owns `DATA_TRANSFORMATION` and reads
  `SEARCH` plus its own intent, so retrieved receivers and chained transforms
  settle before the next conversion. Established output requires qualifier,
  known collection type, or a receiver already classified `SEARCH` or
  `DATA_TRANSFORMATION`. Unknown, unclassified, and offline-oracle receivers
  (including call chains not classified as search/transformation) stay
  candidates unless a configured qualifier matches. Ambiguous helpers
  (`transform`, `process`, `reshape`, `convert`, ...) are candidates.
- `return-value:return-value` owns `RETURN_VALUE`; it reads no other intent and
  is purely additive. It emits over the whole return statement from the `return`
  keyword through the expression, so domain/search/validation intents on the
  same expression are preserved on their own spans. Bare labeled returns are
  skipped; a labeled return with a value emits the labeled return statement.
  `RETURN_VALUE` does not participate in scaffold category resolution, so it
  never replaces or blocks the returned domain intent.
- `chaining:chaining` owns `CHAINING`; it reads no other intent. A link requires
  the next inner call to cover the receiver's exact node/site span, so calls
  buried in indexes, arguments, or binary expressions do not become links.
  Chains at or above `minimumLinks` (default 2) emit once at the outer call;
  ambiguous conditional/ternary bases stay low-confidence candidates.
- `local-copy:local-copy` owns `LOCAL_COPY`; it reads no other intent.
  Established copies require a field-like LHS and a non-field-like RHS whose
  normalized names match exactly and case-sensitively (configured prefix then
  leading underscores are stripped). Declarations and class/constructor
  property initializers are considered, not only assignments. Parameter proof is
  heuristic: declared properties are excluded, but same-named locals, loop/catch
  bindings, or globals are not fully resolved. Field/self copies and ambiguous
  owner-qualified targets do not establish and remain candidates when enabled.

Overlaps are intent sets, not exclusive labels, but packs should avoid
double-tagging unless both meanings genuinely apply. Persistence wins over local
mutation; validation and normalization may coexist; delegation does not replace
the delegate's business intent. Generic local mutation and most
search/lookup/service boundaries are expected to be candidate-heavy and
AI-enhanced rather than painted as established.

Concrete overlaps and precedence:

- **`SEARCH` / `DATA_TRANSFORMATION`** — retrieval that also shapes results can
  carry both. `SEARCH` describes fetching; `DATA_TRANSFORMATION` describes the
  shaping of what was fetched.
- **`BOOL_COMBINATORICS` / `DATA_TRANSFORMATION`** — `all`/`any`/`none` (and
  `every`/`some`, `All`/`Any`) are configured transformations and boolean
  quantifiers, so a quantified predicate may carry both. Reductions such as
  `fold`/`reduce` remain transformations even when they produce a scalar.
- **`DATA_TRANSFORMATION` / `LOCAL_VALUE_MODIFY`** — in-place collection
  mutators (`sort`, `reverse`, `splice`) may be both; the transformation pack
  does not suppress the mutation pack, and vice versa.
- **`DATA_TRANSFORMATION` / `VALIDATION`** — a validating predicate applied to a
  transformed collection can carry both; neither replaces the other.
- **`DATA_TRANSFORMATION` / `NORMALIZATION`** — `NORMALIZATION` canonicalizes a
  single value; a later collection shaping call is still a transformation.
- **`RETURN_VALUE` / any other intent** — `RETURN_VALUE` is additive. A returned
  expression can simultaneously carry `SEARCH`, `VALIDATION`, `DATA_MODIFY`,
  `CHAINING`, and so on; the return pack never removes or replaces those
  intents.
- **`CHAINING` / `SERVICE_INIT`, `SERVICE_DELEGATION`, `SEARCH`,
  `DATA_TRANSFORMATION`** — a fluent construction/fetch/transform chain may carry
  `CHAINING` alongside the domain intent on the same outer call.
  `CHAINING` describes syntax shape; it does not imply ownership transfer,
  construction, retrieval, or shaping by itself.
- **`CHAINING` / `BOOL_COMBINATORICS`** — direct operands of `||` and
  parenthesized OR receivers are not `CHAINING`; `||` inside a string literal,
  call argument, or recognized lambda boundary does not suppress a real chain.
- **`LOCAL_COPY` / `LOCAL_VALUE_MODIFY`** — the mutation pack reads
  `LOCAL_COPY`. With `ignorePureCopies` default-on, an established copy at the
  write suppresses that write's generic local mutation. Setting
  `ignorePureCopies: false` restores co-occurrence; candidate copies never
  suppress because they are not annotations. Persistence (`DATA_MODIFY`) is
  unaffected. With `mutableLocalDeclarations` enabled, a declaration is also
  suppressed when it is an established copy or when all of its later writes are
  pure copies/bookkeeping/loop-counter writes.
- **Callback `SERVICE_DELEGATION` / `DATA_MODIFY` / `LOCAL_VALUE_MODIFY`** — a
  generic function-typed parameter invocation may carry a
  `SERVICE_DELEGATION` candidate. This includes direct invocations, bare
  JS/TS function-reference defaults in function declarations and arrow
  functions, and interface-aware SAM calls such as `Consumer.accept`,
  `Runnable.run`, `Supplier.get`, `Callable.call`, `Predicate.test`,
  `Function.apply`, and Kotlin function-type `invoke`. The outer SAM type wins
  over function arrows in type arguments, so `Consumer<(Path) -> Unit>` maps to
  `accept`. Function-typed aliases/custom delegates fall back to their default
  reference evidence for direct invocation, while receiver-SAM mapping stays
  declared-type-only. A callback whose default/known implementation is a
  qualified configured durable operation
  establishes `DATA_MODIFY`; service suppression reads that classification
  optionally, so it does not keep a second hardcoded target set. Neither shape
  becomes `LOCAL_VALUE_MODIFY` solely because the callee is a function
  parameter; local mutation still needs an actual write to a local.

Structural scratchpad examples:

```kotlin
return _adminUserId.Value            // RETURN_VALUE over the return statement (+ domain intents on the value)
Factory.For().Use().Build()          // CHAINING (+ SERVICE_INIT where applicable)
val _db = db                         // LOCAL_COPY declaration copy
_db = db                             // LOCAL_COPY; LOCAL_VALUE_MODIFY suppressed by default
this._db = _db                       // field/self shape: candidate, not established
this.database = db                   // ambiguous field target: candidate
return@map it + 1                    // RETURN_VALUE over `return@map it + 1`
return@forEach                       // no RETURN_VALUE
```

## 3. Derivative use and scaffolds

### Derivative use

A property/local is `DERIVATIVE_USE` when its reads flow into classified spans.

Local derivative promotion uses a **read-only** policy, not purity:

- promotion is blocked by known writes/mutations: a `WriteFact` inside the
  initializer, or a `DATA_MODIFY`, `LOCAL_VALUE_MODIFY`, or `BOOKKEEPING` source
  annotation inside it.
- external reads are allowed. Known read-only initializers derive at `0.7`:
  literals/constants, identifiers, side-effect-free expressions, annotated pure
  calls, user-pure calls, and user-readonly calls.
- an initializer without that evidence is treated as **unknown read-only** and
  derives at `0.55`. This lowers confidence; it does not promote a candidate to
  established and it does not certify purity.
- a local is only promoted when all reads are classified; mixed/untagged reads
  prevent promotion, except when any read carries `RETURN_VALUE`. In that case
  the local and its writes inherit `RETURN_VALUE` even if sibling reads are
  unclassified, so `val result = ...` reaches a returned `result.value` and
  `val updated = ...` reaches `LanguageConfig(updated)`. Other intents still
  require every read to be classified.
- Intents at a read are resolved from the smallest containing annotation, which
  is what lets reads inside an error-handling lambda inherit `ERROR_HANDLING`.

Declaration and assignment propagation:

- when a property initializer or assignment RHS is read-only and carries source
  intents, the whole declaration/assignment statement inherits them as
  `DERIVATIVE_USE`, so `val uses = <SEARCH expression>` paints the assignment,
  not only the RHS.
- intents from descendants inside lambdas or call arguments lose effect intents
  (`LOGGING`, `ERROR_HANDLING`, `DATA_MODIFY`, `LOCAL_VALUE_MODIFY`,
  `BOOKKEEPING`) but keep value intents such as `SEARCH` and
  `DATA_TRANSFORMATION`.
- the propagated contribution uses the minimum source confidence, and merged
  contributions on the same span also take the minimum, so a high-confidence
  effect cannot inflate a weaker intent.
- plain assignment statements and property initializers follow the same
  read-only gate.

- writes to a local whose reads flow into a classified span inherit the same
  derivative-use intents; a local collection populated in a loop and returned
  through `merged.values.toList()` becomes `RETURN_VALUE` on the declaration and
  its writes.
Functions whose references all occur inside derivative-use declarations are
promoted only when they are explicitly pure. Purity is explicit (`@Pure`,
`@Contract(pure = true)`) or user-declared; there is no body purity inference.
Unannotated calls are not assumed pure — for local initializers they are
unknown read-only (`0.55`).

### Scaffolds

- Bottom-up union of guard/body intents.
- Side-effect-free guards are neutral; effectful guards add `UNTAGGED`.
- Bare `return` / `continue` / `break` are neutral. `return expr` carries the
  expression's intents when it has any.
- Mixed `if`/`else` emits separate then/else spans so an untagged else does not
  force the then branch to be shown.
- Whole-try classification requires full coverage; try-header classification
  only needs covered catches.
- Catch spans include the try-body closing `}`.
- Functions whose bodies are fully classified become scaffolds.

## 4. Confidence, candidates, and review UX

Confidence is **not** a user-tuned filter. It is a review state.

Review states:

- `established` — certified; normal painting.
- `candidate` — possible intent; shown non-destructively; needs review.
- `suppressed` — user rejected this occurrence; don't suggest it again here.

Candidate rules:

- never conceal or apply destructive meanings;
- use a separate style/namespace: dotted underline, muted highlight, `?` sign,
  virtual text, etc.;
- be reviewable on demand, not with popups while typing;
- show counts such as `CodeIntent: 3 unsure`.

Implemented UX:

```text
:CintReview
src/.../NavCommands.kt:42 · VALIDATION · 1 alternative(s)
[Confirm] [Reject] [Skip]
```

- Confirm → this occurrence becomes established: the server writes an existing
  `.cint/user-annotations.json` record and the next analysis re-evaluates it as
  a `user` source. It also keeps a confirm record as audit history.
- Reject → this occurrence is suppressed in `.cint/suggestions.json` and is not
  proposed again for the same identity.
- Skip → unchanged for the current server session only.

Confirmation is **occurrence-scoped**. It does not raise the rule's confidence
for other tags or other files. Decisions are project-local:

```json
{
  "version": 1,
  "decisions": [
    {
      "candidateId": "<identity hash>",
      "path": "src/.../NavCommands.kt",
      "contentHash": "<anchor hash>",
      "intents": ["VALIDATION"],
      "reason": "GAP",
      "decision": "confirm",
      "createdAt": "..."
    }
  ]
}
```

The implemented identity is path + anchor/content hash + byte span + sorted
intents + reason. The stronger identity remains a deferred refinement:

- file + enclosing symbol + tree-sitter node path (kinds/child indices) or a
  stable node anchor;
- normalized evidence hash;
- intent + rule;
- invalidate and re-ask when the construct is structurally edited;
- line/byte spans alone are not sufficient.

The server persists the decision and republishes immediately; the client does
not block on a local overlay. An optional action could write a
`// cint:validation` source comment instead, which is maximally durable but
edits code.

Numeric confidence remains diagnostic; the client uses the explicit
`candidate`/`established` state rather than a threshold slider.

This model is also the right shape for an optional LLM or heuristic suggestion
layer later: uncertain output is candidate-only and becomes knowledge only after
user confirmation.

## 5. User sources

User-source configuration was considered and deliberately deferred for now:

- it is powerful for third-party/generated code;
- it creates repetition/scope/naming problems;
- annotations/comments are simpler and local.

Support both eventually:

- annotations/comments for editable declarations;
- user sources for external symbols or broad project policy;
- the analyzer DSL (see §6) can express the same thing.

## 6. Analyzer DSL and query layer

The long-term value is that CodeIntent users can write their own intent analyzers.

Pipeline:

```text
source -> tree-sitter/facts -> intent analyzers -> intent graph/CFG -> client
```

An analyzer is:

- a match over facts (AST nodes, calls, symbols, writes, scopes, annotations),
- optional predicates over prior intents/CFG,
- an emit of `intent + target + evidence`.

Declarative first:

```yaml
analyzers:
  player-message:
    intent: USER_FEEDBACK
    match:
      call:
        callee: msg
        receiver: player
    emit: call
    confidence: 0.9
```

Or tree-sitter-query style:

```scheme
(call_expression
  (navigation_expression
    (simple_identifier) @receiver
    (navigation_suffix (simple_identifier) @callee))
  (#eq? @callee "msg")) @match
```

For complex rules, a constrained scripted API:

```lua
analyzer("validation_feedback", function(ctx)
  for guard in ctx:guards_with_early_exit() do
    if guard:has_intent("BOOKKEEPING") or guard:has("USER_FEEDBACK") then
      ctx.emit(guard.condition, "VALIDATION", "candidate")
    end
  end
end)
```

Inspired by tree-sitter grammars: users write a constrained DSL, not C; a
compiler turns it into optimized query plans.

Design constraints:

- declarative block semantics, not arbitrary code by default;
- compile to typed IR -> query plan;
- index facts, push filters down, reorder joins, cache per file/version;
- validate rules and explain why they fired;
- support phases/dependencies and candidate/established states;
- sandbox/WASM escape hatch only when necessary;
- no Turing-completeness by default.

## 7. CFG and intent queries

### Implemented v1

A function-local CFG is built from tree-sitter facts before rule evaluation.
`ControlFlowBuilder` walks each function body and records every return with:

- the chain of guarding `if` conditions and branch polarity,
- the enclosing loop,
- the enclosing catch/finally.

`core` `ReturnComposition` then classifies returns using certified guard
annotations, without knowing the validation pack's identity:

- a return on the true edge of a certified validation guard is `VALIDATION`,
- a rejection return (`null`, `false`, `0`, bare return, ...) on the other edge
  of a certified guard is also `VALIDATION`,
- everything else is `UNTAGGED`, including normal final returns.

`UNTAGGED` returns block whole-function scaffolds, so a function ending in a
business return is no longer concealed or painted as one intent.

Current limits: switch/when returns are not yet collected, Kotlin inline
non-local returns are treated as lambda-local, and guard polarity is still
approximate (a pure guard plus any value on the true edge counts as validation).


Once facts include CFG nodes/edges, intents become graph predicates:

- validation branches whose only effect is `USER_FEEDBACK`;
- paths from `VALIDATION` to a business effect;
- logging-only regions with no user-visible exit;
- error-handling paths that emit feedback but never mutate state;
- all user-facing outcomes reachable after validation failure;
- dead bookkeeping counters that never affect control flow.

This is where the gradient problem is resolved properly:

- validation = rejection edge to an exit/feedback path, usually dominating the
  normal path;
- cache hit = guard following a cache lookup, returning cached data;
- found value = loop/search edge returning a found value;
- simple early value = ordinary branching.

Those are CFG/dataflow facts plus domain analyzers, not one local heuristic.

## 8. Live/client notes

- `tags.json` is offline/batch only. The live daemon never writes it.
- Live mode is server-only; if the daemon is unavailable it reports failure
  instead of silently using a stale artifact.
- The live daemon is per project root, detached, and reused across nvim
  restarts.
- `:CintServerDisconnect` disconnects; `:CintServerStop` kills the
  daemon; `:CintServerRestart` stops and respawns.
- `:CintLiveTags` shows versions, profile, shards, annotations, extmarks,
  and pending state.
- The daemon holds in-memory docs and loaded engine config; changes to the
  project detection config passed with `--config`, `server_cmd`, LSP flags, or a
  rebuilt binary require a daemon restart. Config-overlay mutations are the
  exception: the server writes `<root>/.cint/config.overlay.yml` and reloads
  packs in place.
- Profiles/shards are client-side and only re-render cached annotations.
- Live keeps old marks while a new version is pending; reveal-on-cursor reveals
  all annotations contained in the active scaffold/vertical block.
- Tag names and profiles are uppercase. Client defaults include `LOGGING`,
  `ERROR_HANDLING`, `BOOKKEEPING`, `VALIDATION`, `NORMALIZATION`, `SEARCH`,
  `LOOKUP`, `SERVICE_DELEGATION`, `SERVICE_INIT`, `DATA_MODIFY`,
  `LOCAL_VALUE_MODIFY`, `BOOL_COMBINATORICS`, `DATA_TRANSFORMATION`,
  `RETURN_VALUE`, `CHAINING`, `LOCAL_COPY`, `UNTAGGED`.

## 9. Open decisions

1. Validation certificate: explicit-only, or a small set of certifying patterns
   (`BOOKKEEPING` + exit, validated predicate + exit, feedback/error + exit)?
2. How to handle else-exit validation polarity without leaking `VALIDATION`
   into the business branch.
3. When to introduce `CACHE_HIT` / `FOUND_VALUE` and whether they are separate
   intents or analyzer-specific.
4. Confirmation storage: project-local overlay vs source comments vs both.
5. Node-anchor implementation and invalidation rules.
6. Candidate rendering style and review workflow details.
7. Whether `USER_FEEDBACK` is the final name; alternatives: `NOTIFICATION`,
   `USER_MESSAGE`.
8. Analyzer DSL surface syntax and which language runs it.
9. How much of the analyzer layer lives in the engine vs the client.
10. When, if ever, to add LLM-generated candidates.

## 10. Implementation snapshot

The current engine is the V2 tree under `analysis-engine/`. Key files:

- `analysis-engine/contracts/src/commonMain/kotlin/dev/cint/contracts/data/` —
  intents, roles, annotations, candidates, provenance, and spans.
- `analysis-engine/contracts/src/commonMain/kotlin/dev/cint/contracts/ports/` —
  `SyntaxEngine`, `SemanticProvider`, `Index`, `KnowledgeStore`,
  `RulePackSource`, `IntentPipeline`, `Runtime`, and transports.
- `analysis-engine/contracts/src/commonMain/kotlin/dev/cint/contracts/knowledge/` —
  function annotations, span overrides, pattern hypotheses, pack defaults, and
  `KnowledgeChange`.
- `analysis-engine/core/src/main/kotlin/dev/cint/core/` — scheduler, strata,
  capability view, annotation store, and composition.
- `analysis-engine/packs/src/main/resources/packs/bundled-packs.json` —
  manifest packs, playbooks, owned intents, and seed defaults.
- `analysis-engine/index/` — hashing, incremental cache, and indexed source.
- `analysis-engine/knowledge/` and `analysis-engine/knowledge-persistence/` —
  materialized knowledge semantics and JSON/checkpoint adapters.
- `analysis-engine/languages/` — descriptor catalog and adapter binding.
- `analysis-engine/syntax-treesitter/` — tree-sitter parse/facts and node hashes.
- `analysis-engine/lsp/` — managed, attached, and editor-proxy semantic
  providers.
- `analysis-engine/intent-pipeline/` — pack plans, knowledge resolution, and
  deterministic function propagation.
- `analysis-engine/runtime/` — orchestration and the public `analyze` surface.
- `analysis-engine/apps/cli`, `apps/server`, `apps/mcp` — batch, live daemon,
  and agent servers.

Key client files:

The Neovim client, including its live pipeline, render layer, daemon transport,
and commands, lives in the external `code-intent.nvim` repository. The engine
publishes the wire contract in [`ipc/PROTOCOL.md`](ipc/PROTOCOL.md).

Fixtures:

- `testing-grounds/` — Kotlin, C, JavaScript, TypeScript, Vue, and C# source
  fixtures for detection and composition.
- `frozen-v1-corpus/` — read-only V1 outputs and transcripts used by parity
  tests.

Tests and tooling:

- `just build` — assemble all modules without tests.
- `just test-engine` — deterministic module suites, integration tests, and the
  graph guard.
- `just test-lsp` — focused LSP provider tests.
- `just lint` — ktlint over Kotlin sources.
- `just parity`, `just smoke`, and `just syntax-provenance` — frozen-corpus,
  distribution, and pinned-snapshot verification.
