# Known V2 Differences from V1 Behavior

This document lists intentional deviations in V2 behavior from the frozen V1 corpus. These are not bugs — they are deliberate design changes. V2 tests should expect these differences.

The machine-readable contract is `known-differences.json`. It defines one comparison rule per surface (`ordered-exact` for protocol transcripts, `semantic-json` for CLI output, `exact-json` for the renderer projection) and marks every entry `decided` or `tentative` with the owning ticket. This document is the human-readable companion; if the two disagree, the JSON contract wins.

## Protocol Version
- **V1**: Protocol version 4 (LIVE_PROTOCOL_VERSION = 4)
- **V2**: Will use protocol version 5+ with breaking changes

## JSON-RPC Transport
- **V1**: NDJSON over Unix domain sockets (primary) and HTTP/JSON (secondary)
- **V2**: Tentative — owned by ticket 079; may add transports beyond NDJSON/Unix sockets

## Initialize Response
### V1 (frozen)
```json
{
  "protocol": 4,
  "root": "<CORPUS_ROOT>/fixtures/sources/kotlin",
  "languages": ["kotlin", "javascript", "typescript", "vue", "csharp"],
  "config": { "packs": [...], "overlay": {} },
  "suggestions": { "enabled": false, "trigger": "manual", "provider": null, "model": null, "knownIntents": [...], "discovery": false, "restrictions": true, "cache": true, "maxUnitsPerRequest": 24 }
}
```
### V2 Changes
- `suggestions.enabled` defaults to `true` when AI provider configured
- `suggestions.trigger` defaults to `"auto"` (on save/type)
- `suggestions.discovery` defaults to `true`
- New field: `capabilities` object with feature flags
- `protocol` version bumped

## Publish Tags (cint/publishTags)
### V1
```json
{
  "method": "cint/publishTags",
  "params": {
    "uri": "...",
    "version": 1,
    "hash": "...",
    "revision": 0,
    "analysisRevision": 1,
    "annotations": [{ "role": "...", "intents": [...], "startLine": 1, "startCol": 2, "endLine": 1, "endCol": 10, "startByte": 0, "endByte": 10, "provenance": [...] }]
  }
}
```
### V2 Changes
- `revision` → `configRevision` (explicit naming)
- `analysisRevision` → `analysisVersion` (monotonic per-document)
- `annotations` array remains inline objects; V2 adds the fields below
- New field: `diagnostics` for parse/type errors
- Annotations include `nodeAnchor` when available (V1 always null)

## Publish Candidates (cint/publishCandidates)
### V1
```json
{
  "method": "cint/publishCandidates",
  "params": {
    "uri": "...",
    "version": 1,
    "analysisRevision": 1,
    "suggestionRevision": 1,
    "groups": [...],
    "candidates": [...]
  }
}
```
### V2 Changes
- `groups` and `candidates` merged into single `proposals` array with unified structure
- `suggestionRevision` → `proposalRevision`
- Each proposal has explicit `kind: "candidate" | "ai-proposal" | "restriction"`
- `operations` field always present (not gated by protocol version)
- Anchor fingerprints included by default (not gated by short-circuit)

## Config Protocol
### V1
- `cint/config/read` returns `{ packs: [...], overlay: {...} }`
- An invalid overlay makes `cint/config/read` return a JSON-RPC error; `initialize` reports `config.overlayError` instead
- `cint/config/append` and `cint/config/remove` mutate the overlay file, reload packs, republish open documents, and return the resulting overlay
- Overlay stored as YAML at `.cint/config.overlay.yml`
- Only `STRINGS` and `STRING_LIST` fields accept append/remove values

### V2 Changes
- Config stored as JSON (`.cint/config.json`) for better tooling support
- `cint/config/read` returns `{ config: {...}, sources: ["builtin", "project", "user"] }`
- New: `cint/config/write` for atomic full-config replacement
- New: `cint/config/schema` returns JSON Schema for validation
- Pack/field descriptors include `deprecated`, `since`, `examples`

## Analyze Request/Response
### V1 HTTP
```json
POST /analyze
{ "path": "...", "root": "...", "text": "...", "language": "kotlin", "config": "...", "types": {} }
```
Response: `{ "language": "...", "intents": [...], "files": [{ "path": "...", "hash": "...", "spans": [...], "candidates": [...] }] }`

### V2 HTTP
- Request: `types` field removed (LSP handles types)
- Request: new `options` field for per-request overrides
- Response: `files[].spans` → `files[].annotations` (consistent with live protocol)
- Response: new `files[].diagnostics` array
- Response: `intents` at root level removed (per-file only)

## Suggest Protocol
### V1
- `cint/suggest` takes `{ uri, version, requestId? }`; `requestId` is used for cancellation
- `cint/suggest/cancel` takes `{ uri, requestId? }`
- `cint/suggest/log` takes `{ uri }` and requires protocol 4
- Progress (when emitted by a provider) via `cint/suggest/progress` notifications
- The deterministic `fake` provider emits no progress notifications

### V2 Changes
- `cint/suggest` takes `selection` (span) instead of `uri` + `version`
- New: `cint/suggest/stream` for streaming responses (Server-Sent Events or NDJSON)
- New: `cint/suggest/history` to retrieve past suggestions
- Cancellation via request ID (JSON-RPC standard) not custom method
- Progress integrated into streaming response

## Restrictions Protocol
### V1
- `cint/restrictions/list` returns `{ restrictions: [...] }`
- `cint/restrictions/remove` by `id`

### V2 Changes
- `cint/restrictions/list` → `cint/restrictions` (GET-style, no method suffix)
- New: `cint/restrictions/upsert` for create/update
- Restrictions have `scope: "project" | "user" | "workspace"`
- Restrictions support `condition` field for conditional application

## Status/Health
### V1
- HTTP: `GET /health` → `{ "status": "ok" }`
- Live: No status method; `cint/status` returns error `unknown method: cint/status` with code `-32000`
- The only V1 status surface is HTTP `/health`, captured in `protocol-http-health.ndjson`

### V2
- HTTP: `GET /health` → `{ "status": "ok", "version": "...", "uptimeMs": 1234, "activeDocuments": 5, "suggestionQueue": 0 }`
- Live: `cint/status` method returns same structure
- New: `cint/metrics` for Prometheus-compatible metrics

## Annotation/Provenance Structure
### V1
```json
{
  "role": "source-intent",
  "intents": ["LOGGING"],
  "source": "treesitter",
  "confidence": 0.8,
  "startLine": 1,
  "startCol": 2,
  "endLine": 1,
  "endCol": 10,
  "startByte": 0,
  "endByte": 10,
  "nodeAnchor": null,
  "provenance": [{ "rule": "logging:logging", "role": "source-intent", "intents": ["LOGGING"], "source": "treesitter", "confidence": 0.8, "startLine": 1, "startCol": 2, "endLine": 1, "endCol": 10, "startByte": 0, "endByte": 10 }]
}
```

### V2 Changes
- `source` values standardized: `"treesitter"` | `"heuristic"` | `"annotation"` | `"cfg"` | `"ai"`
- `provenance` entries include `ruleId` (fully qualified) and `packId`
- New: `provenance[].evidence` (optional, structured evidence object)
- New: `nodeAnchor` populated for treesitter-based annotations
- `confidence` always finite (no NaN/Infinity)

## Candidate Structure
### V1
```json
{
  "candidateId": "...",
  "path": "...",
  "contentHash": "...",
  "intents": ["LOOKUP"],
  "source": "rule:data-access:lookup",
  "confidence": 0.4,
  "startLine": 1,
  "startCol": 2,
  "endLine": 1,
  "endCol": 10,
  "startByte": 0,
  "endByte": 10,
  "reason": "PACK_DOMAIN",
  "aiEligible": true
}
```

### V2 Changes
- `source` → `origin: { type: "rule" | "ai" | "heuristic", ruleId?: string, provider?: string, model?: string }`
- `reason` → `category: "pack-domain" | "low-confidence" | "user-hint" | "ai-generated"`
- New: `fingerprints` map (anchor fingerprints for identity tracking)
- New: `operations` array (proposed edits)
- `aiEligible` removed (inferred from `origin.type === "ai"`)

## Language Support
### V1
- kotlin, javascript, typescript, vue, csharp
- Vue analyzed via SplitAnalyzer (script regions)

### V2
- Same languages at launch
- New: python, go, rust (planned)
- Vue: separate frontend per region type (script/template/style)

## LSP Integration
### V1
- Optional, on by default for CLI/server
- Timeout: 750ms (LIVE_LSP_TIMEOUT_MS)
- Falls back to heuristics silently

### V2
- Required for type-dependent intents (VALIDATION, RETURN_VALUE, etc.)
- Configurable timeout per language
- Explicit diagnostics when LSP unavailable for required intent
- Type facts cached across requests

## Configuration Files
### V1
- `.cint/config.overlay.yml` (runtime config mutations)
- `.cint/user-annotations.json` (user confirmations)
- `.cint/ai-restrictions.json` (AI restrictions)
- `.cint/suggestions.yml` (suggestion settings, read-only)
- `.cint/suggestions.json` (suggestion decisions)
- `.cint/cache/ai-verdicts.json` (verdict cache)

### V2
- `.cint/config.json` (merged project + overlay)
- `.cint/user-annotations.json`
- `.cint/ai-restrictions.json`
- `.cint/verdict-cache.sqlite` (renamed)
- New: `.cint/schema.json` (generated JSON Schema)

## CLI Output Format
### V1
```json
{
  "language": "kotlin",
  "intents": [...],
  "files": [{ "path": "...", "hash": "...", "spans": [...], "candidates": [...] }]
}
```

### V2
- Top-level `intents` removed (redundant with per-file)
- `files[].spans` → `files[].annotations`
- New: `files[].diagnostics`
- New: `meta: { version: "...", timestamp: "...", elapsedMs": 123 }`
- `--format json|ndjson|text` option

## Renderer-Visible Changes
These affect what the Neovim renderer displays:

1. **Annotation roles**: V2 adds `"ai-proposal"` role for AI-suggested intents
2. **Confidence display**: V2 shows confidence as percentage (0-100) not 0.0-1.0
3. **Provenance UI**: V2 shows packId + ruleId, not just rule
4. **Candidate groups**: V2 merges candidate groups by anchor, not by intent
5. **Operations**: V2 shows proposed edit operations inline
6. **Restrictions**: V2 shows restriction conditions and scope
7. **Progress**: V2 shows streaming suggestion progress with partial results

## Captured V1 Errors
These real V1 errors are frozen in the corpus and must be either preserved or intentionally replaced by V2:

| Transcript | Trigger | V1 error message |
|------------|---------|------------------|
| `protocol-status-flow.ndjson` | `cint/status` | `unknown method: cint/status` |
| `protocol-error-flow.ndjson` | `cint/restrictions/list` after protocol 3 initialize | `cint/restrictions/list requires protocol 4` |
| `protocol-error-flow.ndjson` | append `validation.magicComment` | `rulepack 'validation' field 'magicComment' is not a string list field` |
| `protocol-error-flow.ndjson` | append to unknown pack | `unknown rulepack: fixture-missing` |

Harness failures are not part of the corpus: `capture.py` exits non-zero and prints server stderr if the server dies, returns an unexpected error, times out, or leaves response ids out of sequence.

## Error Handling
### V1
- Errors returned as JSON-RPC error responses with code -32000
- Analysis failures fall back to heuristics, log to stderr

### V2
- Standard JSON-RPC error codes (-32600 to -32603, -32000 to -32099)
- Structured error objects: `{ code, message, data: { kind, details } }`
- Analysis failures return `diagnostics` in response, not fallback
- LSP connection errors surfaced as `diagnostics` with `source: "lsp"`

## Performance Characteristics (Non-Functional)
- V2 target: <50ms p99 for analyze (vs V1 ~200ms)
- V2 target: <200ms for suggest first token (streaming)
- V2: Incremental analysis by default (V1: full re-analyze on change)
- V2: Parallel suggestion processing (V1: sequential)

## Testing Notes for V2
When writing V2 tests against the frozen corpus:

1. **Protocol transcripts**: Replay `protocol-*.session.json` requests and compare the concatenated server messages to the frozen `.ndjson` files in exact order. Every field is exact unless `known-differences.json` allow-lists it.
2. **CLI outputs**: Compare parsed JSON semantically, ignoring object key order but not file or array order. Apply only the `scope: "cli"` allow-list entries.
3. **Renderer behavior**: Compare `renderer-visible-v1.json` exactly against the projection derived from the frozen transcripts; apply only `scope: "renderer"` entries.
4. **Known differences**: Use `known-differences.json` as the executable allow-list. Entries marked `tentative` are not decisions yet and must not be copied into V2 tests without the named ticket.
