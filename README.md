# Semantic Code Painter

> Code to Intent.

Paints code in the editor by *intent* (Logging, Error Handling, Validation,
Business Logic, ...) instead of by syntax.

## Components

| dir | what |
|-----|------|
| [`analysis-cli/`](analysis-cli) | Language-agnostic intent-tagging engine. Parses a repo, emits an editor-agnostic JSON tag artifact. Kotlin/JVM. |
| `nvim-renderer/` | *(planned)* Neovim plugin that consumes the JSON artifact and paints spans (background tint + gutter icon). |

## Flow

```
source repo ──► analysis-cli ──► tags.json ──► nvim-renderer ──► editor
                 (facts + config)
```

The tagging engine is language-agnostic; language specifics live in per-project
config. See [`analysis-cli/README.md`](analysis-cli/README.md).
