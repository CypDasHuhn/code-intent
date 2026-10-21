# CodeIntent — Architecture

The implemented engine shape after the rule/engine restructure. It describes
the current module boundaries and pipeline; `intent-model.md` covers the intent
model and `notes.md` retains the pre-restructure history.

## Goal

Turn the engine into a **hull**: a fixed analysis framework that carries no
detection logic. Detection ships as loadable **rulepacks**; the framework owns
structure, facts, composition, and scheduling. Language- and editor-specific
concerns stay outside: syntax is data in a profile, the renderer is the only
editor-specific part.

```
source → frontend.parse → tree-sitter facts → CFG
       → stratified rule graph (rulepacks) → annotations + candidates
       → core composition (scaffolds, derivative use)
       → artifact / live publish → client
                                  └→ optional candidate enrichment → review
```

## 1. The boundary: core vs rulepack

The engine is split into two kinds of code with a hard line between them.

**Core owns**

- parsing wiring, syntax facts, CFG
- the annotation store and its merge/replacement semantics
- the scheduler: stratification, fixpoint, provenance
- composition: coverage, intent scaffolds, derivative use, return
  categorization
- the separate candidate channel and its publication
- config plumbing, artifact and live publishing

**Rulepacks own**

- detection of `SOURCE_INTENT` annotations only
- a typed, pack-local config

**Roles are core-only at the plugin boundary.** Plugins may only emit
`SOURCE_INTENT`. `SCAFFOLD` and `DERIVATIVE_USE` are produced exclusively by
core composition; they are not part of the plugin API. A plugin cannot remove,
hide, or reclassify anything.

The built-in detectors (logging, error handling, bookkeeping, validation,
normalization, data access, service boundaries, mutation, boolean
combinatorics, and data transformation) are **bundled rulepacks that use the
same API as any third-party pack.** Core contains no hardcoded intent detection.
This is what keeps the hull from decaying back into a monolithic block
implementation.

## 2. Intents, rules, ownership

- Intent names are **open**. A rulepack may introduce new intent names. The
  client tolerates unknown names through its resolve/default policy.
- `UNTAGGED` is reserved and never owned by a rule.
- **One rule owns exactly one intent name.** A rule emits only the intent it
  owns.
- **No two rules may own the same intent name.** A collision is a load error.
- A **span may carry multiple intents** — the union of contributions from
  different rules, and further union from core composition.
- Rules are **monotone**: they only add annotations. There is no removal API.

## 3. Rulepacks

A **rulepack** is a distribution unit — a plugin artifact (JAR) that bundles a
set of rules together with the config schema for those rules.

- A pack declares: an `id`, the languages it applies to, and its rules.
- **Packs have no scheduling role.** The dependency graph is over rules, not
  packs. A rule can be moved between packs and the graph is unchanged.
- Rule ids are globally unique, namespaced for reporting: `<packId>:<localName>`.
  Consumers never write rule ids in dependencies (see §4).

```kotlin
interface RulePack {
    val id: String
    val languages: Set<String>
    fun rules(): List<Rule>
}

interface Rule {
    val id: String          // "<packId>:<localName>", globally unique
    val owns: Intent        // exactly one
    val reads: Set<Intent>  // cross-rule dependencies, by intent name
    fun run(view: View): List<RuleEmit>
}
```

The v5 (`CINT_API_VERSION = 5`) rule output is one sealed `RuleEmit` list.
`Emit` remains the established type; `CandidateEmit` is review-only. Both carry
the common intents/span/source/confidence fields, and candidates add a
`CandidateReason` (`GAP`, `LOW_CONFIDENCE`, `PACK_DOMAIN`), `aiEligible`, and
optional rationale/prompt/provider/model provenance. `toContribution` accepts
only `Emit`, so a candidate cannot become an established contribution.

## 4. Dependencies and stratification

Dependencies are the core of making the system composable without a global
free-for-all.

- **Graph nodes are rules.** **Currency is intent names.** A rule declares
  `reads = {Intent}`; the scheduler resolves each name to its owning rule,
  yielding a rule → rule edge.
- Because ownership is 1:1 (§2), "depend on a rule" and "depend on an intent"
  are the same edge. The declaration names the intent; the node is the rule.
  Consumers never couple to pack structure or rule ids.
- **Unknown dependency is a load error.** A declared intent with no owning rule
  fails the load.
- **Resolution happens after all packs register**, so pack load order is
  irrelevant.
- Topologically sort the rule graph into **strata**.
- **A cycle is a load error** (stratified-Datalog discipline).
- Each stratum runs **once** against the frozen output of earlier strata. A
  rule may still run an internal fixpoint if it reads its own output; that
  iteration is monotone on a finite lattice.
- **Core composition is the terminal node** of the graph. It consumes the union
  of all source intents, names no rule and no pack, and therefore adding or
  removing a pack can only add tags, never change composition.

Worked example of strata:

```
stratum 0:  logging, error-handling-<language>, bookkeeping,
            normalization:normalization, data-access:search,
            data-access:lookup, service:delegation, service:init,
            mutation:data-modify, bool-combinatorics (no dependencies)
stratum 1:  mutation:local-value-modify (reads BOOKKEEPING),
            data-transformation (reads SEARCH; its self-read is ignored)
stratum 2:  validation (reads BOOKKEEPING, DATA_MODIFY,
            LOCAL_VALUE_MODIFY)
terminal:   core composition    (scaffold, derivative-use, return-category)
```

`data-transformation` also reads its own intent so the scheduler permits an
internal fixpoint over chained transforms while still ignoring the self-edge. It
requires the `SEARCH` owner to be present in the bundled set. Transformation
evidence is qualifier/type/`SEARCH`/transformation based; unknown or
unclassified receivers, including offline runs with a null oracle, stay
candidates rather than established spans.

**Termination invariant:** monotone rules + acyclic rule-level dependencies +
a finite intent universe ⇒ evaluation terminates. Because no stratum can
oscillate, no retraction mechanism is ever needed.

## 5. The capability-scoped `View`

A rule can only see what it declared. This is what makes `reads` enforceable
rather than decorative.

```kotlin
interface View {
    // core facts: always available
    fun calls(): List<CallFact>
    fun properties(): List<PropertyFact>
    fun functions(): List<FunctionFact>
    fun ifs(): List<IfFact>
    fun tries(): List<TryFact>
    fun catches(): List<SyntaxNode>
    fun imports(): List<String>
    fun references(): Map<String, List<SyntaxNode>>
    fun throws(): List<SyntaxNode>

    // derived, always available
    fun coverage(node: SyntaxNode): Coverage
    fun enclosingFunction(node: SyntaxNode): SyntaxNode?
    fun enclosingDeclaration(node: SyntaxNode): SyntaxNode?
    fun writes(reference: SyntaxNode): SyntaxNode?
    fun isAnnotatedPure(node: SyntaxNode): Boolean

    // gated by the rule's declared `reads`
    fun annotations(intent: Intent): List<Annotation>
    fun intentsAt(node: SyntaxNode): Set<Intent>
}
```

`annotations` and `intentsAt` surface only intents the rule declared in
`reads`, plus the rule's own owned intent. Reading an undeclared intent yields
empty (defensive) or fails loudly in dev; it never leaks.

## 6. Annotations and provenance

An annotation carries:

- `role` — `SOURCE_INTENT`, `DERIVATIVE_USE`, or `SCAFFOLD`
- `intents` — a set
- `span` — 0-based line/column, plus byte offsets
- `rule` — the producing rule id
- `evidence` — source snippet
- `source` — tier (`treesitter`, `lsp`, `comment`, `mixed`, ...)
- `confidence`
- a stable **node anchor** (reserved now; see §10)

**Provenance is structured data**, not a concatenated rule string. When
multiple rules contribute to one span, the store keeps the per-contributor
entries so the client can answer "why did this fire" with the full chain.

**Per-rule contribution replacement.** Each iteration the framework replaces a
rule's *entire* previous contribution with its new output. Rules are pure
`(View) -> List<RuleEmit>`; they never mutate the store. This is why the current
`ctx.remove(...)` calls disappear: the scaffold rule that emits one whole span
when `then == else` and two ranges when they differ simply returns its current
answer, and the framework swaps out its old shape. Add-only holds from the
author's perspective; the framework handles the diff.

### Candidate channel

A rule may return `CandidateEmit`s alongside established `Emit`s from the same
`List<RuleEmit>`. `settle` validates that every emit uses the rule's owned
intent, turns only `Emit`s into contributions, and collects candidates
separately. The engine returns `AnalysisOutput(annotations, candidates)`.
Candidates never enter `AnnotationStore`, composition, purity, provenance,
fixpoint identity, or the `reads` graph. For fixpoint rules, only established
output drives convergence; candidates come from the final settled iteration.

Candidate identity is path + anchor/content hash + byte span + sorted intents +
reason. Payloads carry `source` (`rule:<rule-id>` or `ai:<provider>`),
`confidence`, `reason`, `aiEligible`, span/positions, and optional
`rationale`/`promptVersion`/`provider`/`model`. AI confidence is diagnostic:
`maxConfidence` defaults to `0.84` and can only lower that ceiling. Candidate-only
is structural because the AI output type is `CandidateEmit`, never an established
`Emit`/`Annotation`.

### AI enrichment boundary

AI is post-analysis and non-authoritative. The server caches an
`AnalysisOutput`, gives the `:suggest` service the candidate context for that
exact document version, and publishes candidate-only results on
`cint/publishCandidates`. AI never runs in the scheduler, never owns an intent,
and cannot create an established annotation. `.cint/suggestions.yml` gates the
manual `cint/suggest` path and is disabled by default; deterministic pack
candidates publish regardless.

The remote provider is DeepSeek (`deepseek-chat`, key from
`DEEPSEEK_CHAT_KEY`). `:suggest` owns the provider and the HTTP transport (JDK
`HttpClient`, Authorization header, per-request timeout); the server resolves
`.cint/suggestions.yml`, injects the config/key lookup, constructs the provider
registry, and orchestrates the pass with no retries. The file accepts `provider`,
`model`, `baseUrl`, `apiKeyEnv`, `maxTokens`, `maxConfidence`,
`excerptRadiusBytes`, `allowGlobs`, and `redactPatterns` alongside the existing
trigger and count/timeout settings. The context is
a bounded excerpt (`excerptRadiusBytes`, default 400) plus candidate metadata,
known/neighboring intents, path/language, and prompt version; imports and
enclosing functions are not collected. `allowGlobs` gates the pass and
`redactPatterns` redact the excerpt before sending. AI candidates carry
`source = ai:deepseek` plus provider/model/promptVersion/rationale diagnostics.
The key is never published in `initialize.suggestions` or logged.

Confirmation writes an existing `.cint/user-annotations.json` record and the
next analysis re-evaluates it as an established `user` source. Rejection
persists suppression in `.cint/suggestions.json`; skip is session-only. The
frozen `Annotation` shape is unchanged.

## 7. DSL and IR

- A **thin Kotlin embedded DSL** over `View`. Rules are ordinary Kotlin using
  the DSL.
- **Lambdas are allowed** for predicates; there is no reification requirement
  today, no CEL, no Datalog engine. The IR is kept deliberately thin so it can
  be replaced later without rewriting rule semantics.
- Comparisons and logic are **infix functions** (`eq`, `ne`, `gt`, `lt`, `and`,
  `or`), not `==`/`&&`/`||`. Kotlin pins `==` to `equals(Any?): Boolean` and
  does not allow overloading `&&`/`||`; the named forms return DSL values and
  stay type-safe.
- The IR is intended to be reifiable in a future version (data-shaped predicate
  leaves). That is a deferred opt-in, not a v1 constraint.

## 8. Configuration

Legacy intent config is retired. Rule *structure* never lives in config again.

- Each rulepack owns a **typed `Config`** with defaults.
- A single project config file supplies values, keyed by pack id, plus a `core:`
  section for framework-wide policy (purity annotations, LSP toggles).
- Values are validated against the owning pack's schema. Unknown packs or keys
  are reported.
- Language-specific detection values are pack config, not duplicated per
  language in one global file.
- An optional `<root>/.cint/config.overlay.yml` is auto-discovered and applied
  after the project config. Additive merge is opt-in per pack via
  `ConfigurableRulePack.configureAdditive`/`ConfigSchema.decodeAdditive` (string
  lists/sets union, string-set maps recurse, scalars from the overlay win). A
  non-opt-in pack keeps its project/default values; the server reports
  `UNSUPPORTED_PACK_OVERLAY`, rejects the mutation, and rolls the overlay back.
  Most bundled packs use `SingleIntentPack`; the mutation pack uses a custom
  `ConfigurableRulePack` with the same additive overlay support. Live config
  commands write it through the server, which reloads packs in place.

## 9. Modules and loading

| module | responsibility |
|---|---|
| `api` | frozen v5 contract: `Intent`, `Relation`, `Rule`, `RulePack`, `View`, `RuleEmit`, `Emit`, `CandidateEmit`, `Annotation`, scheduler contract, DSL |
| `rulepacks` | bundled logging, error-handling, bookkeeping, validation, normalization, data-access, service, mutation, boolean-combinatorics, and data-transformation packs |
| `core` | syntax facts, config, annotation store, CFG, scheduler, candidate identity/channel, composition, artifact writer |
| `suggest` | candidate context (bounded excerpt + metadata), provider SPI/registry, fake and DeepSeek providers, remote transport, privacy gate/redaction, validator, merge, grouping |
| `host` | host pipeline and project config loading shared by all hosts |
| `treesitter` | parsers and per-language syntax profiles |
| `lsp` | spec-driven LSP oracle |
| `frontend` | language registry wiring parser, syntax model, oracle |
| `cli` | batch host |
| `server` | HTTP host and per-project Unix-socket daemon |
| `rulepacks-tests` | bundled-pack integration tests over real parsers |
| `test-fixtures` | shared test fixtures for parsers, packs, and `testing-grounds` |
| `integration-tests` | composition, host, frontend-pipeline, and snapshot tests |

- `api` is the frozen public surface. Plugins compile against it; it changes
  only deliberately and with versioning. The DSL lives in `api`.
- `core` has no tree-sitter, LSP, host, editor, or suggestion dependency. The
  `host` module assembles the production pack selection and applies project
  config; CLI, HTTP, and live server reuse it.
- `suggest` depends on `api` and `core` (candidate identity/hash) only. It owns
  the provider SPI, the fake and DeepSeek providers, and the remote transport
  (`HttpClient`). The `server` depends on `suggest` and owns config/key
  injection, provider construction, orchestration, and persistence. No provider
  SDK or credential value leaks into `core` or `api`.
- Loading reads `META-INF/services/dev.cint.api.RulePack` explicitly rather
  than iterating `ServiceLoader`. That keeps per-provider failure isolation,
  API-version checks, and deterministic ordering; there is no runtime Kotlin
  compiler or classloader swapping. The bundled descriptor registers logging,
  error-handling, bookkeeping, validation, normalization, data-access, service,
  mutation, bool-combinatorics, and data-transformation packs; host selection
  constructs the per-language error-handling rules.
- **Graceful degradation:** a missing, corrupt, or version-mismatched pack is
  reported and skipped; the engine keeps analyzing with what loaded.
- **Reload.** A pack change is normally a dev-time action, never a per-edit one;
  the daemon is per-project and detached, and the client re-opens buffers on
  reconnect, so `:CintServerRestart` is a transparent reload path for new pack
  code. Config-overlay mutations are the exception: the server owns the write
  and calls `HostPipeline.reload()` for that project without restarting the
  daemon. There is no general hot-swap of pack code.

## 10. Deferred / non-goals

Explicitly out of scope, none of which constrains the API:

- custom roles beyond the current three
- multi-language reuse of a single rulepack
- a second (dataflow / whole-program) backend
- splitting `dsl` into its own module
- additional local/remote AI providers and a provider/model picker; the current
  slice ships the deterministic fake provider and a hardcoded DeepSeek remote
  provider
- node-anchored candidate identity; the stable node anchor is reserved in `api`,
  while the current identity uses content hash + byte span + intents + reason
- packs exporting named derived relations (dependency currency stays intents)
- optional dependencies (unknown dependency is a hard error)
- full IR reification / CEL predicates

## 11. Sequencing

1. `api` module: `Intent`, `Relation`, `Rule`, `RulePack`, `View`,
   `RuleEmit`/`Emit`/`CandidateEmit`.
2. Scheduler: rule-level stratification, per-rule contribution replacement,
   internal fixpoint, provenance store.
3. Re-express existing detection as bundled rulepacks on the new API.
4. Move composition (scaffolds, derivative use, return category) into `core`.
5. Migrate config to typed pack config and delete the legacy config/phase path.
6. Cut CLI and server over to the shared `host` pipeline.
7. Split renderer transport and tests; consolidate test ownership and fixtures.
8. Reconcile docs, snapshots, CI, and tooling (tickets 019-030).

There is no legacy compatibility layer; this is a clean rewrite. Golden
`tags.json` fixtures remain useful as a development check on detection
behavior, not as a compatibility contract. The restored pre-018 snapshots are
014-tree `HostPipeline` renderings whose legacy shape was compared at
`3f20435`; they are not a direct capture of the deleted legacy implementation.
The current pipeline snapshot is a separate post-018 baseline because validation
spans were intentionally widened.
