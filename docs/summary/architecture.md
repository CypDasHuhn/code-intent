# Architecture

CodeIntent is a fixed analysis hull with loadable detection. Core owns parsing,
facts, scheduling, composition, and publishing; rulepacks own detection only.

```text
source
  -> frontend.parse
  -> tree-sitter syntax profile -> facts + CFG
  -> stratified rule scheduler (rulepacks -> established Emit + CandidateEmit)
  -> core composition (return, derivative-use, scaffold)
  -> host artifact / live publish
       -> optional AI candidate enrichment -> review -> user annotation
```

## Modules

Under `analysis-engine/`:

| module | role |
|---|---|
| `core/api` | Frozen v5 contract: `Intent`, `Rule`, `RulePack`, `View`, `Emit`, `CandidateEmit`, `Annotation`, spans, config schema, DSL. No detection. |
| `core/core` | Syntax/fact models, CFG, annotation store, scheduler/fixpoint, composition, user annotation resolution/locking, oracle wrapper/budget, config policy. Depends on `api` only. |
| `core/rulepacks` | Bundled detection packs and shared pack support. Depends on `api`; discovered via `META-INF/services/dev.cint.api.RulePack`. |
| `core/suggest` | Expression discovery session/tool loop, candidate enrichment SPI, fake/DeepSeek providers, validation, privacy/redaction, grouping. Depends on `api` + `core`. |
| `core/host` | `HostPipeline` and project config/overlay loading shared by CLI, HTTP, live server. |
| `languages/treesitter` | Tree-sitter parsing and syntax profiles (`src/main/resources/syntax/*.yaml`). |
| `languages/lsp` | Spec-driven `LspOracle`, Kotlin LSP integration, document type cache. |
| `languages/frontend` | Language frontend registry and Vue split-region handling. |
| `hosts/cli` | Batch artifact generator. |
| `hosts/server` | HTTP analyzer and live Unix-socket daemon (protocol 4). |
| `tests/rulepacks-tests` | Pack-level and cross-pack behavior tests. |
| `tests/integration-tests` | Composition, host, snapshot, view, split, and end-to-end tests. |
| `tests/test-fixtures` | Shared fixtures/test packs. |

Dependency direction: `core/core` -> `api` only; `suggest`/`host` -> `api`+`core`;
language modules bind parser/syntax/oracle; hosts depend on `host`; `rulepacks`
is supplied by the bundled service loader. Core never depends on rulepacks or a
host.

## Rule contract and scheduler

- Intent names are open. `UNTAGGED` is reserved and not owned.
- Every rule owns exactly one intent; ownership is globally unique.
- `reads` declares required intent dependencies; `optionalReads` adds an edge
  only when the owner is present. Consumers name intents, not rule ids.
- The scheduler resolves intent dependencies to owning rules, builds a rule
  graph, rejects missing required owners and cycles, and topologically sorts
  strata. A stratum runs once against frozen earlier output. A self-reading rule
  may run an internal monotone fixpoint.
- Core composition is the terminal node and consumes all source intents.
- Rules return `List<RuleEmit>`: established `Emit` and review-only
  `CandidateEmit`. `toContribution` accepts only `Emit`, so candidates never
  enter the annotation store or composition.

Current strata (with bundled packs):

```text
0: logging, error-handling-<language>, bookkeeping, normalization,
   data-access:search, data-access:lookup, service:init,
   mutation:data-modify, return-value, local-copy
1: service:delegation (optional DATA_MODIFY),
   mutation:local-value-modify (BOOKKEEPING, LOCAL_COPY),
   data-transformation (SEARCH; self-read ignored)
2: validation (BOOKKEEPING, DATA_MODIFY, LOCAL_VALUE_MODIFY)
terminal: core composition
```

`BOOL_COMBINATORICS` and `CHAINING` packs exist but are currently disabled in the
bundled service descriptor.

## Annotation model

- Roles: `SOURCE_INTENT` (rule/plugin), `DERIVATIVE_USE`, `SCAFFOLD`
  (core-only at the plugin boundary).
- Each annotation carries intents, a span (0-based line/col plus byte offsets),
  rule id, source tier, confidence, and per-contributor provenance.
- Store semantics are monotone replacement per rule; there is no removal API.
- Cross-rule/merge contributions with the same role+span union intents.
- `Annotation.nodeAnchor` is reserved and currently `null` for user
  annotations.

## Composition

- **Return categorization:** each return gets a `core:return-category` scaffold.
  Certified rejection guards inherit their domain intent (for example
  VALIDATION); ordinary returns are `UNTAGGED`. `RETURN_VALUE` is emitted by the
  structural pack across the whole return statement and is excluded from
  scaffold category resolution so it does not hide domain intents.
- **Derivative use:** read-only local/value flow promotes source intents to
  `DERIVATIVE_USE`; mutation/logging/error intents are effect intents. The
  all-uses-classified rule applies except that a local flowing into a
  `RETURN_VALUE` expression is promoted with `RETURN_VALUE` alone.
- **Scaffolds:** if/try/catch/function scaffolds tag enclosing regions with the
  covered intents. Multi-intent scaffolds skip color/highlight; the reserved
  `UNTAGGED` marker is ignored for visual meaning/tag choice, so
  `{ERROR_HANDLING, UNTAGGED}` renders as ERROR_HANDLING. UNTAGGED-only spans
  keep their configured behavior.

## Languages and oracle

- Tree-sitter profiles for Kotlin, JavaScript, TypeScript, Vue, C#. Vue
  `<script>` regions are analyzed as JS/TS through `SplitAnalyzer` and spans are
  shifted back to host coordinates.
- `LspOracle` is Kotlin-only. `NullOracle` is used elsewhere.
- Per-analysis `OracleSession` memoizes types including negatives. The LSP
  oracle has a bounded per-document-version cache (positive durable, negative
  with a short TTL), serialized open/version/hover path, and an oracle budget
  (timeouts/failures/elapsed) that degrades to text/config evidence. Split
  regions use a budget per region.

## Hosts and protocol

- `HostPipeline` loads project config plus `.cint/config.overlay.yml`, resolves
  user annotations, runs analysis, and produces artifact/live payloads.
- CLI: `cint <root> [--config file] [--lang kotlin] [--out file] [--all]
  [--no-lsp]`.
- HTTP: one analysis request per source.
- Live daemon: NDJSON JSON-RPC over a Unix socket, protocol 4. Methods include
  `initialize`, `cint/publishTags`, `cint/publishCandidates`, `cint/analyze`,
  `cint/suggest`, `cint/suggest/progress`, `cint/suggest/log`,
  `cint/suggest/cancel`, `cint/candidates/decide`, and `cint/config/read|append|remove`. Publishes carry content hash, config
  revision, per-document `analysisRevision`, and candidates add a
  `suggestionRevision`.
- Config: project YAML keyed by pack id with a `core:` policy section; the
  overlay is additive for packs that opt in. Pack schemas are typed and
  descriptors are published to the client.

## Renderer boundary

The renderer is the only editor-specific component. It consumes published
annotations/candidates and owns visual meanings, highlights, profiles/shards,
hover, and review UX. It never runs detection.
