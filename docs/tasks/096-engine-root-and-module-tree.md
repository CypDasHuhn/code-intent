---
name: Engine at repository root and grouped module tree
status: todo
depends-on: [085]
reviewers: [architecture, correctness]
---

## Goal

The engine is the only remaining product, so the repository root should be the
engine root. Remove the `analysis-engine/` wrapper, delete stale build
leftovers, and group the Gradle modules into the six top-level groups from the
original layout design: `contracts`, `analysis`, `sources`, `orchestration`,
`apps`, `tests`.

## Scope

- Move the engine build to the repository root: `settings.gradle.kts`,
  `build.gradle.kts`, `gradlew`, `gradle/`, `buildSrc/`, `graph-guard.json`,
  modules, native assets, and engine scripts. No `analysis-engine/` directory
  remains.
- Group modules physically while keeping canonical Gradle project names:
  - `contracts/` — `:contracts`
  - `analysis/` — `:core`, `:default-rulepacks`, `:intent-pipeline`,
    `:knowledge`, `:knowledge-persistence`
  - `sources/` — `:index`, `:languages`, `:syntax-treesitter`, `:lsp`
  - `orchestration/` — `:runtime`, `:ai`, `:ipc`
  - `apps/` — `:cli`, `:server`, `:mcp`
  - `tests/` — `:test-fixtures`, `:integration-tests`, parity reports/data
- Decide and document the final home for support assets: native libs/grammars,
  `tools/`, `scripts/`, engine-specific docs, and parity fixtures. Prefer
  `sources/syntax-treesitter/native/`, `tools/`, and `tests/parity/` over
  top-level one-off directories.
- Delete `native-spike` and any remaining spike assets once confirmed unused.
- Remove ignored legacy build leftovers currently sitting under
  `analysis-engine/` (`core/api`, `core/core`, `core/host`, `core/rulepacks`,
  `core/suggest`, `hosts/*`, `languages/frontend`, `languages/treesitter`,
  `languages/lsp`, `tests/integration-tests`, `tests/rulepacks-tests`,
  `tests/test-fixtures`). They are untracked build output, not modules.
- Update `settings.gradle.kts` `projectDir` mappings, the graph guard's
  module-to-directory map, justfile, CI, scripts, frozen-corpus tooling, docs,
  `.gitattributes`, and any IDE paths.
- Make one authoritative documented tree. Update `docs/rewrite/module-graph.md`
  and `docs/summary/architecture.md` to the grouped layout, and note that it
  supersedes the flat tree and the amended 063 scope.

## Acceptance

- `./gradlew` at the repository root builds and tests the engine; the
  `analysis-engine/` wrapper is gone.
- The repository root contains the six module groups plus normal top-level
  repo files; no module is hidden behind an `analysis-engine/` prefix.
- Canonical Gradle project names and dependency edges are unchanged; the graph
  guard still passes with updated physical paths.
- `just build`, `just test-engine`, `just lint`, `just graph-guard`,
  `just parity`, `just smoke`, and `just syntax-provenance` pass.
- The ignored legacy build leftovers are removed and not referenced anywhere.
- Documentation shows a single authoritative module tree with no flat-tree
  contradiction.

## Out of scope

- Changing module boundaries, dependency edges, or project names.
- Detection behavior, protocol, renderer, or AI changes.
- Renaming intents or redesigning the scheduler.

## Notes

- The current tracked engine tree has 23 top-level entries; the original 063
  ticket specified six groups, but the module-graph doc and the amended 063
  scope said “flat tree”. This ticket restores the grouped intent and makes the
  docs consistent.
- Keep changes mechanical. This is a move/rename ticket, not a behaviour ticket.
- Run 095 after this ticket so `default-rulepacks` is created at its final path.
