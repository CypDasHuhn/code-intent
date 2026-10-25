# Repository Summary

This directory is a compact, continuation-oriented map of CodeIntent. It is
derived from the canonical docs and the current code; when they disagree, the
canonical docs and the code win.

The repository has completed the V1 to V2 cutover. It contains one engine at
the repository root; V1 remains only in git history.

## Reading order

| file | contents |
|---|---|
| [current-state.md](current-state.md) | What is done, what is left, recent tickets, known residuals, resume commands. Start here. |
| [architecture.md](architecture.md) | Module layout, dependency direction, index/pipeline/runtime flow, hosts. |
| [intents-and-detection.md](intents-and-detection.md) | Intent vocabulary, pack ownership, knowledge propagation, candidates. |
| [annotations-and-ai.md](annotations-and-ai.md) | Persisted knowledge, fallback AI, MCP, and candidate review. |
| [renderer-and-protocol.md](renderer-and-protocol.md) | External renderer boundary, protocol versions, schema, and cross-repo checks. |
| [development-and-testing.md](development-and-testing.md) | Build/test/lint recipes, test topology, CI, ticket/review workflow. |
| [glossary.md](glossary.md) | Short definitions for the vocabulary used across the repo. |

Canonical references: [../architecture.md](../architecture.md),
[../engine.md](../engine.md), [../intent-model.md](../intent-model.md),
[../workflow.md](../workflow.md), [../ipc/PROTOCOL.md](../ipc/PROTOCOL.md), and
[../tasks/README.md](../tasks/README.md).

## Fast resume

```sh
just build && just test-engine && just lint
```

- Engine: repository root (Gradle, Kotlin, tree-sitter, LSP, CLI, live server,
  MCP).
- Client: external `code-intent.nvim` repository (Lua, headless Neovim tests).
- Task queue: `docs/tasks/README.md`.
- Current product surface: manifest packs, persisted function knowledge,
  deterministic propagation, tree-sitter plus mandatory semantic providers,
  batch CLI, live daemon, MCP tools, and the versioned renderer protocol.
