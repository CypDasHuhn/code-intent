# CodeIntent — session notes / knowledge dump

Purpose: raw, self-contained dump of a brainstorming+PoC session so a future
session can resume with no other context. Written for LLM consumption; density
over polish. Not maintained for humans.

Repo: `~/repos/code-intent` (monorepo). Tagline: **Code to Intent.**

This dump is partly historical. Sections 7-19 describe the pre-restructure PoC;
§20 is the current post-restructure state as of ticket 027. Sections 3-6 are
kept current enough to describe the artifact, config, detection, and renderer.

---

## 1. The idea (thesis)

Paint code in the editor by **intent** (Logging, Error Handling, Validation,
Business Logic, …) instead of by syntax. Like syntax highlighting, but for what
code _means_. Rendered as background tint + gutter icon; optionally conceal
(inline char or vertical hiding of lines).

Core premise: **intent is not derivable from the AST alone.** Structurally
identical calls can mean different things (`logger.info(x)` = Logging vs
`audit.record(x)` = Business Logic). So the system is:

> structural facts (frontend) + project config (taught conventions) -> intent
> tags -> editor decoration.

Neither half alone works. Config is where language/project specificity lives.

Design analogy: "syntax highlighting" -> "intent highlighting". Joern's CPG was
the initial inspiration (language-agnostic code graph).

## 2. Architecture / flow

```
source repo -> analysis-engine
              tree-sitter -> syntax facts -> CFG
              LSP (optional) -> type facts
              rules -> annotations -> JSON-RPC over Unix socket
            -> nvim-renderer -> extmarks

```

Components:

- `analysis-engine/` — Kotlin/JVM Gradle app. Parses, builds facts/CFG, tags.
- `nvim-renderer/` — Lua Neovim plugin. Socket-only; renders published tags.

The engine is editor-agnostic. The renderer is the only editor-specific part.

## 3. The artifact contract

`tags.json` is only the CLI/batch output. The live renderer is socket-only and
never reads it. Shape:

```json
{
  "language": "kotlin",
  "intents": ["LOGGING", "ERROR_HANDLING", "VALIDATION", "UNTAGGED"],
  "files": [
    {
      "path": "relative/from/scanned/root.kt",
      "hash": "sha256-hex",
      "spans": [
        {
          "role": "source-intent",
          "intents": ["LOGGING"],
          "evidence": "log.info(...)",
          "source": "heuristic",
          "confidence": 0.8,
          "startLine": 10,
          "startCol": 12,
          "endLine": 10,
          "endCol": 44,
          "startByte": 240,
          "endByte": 278,
          "nodeAnchor": null,
          "provenance": [
            {
              "rule": "logging:logging",
              "role": "source-intent",
              "intents": ["LOGGING"],
              "evidence": "log.info(...)",
              "source": "heuristic",
              "confidence": 0.8,
              "startByte": 240,
              "endByte": 278
            }
          ]
        }
      ]
    }
  ]
}
```

- Lines **0-based**, columns **0-based**; byte offsets are included.
- `hash` = SHA-256 of canonical content: CRLF->LF, strip one trailing `\n`.
  The live client compares buffer hashes/versions and rejects stale publishes.
- `role` is `source-intent`, `derivative-use`, or `scaffold`; `intents` is a set.
- `provenance` keeps per-contributor rule/evidence/source/confidence/span data.
- `nodeAnchor` is reserved and currently null.

## 4. The config contract

Detection config is owned by rulepacks and passed to the hosts with `--config`.
The file is keyed by pack id, plus a `core:` section for framework policy:

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
reported. Language-specific detection values live in the owning pack's config,
not in one global file; the engine never branches on language.

## 5. Detection as implemented (current rulepacks)

Detection is data-driven over `View` facts; rulepacks do not walk grammar node
kinds directly. The bundled packs live in `analysis-engine/core/rulepacks`:

- `logging:logging` — one `LOGGING` rule with print, logger-interaction, and
  logging-state matchers. It recognizes configured print calls/qualifiers,
  factory-initialized logger properties, logger receiver names/types/imports,
  and logging-state guards. Weak receiver/import heuristics consult the LSP type
  oracle when available; a resolved non-logger type vetoes them, an unresolved
  type falls back. Live hover time is capped.
- `error-handling-<language>:error-handling` — throw and configured checks
  (`require`, `assert`, ...), with per-language config and qualifiers.
- `bookkeeping:bookkeeping` — `@Bookkeeping` or `// cint:bookkeeping` declares a
  property; the declaration and writes are tagged, reads are not.
- `validation:validation` — certifies guards by a bookkeeping write, a validity
  predicate, a side-effect-free condition, or `// cint:validation`; the guard
  annotation also carries the return-certificate flag used by core composition.

Core composition adds `core:scaffold`, `core:derivative-use`, and
`core:return-category`; it consumes intent sets without knowing pack identity.
Limits still include approximate guard polarity and no whole-program purity
inference.

## 6. Renderer (nvim) as implemented

- **Socket-only.** It starts or attaches to a per-project `cint-server`, sends
  `didOpen`/`didChange`/`didClose` with versions, and renders published
  annotations. There is no artifact/offline path.
- Per-intent **meaning** resolution: `tags[intent].meaning`, `resolve.precedence`,
  `resolve.default`, then `rules[meaning]` chooses the handler.
- Handler registry (`lua/cint/handlers/`):
  - `color` — bg tint + gutter sign. opts `{bg,sign,icon}`; palette fallback.
  - `conceal` — `{char="…"}` inline replace; `{lines=true}` **vertical** hide.
  - `highlight` — background only; `ignore` — dim foreground.
- Profiles are partial presentation configs in `<root>/.cint/profiles.lua`
  (project scope) or `stdpath("config")/cint/profiles.lua` (global). Shards are
  one-shot patches folded over the profile.
- Vertical conceal uses nvim extmark `conceal_lines=""` (needs conceallevel>0;
  the handler auto-sets 2). `expand_on_cursor` reveals the whole active block.
- Live publishes carry version and content hash; stale versions are discarded
  while the previous marks stay visible. Re-analysis is debounced; writes and
  `:CintAnalyze` force a run.
- Commands include `:CintRender|Clear|Toggle|Refresh|Info|Profile|Shard|Analyze|`
  `LiveTags|ServerStatus|ServerDisconnect|ServerStop|ServerRestart`. Headless
  tests: `just test-nvim` / `nvim-renderer/tests/run.sh`.

Important: for THIS tool, stale data is worse than for a linter — a stale linter
shows wrong squiggles; a stale painter **hides the wrong lines**.

## 7. Freshness problem (unsolved, key)

Tagging lives in a separate JVM process with heavy startup
(KotlinCoreEnvironment), so per-save re-tag is laggy; full-repo per keystroke is
worse. Options considered:

1. single-file re-tag on save (debounced) — cheap, still JVM-startup latency,
   cross-file facts can drift.
2. resident daemon (socket) — amortizes JVM start; keeps Kotlin engine.
3. in-process tagging (tree-sitter in the plugin / native helper) — instant,
   incremental, artifact becomes optional cache. Aligns with the Rust endgame.

Likely destination: #3 (or #1 daemonized into #2 as a midpoint).

## 8. Architecture tiers (the big reframe)

Freshness requirement is PER TAG CLASS, not global. So don't treat it as one
engine. Tiers:

| tier | source                                          | latency                 | owns                                               |
| ---- | ----------------------------------------------- | ----------------------- | -------------------------------------------------- |
| 0    | parser (tree-sitter / today PSI)                | instant, per-buffer     | STRUCTURE: spans, nesting, blocks; structural tags |
| 1    | LSP (types, symbols, refs, call/type hierarchy) | live, server-maintained | semantics on top of tier-0 nodes                   |
| 2    | Joern (whole-program dataflow/taint)            | batch/cached            | reachability tags: Validation, Sanitization, Auth  |

Critical correction: **LSP does NOT give structure.** It has no statement-level
syntax tree. `documentSymbol` = declarations only; `foldingRange` and
`semanticTokens` are lossy interfaces, not a walkable tree. So the parser tier
(tier 0) is non-negotiable — it owns the spans we conceal and the enclosing
structure (`inCatch`). LSP only _annotates_ tier-0 nodes. Stack is
complementary, not substitutable.

Duplication concern: you only duplicate logic if two tiers compute the SAME tag.
Assign each tag to a tier => merge, not duplication. Cheaper tiers are different
queries over different substrates, sharing the output contract. To enable: add
per-span `source` + `confidence`, and degrade gracefully (dim a stale Joern-tier
tag rather than hide it).

## 9. Joern

Value (huge, matches the "can't do" list): type resolution, cross-file symbol
resolution + call graph, dataflow/REACHES (taint), rich control-flow. Cost:
Scala/JVM, full-parse, NOT incremental — wrong runtime shape for a live editor
painter. Irreplaceable for tier-2 taint-class tags; overkill for structural tags
(Logging, even Error Handling). Ecosystem note: Joern itself is deep/mature;
things BUILT ON it are thin and security-focused (~30 querydb rules, a few
security vendors, academic ML).

## 10. Language / rewrite direction

Engine is expected to move to **Rust** later (fast startup, first-class
tree-sitter, single static binary, in-process/daemon-able — also solves
freshness). PoC deliberately in Kotlin (language the author likes; PSI is a free
parser). To make it a PORT not a rewrite, protect:

- the artifact contract (§3),
- the config contract (§4),
- keep language specifics isolated in the frontend (`KotlinFrontend` is the only
  PSI-aware file). K1/PSI usage is throwaway. Consequence: Joern (Scala) and
  "eventually Rust" partly conflict; Joern would be a side/batch service, not
  the live engine.

## 11. Naming history

Renamed to **CodeIntent** before the first push (self-describing, searchable, no
metaphor to outgrow). Tagline "Code to Intent". Short handle: `cint` for the
CLI, Lua module, and commands; the repo stays `code-intent`. CINT (the old C++
interpreter) collides with the short handle, but the repo name disambiguates.

## 12. PoC state / artifacts

- `a726212` initial Kotlin CLI (PSI facts, config query, JSON emit).
- `9102056` move the CLI into its own module (monorepo).
- `743aca1` nvim plugin skeleton (full).
- `f9cd703` bundle config as resource (runs from any CWD).
- `4fb4838` per-tag handlers (color, conceal).
- `145afbc` vertical conceal via `conceal_lines`.
- `3409cbd` reveal concealed block under cursor.
- `fa4fe4d` content-hash staleness guard (CLI + renderer).
- Verified: fixture + `mtquery` (78 files, ~1s full scan), e2e CLI->renderer.
- dotfiles: added PATH entry in `shell/paths/10-unix.lua` ->
  `analysis-engine/build/install/cint/bin`; regenerate via
  `lua bootstrap.lua shell`. (Committed? check; may be uncommitted.)
- Environment gotchas: system gradle broken
  (`GRADLE_HOME=/usr/share/java/gradle` missing module) -> use sdkman gradle or
  the project wrapper (9.7.0). JDK 26. Neovim 0.12.5.

## 13. Open questions / next forks

1. **Next tag class**: another structural/config-only feature (cheap, keeps PoC
   honest) vs a dataflow-class tag (forces the Joern-vs-Rust decision).
2. **Tier contract**: add `source`/`confidence` per span now to unblock tiering?
3. **Freshness**: pick #1/#2/#3 from §7.
4. **Visual tuning**: tint strength, icons, overlap/blend model for nested
   spans.
5. **Artifact scope**: whole-repo vs per-file sidecars (incremental + internal).
6. **Emit ergonomics**: auto-write artifact to repo root (no `--out` flag).

## 14. Known deferred / non-goals (PoC)

No type resolution, no dataflow, no multi-language, no nesting/blend render
model, no profile wizard (config is hand-edited), no incremental indexing, no
watch/auto-regeneration loop. Tag taxonomy exists in palette defaults only
(Logging, Error Handling, Validation, Business Logic) but only Logging is
detected.

## 15. Purity / logging-assist (PoC decision)

- Explicit user-enforced purity only. Per-language `pureAnnotations` such as
  `@Pure`, `@Contract(pure = true)`, and
  `org.checkerframework.dataflow.qual.Pure`. Annotation present => pure;
  annotation absent => impure.
- Literals and local/parameter reads are pure by construction.
- No body inference: `fun giveText() = "test"` without an annotation is impure.
- Assist candidate: a local with at least one read, all reads inside
  Logging-tagged statements, and a pure initializer. An unused `val s = ""` is
  not an assist candidate.
- Deferred: real purity analysis (effect system, interprocedural inference,
  exception/aliasing analysis). Out of scope for the PoC.

## 16. Testing grounds

- `testing-grounds/kotlin/` — fixtures for the tagger, intentionally not part of
  Gradle and not required to compile.
- Covers logger interactions (typed + heuristics), print, Error Handling
  sources, Bookkeeping annotations/comments, pure vs non-pure `if`/`catch`/`try`
  scaffolds, derivative uses, purity annotations, and the heuristic false
  positive for a log-named method on an unknown receiver.
- Run: `cint testing-grounds --no-lsp --all`.

## 17. Classification model (2026-10-17)

- Roles: `SOURCE_INTENT`, `DERIVATIVE_USE`, `SCAFFOLD`.
- Intents are sets; `UNTAGGED` is a reserved label for unclassified effects.
- Sources: Logging (typed/heuristic), Error Handling (`throw`, `require`,
  `requireNotNull`, `check`, `checkNotNull`, `error`, `TODO`, `assert`, plus
  qualified configured calls such as C# `Debug.Assert`), Bookkeeping
  (`@Bookkeeping` or `// cint:bookkeeping`; writes inherit), and Validation
  (early-exit guard conditions; `// cint:validation` override).
- Derivative use: explicitly pure initializer (literal, local read, or annotated
  pure call) and reads flow into classified statements. Transitive closure: a
  declaration whose reads all occur inside other derivative-use declarations is
  derivative too, but function promotion requires explicit purity. An
  unannotated call or operator is assumed impure, so there is no impure-local
  promotion until a purity engine exists.
- Scaffolds: bottom-up union of guard/body intents, including `UNTAGGED`.
  Side-effect-free guards are neutral; effectful guards add `UNTAGGED`. Bare
  `continue`/`break` exits remain neutral. `return` statements are classified by
  the CFG `ReturnBlock` (see §20), not neutral. Mixed `if`/`else` splits into
  then/else spans so an untagged else does not force the then branch to be
  shown. Whole-try classification requires full coverage; try-header
  classification requires only covered catches. Catch spans include the try-body
  closing brace so the boundary conceals with the catch. Functions whose body is
  fully classified are scaffolds as well.
- The engine emits `role` + `intents` only. It has no meaning labels or
  resolution table; clients merge intent sets on render, so presentation changes
  do not require re-analysis.
- Client meanings live on the tag declaration: `tags[tag].meaning` (e.g.
  `LOGGING = { meaning = "CONCEAL" }`). `rules[meaning]` decides how that
  meaning renders. Legacy `intentMeanings` is still accepted for old configs.
- Renderer rules: `CONCEAL` hides lines, `HIGHLIGHT` applies a background;
  `SHOW` is a no-op from the highest-priority intent tag, and `IGNORE` renders
  dark-gray text.

## 18. Client profiles (prototype)

- Profiles are named, partial presentation configs on the client: `tags`
  (including per-tag meanings), `resolve`, `rules`, `handlers`,
  `expand_on_cursor`.
- `extends` chains; deep merge; `false` disables; lists replace.
- Project profiles live in `<root>/.cint/profiles.lua`; saving reloads and
  re-renders. Global profiles can come from `setup({ profiles = ... })`.
- Picker uses `vim.ui.select`. Commands: `:CintProfile [name]`,
  `:CintProfile! name`, `:CintProfileEdit [name]`, `:CintProfileReload`.
- A profile switch applies buffer-locally when the current buffer was rendered;
  otherwise (e.g. the profile editor) it applies globally. Global switches clear
  buffer-local overrides.
- Profiles must use `extends` for derived profiles; assigning one profile table
  to another shares the table and mutating it changes both.
- Default keymaps: `<leader>pp` pick, `<leader>pe` edit; `<Plug>` mappings
  `<Plug>(CintProfilePick)` and `<Plug>(CintProfileEdit)`.
- Switching profiles only re-renders from cached spans; no artifact regeneration
  and no engine run.

## 19. Live freshness and shards (prototype)

- Shards are a history, not an active set: applying appends; effective config is
  profile + history folded in order; last applied wins. `undo` pops, `reset`
  clears. Changing the profile discards shard history and applies the profile
  directly.
- Live server: per-project Unix-socket daemon, NDJSON JSON-RPC. Client sends
  `textDocument/didOpen|didChange|didClose` with full text + monotonic version;
  server re-runs tree-sitter + blocks and publishes versioned tags.
- `BufWritePost`/`:CintAnalyze` request an immediate re-run; normal edits are
  debounced. Stale-version publishes are discarded.
- Profiles/shards are client-side; changing them only re-renders cached
  annotations. There is no artifact/offline renderer path; live mode uses
  exact-version byte ranges.

## 20. Current state (2026-10-20, post-restructure)

Authoritative for resuming. Older sections are historical where they conflict.

### Naming / paths

- Repo: `~/repos/code-intent`; worktrees under `~/repos/code-intent-worktrees/`.
- Product: **CodeIntent**. CLI: `cint`. Server: `cint-server`.
- Kotlin package: `dev.cint.*`. Lua module: `require("cint")`.
- Commands: `:CintRender|Clear|Toggle|Refresh|Info|Profile|ProfileEdit|`
  `ProfileReload|Shard|ShardUndo|ShardReset|ShardHistory|Analyze|ConfigAdd|`
  `ConfigRemove|Suggest|Review|LiveTags|ServerStatus|ServerDisconnect|ServerStop|`
  `ServerRestart`.
- Project presentation config: `<root>/.cint/profiles.lua`. Global config:
  `stdpath("config")/cint/profiles.lua`.
- Detection config is a project YAML file passed with `--config`; it is keyed by
  pack id with a `core:` policy section. It is not auto-discovered.
- User config overlay: `<root>/.cint/config.overlay.yml`, auto-discovered and
  applied after project config. Additive merge is opt-in per pack via
  `ConfigurableRulePack.configureAdditive`/`ConfigSchema.decodeAdditive` (string
  lists/sets union, string-set maps recurse, scalars from the overlay win); all
  bundled packs use `SingleIntentPack` and support it, while a non-opt-in pack
  keeps its project/default values and the server rejects and rolls back the
  mutation (`UNSUPPORTED_PACK_OVERLAY`). It only changes pack config: no source
  annotations, contributions, evidence, confidence, or magic comments.
- Suggestion config: `<root>/.cint/suggestions.yml` under `suggestions:` with
  `enabled` (default false), `trigger`, `provider`, `model`, `maxCandidates`,
  `promptVersion`, `timeoutMs` (default 4000, min 1), and `workerThreads`
  (default 2, clamped 1..8). It gates `cint/suggest`; deterministic pack
  candidates still publish when it is disabled. The suggestion and provider
  pools are bounded by `workerThreads`, provider calls are interrupted after
  `timeoutMs`, and both pools shut down with the server. Decisions live in
  `<root>/.cint/suggestions.json`; confirm also writes
  `<root>/.cint/user-annotations.json`. Decision records carry optional
  `anchorId` plus a span snapshot, and older records without them still load.
- Socket dir: `cint-<uid>`. Log: `stdpath("log")/cint-live.log`.
- JSON-RPC protocol 3: `initialize` (returns pack/field descriptors in `config`
  and a secret-free `suggestions` descriptor), `cint/publishTags`,
  `cint/publishCandidates`, `cint/analyze`, `cint/suggest`,
  `cint/suggest/cancel`, `cint/candidates/decide`,
  `cint/config/read|append|remove`, `cint/error`. Publishes carry the config
  revision and a per-document monotonic `analysisRevision`; candidates add a
  server-wide `suggestionRevision`. The analysis, config snapshot, and new
  `analysisRevision` are allocated together under the global lock, so revision
  order matches analysis order; candidate publishes older than the cached
  analysis or the last published candidates are dropped. The client ignores
  stale versions and older revisions. Analysis and revision sampling are
  serialized under the global lock, while socket writes and republishes happen
  outside it. A `cint/suggest` provider timeout returns `cancelled: true` with
  `timeout: true` and publishes nothing.
- Magic comments: `// cint:bookkeeping`, `// cint:validation`.

### Engine

- Gradle modules: `api`, `rulepacks`, `core`, `suggest`, `host`, `treesitter`,
  `lsp`, `frontend`, `cli`, `server`; test-only `rulepacks-tests`,
  `test-fixtures`, `integration-tests`.
- Dependency line: `core` depends on `api` only. `suggest` depends on `api` and
  `core`; `host` depends on `api` and `core`. `frontend` binds
  parser/syntax/oracle. CLI, HTTP, and live server use `host`'s `HostPipeline`;
  `rulepacks` supplies bundled detection.
- `api` holds the frozen v5 contract (`Intent`, `Rule`, `RulePack`, `View`,
  `RuleEmit`, `Emit`, `CandidateEmit`, `Annotation`, DSL). Rules own one intent;
  composition roles are core-only.
- Candidate channel: `Rule.run` returns `List<RuleEmit>`; `settle` partitions
  established `Emit`s from `CandidateEmit`s and returns
  `AnalysisOutput(annotations, candidates)`. Candidates never enter the
  annotation store, composition, or `reads`; fixpoint rules keep candidates from
  the final iteration. Identity is path + anchor/content hash + byte span +
  sorted intents + reason. Alternatives at one anchor form a group; confirming
  any of them resolves the whole group, so siblings are no longer published or
  confirmable. Decision republishes still emit `cint/publishCandidates`, with
  empty groups when nothing remains. Removing the confirmed user annotation
  lets the next analysis re-propose the candidate unless it was rejected.
- Rulepack loading reads `META-INF/services/dev.cint.api.RulePack` explicitly,
  validates API version/ownership, and degrades per provider. The per-language
  error-handling packs are constructed by `BundledRulePackSelection` because
  they share one intent name; service descriptors cover the single-instance
  logging, bookkeeping, and validation packs.
- Syntax is data: `SyntaxModel`/`ProfileSyntaxModel` plus
  `languages/treesitter/src/main/resources/syntax/*.yaml` for Kotlin, JavaScript,
  TypeScript, Vue, and C#. `LanguageFrontend` lives in core; `frontend` registers
  the language frontends and handles Vue split regions.
- LSP: spec-driven `LspOracle`; Kotlin only. JS/TS/Vue/C# use `NullOracle`.
- Split handlers: `SplitHandler`/`SplitRegion` + `SplitAnalyzer`. Vue extracts
  `<script>` / `<script setup lang="ts">`, analyzes as JS/TS, shifts spans.
- CFG: `core/core/facts/flow/ControlFlow.kt`, built before rule evaluation.
  `ReturnComposition` classifies returns from certified guard annotations:
  validation guard returns inherit `VALIDATION`; normal returns are `UNTAGGED`
  and block whole-function scaffolds.
- Composition lives in `core/core/runtime/composition`: classes named `core:scaffold`,
  `core:derivative-use`, `core:return-category`.
- Purity is explicit only: literal/local reads or annotated pure calls;
  function promotion requires `@Pure`. No purity engine yet.
- Analysis caches: per-context syntax facts; annotation list + exact-span map +
  interval index; rules that do not read annotations are not rerun.

### Renderer

- Socket-only; no artifact/offline path.
- `scope = "project" | "global"` controls profile file and selection.
- `languages` maps Neovim filetypes/extensions to engine language ids.
- Perf: cached render plan per `(spans, config, changedtick)`, style cache,
  line-length cache, config cache, incremental reveal.
- Defaults: `SHOW = false` (no-op), `HIGHLIGHT` paints, `CONCEAL` hides,
  `IGNORE` dims. Multi-intent scaffolds skip color/highlight; `IGNORE`/`CONCEAL`
  still apply.
- Shard syntax:
  `shards = { NAME = { tags = { LOGGING = { meaning = "IGNORE" } } } }`; shards
  are config patches.
- Candidates render non-destructively with an undercurl and `?` sign; status
  text is `CodeIntent: N unsure`. `:CintSuggest` requests a pass;
  `:CintReview` picks a group/alternative then `Confirm`/`Reject`/`Skip`.
  Confirming an alternative resolves the whole anchor group, so siblings vanish
  with the republished candidate list. Candidate commands are gated on a
  returned protocol >= 3 with `suggestions.enabled ~= false`.
- Tests: `nvim-renderer/tests/live_spec.lua` drives an in-process fake daemon;
  `candidates_spec.lua` covers candidate publish/revision gating, suggest,
  review, decisions, and the protocol-2 fallback; `render_spec.lua` covers
  extmarks, profiles, and language mapping.

### Daemon latency / Rust

- Cold start ~150 ms. Warm `didChange` (no LSP): ~5 ms/10 lines, ~30 ms/90,
  ~80 ms/270, ~310 ms/1080 (before CFG; CFG adds ~10-18%).
- Analysis dominates; serialization ~11 ms. Rust would help cold start/constants,
  not linear scaling; optimize algorithms first.

### Snapshots

- `tests/integration-tests/src/test/resources/snapshots/legacy-3f20435.json` and
  `pipeline-3f20435.json` are the pre-018 (014-tree) snapshot resources restored
  from commit `739dff7`. Both are `HostPipeline` renderings over the 11
  fixtures: the legacy-shaped file omits rule ids, the pipeline file includes
  them. Ticket 014 compared that shape against the legacy path at `3f20435`;
  the filenames retain that comparison commit. They are not a direct capture of
  the deleted legacy implementation and were not regenerated from a `3f20435`
  run.
- `pipeline-current.json` is the post-018 current-pipeline baseline. Ticket 018
  intentionally widened validation spans, so current output differs from the
  pre-018 files.

### Tooling

- `just build` assembles every module without running tests.
- `just test-engine` runs deterministic tests including host, server,
  rulepacks-tests, test-fixtures, and integration-tests.
- `just test-lsp` is opt-in via `CINT_LSP_ROOT`; `just test-nvim` runs the
  headless renderer suite.
- `just lint`/`lint-fix` run ktlint over Kotlin sources only.
- CI runs build, engine tests, lint, and renderer tests with
  `CINT_TESTING_GROUNDS` set explicitly.

### Known gaps / next

- CFG does not collect `switch`/`when` returns; Kotlin inline non-local returns
  are treated as lambda-local; guard polarity is approximate.
- Vue LSP is not wired (NullOracle); TSX is unsupported by the binding.
- No independent purity engine; no incremental analysis.
- `tests/integration-tests/.../CompositionTestSupport.kt` still constructs Kotlin
  parser/syntax directly rather than through `Frontends`.
- `test-fixtures` rebuilds the bundled pack selection; a parity check against
  `BundledRulePackSelection` would remove drift.
- Next candidates: switch/when facts; real Volar oracle; dogfood on a real
  project; switch/polarity tests; CFG performance.
