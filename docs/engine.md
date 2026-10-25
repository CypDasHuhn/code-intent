# CodeIntent Engine

The engine turns source files into intent annotations and review candidates. The
repository root is the engine, built as a Kotlin multi-module project. Tree-sitter supplies syntax, a semantic provider supplies
types, manifest packs supply detection and seed defaults, and persisted
knowledge supplies project-specific function contracts.

All commands below run from the repository root.

## Modules

| module | responsibility |
|---|---|
| `contracts` | Data model and narrow ports: intents, facts, annotations, candidates, knowledge DTOs, manifests, IPC/MCP messages. No JVM types. |
| `core` | Deterministic kernel: scheduler, strata, fixpoint, capability view, annotation store, composition. |
| `default-rulepacks` | Language-neutral manifest packs, playbooks, typed rule data, and seed defaults (per-pack resources). |
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
| `tests/integration` (`:integration-tests`) | Pack, knowledge, and end-to-end integration tests. |

Dependency direction is enforced by `graphGuard` against `graph-guard.json`.
`contracts` depends on nothing; `core` depends only on `contracts`; `runtime` may
use default rulepacks, index, knowledge, languages, and the intent pipeline but never imports
a parser or LSP class directly; apps depend on runtime (the server additionally
uses `ipc`).

## Languages

Kotlin, C, JavaScript, TypeScript, Vue, and C# are registered in
`sources/languages/src/main/kotlin/dev/cint/v2/languages/TreeSitterLanguageRegistry.kt`.
Each descriptor carries extensions, aliases, grammar name, MIME types, root
markers, provider mode, and syntax profile.

Adding a language means adding a grammar entry in `syntax-treesitter`, a
descriptor in the `languages` registry, and any pack defaults or detection data
for that language. The core kernel never names a language or an LSP server.

Vue split regions are analyzed through the language facade and shifted back to
host coordinates. The syntax adapter performs no file IO; callers load bytes.

## Packs and detection

Packs are content, not classpath code. `default-rulepacks` loads the per-pack
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
./apps/cli/build/install/cint/bin/cint <root> [options]
```

Useful options: `--knowledge <path>`, `--init`, `--out/-o`, `--format
json|ndjson`, `--full-rescan`, `--all`, `--lang`, `--provider
managed|attached`, `--provider-command`, `--provider-endpoint`, and
`--provider-timeout-ms`. The semantic provider is mandatory; `--no-lsp` is
rejected.

### Live server

```sh
./apps/server/build/install/cint-server/bin/cint-server --socket /path/to/project.sock --root /path/to/project
```

The live transport is NDJSON JSON-RPC over a Unix socket. Current protocol is 5;
protocols 3-4 are accepted as legacy and receive V1-shaped initialize payloads; protocols 1-2 are rejected.
See [`docs/ipc/PROTOCOL.md`](ipc/PROTOCOL.md) for the external contract,
compatibility matrix, and published schema, and
[`orchestration/ipc/PROTOCOL.md`](../orchestration/ipc/PROTOCOL.md) for
implementation notes.

### MCP

`cint-mcp` exposes discovery, analysis, knowledge, and provider tools:

- `repo.files` lists analyzable files under the configured root with language
  and extension filters, deterministic ordering, and documented skips for
  generated directories (`.git`, `.cint`, `build`, `out`, `target`,
  `node_modules`, and similar). It does not require git.
- `analysis.symbols` returns a compact declaration, call, and import inventory
  with receiver/declaring types, spans, and language for target paths. Receiver
  and declaring types include semantic facts when the index requested them.
- `knowledge.resolveSelector` dry-runs a function or span selector against
  target paths or the repository root and reports matches plus unsupported
  reasons without mutating knowledge.
- `analysis.run`, `repo.status`, and `repo.index` run or report analysis.
- `repo.languages` lists supported languages, resolved provider modes, and
  provider configuration status without starting an LSP.
- `knowledge.snapshot`, `knowledge.suggestDefaults`,
  `knowledge.materializeDefaults`, and the function/span/pattern knowledge
  tools read or write knowledge.

The intended agent workflow is documented in
[`docs/mcp-agent-workflow.md`](mcp-agent-workflow.md): discover with
`repo.files`, inspect with `analysis.symbols`, run `analysis.run`, dry-run
selectors with `knowledge.resolveSelector`, then write knowledge through the
validated function/span/pattern or `changes.propose`/`changes.apply` tools.
After a knowledge change, rerun analysis with `forceFullAnalysis: true` because
the init checkpoint skips unchanged content hashes. Restarting `cint-mcp` with
the same root, knowledge path, and checkpoint path resumes init and preserves
knowledge records. MCP is the primary agent workflow; fallback AI is separate
and optional.

Configure non-Kotlin providers with `--provider-config PATH` or repeatable
`--provider-command LANGUAGE=COMMAND` flags; `--provider-mode` and
`--provider-endpoint` cover attached and editor modes. Kotlin keeps its managed
default. `analysis.run` fails explicitly when a provider is missing.

```sh
./apps/mcp/build/install/cint-mcp/bin/cint-mcp --root /path/to/project \
  --provider-config /path/to/providers.json
```

Providers can also be configured per language without a file. The flags are
repeatable and override matching file entries:

```sh
cint-mcp --root /path/to/project \
  --provider-command typescript="typescript-language-server --stdio" \
  --provider-mode javascript=attached \
  --provider-endpoint javascript=unix:/tmp/cint-javascript.sock
```

`--provider-mode` accepts `managed`, `attached`, or `editor`. Kotlin keeps its
managed `kotlin-lsp --stdio` default; `--provider-command kotlin=...` overrides
it. Managed mode requires a command, attached mode requires an endpoint, and
editor mode requires a host channel configuration. `cint-mcp` does not yet
provide an editor `HostSemanticChannelProvider`, so editor entries are
representable and reported but not executable by this app; prefer managed or
attached providers. `repo.languages` reports each
language's resolved mode, whether its configuration is complete, and the
configured command or endpoint. It does not start an LSP process, so
`providerAvailable` means "configuration is complete", not "the server was
probed". `analysis.run` on a missing provider fails with a precondition error
that names the language and points at `--provider-config`/`--provider-command`.

Provider config file shape:

```json
{
  "providers": {
    "kotlin": {
      "mode": "managed",
      "command": "kotlin-lsp --stdio"
    },
    "typescript": {
      "mode": "managed",
      "command": "typescript-language-server --stdio",
      "timeoutMs": 5000,
      "initTimeoutMs": 30000
    },
    "javascript": {
      "mode": "attached",
      "endpoint": "unix:/tmp/cint-javascript.sock"
    },
    "vue": {
      "mode": "editor",
      "hostChannelConfig": {
        "type": "editor",
        "config": {"socket": "/tmp/cint-editor.sock"}
      }
    }
  }
}
```

Each entry may set `mode`, `command`, `endpoint`, `rootPath`, `timeoutMs`,
`initTimeoutMs`, `languageOverrides`, and `hostChannelConfig`. If `mode` is
omitted, `command`, `endpoint`, or `hostChannelConfig` select managed, attached,
or editor respectively. Language keys are case-insensitive; use the canonical
ids from `repo.languages` (`kotlin`, `c`, `javascript`, `typescript`, `vue`,
`csharp`).

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
just graph-guard          # module graph guard
just smoke                # built distribution smoke test
just protocol-schema      # regenerate the renderer protocol JSON Schema
```

Lint scope is Kotlin only. Neovim renderer tests live in the external
`code-intent.nvim` repository.

## Testing grounds

`testing-grounds/` contains Kotlin, JavaScript, TypeScript, Vue, and C# fixtures
for logging, guards, scaffolds, derivative uses, and false-positive cases. They
are intentionally not part of Gradle and need not compile.
