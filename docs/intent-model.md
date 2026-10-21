# Intent Model — Design Notes

Snapshot of the current model and the design directions discussed for CodeIntent.
This is intentionally broader than the implementation: it separates what the
engine does today from what we want to support later.

## 1. Core model

The engine emits **roles + intents + spans + provenance**; the client resolves
intents into visual meanings.

- Roles: `SOURCE_INTENT`, `DERIVATIVE_USE`, `SCAFFOLD`.
- Intents are a set, e.g. `{VALIDATION, BOOKKEEPING}`.
- `UNTAGGED` is the reserved "unclassified effect" intent.
- Tag/intent names are screaming snake case: `LOGGING`, `ERROR_HANDLING`,
  `BOOKKEEPING`, `VALIDATION`, `USER_FEEDBACK` (planned), `UNTAGGED`.
- Source annotations remain code-shaped: `@Bookkeeping`, `@UserFeedback`,
  `// cint:bookkeeping`, `// cint:validation`.
- The engine has no colors or visual policy. `tags[intent].meaning` and
  `rules[meaning]` live on the client.

Each annotation carries `rule`, `source`, `confidence`, `evidence`, and a span.
That provenance is part of the product: it is what makes review/explain
possible.

## 2. Current sources

### `LOGGING`

- Logger calls via typed receiver, factory-initialized property, qualifier, or
  import-gated/receiver-name heuristics.
- Strong heuristics (factory property, qualifier) skip LSP.
- Weak heuristics (`receiver-name`, import-gated) consult the oracle when
  available; a resolved non-logger type vetoes them.
- Live LSP hover timeout is capped; failures fall back to heuristics.
- Trailing lambda arguments are included in the call span.

### `ERROR_HANDLING`

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

### `VALIDATION` (implemented, still being narrowed)

Validation means **rejecting/skipping input**, not every early return.

Current v1 behavior:

- recognizes a then-branch with a direct `return` / `continue` / `break`;
- tags the guard condition `VALIDATION` when one of:
  - the branch contains a `BOOKKEEPING` write,
  - the condition is a validation predicate (`isEmpty`, `isBlank`, `none`,
    `any`, `all`, `isValid*`, `has*`, ...),
  - the condition is side-effect-free,
  - `// cint:validation` precedes the guard;
- contributes an ambient `VALIDATION` scaffold over the guard so plumbing
  locals read inside the guard become `DERIVATIVE_USE` with `VALIDATION`;
- bare early exits are neutral; `return false` and bare `return` do not make a
  scaffold `UNTAGGED`;
- the enclosing `if` composes e.g. `{VALIDATION, BOOKKEEPING}`.

Known problem:

- side-effect-free conditions are too broad. A search/lookup guard such as
  `if (current.kind == ...) return current` is not validation.
- There is a gradient: validation early return, cache-hit return, simple value
  return, found-value return. The line cannot be drawn reliably with local
  shape heuristics.

The current design direction is to stop treating shape as a certificate and to
use candidate/confirmed states (see §7). Possible final rules:

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

### Queued intents (2026-10-21)

Definitions for the next taxonomy batch. Deterministic detection where
config/explicit patterns exist; AI candidates for the ambiguous remainder.

| intent | meaning | include | exclude / boundary |
|---|---|---|---|
| `NORMALIZATION` | canonicalize a value for downstream use | trim, case fold, parse/coerce, date/time format, path/unit normalization | rejecting input (`VALIDATION`), persistence (`DATA_MODIFY`), display-only formatting until configured |
| `SEARCH` | retrieve data by criteria from a source | repository queries, `findBy*`/`search*`/`query*`, filters, external search APIs; empty/many results | known-key single-value mapping (`LOOKUP`), pure transformation (`NORMALIZATION`) |
| `LOOKUP` | resolve one known value by key/constant | map/dictionary get, enum/constant maps, config keys, static tables, enum-like value to DB id | criteria-based retrieval (`SEARCH`), service forwarding (`SERVICE_DELEGATION`) |
| `SERVICE_DELEGATION` | transfer responsibility to another service/component | controller→service, facade, adapter, thin call-through/argument adaptation | constructing/wiring (`SERVICE_INIT`), local business logic, the inner call's own intent |
| `SERVICE_INIT` | build/configure/wire a service or resource | construction, client creation, connection setup, DI/bootstrap | initializing business data, delegation, every constructor call |
| `DATA_MODIFY` | change that persists beyond the process | repository save/update/delete, ORM writes, durable file writes, queue/event publication | in-memory mutation (`LOCAL_VALUE_MODIFY`), bookkeeping counters |
| `LOCAL_VALUE_MODIFY` | mutate a local/field/in-memory object | assignment, increment/decrement, configured mutable collection operations | persistence (`DATA_MODIFY`), bookkeeping writes unless configured, loop counters/noise |

Overlaps are intent sets, not exclusive labels, but packs should avoid
double-tagging unless both meanings genuinely apply. Persistence wins over local
mutation; validation and normalization may coexist; delegation does not replace
the delegate's business intent. Generic local mutation and most
search/lookup/service boundaries are expected to be candidate-heavy and
AI-enhanced rather than painted as established.

## 3. Derivative use and scaffolds

### Derivative use

A property/local is `DERIVATIVE_USE` when its reads flow into classified spans.

- Only explicitly pure initializers qualify: literals, local reads, and calls
  to annotated pure functions.
- A local is only promoted when all reads are classified; mixed/untagged reads
  prevent promotion. There is no impure-local promotion: an unannotated call or
  operator is assumed to have side effects until a purity engine exists.
- Intents at a read are resolved from the smallest containing annotation, which
  is what lets reads inside an error-handling lambda inherit `ERROR_HANDLING`.
- Functions whose references all occur inside derivative-use declarations are
  promoted only when they are explicitly pure.
- Purity is explicit (`@Pure`, `@Contract(pure = true)`) or literal/local; there
  is no real purity inference yet, so unannotated calls and operators are
  treated as impure.

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

Proposed states:

- `established` — certified; normal painting.
- `candidate` — possible intent; shown non-destructively; needs review.
- `suppressed` — user rejected this occurrence; don't suggest it again here.

Candidate rules:

- never conceal or apply destructive meanings;
- use a separate style/namespace: dotted underline, muted highlight, `?` sign,
  virtual text, etc.;
- be reviewable on demand, not with popups while typing;
- show counts such as `CodeIntent: 3 unsure`.

Possible UX:

```text
:CintReview
Possible VALIDATION
evidence: if (target == null) { player.msg(...); return }
[Confirm] [Reject] [Skip]
```

- Confirm → this occurrence becomes established and is persisted.
- Reject → this occurrence is suppressed and persisted.
- Skip → unchanged.

Confirmation is **occurrence-scoped**. It does not raise the rule's confidence
for other tags or other files. Confirmed knowledge can be stored in a project
file such as:

```lua
return {
  {
    file = "src/.../NavCommands.kt",
    intent = "VALIDATION",
    anchor = "...",
    decision = "confirm",
    evidence = "...",
  },
}
```

Identity is the hard part:

- file + enclosing symbol + tree-sitter node path (kinds/child indices) or a
  stable node anchor;
- normalized evidence hash;
- intent + rule;
- invalidate and re-ask when the construct is structurally edited;
- line/byte spans alone are not sufficient.

Confirmation should be applied as a client overlay immediately. The engine can
read the same store later for batch/other clients. An optional action could
write a `// cint:validation` source comment instead, which is maximally durable
but edits code.

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
- Tag names and profiles are uppercase. Client defaults include
  `LOGGING`, `ERROR_HANDLING`, `BOOKKEEPING`, `VALIDATION`, `UNTAGGED`.

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

Key engine files:

- `analysis-engine/core/api/.../Model.kt` and `Contract.kt` — intents, roles,
  annotations, rules, packs, views, emits, and the scheduler contract.
- `analysis-engine/core/api/.../Dsl.kt` — the thin `View` DSL.
- `analysis-engine/core/core/.../facts/view/CapabilityView.kt` — the scope-gated
  view rules consume.
- `analysis-engine/core/core/.../facts/store/AnnotationStore.kt` — per-rule
  contribution replacement and provenance.
- `analysis-engine/core/core/.../runtime/scheduler/RuleEngine.kt` and
  `RuleScheduler.kt` — stratification and fixpoint.
- `analysis-engine/core/core/.../runtime/composition/` — `ScaffoldComposition`,
  `DerivativeComposition`, `ReturnComposition`, and the terminal composition.
- `analysis-engine/core/core/.../facts/flow/ControlFlow.kt` — function-local CFG and
  return sites.
- `analysis-engine/core/core/.../syntax/Syntax.kt` and `ProfileSyntaxModel.kt` —
  language-neutral syntax facts and profile-driven node kinds.
- `analysis-engine/core/core/.../runtime/rulepack/` — explicit
  `META-INF/services` discovery, validation, and per-provider degradation.
- `analysis-engine/core/host/.../HostPipeline.kt`, `HostConfig.kt`, and
  `ProjectConfigLoader.kt` — production pack selection and project config.
- `analysis-engine/core/rulepacks/.../logging|errorhandling|bookkeeping|validation/`
  — bundled detection packs; `support/BundledRulePackSelection.kt` supplies the
  per-language error-handling packs.
- `analysis-engine/languages/treesitter/src/main/resources/syntax/*.yaml` — Kotlin,
  JavaScript, TypeScript, Vue, and C# node-kind profiles.
- `analysis-engine/languages/frontend/.../KotlinFrontend.kt` — `Frontends` registry and
  per-language bindings.
- `analysis-engine/languages/frontend/.../SplitAnalyzer.kt` — embedded-region analysis
  with coordinate shifting; Vue uses it for `<script>` blocks.

Key client files:

- `nvim-renderer/lua/cint/init.lua` — live pipeline, profiles, shards, reveal.
- `nvim-renderer/lua/cint/render.lua` — extmarks/concealment.
- `nvim-renderer/lua/cint/live.lua` — daemon transport.
- `nvim-renderer/plugin/cint.lua` — commands/autocmds.

Fixtures:

- `testing-grounds/kotlin/Bookkeeping.kt` — annotation + comment, writes,
  validation guards.
- `testing-grounds/kotlin/Scaffolds.kt` — guards/branches/untagged.
- `testing-grounds/kotlin/Catches.kt` — try/catch spans.
- `testing-grounds/kotlin/LoggerInteractions.kt` — typed/heuristic sources.

Tests and tooling:

- `just build` — assemble all modules without tests.
- `just test-engine` — deterministic engine/host/server/integration suites.
- `just test-nvim` — headless renderer suite.
- `just test-lsp` — opt-in shared-LSP oracle test.
- `just lint` — ktlint over Kotlin sources.

Snapshots:

- `analysis-engine/tests/integration-tests/src/test/resources/snapshots/` holds the
  pre-018 (014-tree) `HostPipeline` snapshots restored from `739dff7`
  (legacy-shape and pipeline-shape; compared against `3f20435` in ticket 014)
  and the post-018 current pipeline baseline.
