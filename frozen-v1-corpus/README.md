# Frozen V1 Corpus

This directory contains the frozen observable behavior of the V1 analysis engine (CLI, live Unix-socket server, and HTTP facade) as of commit `3ccea9e` (the final pre-skeleton V1 source). All inputs are checked in under `fixtures/`, so a capture does not depend on mutable working trees.

## Purpose
V2 tests must match this corpus exactly, modulo intentional differences registered in `known-differences.json` and described in `KNOWN_DIFFERENCES.md`. This ensures behavioral continuity during the V1→V2 migration.

## Contents

### CLI Outputs (`cli-*.json`)
Batch CLI analysis results for each language, captured from isolated temporary copies of `fixtures/sources/` with `--no-lsp` for determinism. Each run removes `.cint/` so no overlay or cache leaks in. `cli-kotlin-all.json` is byte-identical to `cli-kotlin.json`: `--all` only adds files with neither annotations nor candidates, and every Kotlin fixture file produces at least one of them.

| File | Language | Flags | Files Analyzed |
|------|----------|-------|----------------|
| `cli-kotlin.json` | Kotlin | `--no-lsp` | 11 |
| `cli-kotlin-all.json` | Kotlin | `--no-lsp --all` | 11 |
| `cli-typescript.json` | TypeScript | `--no-lsp --lang typescript` | 2 |
| `cli-javascript.json` | JavaScript | `--no-lsp --lang javascript` | 1 |
| `cli-csharp.json` | C# | `--no-lsp --lang csharp` | 1 |
| `cli-vue.json` | Vue | `--no-lsp --lang vue` | 1 |

Each file contains:
```json
{
  "language": "kotlin",
  "intents": ["BOOKKEEPING", "DATA_MODIFY", "..."],
  "files": [
    { "path": "Bookkeeping.kt", "hash": "...", "spans": [...], "candidates": [...] }
  ]
}
```

### Protocol Transcripts (`protocol-*.ndjson`)
Newline-delimited JSON-RPC 2.0 transcripts of server messages captured from the live Unix-socket server (`cint-server --socket ... --no-lsp`). Each scenario runs over **one persistent socket**, so per-connection document state and protocol negotiation are preserved. `textDocument/didOpen`, `didChange`, and `didClose` are notifications and carry no `id`; response ids are contiguous.

| File | Scenario | Lines |
|------|----------|-------|
| `protocol-kotlin-analyze.ndjson` | Initialize → open doc → analyze → config read → append `logging/kotlin/methods=audit` → analyze → remove → shutdown | 17 |
| `protocol-typescript-analyze.ndjson` | Initialize → open doc → analyze → shutdown | 7 |
| `protocol-config-flow.ndjson` | Initialize → read → append `validation/predicates` → read → append `logging/methods` → read → remove validation → read → shutdown | 9 |
| `protocol-suggest-flow.ndjson` | Initialize (suggestions enabled, fake provider) → open `NeighboursCli.kt` → suggest → cancel → log → shutdown | 8 |
| `protocol-suggest-disabled-flow.ndjson` | Initialize (defaults) → open doc → suggest (disabled) → shutdown | 5 |
| `protocol-restrictions-flow.ndjson` | Initialize → open doc → restriction list → remove fixture → list → shutdown | 9 |
| `protocol-document-lifecycle.ndjson` | Initialize → open → change → analyze → close → shutdown | 9 |
| `protocol-status-flow.ndjson` | Initialize → `cint/status` (real unknown-method error) → shutdown | 3 |
| `protocol-error-flow.ndjson` | Initialize protocol 3 → restriction protocol gate → invalid config field → unknown pack → shutdown | 5 |

Each line is a server-emitted JSON-RPC response, notification, or error. Every live scenario has a companion `protocol-<scenario>.session.json` containing the exact ordered client steps and the normalized messages emitted after each step; replaying the steps reproduces the `.ndjson` transcript. Config append/remove responses include the resulting `overlay`; open documents are republished with incremented `analysisRevision` and `suggestionRevision`. The enabled suggest flow uses `.cint/suggestions.yml` with `provider: fake`, `maxEnrichmentCandidates: 2`, and `discoveryEnabled: false`; the deterministic provider emits no progress notifications.

### HTTP Protocol (`protocol-http-*.ndjson`)
Single request/response captures from the HTTP server on a probed free loopback port (`cint-server --host 127.0.0.1 --port <probed> --no-lsp`). A fresh port is probed for every attempt and startup is retried up to five times, avoiding free-port races. The companion `protocol-http.session.json` records both requests and responses.

| File | Endpoint |
|------|----------|
| `protocol-http-health.ndjson` | `GET /health` |
| `protocol-http-analyze.ndjson` | `POST /analyze` |

The live server has no status method; `protocol-status-flow.ndjson` records the real `unknown method: cint/status` error, and HTTP `/health` is the only V1 status surface.

### Renderer Snapshot (`renderer-visible-v1.json`)
Projection of the wire payloads consumed by `nvim-renderer`: initialize capabilities, `cint/publishTags` annotations, `cint/publishCandidates` groups/candidates, document lifecycle revisions, suggest enabled/disabled results, restriction list/remove, config overlays, and status/error surfaces. It is generated from the transcripts, not by reimplementing renderer logic.

### Manifest (`manifest.json`)
`formatVersion` 3 records the protocol version, pinned V1 commit, stable path tokens, canonical ordering rules, SHA-256 hashes for every generated artifact, the three capture/verify scripts, all fixture files, and the `known-differences.json` contract. Generation time and workspace `HEAD` are deliberately omitted so repeated captures are byte-identical regardless of the surrounding checkout. Before capture, `reproduce.sh` checks staged fixtures and the known-differences contract against the existing manifest, so fixture drift fails instead of being silently re-hashed. `verify.py` checks exact artifact and fixture key sets, all hashes, response-id continuity and uniqueness, request method/param validation and optional server replay, expected results, renderer derivation, CLI shape, and absence of unnormalized absolute paths.

### Path Normalization
Capture runs against temporary copies of `fixtures/sources/` with `.cint` removed. Absolute fixture roots are rewritten to `<CORPUS_ROOT>/fixtures/sources/<language>`. Document URIs are synthetic (`file:///test/Bookkeeping.kt`, `file:///test/NeighboursCli.kt`, `file:///test/Sample.ts`) so transcripts do not depend on the checkout location. Overlays live under `fixtures/overlays/` and are copied into temporary roots; captures never touch the repository or `testing-grounds/`.

### Reproducibility
From the repository root:
```sh
./frozen-v1-corpus/reproduce.sh
```
The script locates the repo from its own path, extracts the exact pinned V1 commit `3ccea9e9cd...` into a temporary tree, and builds `:cli:installDist` and `:server:installDist` there. It never builds the mutable working tree, and captures CLI outputs from isolated temporary fixture copies. Capture and verification happen in a sibling staging directory. `verify.py --server-bin` re-sends every stored session request to the pinned live/HTTP server and compares the resulting messages with the transcripts. Only after all checks pass does `renameat2(RENAME_EXCHANGE)` swap the staged and live corpus directories atomically; before the exchange the old corpus is untouched, and if exchange is unavailable the script aborts. Requirements: Linux with `renameat2` exchange support, JDK 21+ (verified on 26), Gradle via the wrapper, and Python 3.8+ using only the standard library.

### Known Differences
`known-differences.json` is the machine-readable allow-list. It defines ordered-exact protocol comparison, semantic CLI JSON comparison, exact renderer comparison, and marks each difference `decided` or `tentative` with its owning ticket. `KNOWN_DIFFERENCES.md` is the human-readable companion.

## Version
- V1 Commit: `3ccea9e` (final pre-skeleton V1 source)
- Pipeline Note: "validation intentionally widened from the pre-018 014-tree baseline"
- Protocol Version: 4
- Generated: 2026-10-25