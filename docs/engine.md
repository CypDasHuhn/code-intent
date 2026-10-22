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
| `rulepacks` | bundled logging, error-handling, bookkeeping, validation, normalization, data-access, service, mutation, data-transformation, return-value, and local-copy packs (boolean-combinatorics and chaining are implemented but currently disabled) |
| `core` | facts, config, artifact writer, oracle interface, CFG, scheduler, candidate channel, composition |
| `suggest` | candidate context (bounded excerpt + metadata), provider SPI, fake and DeepSeek providers, privacy gate/redaction, validation, merge, and grouping |
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
aborting the whole load. The bundled descriptor registers logging,
error-handling, bookkeeping, validation, normalization, data-access, service,
mutation, data-transformation, return-value, and
local-copy packs; `BundledRulePackSelection` constructs the per-language
error-handling rules. The tests-only modules are not production dependencies.

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
- `validation:validation` — an `if` guard tagged Validation when its branch
  contains a rejecting exit (bare `return`/`continue`/`break`, or a
  sentinel return) and the condition is not a known write. Elvis/coalesce forms
  use the same gate. A validation predicate, a Bookkeeping write, or a
  `// cint:validation` comment is rejection-independent. `throw`/`raise` guards are
`ERROR_HANDLING`, not `VALIDATION`, unless an explicit validation certificate
is present. Unbraced bodies are
  supported, and jumps inside nested functions/lambdas, nested loops, or nested
  switches do not certify the outer guard. The read-only fallback accepts
  unknown read-only conditions at lower confidence; known writes and mutation
  intents block it. The annotation is also a return certificate, so core can
  categorize returns without knowing the validation intent or config. Bare
  exits stay neutral.
- `error-handling-<language>:error-handling` — `throw` and configured checks (elvis/coalesce throws are tagged from the `?:`/`??` operator through the throw)
  (`require`, `assert`, ...). Call spans include trailing lambda arguments.
- `normalization:normalization` — configured normalizer calls and prefixes
  (`trim`, case folding, parse/coerce, canonicalize) that are gated by a
  configured qualifier or oracle-resolved type. Ambiguous display formatting,
  unprefixed unconfigured helpers, and chain tails after an established transform
  are candidates.
- `data-access:search` — configured repository/query calls and prefixes on a
  configured search qualifier/type; ambiguous `get`/`find`/`fetch*` wrappers and
  bare configured calls stay candidates. `findBy*` also gets a `LOOKUP` boundary
  candidate.
- `data-access:lookup` — known-map/table index reads, configured lookup calls on
  known receivers/types, and enum/constant resolution (`valueOf`, `fromCode`,
  ...). Write targets and unknown indexes are not lookup; ambiguous calls are
  candidates, while configured compound mutation calls are excluded from
  SEARCH/LOOKUP ambiguity.
- `service:delegation` — a thin whole-body forwarding expression to a configured
  service call. Mixed bodies, local-write/guard/try bodies, and controller or
  facade patterns without configured service evidence are candidates.
- `service:init` — configured construction, client creation, factory/connection
  setup, DI calls, and constructor injection (Kotlin `@Inject constructor`,
  TypeScript parameter properties, and C# primary/record constructors). Factory
  suffixes are init-only, so factory construction is not delegation. Lifecycle
  names alone are candidates; init call spans use the call/creation node.
- `mutation:data-modify` — configured persistence calls on repository/ORM,
  file, queue, event, and object-store receivers. A receiver must prove a
  durable qualifier, qualified durable type, or templated client; generic
  `Queue`/`Table`/`Session` names do not establish persistence on their own.
  Configured read-prefixed mutations such as `findOneAndUpdate` and
  `findOneAndDelete` are checked before the read filter; bare configured calls
  and unknown wrappers stay candidates.
- `mutation:local-value-modify` — writes to declaration-local variables and
  configured mutable collection operations on proven collection receivers.
  Member/external assignments, unproven `add`/`put`/`remove` calls, loop
  counters, and bookkeeping writes stay out of the established set. The rule
  reads `LOCAL_COPY` (and `BOOKKEEPING`); with `ignorePureCopies` default-on, a
  write carrying established `LOCAL_COPY` is skipped entirely, while setting it
  false restores `LOCAL_COPY` + `LOCAL_VALUE_MODIFY` co-occurrence. Candidate
  copies do not suppress because only established annotations enter the reads
  graph.
- `bool-combinatorics:bool-combinatorics` — complex boolean shape from guard
  conditions, return expressions, and initializers. Established when the
  expression has multiple/mixed `&&`/`||`, a negated compound, a configured
  quantifier (`all`/`any`/`none`, `every`/`some`, `All`/`Any`), or two or more
  distinct classified predicates. Simple comparisons, `if (x != null)`, trivial
  `a && b`, negation-only guards, and prefix collisions like `issue`/`hashCode`
  stay untagged or candidate.
- `data-transformation:data-transformation` — configured collection-shaping
  calls (map/filter/fold/reduce/distinct/sorted/groupBy/quantifiers, and
  language equivalents). It reads `SEARCH` and its own intent so search-owned
  receivers and chained transformations settle first. Established only with a
  configured qualifier, a known collection type, or a receiver already carrying
  `SEARCH`/`DATA_TRANSFORMATION`; bare calls, unknown types, unclassified call
  receivers, and offline-oracle receivers stay candidates. In-place JS mutators
  may overlap `LOCAL_VALUE_MODIFY`.
- `return-value:return-value` — explicit value returns from CFG return sites.
  Emission lands on the returned expression, so it is additive with `SEARCH`,
  `VALIDATION`, `DATA_MODIFY`, `CHAINING`, and other intents at the same span.
  Bare `return` and bare `return@label` do not qualify; a labeled return with a
  value emits that value. Constant/literal-only expressions are excluded unless
  `includeLiterals` (alias `allowLiterals`) is enabled. The rule reads no other
  intent.
- `chaining:chaining` — fluent call shape with at least `minimumLinks` (default
  2, alias `minLinks`). The walker keeps a call only when it covers the
  receiver's exact node/site span, so array/index/binary bases contribute only
  their real call links (for example `a[b()].c().d()` collects the `c`→`d`
  links and emits the full expression, while `a[b()].c()` is below the minimum).
  The outermost call emits once and inner calls are members. Boolean `||`
  operands and parenthesized OR
  receivers are excluded when `excludeBooleanChains` is on (default); `||`
  inside strings, arguments, or a recognized lambda boundary does not suppress a chain.
  Conditional/ternary bases stay `LOW_CONFIDENCE` candidates. The rule reads no
  other intent.
- `local-copy:local-copy` — parameter-to-field/local copies from assignments and
  declaration/property initializers, including class/constructor-scoped
  properties. Established requires a field-like LHS (owner-qualified, leading
  underscore, or configured prefix), a non-field RHS, and case-sensitive
  equality after stripping the configured prefix and leading underscores, with
  declared properties excluded as sources. Field/self copies do not establish.
  Enclosing-function parameter names are parsed as supporting evidence, but this
  remains a local heuristic rather than binding resolution; field/self,
  ambiguous owner-qualified, and differently named local shapes are candidates
  (`candidates`/`includeCandidates` default on). Literals and call expressions
  do not qualify. General mutation and persistence are excluded.
- `core:return-category` — classifies each `return` from the function CFG. A
  return on a certified guard's true edge inherits that guard's source intents
  (for example `VALIDATION`); a rejection sentinel on the other edge inherits
  them too. A normal return is `UNTAGGED` and blocks whole-function scaffolds
  instead of staying neutral. Core reads the generic certificate flag on source
  annotations, not validation-specific config.
- `core:derivative-use` — a local whose initializer is read-only (no known
  writes or `DATA_MODIFY`/`LOCAL_VALUE_MODIFY`/`BOOKKEEPING` mutation) and whose
  reads flow into classified statements. Known read-only initializers derive at
  `0.7`; unknown read-only initializers derive at `0.55`. External reads are
  allowed, while known writes/mutations block promotion. A read-only property
  initializer or assignment RHS propagates its source intents to the whole
  declaration/assignment statement; effects inside lambdas/call arguments are
  dropped while value intents (`SEARCH`, `DATA_TRANSFORMATION`) are kept, and
  confidence is the minimum source confidence. Annotated/user-pure functions
  whose references all occur inside other derivative-use declarations are still
  promoted only when explicitly pure.
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

AI enrichment is post-analysis and optional. The server resolves
`.cint/suggestions.yml`, injects the provider config and key lookup, and
orchestrates one pass per document; `:suggest` supplies the provider SPI and owns
the remote transport. The only remote provider in this slice is DeepSeek
(`deepseek-chat`, `DEEPSEEK_CHAT_KEY`), using the JDK `HttpClient` with no
provider SDK and no retries. AI proposals carry `source = ai:deepseek`,
`provider`/`model`/`promptVersion`/`rationale`, and the input candidate's reason.
Their confidence is diagnostic: `maxConfidence` defaults to `0.84` and can only
lower that ceiling. Candidate-only is structural, not a confidence threshold:
the output is a `CandidateEmit` and never enters the annotation store.

### Renderer review UI

The nvim renderer keeps candidates passive (dotted underline and a muted `?`
sign) until `:CintReview` toggles buffer-local review mode. In review mode every
candidate range uses a visible review highlight and a virtual line above the
range with intent name(s), confidence, source/reason, and a concise rationale.
`:CintReviewAccept [intent]` confirms the candidate under the caret, opening a picker when several alternatives cover the caret (or choosing the
alternative matching `intent`).
`:CintReviewDeny` rejects every alternative in that anchor group. Both commands
keep review mode active and let the server republish update the marks; toggling
review off restores passive rendering. When no candidate covers the caret, or a
requested intent is absent, the renderer notifies instead of sending a decision.
`:CintIntentLabels` toggles virtual intent labels above every annotation. `:CintStripes` toggles active-span mixed-intent color striping. `:CintSuggest` reports a protocol below 3, a missing suggestions descriptor, and
`suggestions.enabled: false` in `.cint/suggestions.yml` as distinct errors; it
never enables AI.

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

mutation:
  kotlin:
    persistenceCalls: [save, update, delete, put, sendMessage, convertAndSend]
    persistencePaths: [Files.write, File.WriteAllTextAsync]
    persistenceQualifiers: [repository, dao, bucket, kafka]
    persistenceTypes:
      [Repository, EntityManager, org.hibernate.Session, javax.jms.Queue]
    persistencePrefixes: [persist, publish, send, write, flush, commit]
    mutableOperations: [add, put, remove, clear]
    mutableTypes: [MutableList, MutableMap, ArrayList]
    mutableReceiverNames: [items, values, cache]
    assignmentPatterns: [assignment, increment, decrement]
    ignorePureCopies: true

return-value:
  kotlin:
    includeLiterals: false

chaining:
  kotlin:
    minimumLinks: 2
    excludeBooleanChains: true

local-copy:
  kotlin:
    prefixes: [m_, s_, f_]
    candidates: true
```

Bundled pack ids are `logging`, `error-handling`, `bookkeeping`, `validation`,
`normalization`, `data-access`, `service`, `mutation`, `bool-combinatorics`,
`data-transformation`, `return-value`, `chaining`, and `local-copy`; each carries
per-language fields for its own detection vocabulary.

Missing values use the owning pack's defaults. Unknown packs and keys are
reported. Purity annotations remain explicit: an annotation means pure and
absence means no purity claim; there is no body inference. Purity still gates
function-reference derivative promotion, while local initializer promotion uses
the read-only policy above.

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
overlay back. Most bundled packs use `SingleIntentPack`; the mutation pack uses
a custom `ConfigurableRulePack` with the same additive semantics. The overlay only changes pack config; it never creates source
annotations, contributions, evidence, confidence, or magic comments. A malformed
overlay is reported, and mutations are refused until it is fixed.

Suggestion settings are discovered at `<root>/.cint/suggestions.yml` under a
top-level `suggestions:` mapping. Implemented keys are `enabled` (default
`false`), `trigger` (default `manual`), `provider` (default `deepseek` when
enabled and unset; `fake` remains available), `model` (default `deepseek-chat`
for the DeepSeek provider), `maxCandidates` (default `5`, minimum `1`), `maxEnrichmentCandidates`
(default `5`, minimum `0`),
`promptVersion` (default `deepseek-intent-v1` for DeepSeek), `timeoutMs`
(default `10000`, minimum `1`), `workerThreads` (default `2`, clamped to `1..8`),
`apiKeyEnv` (default `DEEPSEEK_CHAT_KEY`), `baseUrl` (default
`https://api.deepseek.com/chat/completions`), `maxTokens` (default `512`),
`maxConfidence` (default `0.84`, clamped to `0..0.84`), `excerptRadiusBytes`
(default `400`, minimum `0`), `allowGlobs` (default empty, which allows every
path), and `redactPatterns` (default empty). This gate controls the
`cint/suggest` enrichment pass; deterministic pack candidates still publish when
it is disabled.

The server resolves this file, injects the provider config and key lookup, and
orchestrates the pass. `:suggest` owns the DeepSeek provider and its remote
transport: it builds the `Authorization` header, uses the JDK `HttpClient`, and
applies `timeoutMs` to the HTTP request. `baseUrl` and `apiKeyEnv` are
project-controlled, so enabling suggestions trusts the project config. The key
value appears only in the request header, never in the secret-free capability
descriptor or logs. Missing keys, non-200 responses, timeouts, and malformed
output yield no AI proposals after one attempt; there are no retries. AI
candidates carry `source = ai:deepseek` plus `provider`/`model`/`promptVersion`/
`rationale`, and their confidence is a diagnostic value capped at
`maxConfidence`, not an auto-establish threshold.

Only the candidate span plus `excerptRadiusBytes` per side is sent, with
candidate metadata, known/neighboring intents, path/language, and the prompt
version; imports and enclosing functions are not sent. `allowGlobs` gates the
pass, and `redactPatterns` star out regex matches in the excerpt before sending
while preserving byte length and line breaks. The suggestion and provider pools
are bounded by `workerThreads`, and provider calls are interrupted after
`timeoutMs`. Both pools stop when the server run loop exits or the JVM shutdown
hook runs; the protocol `shutdown` request only acknowledges and leaves them
usable for later clients. Decisions are stored in `<root>/.cint/suggestions.json`; each record
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

Receiver types are memoized per analysis, so rules asking about the same
receiver/offset share one result. A session key includes the file path and
content hash; reusing a session for new text resets its per-analysis cache,
counters and budget. The oracle keeps a bounded per-document-version cache for
positive and negative hover results. It invalidates a URI lazily when a later
query sees changed text, sending `didClose` then `didOpen` with a bumped
version; other URIs survive. Positive types are durable for a document version.
Empty hovers use a bounded negative TTL (500 ms by default, configurable at the
oracle seam): they are reused for immediate analyses and re-hovered after the
TTL, so a cold or partially indexed server can recover without pinning a
negative for the whole version. Timeouts and failures are not stored in the
document cache; they are memoized only within one analysis by `OracleSession`, so
duplicate hovers are still impossible in an analysis while a later analysis
retries. A revalidated negative or retried failure is a hover call, not a cache
hit. Each analysis also has an oracle budget/circuit breaker: after two
timeouts, three failures, or 1500 ms of cumulative hover time, further queries
return no type and detection degrades to text/config evidence. Split documents
use one budget per analyzed region because each region has its own frontend and
oracle; the bundled split languages currently use the null oracle, so this has
no live hover cost today.

Data-access lookup/search and enum queries gate the oracle behind callee,
qualifier, known-map, enum-shape and AST receiver-shape evidence, so literals,
binary expressions and lambdas do not hover while call/index/navigation
receivers remain typeable. Service delegation applies the same receiver-shape
gate before its type fallback. The live server reports per-analysis counters on
stderr: `requests`, `queries` (actual hover/delegate calls), `cacheHits`,
`sessionHits`, `delegateHits`, `timeouts`, `failures`, `blocked` and
`elapsedMs`; prompts and keys are never logged. Null-oracle analyses emit no
oracle diagnostic.

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
provider/model; an enabled file without a provider advertises `deepseek` and
`deepseek-chat`. The server answers with its own protocol version and does not
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
