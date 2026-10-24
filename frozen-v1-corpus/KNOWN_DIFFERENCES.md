# Known V2 Differences from V1 Behavior

This document lists intentional deviations in V2 behavior from the frozen V1 corpus. These are not bugs — they are deliberate design changes. V2 tests should expect these differences.

## Protocol Version
- **V1**: Protocol version 4 (LIVE_PROTOCOL_VERSION = 4)
- **V2**: Will use protocol version 5+ with breaking changes

## JSON-RPC Transport
- **V1**: NDJSON over Unix domain sockets (primary) and HTTP/JSON (secondary)
- **V2**: TBD — may use different transport (e.g., stdio, WebSocket, or improved Unix socket framing)

## Initialize Response
### V1 (frozen)
```json
{
  "protocol": 4,
  "root": "/path/to/root",
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
    "annotations": [RawValue(...)]
  }
}
```
### V2 Changes
- `revision` → `configRevision` (explicit naming)
- `analysisRevision` → `analysisVersion` (monotonic per-document)
- `annotations` array contains inline objects, not RawValue-wrapped strings
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
- `cint/config/read` returns `{ packs: [...], overlay: {...}, error?: string }`
- `cint/config/append` and `cint/config/remove` mutate overlay file
- Overlay stored as YAML at `.cint/overlays.yaml`

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
- `cint/suggest` with `anchorId` (opaque string)
- `cint/suggest/cancel` by URI
- `cint/suggest/log` for debugging
- Progress via `cint/suggest/progress` notifications

### V2 Changes
- `cint/suggest` takes `selection` (span) instead of `anchorId`
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
- Live: No status method

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
  "span": { "startLine": 1, "startCol": 2, "endLine": 1, "endCol": 10 },
  "nodeAnchor": null,
  "provenance": [{ "rule": "logging:logging", "role": "source-intent", "intents": ["LOGGING"], "source": "treesitter", "confidence": 0.8, "span": {...}, "startByte": 0, "endByte": 10 }]
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
  "span": {...},
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
- `.cint.yaml` (project config)
- `.cint/overlays.yaml` (runtime mutations)
- `.cint/user-annotations.yaml` (user confirmations)
- `.cint/ai-restrictions.yaml` (AI restrictions)
- `.cint/suggestions.db` (SQLite verdict cache)

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

1. **Protocol transcripts**: Compare V2 NDJSON output line-by-line with frozen `.ndjson` files, allowing for the field changes documented above
2. **CLI outputs**: Compare V2 JSON output with frozen `.json` files using semantic equivalence (ignore field order, allow new optional fields)
3. **Renderer behavior**: Snapshot tests should capture V2's new UI elements (operations, confidence %, provenance details)
4. **Known differences**: Use this document as the allow-list for test assertions