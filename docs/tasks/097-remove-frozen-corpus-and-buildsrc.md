---
name: Remove frozen corpus, buildSrc, and V1 syntax capture
status: done
depends-on: [096]
reviewers: [architecture, correctness]
---

## Goal

Delete the obsolete V1-era reference infrastructure now that the engine no longer
needs it: `frozen-v1-corpus/`, `buildSrc/`, and `tools/v1-syntax-capture/`,
together with every active test, script, build setting, recipe, CI step, and
document that only exists to support them.

## Scope

- Delete `frozen-v1-corpus/`, `tests/parity/`, `CLI_DIFFERENCES.md`, the parity
  merge script, and the V1 syntax capture/regen/verify scripts.
- Delete `tools/v1-syntax-capture/`, `tests/fixtures/syntax/`, the generated
  `V1KotlinSnapshot.kt`, and all tests/loaders that depend on the frozen corpus
  or V1 snapshots: `FrozenV1Corpus`, `FrozenCorpusLoaderTest`,
  `FrozenCorpusParityTest`, `CorpusParityTest`, `SurfaceIntegrationTest`,
  `FrozenProtocolParityTest`, `FrozenTranscriptTest`, and the frozen-corpus CLI
  tests.
- Remove `v2.frozenCorpusRoot`/`v2.v2Root` test properties and their Gradle
  wiring in `apps/cli`, `apps/server`, `orchestration/ipc`, and
  `tests/integration`.
- Remove `just parity` and `just syntax-provenance`, plus their CI steps and
  references. Keep the non-frozen test suites: engine unit/integration tests,
  live server, IPC protocol/framing, MCP, pack knowledge/materialization,
  language-neutral detection, fake end-to-end, and smoke.
- Delete `buildSrc/`. Move the graph-guard validator into the root
  `build.gradle.kts` (or an existing non-buildSrc script), keep the
  `graphGuard` Gradle task and `just graph-guard`, and remove the
  `graphGuardSelfTest` task and buildSrc tests. Remove `buildSrc` from ktlint
  globs and docs.
- Update active docs and recipes so they no longer mention the frozen corpus,
  parity report, V1 syntax provenance, or buildSrc. Historical
  `docs/tasks/**`/`docs/reviews/**` may keep archival references; active
  documentation must not.
- Ensure no active code, build, test, or script references the deleted paths.

## Acceptance

- `frozen-v1-corpus/`, `tests/parity/`, `buildSrc/`, and
  `tools/v1-syntax-capture/` are gone.
- No active `frozen-v1-corpus`, `buildSrc`, `v1-syntax-capture`, parity, or
  syntax-provenance references remain.
- The graph guard still enforces `graph-guard.json` from the root build.
- `just build`, `just test-engine`, `just lint`, `just graph-guard`, and
  `just smoke` pass; CI is updated and green.
- No V1 runtime or test dependency is reintroduced as a replacement.

## Out of scope

- New detection features, protocol/renderer changes, or replacement corpus
  infrastructure.
- Rewriting historical task/review documents.

## Notes

- This is deletion and rewiring only; do not preserve the frozen gate under a
  new name.
- Prefer keeping self-contained fixtures already used by the non-frozen tests.
