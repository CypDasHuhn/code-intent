# Architecture review — ticket 034 (engine module layout)

## Round 1

Verdict: The move itself is faithful. All 162 engine files tracked at `HEAD`
exist at their grouped destinations, and every one is byte-identical except the
structural-boundary test, so module source, resources, tests, and service
descriptors were relocated without changing production behavior. Gradle project
names and task paths remain `:api`, `:core`, and so on; the grouped layout is
sensible (`api`/`core`/`host`/`rulepacks`/`rulepacks-tests`, `treesitter`/`lsp`/
`frontend`, `cli`/`server`, `integration-tests`/`test-fixtures`), and the
dependency directions are unchanged. `analysis-engine` now has seven entries
under the directory-size rule (the generated `build`, `.gradle`, and `.kotlin`
entries are excluded), so the root-size acceptance is met. Current path
references are updated in the root README, docs index, `docs/engine.md`,
`docs/intent-model.md`, and `docs/notes.md`; the justfile and CI use task names
and remain valid, and the install paths still resolve under the root project.
The remaining issue is that the layout contract is duplicated and the
architecture test now accepts the old/ungrouped layouts, so the grouping is not
actually enforced.

### Findings

1. **`analysis-engine/modules/core/core/src/test/kotlin/dev/cint/core/architecture/StructuralBoundariesTest.kt:197-211` — the module resolver still accepts legacy and ungrouped locations, so the structural test does not enforce the new grouping.**

   `moduleSourceRoot` tries `analysis-engine/<module>`, `modules/<module>`, and
   `analysis-engine/modules/<module>` as well as the canonical grouped path.
   A module moved back out of its group would therefore still resolve and the
   architecture tests would pass, and a stale duplicate source tree could be
   silently tested instead of the moved one. `analysis-engine/modules/<module>`
   is also unreachable by construction because every module has a group. This
   weakens the very boundary guard the ticket's layout is supposed to establish.

   Fix: resolve only `analysis-engine/modules/<group>/<module>` (or
   `modules/<group>/<module>` when the working directory is `analysis-engine`),
   drop the legacy and ungrouped candidates, and assert that the resolved path
   matches the Gradle project directory. If multiple roots genuinely need
   support, derive them from `settings.gradle.kts` rather than hardcoding old
   layouts.

2. **`analysis-engine/settings.gradle.kts:18-35` and `StructuralBoundariesTest.kt:226-241` — the module-to-group map exists twice.**

   The settings script and the architecture test each carry an identical
   twelve-entry `moduleGroups` map. Adding or regrouping a module requires
   editing both, and a missing entry in either source fails with the opaque
   `NoSuchElementException` from `getValue`, rather than a clear layout error.
   The group assignment is now a project convention with two sources of truth.

   Fix: make one source authoritative. Either expose the settings mapping in a
   form the test can read (for example derive the expected path from the actual
   Gradle project directory), or keep the map in the test but make settings
   validation explicit, such as reporting the missing module names before
   assigning `projectDir`. A test that compares the settings mapping against the
   expected canonical layout would also catch drift.

### Concurs / Dissents

- Concurs: the grouped module set preserves the existing boundaries. `core`
  still depends only on `:api`, `rulepacks` only on `:api` in production, `host`
  on `:api`/`:core`, and the test-only modules remain leaves.
- Concurs: project names and tasks are unchanged, and the host install
  destinations still point at `analysis-engine/build/install`, so the `live` and
  `tags` recipes and the manual-test path remain correct.
- Concurs: documentation moves are coherent; `docs/engine.md` keeps working
  relative links (including `../testing-grounds/`), and historical review
  reports were appropriately left as point-in-time records.
- Dissents: findings 1-2.
- Residual, not filed: `rulepacks-tests` sits under `modules/core` while the
  other test-only modules sit under `modules/tests`; the subject-based grouping
  is defensible, but the group names no longer describe test modules uniformly.
- No build, test, lint, or formatter command was run; handover is assumed green
  per `docs/workflow.md`.

## Round 2

Verdict: Round 1 is addressed. `settings.gradle.kts` now wires every project's
`projectDir` explicitly and is the only module-to-group source; the test no
longer carries a duplicate `moduleGroups` map, resolves modules through the
settings-declared roots, and rejects the old ungrouped/legacy fallbacks. The new
`modulesUseGroupedProjectDirectories` test pins the layout: every included
module has a `projectDir`, the path is exactly `modules/<group>/<module>`, the
directory matches the project name, and the source directory exists. The engine
docs now state their repository-root-relative convention and use the full
`analysis-engine/modules/...` paths, so the remaining documentation references
are consistent. The move is still behavior-preserving: all 162 tracked engine
files compare byte-identically at their destinations except
`StructuralBoundariesTest`, and the root remains at seven dir-size entries. No
new architecture findings.

### Findings

None.

### Concurs / Dissents

- Concurs with R1 F1: the grouped-projectDir test and the removal of the
  `analysis-engine/<module>` and ungrouped candidates make the layout guard
  enforce the new structure rather than merely tolerate it.
- Concurs with R1 F2: the settings file is authoritative and the test derives
  its roots from the parsed `projectDir` declarations; a missing or malformed
  wiring fails the test instead of drifting silently.
- Concurs: module boundaries, project names/tasks, install paths, and the
  documentation path updates remain correct.
- Residual, not filed: `settings.gradle.kts` now enumerates each module name in
  both the `include` block and its `projectDir` line. This is explicit and
  covered by the new test, so it is acceptable, but a future helper that derives
  both from one list would remove the remaining repetition.
- No build, test, lint, or formatter command was run; handover is assumed green
  per `docs/workflow.md`.
