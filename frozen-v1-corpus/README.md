# Frozen V1 Corpus

This directory contains the frozen observable behavior of the V1 analysis engine (CLI and live server) as of commit `f22b596` (post-018 current pipeline).

## Purpose
V2 tests must match this corpus exactly (modulo documented intentional differences in `KNOWN_DIFFERENCES.md`). This ensures behavioral continuity during the V1→V2 migration.

## Contents

### CLI Outputs (`cli-*.json`)
Batch CLI analysis results for each language in `testing-grounds/`, run with `--no-lsp` for determinism.

| File | Language | Flags | Files Analyzed |
|------|----------|-------|----------------|
| `cli-kotlin.json` | Kotlin | `--no-lsp` | 11 |
| `cli-kotlin-all.json` | Kotlin | `--no-lsp --all` | 11 (includes empty-annotation files) |
| `cli-typescript.json` | TypeScript | `--no-lsp --lang typescript` | 2 |
| `cli-javascript.json` | JavaScript | `--no-lsp --lang javascript` | 1 |
| `cli-csharp.json` | C# | `--no-lsp --lang csharp` | 1 |
| `cli-vue.json` | Vue | `--no-lsp --lang vue` | 1 |

Each file contains:
```json
{
  "language": "kotlin",
  "intents": ["BOOKKEEPING", "DATA_MODIFY", ...],
  "files": [
    { "path": "Bookkeeping.kt", "hash": "...", "spans": [...], "candidates": [...] }
  ]
}
```

### Protocol Transcripts (`protocol-*.ndjson`)
Newline-delimited JSON-RPC 2.0 transcripts captured from the live Unix-socket server (`cint-server --socket ... --no-lsp`).

| File | Scenario | Messages |
|------|----------|----------|
| `protocol-kotlin-analyze.ndjson` | Initialize → open doc → analyze → config read → config append → analyze → config remove → shutdown | 7 |
| `protocol-typescript-analyze.ndjson` | Initialize → open doc → analyze → shutdown | 4 |
| `protocol-config-flow.ndjson` | Initialize → config read → append logging → config read → append validation → config read → remove logging → config read → shutdown | 9 |
| `protocol-suggest-flow.ndjson` | Initialize → open doc → suggest → cancel → shutdown | 6 |
| `protocol-restrictions-flow.ndjson` | Initialize → open doc → restrictions list → shutdown | 5 |
| `protocol-document-lifecycle.ndjson` | Initialize → open → change → analyze → close → shutdown | 6 |

Each line is a complete JSON-RPC message (request response or notification).

### HTTP Protocol (`protocol-http-*.ndjson`)
Single-message captures from the HTTP server (`cint-server --port 18765 --no-lsp`).

| File | Endpoint |
|------|----------|
| `protocol-http-health.ndjson` | `GET /health` |
| `protocol-http-analyze.ndjson` | `POST /analyze` |

### Reproducibility
Run `./reproduce.sh` from the repo root to regenerate all artifacts. Requires:
- JDK 21+
- Gradle (via wrapper)
- Python 3.8+ with `requests` module

### Known Differences
See `KNOWN_DIFFERENCES.md` for the exhaustive list of intentional V2 deviations from this frozen behavior.

## Version
- V1 Commit: `f22b596` (post-018 current pipeline)
- Pipeline Note: "validation intentionally widened from the pre-018 014-tree baseline"
- Protocol Version: 4
- Generated: 2026-10-24