# V2 Rewrite: Module Graph and Boundaries

Working notes, not a committed plan. The first V2 deliverable is a compilable
skeleton with every project, every dependency, and every interaction contract
named before feature code exists.

V1 proved the intent model, the rule/fact split, and the renderer protocol. It
is the behavioral reference. It is not the architectural template.

## Why start with the graph

The expensive V1 problems were boundary problems, not algorithm problems:

- rulepacks became classpath code plus `ServiceLoader`
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
5. **Rulepacks are manifests plus playbooks.** They are not classpath code and
   they do not own scheduling. `intent-pipeline` selects them; `core` evaluates
   the resulting plan.
6. **Runtime orchestrates, languages hides adapters.** `runtime` may call
   `index`, `languages`, and `intent-pipeline`, but never imports tree-sitter or
   LSP classes. `languages` is the facade that owns those implementations.
7. **IPC is lockstep in the PoC.** No version negotiation; one schema and a
   fingerprint handshake. Break freely and rebuild both halves.
8. **V2 starts as a walking skeleton.** Empty projects, then contracts, then
   fakes. Tree-sitter, SQLite, and LSP come after the skeleton proves the graph.

## Proposed project tree

Names describe roles, not directory nesting.

```text
analysis-engine/
  contracts/          :contracts
  core/               :core
  packs/              :packs
  index/              :index
  languages/          :languages
  syntax-treesitter/  :syntax-treesitter
  lsp/                :lsp
  intent-pipeline/    :intent-pipeline
  runtime/            :runtime
  ai/                 :ai
  ipc/                :ipc
  apps/cli/           :cli
  apps/server/        :server
  tests/fixtures/     :test-fixtures
  tests/integration/  :integration-tests
```

`ai` is the former `suggest`; `ipc` is the former `protocol`; `core` is the
former `kernel`. Names are still provisional, but the roles are not.

Possible later splits: `ai` may become a sidecar or move to the host, and
`lsp` may split into `lsp-client` and `lsp-editor` if the editor proxy grows
enough to justify it.

## Dependency graph

```text
contracts
  ├── core
  ├── packs
  ├── index
  ├── languages
  ├── syntax-treesitter
  ├── lsp
  ├── intent-pipeline
  ├── ai
  └── ipc

core  -> contracts
packs -> contracts
index -> contracts

syntax-treesitter -> contracts
lsp               -> contracts
languages         -> contracts, syntax-treesitter, lsp

intent-pipeline -> contracts, core, packs, ai
ai              -> contracts
ipc             -> contracts

runtime -> contracts, packs, index, languages, intent-pipeline

cli    -> runtime
server -> runtime, ipc
```

Everything points down. No module points back at an app. Only `languages` may
point at source adapters; `index` and `runtime` must not.

### Allowed edges

| Module | May depend on | Must not depend on |
|---|---|---|
| `contracts` | nothing | everything |
| `core` | `contracts` | parser, LSP, filesystem, network, JVM |
| `packs` | `contracts` | `core`, parser, LSP |
| `index` | `contracts` | `core`, `packs`, `languages`, parser, LSP |
| `languages` | `contracts`, `syntax-treesitter`, `lsp` | `core`, `runtime`, `index` |
| `syntax-treesitter` | `contracts` | `core`, `runtime`, `packs`, `languages` |
| `lsp` | `contracts` | `core`, `runtime`, `packs`, `languages` |
| `intent-pipeline` | `contracts`, `core`, `packs`, `ai` | `runtime`, `index`, parser, LSP, IPC |
| `ai` | `contracts` | `core`, `runtime`, `packs`, `index` |
| `ipc` | `contracts` | `core`, `runtime`, adapters, apps |
| `runtime` | `contracts`, `packs`, `index`, `languages`, `intent-pipeline` | adapter classes, `ai`, `ipc`, apps |
| `cli` | `runtime` | `core` internals, adapters |
| `server` | `runtime`, `ipc` | `core` internals, adapters |

`runtime` is the analysis composition root; `languages` is the source-adapter
facade. Apps configure and call `runtime`, and only the server touches `ipc`.

## Module responsibilities

### `contracts`

The stable vocabulary. Intents, roles, contributions, annotations, spans, fact
DTOs, syntax node interface, language descriptors, `LanguageSupport`,
`SyntaxEngine`, `SemanticProvider`, semantic requests and results, rulepack
manifest, playbook schema, and IPC messages.

No behavior beyond pure helpers. If a type needs a file handle, a socket, or a
classloader, it is in the wrong module.

### `core`

The deterministic semantic kernel: scheduler, strata, fixpoint rules, capability
view, annotation store, composition (`DERIVATIVE_USE`, `SCAFFOLD`), and rule
evaluation.

The kernel receives facts; it does not obtain them. It never knows a language
name, an LSP server, or a file path format.

### `packs`

Bundled intent content: manifests, playbooks, matchers, defaults, and language
overrides. Discovery is manifest-based. No `META-INF/services`, no dynamic JAR
loading, no classpath scanning.

Packs are content. `intent-pipeline` selects them into an execution plan; `core`
evaluates it. If a pack needs a new engine capability, that capability goes into
`core` behind a contract, not into the pack.

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

Turns indexed source into intents. It selects packs, builds the execution plan,
merges user annotations and confirmed AI restrictions, invokes `core`, optionally
runs AI candidate review, and shapes the result.

It owns the "what to analyze" policy. `runtime` owns "when and where"; `core`
owns "how". It does not read files, open sockets, or know parser/LSP classes.

### `runtime`

Orchestration: configuration, pack lifecycle, index lifecycle, language
resolution, pipeline invocation, publish, and the public `analyze` entry point.
It resolves `LanguageSupport` and passes it to the index. AI review belongs to
`intent-pipeline`, not runtime.

It is the only analysis module apps need to know. It never imports source
adapters and never touches IPC.

### `ai`

Optional AI enrichment and discovery. Consumes candidates, produces reviewed
candidate proposals. Confirmed restrictions may enter the pipeline before core;
live candidate review stays after core. It must not be on the deterministic
analysis path.

### `ipc`

NDJSON message DTOs and framing shared with the renderer. One schema. A
fingerprint in the handshake, not a version matrix. The `server` app binds it to
a socket; `runtime` does not know it exists.

### `apps`

Argument parsing, process lifecycle, logging, and transport. No analysis logic.
`cli` is batch and calls `runtime`; `server` is the live socket daemon and owns
the IPC boundary.

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

5. **`RulePackSource`**
   - `manifests() -> List<Manifest>`
   - `playbook(packId) -> Playbook`
   - No code loading.

6. **`IntentPipeline`**
   - `analyze(request, source, config) -> AnalysisResult`
   - Selects packs, merges annotations and confirmed restrictions, calls `core`,
     optionally runs AI candidate review, and shapes the result.
   - `runtime` calls this; apps do not.

7. **`Runtime`**
   - `analyze(AnalysisRequest) -> AnalysisResult`
   - `config()`, `reload()`, `publish(...)`, `status()`
   - Resolves `LanguageSupport`, calls `index.ensure`, then calls
     `intent-pipeline`.
   - This is what apps call.

8. **IPC / Wire**
   - `analyze`, `publish`, `suggest`, `config`, `status`
   - One current schema. Unknown type or field mismatch is a hard error.
   - Owned by `ipc` and `server`; not visible to `runtime` internals.

## Walking skeleton milestone

This is the V2 PoC. It proves the graph before any real parser exists.

1. Create every project with an empty build file and the declared edges.
2. Add a graph guard test that reads the module graph and fails on any
   undeclared dependency.
3. Add `contracts`: model, ports, manifest, IPC DTOs.
4. Add fakes: syntax engine, semantic provider, language support, index, pack
   source, and AI reviewer.
5. Make `cli` analyze one file end to end through `runtime` and emit JSON.
6. Make `server` answer one NDJSON request over a Unix socket.
7. Leave the renderer untouched; it only sees the IPC types.

Only after that: tree-sitter, Merkle index, SQLite, LSP, playbook evaluator, and
the actual intent packs.

## V1 lessons encoded in V2

- Replace classpath/ServiceLoader packs with manifests and data.
- Remove the language-specific LSP path; make `languages` a facade and the
  host answer semantic queries.
- Move file IO, user state, and restrictions out of `core` into `runtime`,
  `index`, and `intent-pipeline`.
- Do not version the IPC protocol during the PoC; lockstep plus fingerprint.
- Do not optimize per-file CPU before the index removes redundant work.
- Do not let apps import core internals; expose a runtime facade.
- Do not let `index` resolve languages or import adapters.
- Preserve V1's golden corpus and observable behavior before deleting it.

## Open questions

- Config format: keep YAML, or move to JSON/TOML for multiplatform parsing?
- Syntax kernel: pure Kotlin, or Rust behind a small C ABI with tree-sitter?
- Index storage: SQLite from the start, or in-memory with a JSON snapshot?
- Bulk semantic indexing: compiler/SCIP-style batch index, or LSP-only?
- Name and placement of `ai`: in-process, sidecar, or host-owned?
- Should `contracts` split into packages or a separate `source-contracts`
  module once a second syntax or semantic backend lands?
- Should runtime depend on the `languages` facade, or receive a
  `LanguageRegistry` port from bootstrap?
- How strict should the graph guard be for test-only edges?

## Near-term checklist

- [x] Revise the draft for `core`, `ipc`, `ai`, `intent-pipeline`, and the
  index/languages boundary.
- [ ] Agree final module names and directory layout.
- [ ] Create the empty projects and declared dependency graph.
- [ ] Add the architecture guard to CI.
- [ ] Define contracts and fake implementations.
- [ ] Prove the CLI and server walking skeleton.
- [ ] Freeze the V1 corpus and IPC transcripts.
- [ ] Start the index and tree-sitter adapter.
