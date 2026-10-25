# CodeIntent Engine

The engine turns source files into intent annotations and review candidates. The
repository now contains one engine, `analysis-engine/`, built as a Kotlin
multi-module project. Tree-sitter supplies syntax, a semantic provider supplies
types, manifest packs supply detection and seed defaults, and persisted
knowledge supplies project-specific function contracts.

All commands below run from the repository root unless a `cd analysis-engine`
is shown.

## Modules

| module | responsibility |
|---|---|
| `contracts` | Data model and narrow ports: intents, facts, annotations, candidates, knowledge DTOs, manifests, IPC/MCP messages. No JVM types. |
| `core` | Deterministic kernel: scheduler, strata, fixpoint, capability view, annotation store, composition. |
| `packs` | Bundled manifest packs, playbooks, matchers, and seed defaults (`packs/src/main/resources/packs/bundled-packs.json`). |
| `index` | File hashing, incremental fact cache, dependency tracking, and `IndexedSource` construction. |
| `knowledge` | Materialized function annotations, span overrides, pattern hypotheses, pack defaults, provenance, validation, and mutations. |
| `knowledge-persistence` | JSON snapshot and init-checkpoint adapters around knowledge. |
| `languages` | Source-adapter facade. The only module allowed to bind `syntax-treesitter` and `lsp`. |
| `syntax-treesitter` | Tree-sitter parse, incremental tree, changed ranges, node hashes, and fact extraction on JVM and Kotlin/Native. |
| `lsp` | Managed, attached, and editor-proxy `SemanticProvider` implementations. |
| `intent-pipeline` | Pack selection, rule plans, function knowledge resolution, core invocation, and result shaping. |
| `ai` | Fallback AI for uninitialized or dirty code. It returns proposals and never owns knowledge. |
| `runtime` | Composition root: index lifecycle, language resolution, knowledge lifecycle, pipeline invocation, and the public `analyze` surface. |
| `ipc` | NDJSON JSON-RPC framing and wire DTOs shared with the renderer. |
| `apps/cli` | Batch `cint` executable. |
| `apps/server` | Live Unix-socket `cint-server` daemon (and protocol compatibility). |
| `apps/mcp` | `cint-mcp` agent server for analysis and knowledge tools. |
| `tests/fixtures` (`:test-fixtures`) | Shared fakes and fixture loaders. |
| `tests/integration` (`:integration-tests`) | Pack/knowledge integration and frozen-corpus parity tests. |

Dependency direction is enforced by `graphGuard` against `graph-guard.json`.
`contracts` depends on nothing; `core` depends only on `contracts`; `runtime` may
use packs, index, knowledge, languages, and the intent pipeline but never imports
a parser or LSP class directly; apps depend on runtime (the server additionally
uses `ipc`).

## Languages

Kotlin, C, JavaScript, TypeScript, Vue, and C# are registered in
`languages/src/main/kotlin/dev/cint/v2/languages/TreeSitterLanguageRegistry.kt`.
Each descriptor carries extensions, aliases, grammar name, MIME types, root
markers, provider mode, and syntax profile.

Adding a language means adding a grammar entry in `syntax-treesitter`, a
descriptor in the `languages` registry, and any pack defaults or detection data
for that language. The core kernel never names a language or an LSP server.

Vue split regions are analyzed through the language facade and shifted back to
host coordinates. The syntax adapter performs no file IO; callers load bytes.

## Packs and detection

Packs are content, not classpath code. `packs` loads `bundled-packs.json`, which
declares pack ids, rule ids, owned intents, dependencies, and seed defaults.
`intent-pipeline` selects packs and builds rule plans; `core` evaluates them.

Bundled intents include `LOGGING`, `ERROR_HANDLING`, `BOOKKEEPING`,
`VALIDATION`, `NORMALIZATION`, `SEARCH`, `LOOKUP`, `SERVICE_DELEGATION`,
`SERVICE_INIT`, `DATA_MODIFY`, `LOCAL_VALUE_MODIFY`, `BOOL_COMBINATORICS`,
`DATA_TRANSFORMATION`, `RETURN_VALUE`, `CHAINING`, `LOCAL_COPY`, and
`USER_FEEDBACK`. `UNTAGGED` is reserved and never owned by a rule.

The core scheduler resolves required and optional intent reads to owning rules,
builds strata, rejects cycles and missing owners, and runs each stratum once.
Core composition is the terminal node and produces scaffold and derivative-use
annotations. Candidates never enter composition or the knowledge store.

## Knowledge

Function annotations, span overrides, pattern hypotheses, and materialized pack
defaults are persisted project data. Users, MCP agents, and fallback AI all write
through the same knowledge mutation API. The default store lives at
`<root>/.cint/knowledge.json`; `--knowledge` overrides the path.

Pack defaults are seeds. They are materialized once and then belong to the
project, so deleting a default does not make it reappear on every analysis.

## Runtime and hosts

`Runtime` resolves `LanguageSupport`, asks `index.ensure` for indexed source, and
invokes `intent-pipeline`. It is the only analysis surface apps need.

### CLI

```sh
./analysis-engine/apps/cli/build/install/cint/bin/cint <root> [options]
```

Useful options: `--knowledge <path>`, `--init`, `--out/-o`, `--format
json|ndjson`, `--full-rescan`, `--all`, `--lang`, `--provider
managed|attached`, `--provider-command`, `--provider-endpoint`, and
`--provider-timeout-ms`. The semantic provider is mandatory; `--no-lsp` is
rejected.

### Live server

```sh
./analysis-engine/apps/server/build/install/cint-server/bin/cint-server --socket /path/to/project.sock --root /path/to/project
```

The live transport is NDJSON JSON-RPC over a Unix socket. Current protocol is 5;
protocols 3-4 are accepted as legacy and receive V1-shaped initialize payloads; protocols 1-2 are rejected.
See [`docs/ipc/PROTOCOL.md`](ipc/PROTOCOL.md) for the external contract,
compatibility matrix, and published schema, and
[`analysis-engine/ipc/PROTOCOL.md`](../analysis-engine/ipc/PROTOCOL.md) for
implementation notes.

### MCP

`cint-mcp` exposes `analysis.run`, `repo.status`, `repo.index`,
`knowledge.snapshot`, `knowledge.suggestDefaults`,
`knowledge.materializeDefaults`, and the function/span/pattern knowledge tools.
It is the primary agent workflow; fallback AI is separate and optional.

## Config

Project configuration and knowledge live under `<root>/.cint/`. Detection
values and seed defaults are pack content. Runtime options (knowledge path,
provider command/endpoint/timeout, and language selection) are supplied by each
app. The renderer reads published protocol data and does not evaluate rules.

## Development

```sh
just build                # assemble every module
just test-engine          # all deterministic tests plus graph guard
just test-lsp             # focused LSP provider tests
just lint                 # ktlint over Kotlin sources
just install              # cint, cint-server, cint-mcp distributions
just graph-guard          # module graph guard and self-test
just parity               # frozen CLI/IPC parity suites and report check
just smoke                # built distribution smoke test
just syntax-provenance    # pinned V1 syntax snapshot verification
just protocol-schema      # regenerate the renderer protocol JSON Schema
```

Lint scope is Kotlin only. Neovim renderer tests live in the external
`code-intent.nvim` repository.

## Testing grounds

`testing-grounds/` contains Kotlin, JavaScript, TypeScript, Vue, and C# fixtures
for logging, guards, scaffolds, derivative uses, and false-positive cases. They
are intentionally not part of Gradle and need not compile.
