# CodeIntent Engine

Kotlin engine for intent classification. Tree-sitter provides structure, a
function-local CFG provides control-flow facts, LSP provides types, rulepacks
classify source intents, and core composition builds scaffolds and derivative
use.
The engine emits only semantic facts; clients resolve intent sets into meanings
and visuals. Built-in frontends cover Kotlin, JavaScript, TypeScript, Vue, and
C#.

Module paths below are relative to the repository root. Command snippets that
use `./gradlew` or `build/install` run from `analysis-engine/`.

## Modules

| module | responsibility |
|--------|----------------|
| `api` | frozen plugin contract: intents, rules, views, emits, annotations |
| `rulepacks` | bundled logging, error-handling, bookkeeping, and validation packs |
| `core` | facts, config, artifact writer, oracle interface, CFG, scheduler, composition |
| `host` | host pipeline and project config loading shared by CLI, HTTP, and live server |
| `treesitter` | tree-sitter parsers and syntax profiles for Kotlin, JavaScript, TypeScript, Vue, and C# |
| `lsp` | spec-driven LSP oracle |
| `frontend` | language registry wiring parser, syntax model, and oracle |
| `cli` | batch host over a directory |
| `server` | HTTP host and per-project Unix-socket daemon |
| `rulepacks-tests` | integration tests for bundled packs using real parsers/frontends |
| `test-fixtures` | shared `java-test-fixtures` parser, pack, and fixture helpers |
| `integration-tests` | composition, host, frontend-pipeline, and snapshot integration tests |

Modules are grouped under `analysis-engine/core/`,
`analysis-engine/languages/`, `analysis-engine/hosts/`, and
`analysis-engine/tests/`; Gradle project names remain `:api`, `:core`,
and so on. `core` has no tree-sitter, LSP, or editor dependency. `frontend` is the composition
root that binds a language id to its parser, syntax profile, and oracle. `host`
owns the pipeline that assembles discovered and constructed packs, applies
project config, and is reused by every host.

Rulepacks are discovered from `META-INF/services/dev.cint.api.RulePack`
descriptors. The loader parses those descriptors explicitly, validates each
provider's API version and ownership, and degrades per provider instead of
aborting the whole load. The tests-only modules are not production dependencies.

## Languages

Rules, the engine, and intents are shared. A new language adds data and a small
frontend binding, not new analysis code:

1. Add the grammar dependency and a parser under `treesitter`.
2. Add `analysis-engine/languages/treesitter/src/main/resources/syntax/<language>.yaml`. It maps the
   canonical `SyntaxGroups` names to that grammar's node kinds.
3. Register a `LanguageFrontend` under `frontend`. It supplies `parse`, `syntax`,
   and `oracle`.
4. Add the language's detection names (calls, types, imports, predicates) to the
   owning rulepack's typed config defaults.

Kotlin, JavaScript, TypeScript, Vue, and C# are built in. Profiles live under
`analysis-engine/languages/treesitter/src/main/resources/syntax/`, and
frontends are registered in `analysis-engine/languages/frontend/`.

### Split handlers

A frontend can also implement `SplitHandler`. Its `regions(file)` returns
`SplitRegion(language, text, startByte, startLine, startCol)` entries for
embedded content. `SplitAnalyzer` parses each region with that language's
frontend, runs the normal rules, and shifts the resulting spans back into the
host file coordinates. Vue uses this to analyze `<script>` and
`<script setup lang="ts">` blocks as JavaScript or TypeScript while reporting
positions in the `.vue` file.

Split handlers can nest. The host file itself is not analyzed unless a handler
returns a region for it.

## Classification

Every annotation has:

- `role`: `SOURCE_INTENT`, `DERIVATIVE_USE`, or `SCAFFOLD`
- `intents`: a set such as `{LOGGING}` or `{LOGGING, ERROR_HANDLING}`

`UNTAGGED` is a reserved intent label for unclassified effects. Clients decide
how `UNTAGGED` should influence a scaffold or derivative.

Detection rulepacks and core composition:

- `logging:logging` — configured print calls and qualifiers, logger receiver
  interaction, and logging-state conditions. A call on a receiver whose LSP type
  is a configured logger type is tagged; factory properties and qualifiers answer
  without LSP, while ambiguous receiver names and import-gated matches consult
  the type oracle. A resolved non-logger type rejects the heuristic; an
  unresolved type falls back to it. Live mode caps hover time.
- `bookkeeping:bookkeeping` — a property annotated with `@Bookkeeping` or
  preceded by `// cint:bookkeeping`. The declaration and all writes to it are
  tagged Bookkeeping; reads are not.
- `validation:validation` — an `if` guard tagged Validation when its branch has a
  Bookkeeping write, the condition is a validity predicate, the condition is
  side-effect-free, or a `// cint:validation` comment precedes it. The annotation
  is also a return certificate, so core can categorize returns without knowing
  the validation intent or config. Bare exits stay neutral.
- `error-handling-<language>:error-handling` — `throw` and configured checks
  (`require`, `assert`, ...). Call spans include trailing lambda arguments.
- `core:return-category` — classifies each `return` from the function CFG. A
  return on a certified guard's true edge inherits that guard's source intents
  (for example `VALIDATION`); a rejection sentinel on the other edge inherits
  them too. A normal return is `UNTAGGED` and blocks whole-function scaffolds
  instead of staying neutral. Core reads the generic certificate flag on source
  annotations, not validation-specific config.
- `core:derivative-use` — a local whose initializer is explicitly pure (literal,
  local read, or an annotated pure call) and whose reads flow into classified
  statements, or an annotated pure function whose references all occur inside
  other derivative-use declarations. Unannotated calls and operators are assumed
  to have side effects and are not derivative.
- `core:scaffold` — bottom-up control-flow classification. An `if`/`catch` gets
  the union of its guard and body intents. Side-effect-free guards (constants,
  comparisons, reads) are neutral; effectful guards add `UNTAGGED`. Mixed
  `if`/`else` emits separate then/else spans, so the `if` part can be `LOGGING`
  while an untagged else stays visible. A catch span includes the preceding
  try-body `}` so the try/catch boundary is concealed as one unit. A `try` is
  classified whole only when the body, catches, and finally are fully covered;
  otherwise all-logging catches can still classify the `try {` header.

## Client-side meanings

The engine deliberately emits only `role` and `intents`; it has no meaning
labels or resolution table. Clients (for example the nvim renderer) map intents
to labels and merge sets, so presentation can change without re-running analysis.
A client might map `LOGGING -> CONCEAL`, `ERROR_HANDLING -> SHOW`, and let `SHOW`
win for mixed scaffolds.

## Config

Detection config belongs to rulepacks. A project config file supplies pack
sections keyed by pack id plus a `core:` section for framework policy:

```yaml
core:
  purity:
    annotations: [Pure, "Contract(pure = true)"]
  lsp:
    enabled: true
    timeoutMs: 750

logging:
  kotlin:
    printCalls: [println, print]
    printQualifiers: [System.out, System.err]

error-handling-kotlin:
  calls: [require, requireNotNull, check, checkNotNull, error, TODO, assert]
  throws: true

validation:
  predicates: [none, any, all, isEmpty, isBlank, contains, matches]
  predicatePrefixes: [is, has]
```

Missing values use the owning pack's defaults. Unknown packs and keys are
reported. Purity is explicit: an annotation means pure; its absence means
impure. There is no body inference.

## LSP sharing

The Kotlin oracle connects to the **same** `kotlin-lsp --multi-client` server
that Neovim uses. The socket is derived deterministically from the project root
(`13000 + sha256(root)[0:8] % 20000`), matching the nvim adapter. If no server
is listening, the engine starts one with the same flags; otherwise it attaches
and never spawns a duplicate. `--no-lsp` forces heuristic facts only.

LSP queries are read-only (`textDocument/hover` for the receiver type). The
engine does not share document sync, diagnostics, or capabilities with the
editor.

## Live server

The server can run as a per-project daemon over a Unix socket:

```sh
./gradlew :server:installDist
cint-server --socket /path/to/project.sock --root /path/to/project
```

Transport is NDJSON JSON-RPC. The client sends `textDocument/didOpen`,
`didChange`, and `didClose` with full text and a monotonic version. The server
re-runs that file and emits `cint/publishTags` with the analyzed
version, content hash, and annotations. `cint/analyze` requests an
explicit re-run. Profiles and shards stay client-side; changing them never
re-runs analysis.

Flags: `--socket`, `--root`, `--no-lsp`, `--config`, `--lang`. The live server
reuses the shared `kotlin-lsp` socket. The HTTP server remains available for
batch and editor-agnostic requests.

## Usage

```sh
# batch
./gradlew :cli:run --args="/path/to/project --out tags.json"

# install the launcher
./gradlew :cli:installDist
build/install/cint/bin/cint /path/to/project

# HTTP
./gradlew :server:run --args="--port 8765"
curl -X POST http://127.0.0.1:8765/analyze \
  -H 'Content-Type: application/json' \
  -d '{"path":"Sample.kt","root":"/path/to/project","text":"..."}'
```

POST `/analyze` accepts `path`, `root`, `text`, `language`, `config`, and an
optional `types` map (`{"<byteOffset>": "fq.Type"}`) to inject editor-provided
facts. `--no-lsp` disables the LSP oracle on the server.

## Artifact

```json
{
  "language": "kotlin",
  "intents": ["LOGGING", "BOOKKEEPING", "ERROR_HANDLING", "VALIDATION", "UNTAGGED"],
  "files": [
    {
      "path": "Sample.kt",
      "hash": "sha256-hex",
      "spans": [
        {"role":"source-intent","intents":["LOGGING"],
         "source":"lsp","confidence":0.95,
         "startLine":10,"startCol":8,"endLine":10,"endCol":22,
         "startByte":240,"endByte":278,"nodeAnchor":null,
         "provenance":[{"rule":"logging:logging","role":"source-intent",
           "intents":["LOGGING"],"source":"lsp",
           "confidence":0.95,"startLine":10,"startCol":8,"endLine":10,"endCol":22,
           "startByte":240,"endByte":278}]},
        {"role":"scaffold","intents":["LOGGING","ERROR_HANDLING"],
         "source":"treesitter","confidence":0.9,
         "startLine":12,"startCol":4,"endLine":18,"endCol":5,
         "startByte":310,"endByte":455,"nodeAnchor":null,
         "provenance":[{"rule":"core:scaffold","role":"scaffold",
           "intents":["LOGGING","ERROR_HANDLING"],
           "source":"treesitter","confidence":0.9,"startLine":12,"startCol":4,
           "endLine":18,"endCol":5,"startByte":310,"endByte":455}]}
      ]
    }
  ]
}
```

Lines and columns are 0-based. `hash` covers canonical content (CRLF -> LF, one
trailing newline removed) so consumers can reject stale artifacts.

## Development

```sh
just build        # assemble every module; does not run tests
just test-engine  # deterministic engine, host, server, and integration tests
just test-lsp     # opt-in shared-LSP oracle test (CINT_LSP_ROOT)
just test-nvim    # headless renderer tests
just lint         # ktlint over Kotlin sources
just install      # CLI and live-server distributions
```

Lint scope is Kotlin only; the Lua renderer is covered by `just test-nvim` but
is not format-linted here.

## Testing grounds

`../testing-grounds/kotlin/`, `javascript/`, `typescript/`, `vue/`, and `csharp/`
contain fixtures for interactions, scaffolds, catches, derivative uses, purity
annotations, Error Handling, and heuristic false positives. They are
intentionally not part of Gradle and need not compile.

```sh
cint ../testing-grounds --no-lsp --all
```
