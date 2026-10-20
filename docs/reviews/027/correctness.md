# Correctness review — ticket 027 (docs snapshots and tooling)

## Round 1

Verdict: The restored snapshot data is the right pre-018 data. Both
`legacy-3f20435.json` and `pipeline-3f20435.json` are byte-identical to the
snapshot files committed in `739dff7` (ticket 014), and they differ from the
current baseline exactly by the validation annotations ticket 018 added; the
current test renders all 11 fixtures, including the Vue region path, with full
rule identity. The recipes now build/assemble and test the intended modules,
`live`/`tags` depend on `install` and quote paths, the size scripts exclude
`bin/`, and the CI/docs index exist. Two gaps remain: the legacy snapshot test
only checks the provenance label (so it would also have passed on the corrupted
post-018 files), and several docs still describe those restored files as
legacy-engine output actually regenerated from `3f20435`, which the repository
does not support.

### Findings

1. **`analysis-engine/integration-tests/src/test/kotlin/dev/cint/integration/host/PipelineSnapshotTest.kt:178-184` — the pre-014 test cannot detect the corruption it was added to prevent.**

   `pre014SnapshotsKeepTheirRecordedCommit` only asserts that each resource
   contains the literal `"commit":"3f20435"`. The corrupted snapshots that
   ticket 018 left behind had exactly that label while containing widened
   validation output, so this test would pass on them. It therefore does not
   verify that `legacy-3f20435.json` / `pipeline-3f20435.json` are the true
   pre-018 files, nor that they remain distinct from `pipeline-current.json` for
   the expected reason. Fix: pin the frozen resources by content (for example
   SHA-256 of each file) and/or assert their pre-018 shape, such as the known
   annotation counts and the absence of the widened `VALIDATION` guard
   annotations that `pipeline-current.json` contains.

2. **`docs/manual-test.md:16`, `docs/notes.md:463-465`, `docs/intent-model.md:445-447`, `docs/architecture.md:272-273`, `docs/tasks/014-delete-legacy-config-and-phase.md:49-50`, `docs/tasks/018-return-parity-non-direct-exit.md:54-56` — provenance wording overstates what was regenerated.**

   The restored files are the pre-018 014-tree snapshots: `739dff7` added
   `legacy-pre014.json` by rendering `HostPipeline` output in
   `LegacyParitySnapshotTest.currentFixture`, not by capturing the deleted
   `AnalysisEngine`. The ticket-014 review already noted that the `commit`
   field was self-declared and that the legacy file could not be independently
   regenerated. Saying the files are "regenerated from the actual `3f20435`
   code" or are annotations "from the legacy engine at commit `3f20435`" is
   therefore not established by anything in the repository; the files are the
   post-restructure host-pipeline baseline whose behavior was compared against
   the legacy engine at that commit. Fix: describe them as restored pre-018
   (014-tree) host snapshots that matched the `3f20435` legacy output on the
   compared shape, or actually regenerate them from a `3f20435` legacy run and
   record the command/script used.

### Concurs / Dissents

- Concurs: the restored legacy and pipeline snapshots have the pre-018 content;
  the diff against the post-018 files is limited to the widened validation
  guards (`csharp/Sample.cs`, `javascript/Sample.js`, `typescript/Sample.ts`,
  `kotlin/Scaffolds.kt`), which matches ticket 018's stated behavior change.
- Concurs: `currentPipelineMatchesFrozenSnapshot` compares the complete rendered
  snapshot, including provenance, fixture languages, roles, intents, evidence,
  spans, source, confidence, byte offsets, and rule identity, so it is a real
  regression test for the post-018 baseline.
- Concurs: the fixture set still covers all 11 files and the Vue script-region
  path, and `assumeFixtureRoot` keeps missing fixtures an explicit skip rather
  than a silent pass.
- Concurs: `just build` uses `assemble` and no longer runs tests, while
  `just test-engine` includes `host`, `server`, `cli`, `rulepacks-tests`,
  `test-fixtures`, and `integration-tests`; keeping `:lsp:test` opt-in through
  `just test-lsp` is deliberate.
- Concurs: `live` and `tags` now depend on `install`, use quoted paths, and
  forward arguments; both size scripts exclude `bin/`; the just descriptions
  match the new behavior.
- Concurs: CI exists and runs build, engine tests, lint, and renderer tests with
  an explicit `CINT_TESTING_GROUNDS`; `.gitattributes` and `docs/README.md` are
  present and the index links resolve.
- Concurs: current docs no longer point at deleted production paths; remaining
  `intents.yaml`/`Phase`/`rules/Blocks.kt` references are in historical ticket
  and notes sections that are explicitly marked historical.
- Concurs: `manual-test.md` m008 is now `superseded`, and m001/m003/m006 no
  longer claim pre-014 parity is automated.
- Dissents: findings 1 and 2.

### Residuals

- `pipeline-current.json` still carries the legacy-only
  `returnRoleNormalization` provenance note; it is harmless but has no meaning
  for a current host-pipeline baseline.
- `just test-lsp` says a running `kotlin-lsp` is required, while
  `KotlinLspOracle.connect` can start one; the comment is conservative rather
  than wrong.
- `:test-fixtures:test` currently has no test sources; including it in
  `test-engine` is a no-op until tests are added there.
- No build, test, or formatter command was run by this role; the handover was
  treated as green per `workflow.md`.

## Round 2

Verdict: Both round-1 findings are addressed and I found no new correctness
defect. `pre018SnapshotsMatchTheirFrozenContent` now pins both pre-018 resources
by SHA-256, and the recorded digests match the actual files and the bytes from
`739dff7`, so the test fails on the corrupted post-018 snapshots instead of
passing on their label. The provenance wording in `manual-test.md`,
`notes.md`, `intent-model.md`, `architecture.md`, and tickets 014/018 now
consistently describes the files as restored pre-018 (014-tree) `HostPipeline`
renderings whose legacy shape was compared at `3f20435`, not as direct legacy
captures. The `pipeline-current.json` note was updated to match the test, and
the full rendered comparison still covers all fixtures.

### Findings

- None.

### Concurs / Dissents

- F1: concurs. The two digests are correct for the resources, the hexadecimal
  formatting is byte-safe, and the hash check is independent of
  `testing-grounds`, so it will not silently skip with the fixture-dependent
  tests. A later overwrite, including one that preserves the provenance label,
  now fails.
- F2: concurs. The stale "regenerated from actual `3f20435`" and "legacy engine"
  claims are gone; the docs correctly identify `739dff7` as the source tree and
  explain that `3f20435` is the comparison commit retained in the filenames.
  The m008 row is now historical/superseded rather than claiming current parity.
- Concurs: the current-pipeline snapshot's provenance note was regenerated
  alongside the test constant, and the full-content assertion remains intact.
- Concurs: the round-1 snapshot-content, recipe, CI, and documentation findings
  remain otherwise unchanged.
- Dissents: none.

### Residuals (Round 2)

- The frozen files' in-file `provenance.commit` is still the comparison label
  `3f20435` and `legacy-3f20435.json` still says `"kind":"legacy"`; the docs now
  carry the necessary caveat, so this is documentation context rather than a
  correctness defect. Adding a note to the JSON would change the pinned bytes.
- `pipeline-current.json` still carries the legacy-only
  `returnRoleNormalization` metadata from the original renderer.
- No build, test, or formatter command was run by this role; the handover was
  treated as green per `workflow.md`.
