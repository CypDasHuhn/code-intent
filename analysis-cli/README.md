# Semantic Code Painter

> Code to Intent.

PoC #1: a language-agnostic intent-tagging engine over a normalizing frontend,
driven entirely by per-language **config**. First target: **Logging** in Kotlin.

## The idea

Code editors already color code by *syntax*. This paints code by *intent*
(error handling, logging, validation, business logic, ...). Intent is not
derivable from structure alone, so the model is:

> **structural facts** (from a frontend) **+ project config** → **intent tags**
> → **editor decoration**

The query engine never mentions a language. Every language detail lives in
config as data. The same query template fires for Kotlin, Python, etc.; only
the vocabulary differs.

## The generic logging query

Given facts, tag a call when **any** of these generic patterns match:

| rule | pattern | config keys |
|------|---------|-------------|
| `call-on-logger-symbol` | call on a symbol whose initializer is a known factory | `factories` |
| `receiver-name` | call on a receiver named like a logger, with a log-ish method | `receiverNames`, `methods` |
| `qualified-static` | static-looking call on a known qualifier | `qualifiers`, `methods` |
| `name-import-gated` | qualified log-ish method, file imports a logging module | `methods`, `imports` |
| `print` | `println`/`print`, or on stdout/stderr | *(builtin)* |

Note all method-name rules require a **receiver**, which is why Kotlin's
`error(...)`, `require(...)`, `check(...)` (stdlib preconditions, not logging)
are correctly ignored.

## Config

`config/logging.yaml`, keyed **feature → language**:

```yaml
features:
  logging:
    tag: Logging
    languages:
      kotlin:
        methods: [info, debug, warn, error, trace, log, verbose, fatal]
        factories: [LoggerFactory.getLogger, KotlinLogging.logger, LogManager.getLogger, Logger.getLogger]
        receiverNames: [log, logger, LOG, LOGGER, Logger]
        qualifiers: [System.out, System.err]
        imports: [org.slf4j, mu.KotlinLogging, java.util.logging]
```

## Usage

```sh
./gradlew run --args="<path> [--config file] [--lang kotlin] [--feature logging] [--out file] [--all]"
```

Prints a JSON tag artifact:

```json
{
  "feature": "logging",
  "tag": "Logging",
  "language": "kotlin",
  "files": [
    {
      "path": "Sample.kt",
      "hash": "43656976f5…",
      "spans": [
        {"tag":"Logging","rule":"call-on-logger-symbol",
         "evidence":"log.info(\"starting work for {}\", id)",
         "startLine":10,"startCol":12,"endLine":10,"endCol":44}
      ]
    }
  ]
}
```

Lines are **0-based**, columns **0-based** (LSP/Neovim friendly). Each file
carries a `hash` of its content so consumers can detect a stale artifact. The
artifact is deliberately editor-agnostic; a Neovim renderer consumes it.

## Layout

```
src/main/kotlin/com/semanticpainter/
  Main.kt          CLI, repo walk, JSON emit
  Model.kt         facts + spans + line index
  KotlinFrontend.kt  Kotlin PSI -> language-agnostic facts
  Config.kt        feature config loading
  IntentQuery.kt   the generic query engine
  Json.kt          tiny JSON escaping
config/logging.yaml
samples/Sample.kt
```

## Requires

JDK 21+. `kotlin-compiler-embeddable` is used for parsing (PSI, no type
resolution yet).

## Explicit non-goals (PoC #1)

No type/dataflow resolution, no other languages, no nesting/blend model, no
profile wizard, no incremental indexing, no editor renderer yet.
