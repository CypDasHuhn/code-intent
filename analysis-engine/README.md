# Semantic Code Painter Engine

Kotlin engine for intent tagging. Tree-sitter provides structure, LSP provides
types and symbols, and a playset of rule blocks turns those facts into tags.

## Modules

| module | responsibility |
|--------|----------------|
| `core` | model, config, artifact writer, oracle interface, block engine |
| `treesitter` | Kotlin parser and structural blocks (`print`, `logger-interaction`, `intent-scaffold`) |
| `lsp` | shared `kotlin-lsp` oracle |
| `cli` | batch host over a directory |
| `server` | HTTP host for buffers and files |

`core` has no tree-sitter, LSP, or editor dependency. Hosts wire the pieces.

## Blocks

Blocks are applied to a fixed point. Interactions are tagged first, then
structural blocks can consume them.

- `print` (tree-sitter): `println`/`print`, including `System.out`/`System.err`.
- `logger-interaction` (LSP): a call on a receiver whose resolved type is a
  configured logger type. Falls back to configured factory symbols, receiver
  names, qualifiers, and imports with lower confidence.
- `intent-scaffold` (tree-sitter): an `if` whose body is entirely tagged for the
  domain and whose guard is neutral (constant, domain-tagged, or a configured
  guard name/type). This is domain-agnostic: it works for any feature tag.

Tags carry `kind` (`interaction` / `control-flow`), `source`
(`treesitter` / `lsp` / `heuristic`), and `confidence`.

## Config

`core/src/main/resources/logging.yaml` is bundled; `--config` overrides it.

```yaml
features:
  logging:
    tag: Logging
    blocks: [print, logger-interaction, intent-scaffold]
    languages:
      kotlin:
        methods: [info, debug, warn, error, trace, log, verbose, fatal]
        types: [org.slf4j.Logger, org.apache.logging.log4j.Logger, java.util.logging.Logger]
        factories: [LoggerFactory.getLogger, KotlinLogging.logger, LogManager.getLogger, Logger.getLogger]
        receiverNames: [log, logger, LOG, LOGGER, Logger]
        qualifiers: [System.out, System.err]
        guardNames: [loggingEnabled, logEnabled, debugEnabled, logging]
        guardTypes: []
        imports: [org.slf4j, mu.KotlinLogging, java.util.logging]
```

## LSP sharing

The Kotlin oracle connects to the **same** `kotlin-lsp --multi-client` server
that Neovim uses. The socket is derived deterministically from the project root
(`13000 + sha256(root)[0:8] % 20000`), matching the nvim adapter. If no server
is listening, the engine starts one with the same flags; otherwise it attaches
and never spawns a duplicate. `--no-lsp` forces heuristic facts only.

LSP queries are read-only (`textDocument/hover` for the receiver type). The
engine does not share document sync, diagnostics, or capabilities with the
editor.

## Usage

```sh
# batch
./gradlew :cli:run --args="/path/to/project --out tags.json"

# install the launcher
./gradlew :cli:installDist
cli/build/install/semantic-code-painter/bin/semantic-code-painter /path/to/project

# HTTP
./gradlew :server:run --args="--port 8765"
curl -X POST http://127.0.0.1:8765/analyze \
  -H 'Content-Type: application/json' \
  -d '{"path":"Sample.kt","root":"/path/to/project","text":"..."}'
```

POST `/analyze` accepts `path`, `root`, `text`, `language`, `feature`,
`config`, and an optional `types` map (`{"<byteOffset>": "fq.Type"}`) to inject
editor-provided facts. `--no-lsp` disables the LSP oracle on the server.

## Artifact

```json
{
  "feature": "logging",
  "tag": "Logging",
  "language": "kotlin",
  "files": [
    {
      "path": "Sample.kt",
      "hash": "sha256-hex",
      "spans": [
        {"tag":"Logging","kind":"interaction","rule":"typed-receiver",
         "evidence":"log.info(...)","source":"lsp","confidence":0.95,
         "startLine":10,"startCol":8,"endLine":10,"endCol":22}
      ]
    }
  ]
}
```

Lines and columns are 0-based. `hash` covers canonical content (CRLF -> LF, one
trailing newline removed) so consumers can reject stale artifacts.
