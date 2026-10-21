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
       → stratified rule graph (rulepacks) → annotations
       → core composition (scaffolds, derivative use)
       → artifact / live publish → client
```

## 1. The boundary: core vs rulepack

The engine is split into two kinds of code with a hard line between them.

**Core owns**

- parsing wiring, syntax facts, CFG
- the annotation store and its merge/replacement semantics
- the scheduler: stratification, fixpoint, provenance
- composition: coverage, intent scaffolds, derivative use, return
  categorization
- config plumbing, artifact and live publishing

**Rulepacks own**

- detection of `SOURCE_INTENT` annotations only
- a typed, pack-local config

**Roles are core-only at the plugin boundary.** Plugins may only emit
`SOURCE_INTENT`. `SCAFFOLD` and `DERIVATIVE_USE` are produced exclusively by
core composition; they are not part of the plugin API. A plugin cannot remove,
hide, or reclassify anything.

The built-in detectors (logging, error handling, bookkeeping, validation) are
**bundled rulepacks that use the same API as any third-party pack.** Core
contains no hardcoded intent detection. This is what keeps the hull from
decaying back into a monolithic block implementation.

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
    fun run(view: View): List<Emit>
}
```

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
stratum 0:  logging, error-handling-<language>, bookkeeping
            (no dependencies)
stratum 1:  validation         (reads BOOKKEEPING)
terminal:   core composition    (scaffold, derivative-use, return-category)
```

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
`(View) -> List<Emit>`; they never mutate the store. This is why the current
`ctx.remove(...)` calls disappear: the scaffold rule that emits one whole span
when `then == else` and two ranges when they differ simply returns its current
answer, and the framework swaps out its old shape. Add-only holds from the
author's perspective; the framework handles the diff.

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
  All bundled packs use `SingleIntentPack` and support overlay. Live config
  commands write it through the server, which reloads packs in place.

## 9. Modules and loading

| module | responsibility |
|---|---|
| `api` | frozen contract: `Intent`, `Relation`, `Rule`, `RulePack`, `View`, `Emit`, `Annotation`, scheduler contract, DSL |
| `rulepacks` | bundled logging, error-handling, bookkeeping, and validation packs |
| `core` | syntax facts, config, annotation store, CFG, scheduler, composition, artifact writer |
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
- `core` has no tree-sitter, LSP, host, or editor dependency. The `host` module
  assembles the production pack selection and applies project config; CLI,
  HTTP, and live server reuse it.
- Loading reads `META-INF/services/dev.cint.api.RulePack` explicitly rather
  than iterating `ServiceLoader`. That keeps per-provider failure isolation,
  API-version checks, and deterministic ordering; there is no runtime Kotlin
  compiler or classloader swapping.
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
- occurrence persistence (candidate / established / suppressed) — the stable
  node anchor is reserved in `api` now so it can be added later
- packs exporting named derived relations (dependency currency stays intents)
- optional dependencies (unknown dependency is a hard error)
- full IR reification / CEL predicates

## 11. Sequencing

1. `api` module: `Intent`, `Relation`, `Rule`, `RulePack`, `View`, `Emit`.
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
