# Semantic Code Painter Engine

Kotlin engine for intent classification. Tree-sitter provides structure, LSP
provides types, and rule blocks classify the result into roles and intent sets.
The engine emits only semantic facts; clients resolve intent sets into meanings
and visuals.

## Modules

| module | responsibility |
|--------|----------------|
| `core` | model, config, artifact writer, oracle interface, block engine |
| `treesitter` | Kotlin parser and rule blocks |
| `lsp` | shared `kotlin-lsp` oracle |
| `cli` | batch host over a directory |
| `server` | HTTP host for buffers and files |

`core` has no tree-sitter, LSP, or editor dependency. Hosts wire the pieces.

## Classification

Every annotation has:

- `role`: `SOURCE_INTENT`, `DERIVATIVE_USE`, or `SCAFFOLD`
- `intents`: a set such as `{Logging}` or `{Logging, ErrorHandling}`

`UNTAGGED` is a reserved intent label for unclassified effects. Clients decide
how `UNTAGGED` should influence a scaffold or derivative.

Blocks:

- `print` — `println`/`print`, including `System.out`/`System.err`.
- `logger-interaction` — a call on a receiver whose LSP type is a configured
  logger type. Factory properties and qualifiers answer without LSP; ambiguous
  receiver names and import-gated matches consult the type oracle. A resolved
  non-logger type rejects the heuristic; an unresolved type falls back to it.
  Live mode caps hover time.
- `logging-state` — conditions using configured logging-state names.
- `error-handling` — `throw` and configured Kotlin checks
  (`require`, `requireNotNull`, `check`, `checkNotNull`, `error`, `TODO`,
  `assert`). Call spans include trailing lambda arguments.
- `derivative-use` — a local whose initializer is pure and whose reads flow into
  classified statements, or a declaration (local or function) whose references
  all occur inside other derivative-use declarations. Purity is literal/local
  or an explicit annotation; the transitive rule also promotes declarations with
  impure initializers or bodies.
- `intent-scaffold` — bottom-up control-flow classification. An `if`/`catch`
  gets the union of its guard and body intents. Side-effect-free guards
  (constants, comparisons, reads) are neutral; effectful guards add `UNTAGGED`.
  Mixed `if`/`else` emits separate then/else spans, so the `if` part can be
  Logging while an untagged else stays visible. A catch span includes the
  preceding try-body `}` so the try/catch boundary is concealed as one unit. A
  `try` is classified whole only when the body, catches, and finally are fully
  covered; otherwise all-logging catches can still classify the `try {` header.

## Client-side meanings

The engine deliberately emits only `role` and `intents`; it has no meaning
labels or resolution table. Clients (for example the nvim renderer) map intents
to labels and merge sets, so presentation can change without re-running analysis.
A client might map `Logging -> CONCEAL`, `ErrorHandling -> SHOW`, and let `SHOW`
win for mixed scaffolds.

## Config

`core/src/main/resources/intents.yaml` is bundled; `--config` overrides it.

```yaml
language: kotlin

intents:
  Logging:
    blocks: [print, logger-interaction, logging-state]
    kotlin:
      methods: [info, debug, warn, error, trace, log, verbose, fatal]
      types: [org.slf4j.Logger, org.apache.logging.log4j.Logger, java.util.logging.Logger]
      factories: [LoggerFactory.getLogger, KotlinLogging.logger, LogManager.getLogger, Logger.getLogger]
      receiverNames: [log, logger, LOG, LOGGER, Logger]
      qualifiers: [System.out, System.err]
      guardNames: [loggingEnabled, logEnabled, debugEnabled, logging]
      imports: [org.slf4j, mu.KotlinLogging, java.util.logging]

  ErrorHandling:
    blocks: [error-handling]
    kotlin:
      calls: [require, requireNotNull, check, checkNotNull, error, TODO, assert]
      throws: true

purity:
  annotations:
    - Pure
    - Contract(pure = true)
    - org.checkerframework.dataflow.qual.Pure
```

Purity is explicit: an annotation means pure; its absence means impure. There is
no body inference.

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
semantic-code-painter-server --socket /path/to/project.sock --root /path/to/project
```

Transport is NDJSON JSON-RPC. The client sends `textDocument/didOpen`,
`didChange`, and `didClose` with full text and a monotonic version. The server
re-runs that file and emits `semanticPainter/publishTags` with the analyzed
version, content hash, and annotations. `semanticPainter/analyze` requests an
explicit re-run. Profiles and shards stay client-side; changing them never
re-runs analysis.

Flags: `--socket`, `--root`, `--no-lsp`, `--config`. The live server reuses the
shared `kotlin-lsp` socket. The HTTP server remains available for batch and
editor-agnostic requests.

## Usage

```sh
# batch
./gradlew :cli:run --args="/path/to/project --out tags.json"

# install the launcher
./gradlew :cli:installDist
build/install/semantic-code-painter/bin/semantic-code-painter /path/to/project

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
  "intents": ["Logging", "ErrorHandling", "UNTAGGED"],
  "files": [
    {
      "path": "Sample.kt",
      "hash": "sha256-hex",
      "spans": [
        {"role":"source-intent","intents":["Logging"],
         "rule":"typed-receiver","evidence":"log.info(...)","source":"lsp",
         "confidence":0.95,"startLine":10,"startCol":8,"endLine":10,"endCol":22},
        {"role":"scaffold","intents":["Logging","ErrorHandling"],
         "rule":"intent-scaffold","evidence":"if (...) { ... }",
         "source":"treesitter","confidence":0.9,
         "startLine":12,"startCol":4,"endLine":18,"endCol":5}
      ]
    }
  ]
}
```

Lines and columns are 0-based. `hash` covers canonical content (CRLF -> LF, one
trailing newline removed) so consumers can reject stale artifacts.

## Testing grounds

`../testing-grounds/kotlin/` contains fixtures for interactions, scaffolds,
catches, derivative uses, purity annotations, Error Handling, and heuristic
false positives. They are intentionally not part of Gradle and need not compile.

```sh
semantic-code-painter ../testing-grounds --no-lsp --all
```
