# Semantic Code Painter — session notes / knowledge dump

Purpose: raw, self-contained dump of a brainstorming+PoC session so a future
session can resume with no other context. Written for LLM consumption; density
over polish. Not maintained for humans.

Repo: `~/repos/semantic-code-painter` (monorepo).
Tagline: **Code to Intent.**
Date of session: 2026-10-16.

> Update 2026-10-17: the engine is now `analysis-engine/` (core / treesitter /
> lsp / cli / server). Sections 3–5 and 12 describe the old PSI PoC.

---

## 1. The idea (thesis)

Paint code in the editor by **intent** (Logging, Error Handling, Validation,
Business Logic, …) instead of by syntax. Like syntax highlighting, but for what
code *means*. Rendered as background tint + gutter icon; optionally conceal
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
source repo -> analysis-engine (facts + config -> tags) -> tags.json (editor-agnostic)
            -> nvim-renderer (reads artifact) -> editor decorations
```

Two components:
- `analysis-engine/` — Kotlin/JVM Gradle app. Parses, tags, emits JSON.
- `nvim-renderer/` — Lua Neovim plugin. Consumes artifact, paints extmarks.

Editor-agnostic: only the renderer is editor-specific; artifact is the contract.

## 3. The artifact contract

`tags.json` at project root (default). Shape:

```json
{
  "feature": "logging",
  "tag": "Logging",
  "language": "kotlin",
  "files": [
    { "path": "relative/from/scanned/root.kt",
      "hash": "sha256-hex",
      "spans": [
        {"tag":"Logging","rule":"call-on-logger-symbol","evidence":"log.info(...)",
         "startLine":10,"startCol":12,"endLine":10,"endCol":44} ] }
  ]
}
```

- Lines **0-based**, columns **0-based** (LSP/Neovim friendly).
- `hash` = SHA-256 of canonical content: CRLF->LF, strip one trailing `\n`.
  Renderer recomputes buffer hash; mismatch => refuse to paint + warn (stale).
- `rule` = provenance (which generic rule fired). `evidence` = source snippet.
- Missing but planned: `source` (tier) and `confidence` per span — see tiering.

## 4. The config contract

`analysis-engine/core/src/main/resources/logging.yaml`, keyed **feature -> language -> params**:

```yaml
features:
  logging:
    tag: Logging
    languages:
      kotlin:
        methods: [info, debug, warn, error, trace, log, verbose, fatal]
        factories: [LoggerFactory.getLogger, KotlinLogging.logger,
                    LogManager.getLogger, Logger.getLogger]
        receiverNames: [log, logger, LOG, LOGGER, Logger]
        qualifiers: [System.out, System.err]
        imports: [org.slf4j, mu.KotlinLogging, java.util.logging]
```

Bundled into the jar as a resource (`config/` is a resources srcDir); `--config`
overrides. Engine NEVER branches on language — only config values are specific.

## 5. Detection as implemented (Logging only)

Frontend facts (`KotlinFrontend`, PSI, NO type resolution):
- `imports` — FQNs
- `declarations` — property name + initializer call-path (e.g.
  `LoggerFactory.getLogger`)
- `calls` — calleeName, receiverText, callPath, args, offsets, `inCatch`
- `receiverText` = text left of `.` in a dot-qualified selector call.
- `callPath` for initializers = `Receiver.callee`.

Generic rules (`IntentQuery`, first match wins). Call tagged `Logging` if:
1. `call-on-logger-symbol` — receiver is a symbol declared via a `factories`
   initializer. (Strong rule; name-independent.)
2. `receiver-name` — receiver simple-name case-insensitive in `receiverNames`
   AND callee in `methods`.
3. `qualified-static` — receiver in `qualifiers` AND callee in `methods`.
4. `name-import-gated` — callee in `methods` AND file imports a `imports` module.
5. `print` — callee is `println`/`print` (unqualified, or on `System.out`/
   `System.err`). Builtin, not config.

Design choice: rules 2–4 require a **receiver** — this is what suppresses
Kotlin stdlib `error()`/`require()`/`check()` false positives (unqualified).
`has(factories, x)` matches exact OR suffix (`…KotlinLogging.logger`).

Limits (deliberate PoC scope): no type resolution, no cross-file symbol
resolution, no dataflow, no context sanitization. A non-logger receiver named
`logger` with a log-ish method would still false-positive.

## 6. Renderer (nvim) as implemented

- Per-tag **handler** registry (`lua/semantic_code_painter/handlers/`):
  - `color` — bg tint + gutter sign. opts `{bg,sign,icon}`. Falls back to
    builtin palette per tag name (`colors.lua`), else `fallback`.
  - `conceal` — `{char="…"}` inline replace; `{lines=true}` **vertical** hide.
- Config: `tags[tag][handlerName] = opts`; presence enables; `true`=defaults,
  `false`=disabled; tags with no entry use global `handlers` list.
- Vertical conceal uses nvim extmark `conceal_lines=""` (needs conceallevel>0;
  handler auto-sets to 2). VERIFIED it reduces `nvim_win_text_height`.
- `expand_on_cursor` (default true): cursor inside a vertical block reveals the
  WHOLE block; leaving re-conceals. Implemented via `render_buffer` `reveal`
  hook + `init.reveal_block()` + `CursorMoved`/`CursorMovedI`. Re-renders only
  when the active block changes.
- Staleness guard in `init.render`: buffer hash != artifact hash => clear +
  notify + return 0. (Warns, does not self-heal.)
- Commands: `:SCPainterRender|Clear|Toggle|Refresh|Info`. Headless test:
  `nvim-renderer/tests/run.sh`.

Important: for THIS tool, stale data is worse than for a linter — a stale
linter shows wrong squiggles; a stale painter **hides the wrong lines**.

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

| tier | source | latency | owns |
|---|---|---|---|
| 0 | parser (tree-sitter / today PSI) | instant, per-buffer | STRUCTURE: spans, nesting, blocks; structural tags |
| 1 | LSP (types, symbols, refs, call/type hierarchy) | live, server-maintained | semantics on top of tier-0 nodes |
| 2 | Joern (whole-program dataflow/taint) | batch/cached | reachability tags: Validation, Sanitization, Auth |

Critical correction: **LSP does NOT give structure.** It has no statement-level
syntax tree. `documentSymbol` = declarations only; `foldingRange` and
`semanticTokens` are lossy interfaces, not a walkable tree. So the parser tier
(tier 0) is non-negotiable — it owns the spans we conceal and the enclosing
structure (`inCatch`). LSP only *annotates* tier-0 nodes. Stack is
complementary, not substitutable.

Duplication concern: you only duplicate logic if two tiers compute the SAME tag.
Assign each tag to a tier => merge, not duplication. Cheaper tiers are different
queries over different substrates, sharing the output contract. To enable:
add per-span `source` + `confidence`, and degrade gracefully (dim a stale
Joern-tier tag rather than hide it).

## 9. Joern

Value (huge, matches the "can't do" list): type resolution, cross-file symbol
resolution + call graph, dataflow/REACHES (taint), rich control-flow.
Cost: Scala/JVM, full-parse, NOT incremental — wrong runtime shape for a live
editor painter. Irreplaceable for tier-2 taint-class tags; overkill for
structural tags (Logging, even Error Handling).
Ecosystem note: Joern itself is deep/mature; things BUILT ON it are thin and
security-focused (~30 querydb rules, a few security vendors, academic ML).

## 10. Language / rewrite direction

Engine is expected to move to **Rust** later (fast startup, first-class
tree-sitter, single static binary, in-process/daemon-able — also solves
freshness). PoC deliberately in Kotlin (language the author likes; PSI is a
free parser). To make it a PORT not a rewrite, protect:
- the artifact contract (§3),
- the config contract (§4),
- keep language specifics isolated in the frontend (`KotlinFrontend` is the
  only PSI-aware file).
K1/PSI usage is throwaway. Consequence: Joern (Scala) and "eventually Rust"
partly conflict; Joern would be a side/batch service, not the live engine.

## 11. Naming history

Chose **Semantic Code Painter** (self-describing, searchable, no metaphor to
outgrow). Tagline "Code to Intent". Avoid the acronym `scp` (collides with
secure copy; npm `gloss`, Haskell `gloss`, etc. all taken — single-word names
rejected as too crowded / hard to make meaningful). Installed launcher is
`semantic-code-painter`; shorter candidate `scpaint` proposed but not adopted.

## 12. PoC state / artifacts

- `a726212` initial Kotlin CLI (PSI facts, config query, JSON emit).
- `9102056` move CLI into `analysis-cli/` (monorepo).
- `743aca1` nvim plugin skeleton (full).
- `f9cd703` bundle config as resource (runs from any CWD).
- `4fb4838` per-tag handlers (color, conceal).
- `145afbc` vertical conceal via `conceal_lines`.
- `3409cbd` reveal concealed block under cursor.
- `fa4fe4d` content-hash staleness guard (CLI + renderer).
- Verified: fixture + `mtquery` (78 files, ~1s full scan), e2e CLI->renderer.
- dotfiles: added PATH entry in `shell/paths/10-unix.lua` ->
  `analysis-engine/build/install/semantic-code-painter/bin`; regenerate via
  `lua bootstrap.lua shell`. (Committed? check; may be uncommitted.)
- Environment gotchas: system gradle broken (`GRADLE_HOME=/usr/share/java/gradle`
  missing module) -> use sdkman gradle or the project wrapper (9.7.0). JDK 26.
  Neovim 0.12.5.

## 13. Open questions / next forks

1. **Next tag class**: another structural/config-only feature (cheap, keeps PoC
   honest) vs a dataflow-class tag (forces the Joern-vs-Rust decision).
2. **Tier contract**: add `source`/`confidence` per span now to unblock tiering?
3. **Freshness**: pick #1/#2/#3 from §7.
4. **Visual tuning**: tint strength, icons, overlap/blend model for nested spans.
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

- `testing-grounds/kotlin/` — fixtures for the tagger, intentionally not part
  of Gradle and not required to compile.
- Covers logger interactions (typed + heuristics), print, Error Handling
  sources, pure vs non-pure `if`/`catch`/`try` scaffolds, derivative uses,
  purity annotations, and the heuristic false positive for a log-named method
  on an unknown receiver.
- Run: `semantic-code-painter testing-grounds --no-lsp --all`.

## 17. Classification model (2026-10-17)

- Roles: `SOURCE_INTENT`, `DERIVATIVE_USE`, `SCAFFOLD`.
- Intents are sets; `UNTAGGED` is a reserved label for unclassified effects.
- Sources: Logging (typed/heuristic) and Error Handling (`throw`, `require`,
  `requireNotNull`, `check`, `checkNotNull`, `error`, `TODO`, `assert`).
- Derivative use: pure initializer (literal, local read, or explicit purity
  annotation) and reads flow into classified statements. Transitive closure: a
  declaration whose reads all occur inside other derivative-use declarations is
  derivative too, even with an impure initializer.
- Scaffolds: bottom-up union of guard/body intents, including `UNTAGGED`.
  Whole-try classification requires full coverage; try-header classification
  requires only covered catches. Functions whose body is fully classified are
  scaffolds as well.
- The engine emits `role` + `intents` only. It has no meaning labels or
  resolution table; clients merge intent sets on render, so presentation changes
  do not require re-analysis.
- Client meanings live on the tag declaration: `tags[tag].meaning` (e.g.
  `Logging = { meaning = "CONCEAL" }`). `rules[meaning]` decides how that meaning
  renders. Legacy `intentMeanings` is still accepted for old configs.
- Renderer rules: `CONCEAL` hides lines, `HIGHLIGHT`/`SHOW` applies a background
  from the highest-priority intent tag, and `IGNORE` renders dark-gray text.

## 18. Client profiles (prototype)

- Profiles are named, partial presentation configs on the client: `tags`
  (including per-tag meanings), `resolve`, `rules`, `handlers`,
  `expand_on_cursor`.
- `extends` chains; deep merge; `false` disables; lists replace.
- Project profiles live in `<root>/.scpainter/profiles.lua`; saving reloads and
  re-renders. Global profiles can come from `setup({ profiles = ... })`.
- Picker uses `vim.ui.select`. Commands: `:SCPainterProfile [name]`,
  `:SCPainterProfile! name`, `:SCPainterProfileEdit [name]`,
  `:SCPainterProfileReload`.
- A profile switch applies buffer-locally when the current buffer was rendered;
  otherwise (e.g. the profile editor) it applies globally. Global switches clear
  buffer-local overrides.
- Profiles must use `extends` for derived profiles; assigning one profile table
  to another shares the table and mutating it changes both.
- Default keymaps: `<leader>pp` pick, `<leader>pe` edit; `<Plug>` mappings
  `<Plug>(SCPainterProfilePick)` and `<Plug>(SCPainterProfileEdit)`.
- Switching profiles only re-renders from cached spans; no artifact
  regeneration and no engine run.

## 19. Live freshness and shards (prototype)

- Shards are a history, not an active set: applying appends; effective config is
  profile + history folded in order; last applied wins. `undo` pops, `reset`
  clears. Changing the profile discards shard history and applies the profile
  directly.
- Live server: per-project Unix-socket daemon, NDJSON JSON-RPC. Client sends
  `textDocument/didOpen|didChange|didClose` with full text + monotonic version;
  server re-runs tree-sitter + blocks and publishes versioned tags.
- `BufWritePost`/`:SCPainterAnalyze` request an immediate re-run; normal edits
  are debounced. Stale-version publishes are discarded.
- Profiles/shards are client-side; changing them only re-renders cached
  annotations. The artifact + node anchors remain the offline/batch path; live
  mode uses exact-version byte ranges.
