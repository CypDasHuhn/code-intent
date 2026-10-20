# Correctness review — ticket 034 (engine module layout)

## Round 1

Verdict: The relocation is functionally faithful. Every one of the 162 tracked
files under the twelve engine module directories has a destination under
`analysis-engine/modules/<group>/<module>/`, and all are byte-identical to
`HEAD` except the intentionally updated structural-boundary test. The
`settings.gradle.kts` map covers all twelve included projects, so Gradle project
names (`:api`, `:core`, `:cli`, etc.) and therefore `just` task names are
unchanged. Install destinations still use `rootProject.layout`, so
`analysis-engine/build/install/cint` and `cint-server`, the `live`/`tags`
recipes, and the manual-test path remain correct. Fixture discovery is safe:
`TestFixtures.rootOrNull()` walks parent directories from the module working
directory, snapshot resources are classpath-relative, and the CI
`CINT_TESTING_GROUNDS` is absolute. `analysis-engine` now has seven
dir-size-visible entries, meeting the root-size acceptance. One documentation
path problem remains after moving the engine README out of the engine tree.

### Findings

1. **`docs/engine.md:28-29,46,54-55` — the module paths are written
   engine-relative after the README moved to `docs/`, so they no longer resolve
   from the document's directory or the repository root.**

   The old `analysis-engine/README.md` could say `treesitter/src/...` because
   it lived in the engine root. Now the same references read
   `modules/languages/treesitter/src/...` and `modules/core/...`, which only
   exist beneath `analysis-engine/`; from `docs/` the real relative path is
   `../analysis-engine/modules/...`, and from the repo root it is
   `analysis-engine/modules/...`. `docs/intent-model.md:391-445` already uses
   the full `analysis-engine/modules/...` form, so the two engine docs are
   inconsistent. The command snippets at `docs/engine.md:174,193-197,200` still
   assume the reader is in `analysis-engine` (`./gradlew`,
   `build/install/cint/...`) and are now likewise ambiguous.

   Fix: prefix the module paths with `analysis-engine/` (matching
   `docs/intent-model.md`), or state once that paths and commands in the
   document are relative to `analysis-engine/`, and keep the command snippets
   consistent with that statement.

### Concurs / Dissents

- Concurs with the architecture review's findings on the over-permissive
  `moduleSourceRoot` fallbacks and the duplicated module-group map. From the
  correctness side they do not change current output, but the extra resolver
  branches could silently test a stale duplicate tree, and the duplicate map can
  drift from `settings.gradle.kts` with an opaque `getValue` failure.
- Concurs: Gradle task paths are unchanged. The project names in `include(...)`
  are preserved and only `projectDir` is reassigned, so `:api:test`,
  `:cli:installDist`, and every `just test-engine` task resolve as before.
- Concurs: install outputs are stable. Both application modules set
  `installDist.destinationDir` from `rootProject.layout`, which is still
  `analysis-engine`, and the justfile's `live` and `tags` recipes still point at
  those outputs.
- Concurs: fixture paths are safe. Moving `test-fixtures` deeper does not break
  fixture lookup because the helper ascends from the test working directory, and
  `PipelineSnapshotTest` still loads `/snapshots/*.json` from the classpath.
- Dissents: finding 1.

### Residual observations (not filed)

- The moves are not staged as moves in the worktree: `git status` shows the old
  module paths as unstaged deletions and `analysis-engine/modules/` as
  untracked. Content comparison against `HEAD` is clean (only the boundary test
  differs), but stage the destinations explicitly before committing so rename
  detection and history follow the files.
- `docs/intent-model.md` still points at `api/.../Dsl.kt`,
  `ProjectConfigLoader.kt`, and `support/BundledRulePackSelection.kt`, none of
  which exist. Those references were stale before this ticket; the move only
  rewrote their directory prefix.
- `docs/README.md` links the engine doc as `../docs/engine.md`; it resolves, but
  `engine.md` would be the direct sibling link.

No build, test, lint, or formatter command was run by this role; the handover is
assumed green per `docs/workflow.md`.

## Round 2

Verdict: The round-1 finding is addressed. `docs/engine.md` now states that
module paths are repository-root-relative, prefixes them with
`analysis-engine/`, and explicitly says the `./gradlew` and `build/install`
commands run from `analysis-engine/`; its module paths and command context are
now consistent with `docs/intent-model.md`. `settings.gradle.kts` also replaced
the duplicated group map with one explicit `projectDir` assignment per included
project, making it the authoritative layout source. The new
`modulesUseGroupedProjectDirectories` test parses those assignments, checks one
exists for every included project, verifies each resolves to
`modules/<group>/<module>`, and checks each root has a `src/` directory; the
permissive `moduleSourceRoot` fallbacks and the test-side group map are gone.
All other module files remain byte-identical to `HEAD`, so task names, install
outputs, fixture lookup, and production behavior are unchanged from round 1. I
found no new correctness defect.

### Findings

- None.

### Concurs / Dissents

- Concurs with round-1 finding 1: `docs/engine.md` contains no remaining
  engine-relative module path, and the command snippets are now explicitly tied
  to the `analysis-engine/` working directory.
- Concurs with the architecture findings being resolved: the test no longer
  accepts legacy/ungrouped module locations, and the settings map is no longer
  duplicated. The regex-based test necessarily knows the settings statement
  shape, but a malformed or missing assignment now fails loudly instead of
  falling back to an old tree.
- Concurs: task names, install destinations, `live`/`tags` recipes, and fixture
  paths are unaffected by the round-2 edits.
- Dissents: none.

### Residual observations (not filed)

- The worktree still presents the module relocation as unstaged deletions plus
  untracked `analysis-engine/modules/`; content comparison is clean, but the
  moves should be staged explicitly before commit to preserve rename history.
- The pre-existing stale `docs/intent-model.md` references to `Dsl.kt`,
  `ProjectConfigLoader.kt`, and `BundledRulePackSelection.kt`, and the redundant
  `../docs/engine.md` link in `docs/README.md`, remain unchanged and unrelated
  to this ticket.

No build, test, lint, or formatter command was run by this role; the handover is
assumed green per `docs/workflow.md`.
