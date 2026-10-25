# Correctness Review — 096

## Round 1

Verdict: approve with one minor fix.

Verified the Gradle entry points (`gradlew`, `settings.gradle.kts`,
`build.gradle.kts`, `buildSrc/`, `graph-guard.json`) now live at the repository
root and no `analysis-engine/` wrapper remains. `settings.gradle.kts:25-42` maps
all canonical project names to existing grouped directories; project names and
dependency edges match HEAD apart from the expected deletion of the optional
`native-spike` edge. Native assets moved intact to
`sources/syntax-treesitter/native/`, and `SHA256SUMS`, `verify-native-libs.sh`,
the fetch/extract scripts, and `GRAMMARS.md` agree on those paths. Corpus,
parity, release, smoke, CI, and just paths resolve from the new root. The
committed parity report differs from HEAD only in path labels; source counts and
defect data are unchanged. Docs otherwise match the tree.

### Findings

1. `tests/integration/src/test/kotlin/dev/cint/integration/parity/CorpusParityTest.kt:654`
   — `C_SYNTAX_PATH` is still `"native-spike/fixtures/CConstructs.c"`, a path
   deleted by this ticket and used as a report key at line 204. Fix: move that
   C source into `tests/fixtures/` and reference it, or rename the synthetic key
   to a non-spike label such as `synthetic/CConstructs.c`.

### Concurrent findings (independently confirmed)

- `justfile:26,28` and `docs/workflow.md:68` omit `buildSrc` from lint, losing
  coverage that the old `analysis-engine/**/src/**/*.kt` glob provided
  (architecture review finding 1).
- `docs/tasks/README.md:11` still says the single engine lives in
  `analysis-engine/` (architecture review finding 2).
- `docs/rewrite/module-graph.md:114-115` versus the 095/096 ticket wording
  leaves the `:packs`/`:default-rulepacks` rename ambiguous (architecture review
  finding 3).

No other broken paths, wrong Gradle project directories, missing files, or
worktree-only references were found. The `analysis-engine` archive paths in the
corpus scripts are intentionally historical V1 references.

## Round 2

Verdict: concur. All six fixes verified.

- `justfile:26,28` and `docs/workflow.md:68` now include `buildSrc` in the
  ktlint scope.
- `docs/tasks/README.md:11,82-84` place the engine at the repository root;
  remaining `analysis-engine` mentions are historical ticket/archive references.
- `docs/rewrite/module-graph.md:114-118` states 095 renames both the directory
  and the Gradle project to `default-rulepacks`, with `:packs` canonical until
  then.
- `docs/summary/architecture.md:20-24,47-50` defers to module-graph as the sole
  authoritative tree; no competing claim remains.
- Pack-resource references in `docs/engine.md`, `data-access-intents.md`,
  `intent-model.md`, `notes.md`, and `service-intents.md` are path-stable.
- `CorpusParityTest.kt:654` uses `synthetic/CConstructs.c`; no `native-spike`
  reference remains in active files, and the parity report is regenerated
  consistently.

No dissent or new findings.
