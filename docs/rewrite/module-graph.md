# V2 Rewrite: Module Graph and Boundaries

Working notes, not a committed plan. The first V2 deliverable is a compilable
skeleton with every project, every dependency, and every interaction contract
named before feature code exists.

V1 proved the intent model, the rule/fact split, and the renderer protocol. It
is the behavioral reference. It is not the architectural template.

The semantic knowledge, function-contract, and MCP-first AI model is in
[knowledge-model.md](knowledge-model.md). The implementation queue is
[../tasks/README.md](../tasks/README.md), tickets 063–087.

## Why start with the graph

The expensive V1 problems were boundary problems, not algorithm problems:

- rulepacks became classpath code plus `ServiceLoader`
- rulepacks owned user-facing config shapes instead of shipping seed defaults
- AI became the analysis engine instead of a client that writes knowledge
- one language got a hardcoded Kotlin LSP path
- file IO, config, user state, and analysis all lived in `core`
- the wire protocol grew versions before there was anything stable to version
- naming described directories instead of roles (`core/core`, `core/host` vs
  `hosts/*`, `:api`, `:frontend`)

V2 should make the wrong dependency impossible to compile, not merely discouraged
in review. Create the projects first, declare the allowed edges, and add a guard
that fails the build when an edge is added outside the graph.

## Working decisions

1. **Language and transport are edge concerns.** `core` contains no parser,
   LSP, filesystem, socket, or editor types. Source adapters are reachable only
   through `languages`; apps compose `runtime`.
2. **Contracts are data and narrow ports.** DTOs, ports, manifests, and wire
   messages. No JVM types, no `Path`, no `ClassLoader`.
3. **The index owns change detection and incremental facts.** Merkle node
   hashes are the identity; file mtime and size are only prefilters. The index
   does not resolve languages or construct adapters; it receives
   `LanguageSupport` through its API.
4. **The semantic provider is mandatory.** The index asks for normalized
   semantic facts; the host supplies an editor proxy, a managed LSP, or an
   attached LSP. If no provider can be resolved or started, analysis fails.
   There is no syntax-only production path.
5. **Rulepacks are manifests, playbooks, and seed defaults.** They are not
   classpath code and they do not own scheduling or a user-facing config shape.
   `intent-pipeline` selects them; `core` evaluates the resulting plan.
6. **Runtime orchestrates, languages hides adapters.** `runtime` may call
   `index`, `knowledge`, `languages`, and `intent-pipeline`, but never imports
   tree-sitter or LSP classes. `languages` is the facade that owns those
   implementations.
7. **Semantic knowledge is persisted project data.** Function annotations and
   span overrides are materialized, versioned, and owned by the project. Pack
   defaults seed that store; they do not replace it.
8. **Function contracts drive deterministic propagation.** A resolved function
   intent is reused at call sites and through chains without calling AI again.
9. **MCP is the primary AI surface.** External agents behave like users: they
   read analysis and write knowledge through the same mutation API. In-house AI
   is fallback for uninitialized repos, dirty code, CI, and offline use.
10. **IPC is lockstep in the PoC.** No version negotiation; one schema and a
    fingerprint handshake. Break freely and rebuild both halves.
11. **V2 starts as a walking skeleton.** Empty projects, then contracts, then
    fakes. Tree-sitter, SQLite, and LSP come after the skeleton proves the graph.

## Proposed project tree

Names describe roles, not directory nesting.

```text
analysis-engine/
  contracts/          :contracts
  core/               :core
  packs/              :packs
  index/              :index
  knowledge/          :knowledge
  languages/          :languages
  syntax-treesitter/  :syntax-treesitter
  lsp/                :lsp
  intent-pipeline/    :intent-pipeline
  runtime/            :runtime
  ai/                 :ai
  ipc/                :ipc
  apps/cli/           :cli
  apps/server/        :server
  apps/mcp/           :mcp
  tests/fixtures/     :test-fixtures
  tests/integration/  :integration-tests
```

`ai` is the former `suggest`; `ipc` is the former `protocol`; `core` is the
former `kernel`. Names are still provisional, but the roles are not.

`knowledge` is the materialized semantic store: function annotations, span
overrides, pattern hypotheses, and provenance. `mcp` is the external-agent
boundary; it is an app, not a library.

Possible later splits: `ai` may become a sidecar or move to the host, and
`lsp` may split into `lsp-client` and `lsp-editor` if the editor proxy grows
enough to justify it.

## Dependency graph

```text
contracts
  ├── core
  ├── packs
  ├── index
  ├── knowledge
  ├── languages
  ├── syntax-treesitter
  ├── lsp
  ├── intent-pipeline
  ├── ai
  └── ipc

core      -> contracts
packs     -> contracts
index     -> contracts
knowledge -> contracts

syntax-treesitter -> contracts
lsp               -> contracts
languages         -> contracts, syntax-treesitter, lsp

intent-pipeline -> contracts, core, packs, knowledge, ai
ai              -> contracts
ipc             -> contracts

runtime -> contracts, packs, index, knowledge, languages, intent-pipeline

cli    -> runtime
server -> runtime, ipc
mcp    -> runtime, contracts
```

Everything points down. No module points back at an app. Only `languages` may
point at source adapters; `index` and `runtime` must not. `mcp` is an app
boundary and may call runtime, but no library depends on `mcp`.

### Allowed edges

| Module | May depend on | Must not depend on |
|---|---|---|
| `contracts` | nothing | everything |
| `core` | `contracts` | parser, LSP, filesystem, network, JVM |
| `packs` | `contracts` | `core`, `knowledge`, parser, LSP |
| `index` | `contracts` | `core`, `packs`, `knowledge`, `languages`, parser, LSP |
| `knowledge` | `contracts` | `core`, `packs`, `index`, adapters, apps |
| `languages` | `contracts`, `syntax-treesitter`, `lsp` | `core`, `runtime`, `index`, `knowledge` |
| `syntax-treesitter` | `contracts` | `core`, `runtime`, `packs`, `languages` |
| `lsp` | `contracts` | `core`, `runtime`, `packs`, `languages` |
| `intent-pipeline` | `contracts`, `core`, `packs`, `knowledge`, `ai` | `runtime`, `index`, parser, LSP, IPC |
| `ai` | `contracts` | `core`, `runtime`, `packs`, `index`, `knowledge` |
| `ipc` | `contracts` | `core`, `runtime`, adapters, apps |
| `runtime` | `contracts`, `packs`, `index`, `knowledge`, `languages`, `intent-pipeline` | adapter classes, `ai`, `ipc`, apps |
| `cli` | `runtime` | `core` internals, adapters |
| `server` | `runtime`, `ipc` | `core` internals, adapters |
| `mcp` | `runtime`, `contracts` | `core` internals, adapters |

`runtime` is the analysis composition root; `languages` is the source-adapter
facade; `knowledge` is the only owner of materialized semantic data. Apps
configure and call `runtime`; only the server touches `ipc`, and only the MCP
app touches the agent boundary.

## Module responsibilities

### `contracts`

The stable vocabulary. Intents, roles, contributions, annotations, spans, fact
DTOs, syntax node interface, language descriptors, `LanguageSupport`,
`SyntaxEngine`, `SemanticProvider`, semantic requests and results, rulepack
manifest, playbook schema, function annotations, span overrides, pack defaults,
pattern hypotheses, knowledge mutations, and IPC/MCP messages.

No behavior beyond pure helpers. If a type needs a file handle, a socket, or a
classloader, it is in the wrong module.

### `core`

The deterministic semantic kernel: scheduler, strata, fixpoint rules, capability
view, annotation store, composition (`DERIVATIVE_USE`, `SCAFFOLD`), and rule
evaluation.

The kernel receives facts; it does not obtain them. It never knows a language
name, an LSP server, or a file path format.

### `packs`

Bundled intent content: manifests, playbooks, matchers, and seed defaults.
Discovery is manifest-based. No `META-INF/services`, no dynamic JAR loading, no
classpath scanning.

Packs are content. `intent-pipeline` selects them into an execution plan; `core`
evaluates it. Pack defaults are suggestions materialized into `knowledge`; packs
do not own a user-facing config shape. If a pack needs a new engine capability,
that capability goes into `core` behind a contract, not into the pack.

### `index`

File metadata, content hashing, structural hashes, parse/fact cache, dependency
edges, and incremental invalidation.

Fast path: `mtime + size` says "look here"; content hash says "it changed";
Merkle node hash says "reuse this subtree". Renames, atomic saves, and clock skew
are handled by hashes, not metadata.

The index owns its change detection but not language resolution. Runtime builds
a `SourceInput` (path or in-memory text plus version) and resolves
`LanguageSupport`, then calls `index.ensure(input, support)`; the index invokes
the injected `SyntaxEngine` and mandatory `SemanticProvider`. It never imports
`languages` or an adapter.

Git is an optional accelerator later, never a correctness dependency.

### `knowledge`

The materialized semantic store: function annotations, span overrides, pattern
hypotheses, materialized pack defaults, and provenance.

It owns validation, selector identity, versioning, and mutation. It does not own
analysis, rule evaluation, parsing, or provider access. Users, MCP agents, and
fallback AI all write through its mutation API; AI gets no separate store.

### `languages`

The source-adapter facade. It binds language descriptors to concrete
`SyntaxEngine` and `SemanticProvider` implementations and hands back a
`LanguageSupport` for a file.

It is the only analysis module allowed to depend on `syntax-treesitter` and
`lsp`. Kotlin is one entry, not a class hierarchy. `languages` does not
parse files itself and does not depend on `index` or `runtime`.

### `syntax-treesitter`

The tree-sitter adapter: parse, incremental tree, changed ranges, node hashes,
and batch fact extraction. If the syntax kernel moves to Rust, this is the only
module that changes; the contract stays.

### `lsp`

The LSP implementation behind `SemanticProvider`: managed LSP, attached LSP,
and editor proxy. Managed and attached share one `LspSession`; the editor proxy
uses a `HostSemanticChannel` port and never opens an LSP connection itself.
Document sync, hover/definition parsing, timeouts, caching, and capability
negotiation live here.

Start as one module. Split into `lsp-client` and `lsp-editor` later only if the
editor proxy grows enough to justify it.

### `intent-pipeline`

Turns indexed source and persisted knowledge into intents. It selects packs,
builds the execution plan, resolves function annotations and span overrides,
invokes `core`, optionally runs fallback AI for dirty or uninitialized code, and
shapes the result.

Function annotations propagate deterministically to call sites and chains. The
pipeline does not call AI during that derivation.

It owns the "what to analyze" policy. `runtime` owns "when and where"; `core`
owns "how". It does not read files, open sockets, or know parser/LSP classes.

### `runtime`

Orchestration: knowledge lifecycle, initialization/materialization, pack
lifecycle, index lifecycle, language resolution, pipeline invocation, publish,
and the public `analyze` entry point. It resolves `LanguageSupport` and passes it
to the index. Fallback AI belongs to `intent-pipeline`, not runtime.

It is the only analysis module apps need to know. It never imports source
adapters and never touches IPC or MCP.

### `ai`

Fallback AI only: uninitialized repositories, recently changed code, CI, tests,
and offline environments. It returns proposals; `intent-pipeline` and `runtime`
apply them through the same knowledge mutation API used by users and MCP agents.
It is not the primary AI workflow and not part of deterministic derivation.

The primary AI workflow is an external MCP agent that reads analysis and writes
knowledge like a user.

### `ipc`

NDJSON message DTOs and framing shared with the renderer. One schema. A
fingerprint in the handshake, not a version matrix. The `server` app binds it to
a socket; `runtime` does not know it exists.

### `apps`

Argument parsing, process lifecycle, logging, and transport. No analysis logic.
`cli` is batch and calls `runtime`; `server` is the live socket daemon and owns
the IPC boundary; `mcp` exposes the agent boundary over MCP.

## Interaction contracts to define first

1. **`SyntaxEngine`**
   - `parse(language, bytes, previous?) -> Tree`
   - `diff(previous, next) -> ChangedRanges`
   - `nodeHash(tree, nodeId) -> Hash`
   - `facts(tree, queryIds) -> FactBatch`
   - No file IO. Bytes in, packed data out.

2. **`SemanticProvider`**
   - `typeAt(uri, version, offset) -> FactResult`
   - `symbolAt`, `definitionOf`, `diagnosticsFor`
   - Result carries value, source, confidence, and an `Unavailable` reason.
   - Request carries a deadline; analysis never blocks indefinitely on it.
   - Resolved from `SemanticProviderConfig`: mode (`editor`, `managed`,
     `attached`), command, endpoint, host channel, and language overrides. There
     is no `none` mode; failure to resolve is a hard error.

3. **`LanguageSupport`**
   - `descriptor() -> LanguageDescriptor`
   - `syntax() -> SyntaxEngine`
   - `semanticProvider() -> SemanticProvider`
   - `languages` implements this facade; `index` consumes it. The index never
     resolves a language or constructs an adapter itself.

4. **`Index`**
   - `ensure(input: SourceInput, support) -> IndexedSource`
   - `invalidate(paths)`, `snapshot(file)`, `dependentsOf(...)`
   - `SourceInput` is a path or in-memory text plus a version and language hint.
   - `IndexedSource` carries text/version, syntax facts, semantic facts, source
     epoch, and a completeness state (`complete`, `stale`).
   - The index owns `FactStore` internally; it is not a cross-module contract
     unless the pipeline needs direct queries. Backed by SQLite/WAL later,
     in-memory first.

5. **`KnowledgeStore`**
   - `functionAnnotations()`, `setFunctionAnnotation(...)`,
     `removeFunctionAnnotation(...)`
   - `spanOverrides()`, `setSpanOverride(...)`, `removeSpanOverride(...)`
   - `materializeDefaults(defaults)`, `proposeChange(...)`
   - Entries carry selector, intents, scope, provenance, status, and timestamps.
   - Users, MCP agents, and fallback AI all write through this API.

6. **`RulePackSource`**
   - `manifests() -> List<Manifest>`
   - `playbook(packId) -> Playbook`
   - `defaults(language) -> List<PackDefault>`
   - No code loading and no user-facing config shape.

7. **`IntentPipeline`**
   - `analyze(request, source, knowledge) -> AnalysisResult`
   - Selects packs, resolves function annotations and span overrides, calls
     `core`, optionally runs fallback AI, and shapes the result.
   - Function annotations propagate deterministically; AI is not consulted per
     call site.
   - `runtime` calls this; apps do not.

8. **`Runtime`**
   - `analyze(AnalysisRequest) -> AnalysisResult`
   - `knowledge()`, `materializeDefaults()`, `reload()`, `publish(...)`,
     `status()`
   - Resolves `LanguageSupport`, calls `index.ensure`, then calls
     `intent-pipeline`.
   - This is what apps call.

9. **IPC / Wire**
   - `analyze`, `publish`, `suggest`, `config`, `status`
   - One current schema. Unknown type or field mismatch is a hard error.
   - Owned by `ipc` and `server`; not visible to `runtime` internals.

10. **MCP**
    - Knowledge: `knowledge.snapshot`, `function_annotations.*`,
      `span_overrides.*`, `patterns.*`
    - Analysis: `analysis.run`, `repo.status`, `repo.index`
    - Changes: `changes.propose`, `changes.apply`
    - Init: `knowledge.materializeDefaults`, `knowledge.suggestDefaults`
    - Handlers call `runtime`; tool DTOs live in `contracts`.
    - MCP is the primary AI workflow. It is not the renderer IPC protocol.

## Walking skeleton milestone

This is the V2 PoC. It proves the graph before any real parser exists.

1. Create every project with an empty build file and the declared edges.
2. Add a graph guard test that reads the module graph and fails on any
   undeclared dependency.
3. Add `contracts`: model, knowledge DTOs, ports, manifest, IPC and MCP DTOs.
4. Add fakes: syntax engine, semantic provider, language support, index,
   knowledge store, pack source, and fallback AI.
5. Make `cli` analyze one file end to end through `runtime` and emit JSON.
6. Make `server` answer one NDJSON request over a Unix socket.
7. Make `mcp` answer one knowledge read and one knowledge write.
8. Leave the renderer untouched; it only sees the IPC types.

Only after that: tree-sitter, Merkle index, SQLite, LSP, playbook evaluator, and
the actual intent packs.

## V1 lessons encoded in V2

- Replace classpath/ServiceLoader packs with manifests and data.
- Remove the language-specific LSP path; make `languages` a facade and the
  host answer semantic queries.
- Move file IO, user state, and restrictions out of `core` into `runtime`,
  `knowledge`, `index`, and `intent-pipeline`.
- Replace pack-owned user config with materialized semantic knowledge.
- Make AI an MCP-first client, not the primary analysis engine.
- Do not version the IPC protocol during the PoC; lockstep plus fingerprint.
- Do not optimize per-file CPU before the index removes redundant work.
- Do not let apps import core internals; expose a runtime facade.
- Do not let `index` resolve languages or import adapters.
- Preserve V1's golden corpus and observable behavior before deleting it.

## Open questions

- Config format: keep YAML, or move to JSON/TOML for multiplatform parsing?
- Knowledge file layout: one file, or split function annotations and overrides?
- Selector stability under rename, move, overload, and signature change.
- Trust and promotion policy for suggested knowledge.
- Does fallback AI write accepted knowledge or only suggestions?
- Are pattern hypotheses persisted knowledge or agent artifacts?
- Syntax kernel: pure Kotlin, or Rust behind a small C ABI with tree-sitter?
- Index storage: SQLite from the start, or in-memory with a JSON snapshot?
- Bulk semantic indexing: compiler/SCIP-style batch index, or LSP-only?
- Should `contracts` split into packages or a separate `source-contracts`
  module once a second syntax or semantic backend lands?
- Should runtime depend on the `languages` facade, or receive a
  `LanguageRegistry` port from bootstrap?
- How strict should the graph guard be for test-only edges?

## Near-term checklist

- [x] Revise the draft for `core`, `ipc`, `ai`, `intent-pipeline`, `knowledge`,
  `mcp`, and the index/languages boundary.
- [x] Capture the V2 knowledge, function-contract, and MCP-first AI model.
- [ ] Agree final module names and directory layout.
- [ ] Create the empty projects and declared dependency graph.
- [ ] Add the architecture guard to CI.
- [ ] Define contracts and fake implementations.
- [ ] Prove the CLI and server walking skeleton.
- [ ] Freeze the V1 corpus and IPC transcripts.
- [ ] Start the index and tree-sitter adapter.

## Spike exception: native-spike

`native-spike` is an opt-in, throwaway spike, not a production module. It is included only with
`-PnativeSpike` (or when a `native-spike` task is named) and is excluded from default `just
v2-build` and CI. It has a single edge, `native-spike -> syntax-treesitter`; it does not depend on
`contracts` directly, and no production module may depend on it. The cinterop and link
configuration live in `syntax-treesitter`, not in the spike.
