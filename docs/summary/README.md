# Repository Summary

This directory is a compact, continuation-oriented map of CodeIntent. It is
derived from the canonical docs and the current code; when they disagree, the
canonical docs and the code win.

As of 2026-10-22, `main` contains the rule/engine restructure, the user
annotation and AI candidate channels, the Neovim hover/candidate UI, and the
size-hygiene split. `just build`, `just test-engine`, `just lint`, and
`just test-nvim` pass.

## Reading order

| file | contents |
|---|---|
| [current-state.md](current-state.md) | What is done, what is left, recent tickets, known residuals, resume commands. Start here. |
| [architecture.md](architecture.md) | Module layout, dependency direction, analysis pipeline, scheduler, composition, hosts. |
| [intents-and-detection.md](intents-and-detection.md) | Intent vocabulary, owners, detection semantics, SEARCH vs LOOKUP, user directives. |
| [annotations-and-ai.md](annotations-and-ai.md) | User annotation persistence/locking/directives, candidate channel, AI provider/config/lifecycle. |
| [renderer-and-protocol.md](renderer-and-protocol.md) | Neovim client layout, commands, render meanings/handlers, hover, candidates, socket protocol. |
| [development-and-testing.md](development-and-testing.md) | Build/test/lint recipes, test topology, CI, size hygiene, ticket/review workflow, git. |
| [glossary.md](glossary.md) | Short definitions for the vocabulary used across the repo. |

Canonical references: [../architecture.md](../architecture.md),
[../engine.md](../engine.md), [../intent-model.md](../intent-model.md),
[../ai-intents.md](../ai-intents.md), [../workflow.md](../workflow.md), and
[../tasks/README.md](../tasks/README.md).

## Fast resume

```sh
just build && just test-engine && just lint && just test-nvim
```

- Engine: `analysis-engine/` (Gradle, Kotlin, tree-sitter, optional kotlin-lsp).
- Client: `nvim-renderer/` (Lua, headless Neovim tests).
- Task queue: `docs/tasks/README.md`. 058-061 are the proposed AI annotation
  session/discovery, restrictions, reuse cache, and review UX; 017 remains
  blocked. 023/028 file frontmatter is stale (README says done).
- Current product surface: deterministic intent tags plus AI candidate review,
  user annotations with `PURE`/`READONLY`, live daemon protocol 3, and the
  Neovim renderer with inline labels, stripes, hover, and candidate review.
