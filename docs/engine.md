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
| `api` | frozen plugin contract: intents, rules, views, `RuleEmit`/`Emit`/`CandidateEmit`, annotations |
| `rulepacks` | bundled logging, error-handling, bookkeeping, and validation packs |
| `core` | facts, config, artifact writer, oracle interface, CFG, scheduler, candidate channel, composition |
| `suggest` | candidate context/provider SPI, fake provider, validation, merge, and grouping |
| `host` | host pipeline and project config loading shared by CLI, HTTP, and live server |
| `treesitter` | tree-sitter parsers and syntax profiles for Kotlin, JavaScript, TypeScript, Vue, and C# |
| `lsp` | spec-driven LSP oracle |
| `frontend` | language registry wiring parser, syntax model, and oracle |
| `cli` | batch host over a directory |
| `server` | HTTP host and per-project Unix-socket daemon |
| `rulepacks-tests` | integration tests for bundled packs using real parsers/frontends |
| `test-fixtures` | shared `java-test-fixtures` parser, pack, and fixture helpers |
| `integration-tests` | composition, host, frontend-pipeline, and snapshot integration tests |

Modules are grouped under `analysis-engine/core/` (`api`, `core`, `suggest`),
`analysis-engine/languages/`, `analysis-engine/hosts/`, and
`analysis-engine/tests/`; Gradle project names remain `:api`, `:core`,
and so on. `core` has no tree-sitter, LSP, or editor dependency. `suggest`
depends on `api` and `core` (candidate identity/hash) and carries no server or
provider SDK. `frontend` is the composition
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

## Candidate channel

The v5 rule contract (`CINT_API_VERSION = 5`) returns one list:

```kotlin
sealed interface RuleEmit { /* intents, span, source, confidence, bytes */ }

data class Emit(...) : RuleEmit                    // established

data class CandidateEmit(
    ...,
    val reason: CandidateReason,                   // GAP, LOW_CONFIDENCE, PACK_DOMAIN
    val aiEligible: Boolean = true,
    val rationale: String? = null,
    val promptVersion: String? = null,
    val provider: String? = null,
    val model: String? = null,
) : RuleEmit
```

`Rule.run` and `Matcher.match` return `List<RuleEmit>`. `Emit` is the only type
`toContribution` accepts. `settle` checks that every emit uses the rule's owned
intent, turns only established emits into contributions, and collects
candidates separately. Fixpoint rules converge on established output only and
keep candidates from the final settled iteration. `RuleEngine.analyze` returns
`AnalysisOutput(annotations, candidates)`.

Candidates never enter `AnnotationStore`, composition, purity, provenance, the
`reads` graph, or `cint/publishTags`; they travel on a separate channel.
Candidate builders default to source `candidate`; deterministic candidates use
`rule:<rule-id>` and AI proposals use `ai:<provider>`. Identity is path +
anchor/content hash + byte span + sorted intents + reason. Confirmed candidates
re-enter through the existing user-annotation path on the next analysis.

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

An optional user overlay at `<root>/.cint/config.overlay.yml` is auto-discovered
on load and after live config mutations. It is keyed by pack id; `PACK`-scoped
fields sit directly under the pack and `LANGUAGE`-scoped fields nest under the
language:

```yaml
logging:
  kotlin:
    methods: [audit]

validation:
  predicates: [isPresent]
```

Additive overlay support is opt-in: a pack must implement
`ConfigurableRulePack.configureAdditive` (normally via
`ConfigSchema.decodeAdditive`). For those packs the overlay is applied after the
project config with additive semantics: string lists and sets union with the
effective pack value, string-set maps recurse, and scalars from the overlay win.
Packs that do not opt in keep their project/default values; the loader reports
`UNSUPPORTED_PACK_OVERLAY`, and the server rejects the mutation and rolls the
overlay back. All bundled packs use `SingleIntentPack` and therefore support
additive overlay. The overlay only changes pack config; it never creates source
annotations, contributions, evidence, confidence, or magic comments. A malformed
overlay is reported, and mutations are refused until it is fixed.

Suggestion settings are discovered at `<root>/.cint/suggestions.yml` under a
top-level `suggestions:` mapping. Implemented keys are `enabled` (default
`false`), `trigger` (default `manual`), `provider`, `model`, `maxCandidates`
(default `5`, minimum `1`), `promptVersion`, `timeoutMs` (default `4000`,
minimum `1`), and `workerThreads` (default `2`, clamped to `1..8`). This gate
controls the `cint/suggest` enrichment pass; deterministic pack candidates still
publish when it is disabled. The suggestion and provider pools are bounded by
`workerThreads`, and provider calls are interrupted after `timeoutMs`. Both
pools stop when the server run loop exits or the JVM shutdown hook runs; the
protocol `shutdown` request only acknowledges and leaves them usable for later
clients. Decisions are stored in `<root>/.cint/suggestions.json`; each record
carries `candidateId`, `path`, `contentHash`, `intents`, `reason`, `decision`,
`createdAt`, and optional `anchorId` plus a `startLine`/`startCol`/`endLine`/
`endCol` span snapshot. Missing `anchorId` and span fields default sensibly, so
older records still load. A confirm also writes the existing
`<root>/.cint/user-annotations.json` record. That file is guarded by the
`<root>/.cint/user-annotations.json.lock` lock directory, which is shared by the
Kotlin writer and the nvim annotation writer. Writers acquire the directory
exclusively for the read-modify-write and remove it afterward; readers only read
`user-annotations.json`, which is replaced atomically.

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

Transport is NDJSON JSON-RPC, protocol `3`. `initialize` takes
`{protocol, root, client}` and returns `{protocol, root, languages, config,
suggestions}`. `config` is `{packs, overlay}`: `packs` lists pack descriptors
(`packId`, `rules` with `id`/`owns`/`reads`, `languages`, and `fields` with
`key`/`kind`/`scope`/`languages`), and `overlay` is the user overlay map. An
unreadable overlay adds `overlayError` to `config`. Field `kind` is one of
`STRING`, `BOOLEAN`, `STRINGS`, `STRING_LIST`, `STRING_SETS`, `CUSTOM`; `scope`
is `PACK` or `LANGUAGE`, and only language-scoped fields carry concrete
`languages`.

`suggestions` is a secret-free capability descriptor: `{enabled, trigger,
provider, model, knownIntents}`. It comes from `.cint/suggestions.yml`; a
missing file advertises `enabled: false`, `trigger: "manual"`, and null
provider/model. The server answers with its own protocol version and does not
negotiate the client's requested value; client suggestion features are gated on
a returned protocol of at least 3.

The client sends `textDocument/didOpen`, `didChange`, and `didClose` with full
text and a monotonic version. The server re-runs that file and emits
`cint/publishTags` with `uri`, `version`, content `hash`, config `revision`,
`analysisRevision`, and `annotations`. `revision` tracks pack/config changes;
`analysisRevision` is a monotonic per-document counter for every analysis
publish. The analysis, its config snapshot, and the new `analysisRevision` are
allocated together under the global lock, so revision order always matches
analysis order and a publish from a newer config snapshot cannot receive an
older `analysisRevision`. The client ignores stale out-of-order publishes whose
version or `analysisRevision` is older than the last accepted one, using
`revision` as a tiebreaker when an `analysisRevision` is reused. Analysis and
revision sampling are serialized under the global lock; socket writes and config
republishes happen outside it. `cint/analyze` requests an explicit re-run.
Profiles and shards stay client-side; changing them never re-runs analysis.

Every tags publish is followed by `cint/publishCandidates` on the secondary
channel (see below). Config mutations remain unchanged:

- `cint/config/read` takes no params and returns `{packs, overlay}`.
- `cint/config/append` and `cint/config/remove` take
  `{packId, language, field, value}` and return the same `{packs, overlay}`.
  They validate the pack, language, field, kind (`STRINGS` or `STRING_LIST`),
  and language scope, write `<root>/.cint/config.overlay.yml`, reload packs, bump
  the config revision, and republish tags for open documents. If the target pack
  does not support additive overlay (`UNSUPPORTED_PACK_OVERLAY`), the server
  restores or deletes the overlay, reloads the previous state, and reports an
  error without bumping the revision.

### Candidate protocol

- `cint/publishCandidates` is pushed after each tags publish and after a
  suggest/decision republish, including when the result is empty. Params are
  `uri`, `version`, `analysisRevision`, `suggestionRevision`, flat `candidates`,
  and `groups`. A candidate carries `candidateId`, `path`, `contentHash`,
  `intents`, `source`, `confidence`, span and byte positions, `reason`,
  `aiEligible`, plus optional `rationale`, `promptVersion`, `provider`, and
  `model`. A group carries `anchorId`, `path`, `contentHash`, span/positions, and
  its `candidates` alternatives for one anchored span. `suggestionRevision` is
  server-wide and monotonic. A candidate publish is dropped if its
  `analysisRevision` is older than the cached analysis or the last published
  candidates; the cached analysis revision advances with the config snapshot.
- `cint/suggest` takes `{uri, version, requestId}` and asynchronously enriches
  the cached analysis for that exact document version. It returns
  `{accepted, cancelled, count}`; a disabled, stale-version, or already-busy
  pass returns `{accepted: false, ...}` with `disabled`, `stale`, or `busy`.
  A provider timeout returns `{accepted: false, cancelled: true, timeout: true,
  count: 0}` after interrupting the provider. Only one pass runs per document,
  and the result is dropped if the document, `analysisRevision`, or candidate
  state changes before publish.
- `cint/suggest/cancel` takes `{uri?, requestId?}`, marks the matching in-flight
  pass cancelled, and returns `true`. Cancelled request ids are pruned after 60
  seconds.
- `cint/candidates/decide` takes `{uri, version, candidateId, decision}` with
  `decision` one of `confirm`, `reject`, or `skip`. It validates the document
  version and current anchor hash, persists the decision, and republishes. It
  returns `{accepted: true, stale: false, decision}` or
  `{accepted: false, stale: true}` / `{accepted: false, missing: true}`.
  Confirm adds a user annotation and records the candidate's `anchorId` and span,
  which resolves the whole anchor group: sibling alternatives are no longer
  published or confirmable. Reject persists suppression for that candidate.
  Skip remains per-client/session and is not persisted. Confirm and reject are
  project-global: the server republishes the URI to every client that has it
  open, so all owners of the same URI drop the resolved group or suppressed
  candidate. A republish after a decision still emits
  `cint/publishCandidates`, with empty `candidates`/`groups` when nothing
  remains. If the user annotation is later removed, the next analysis
  re-proposes the candidate unless it is rejected.

Failures use the existing JSON-RPC error shape `{code: -32000, message}`.
`cint/config/read` errors when the overlay is unreadable; append/remove also
refuse to overwrite an invalid overlay.

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
      ],
      "candidates": [
        {"intents":["VALIDATION"],
         "source":"rule:validation:validation","confidence":0.4,
         "startLine":20,"startCol":4,"endLine":20,"endCol":24,
         "startByte":520,"endByte":544,
         "reason":"GAP","aiEligible":true}
      ]
    }
  ]
}
```

Lines and columns are 0-based. `hash` covers canonical content (CRLF -> LF, one
trailing newline removed) so consumers can reject stale artifacts. `candidates`
defaults to an empty list. Entries share the live presentation fields (`intents`,
`source`, `confidence`, span/bytes, `reason`, `aiEligible`, and optional
`rationale`/`promptVersion`/`provider`/`model`) but omit the live-only
`candidateId` and `contentHash`; the artifact is a presentation surface, not a
decision surface.

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
