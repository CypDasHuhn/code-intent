# Ticket 097 — Correctness Review

**Verdict: PASS with minor cleanup items.** No blocking correctness issues.

## Verified

- `frozen-v1-corpus/`, `tests/parity/`, `buildSrc/`, `tools/v1-syntax-capture/`, and
  `tests/fixtures/syntax/` are gone. The removed loaders/tests match the ticket list.
- No active references to deleted paths or `v2.frozenCorpusRoot`, `v2.v2Root`,
  `v2.parityReportDir`, `v2.ipcKnownDifferences`, `buildSrc`, parity reports, or
  syntax-provenance remain in code, build, tests, scripts, or active docs.
- Independent coverage from `SurfaceIntegrationTest` is not lost: managed LSP is covered by
  `sources/lsp/src/test/kotlin/dev/cint/v2/lsp/LspSemanticProviderTest.kt:45`, and the V1
  classpath check by `apps/cli/src/test/kotlin/dev/cint/v2/cli/CliTest.kt:547`.
- `graph-guard.json` is unchanged. The validator moved verbatim into the root
  `build.gradle.kts` (diff is only package/import removal); `graphGuard` remains, `check`
  now depends only on it, and `graphGuardSelfTest` is removed.
- `justfile` drops `parity`, `syntax-provenance`, and `graphGuardSelfTest`; ktlint globs no
  longer include `buildSrc`. CI drops the parity report/provenance steps and retains
  build, test-engine, lint, install, graph-guard, and smoke.

## Findings

1. `sources/syntax-treesitter/build.gradle.kts:101` — the `cint.repo.root` system property
   remains, but its only reader was the deleted `FrozenCorpusParityTest`
   (`git show HEAD:sources/syntax-treesitter/src/jvmTest/.../FrozenCorpusParityTest.kt:15`).
   Remove the `tasks.withType<Test>` block so no build setting survives only for the
   deleted corpus.
2. `docs/summary/current-state.md:29` — claims ticket 084 left "committed baselines", but
   `tests/parity/` was deleted. Reword without "committed baselines".
3. `docs/summary/development-and-testing.md:36` — still lists "surface" integration tests
   although `SurfaceIntegrationTest` was removed. Drop "surface".

No builds, tests, lint, or commits were run, per the review role.

## Round 2

**Verdict: DISSENT (minor).** The three prior findings are fixed: `cint.repo.root` is gone,
current-state no longer claims baselines, and "surface" was dropped. Remaining
`:integration-tests` imports match its reduced dependency/edge set, and the
`ProtocolMessagesCompatibilityTest` rename is clean.

Residual finding: `apps/server/build.gradle.kts:20` still declares test
`implementation(project(":languages"))`, plus `graph-guard.json` `["server", "languages"]`,
but only the deleted `FrozenProtocolParityTest` used `dev.cint.v2.languages.*`.
`LiveServerTest` uses `FakeLanguageSupport`/`fakeDescriptor` from `test-fixtures`
(`LiveServerTest.kt:48`) and `InMemoryIndex` from `:index` (`:46`), not `:languages`.
Remove both the dependency and the allowed test edge. No builds run.

## Round 3

**Verdict: CONCUR — Round 2 finding withdrawn.** Evidence confirmed: `RuntimeConfig`'s
public constructor exposes `languageOptions: LanguageOptions` (`RuntimeConfig.kt:19`), and
`:runtime` declares `:languages` as `implementation`, not `api`, so it is not transitive.
`LiveServerTest.kt:757` calls `RuntimeConfig()` inside `RuntimeComponents`, whose
`languageComposition` lambda's `LanguageOptions` parameter also requires the type. The
server test dependency and `["server", "languages"]` edge are therefore live and correctly
retained. No alternative fix needed. No builds run.
