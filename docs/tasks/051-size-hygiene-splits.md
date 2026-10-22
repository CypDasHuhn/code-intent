---
name: size hygiene splits
status: in-progress
depends-on: [042, 044, 048, 050]
reviewers: [correctness, architecture]
---

## Goal

Break up the files and directories that remain over the size thresholds after
the rulepack, oracle, mutation, and cross-writer work, without changing
behavior. Single-file packages are intentionally out of scope.

## Scope

Split EXTREME (>400 lines) files by responsibility and reduce OVERCROWDED
directories (>5 entries or >8 total entries), prioritizing:

- Server: `LiveServer.kt` and its `LiveServerConfigTest`/`LiveSuggestionsTest`.
- Rulepacks service: `ServiceRulePack`, `ServiceConfig`, and the service tests
  and `rulepacks-tests/.../tests` crowding.
- Rulepacks mutation: `MutationRulePack`, `MutationConfig`, `CallbackFacts`, the
  mutation tests, and the data-access/validation test directories.
- Rulepacks data/validation: `DataAccessRulePack`, `ValidationRulePack`, and
  their configs/tests.
- Core/user: `UserAnnotationsFile` (lock protocol vs file I/O) and its tests,
  including the cross-writer harness.
- Core/test runtime: the `runtime/engine` test pile and `RulePackFixtures`.
- API/suggest: `api/Config.kt`, `ConfigSupportTest`/`ContractTest`, and the
  `core/suggest` package.

Moves may change package names and file locations. Test behavior, public
contracts, and detection output must not change. Keep each resulting file below
the EXTREME threshold where practical, and reduce the listed OVERCROWDED
directories below their thresholds.

## Acceptance

- No production or test file in `analysis-engine` is EXTREME (>400 lines),
  unless a ticket note records a concrete reason.
- The listed OVERCROWDED directories are reduced, ignoring single-file packages.
- `just build`, `just test-engine`, `just lint`, and `just test-nvim` pass.
- No detection snapshot, public API, or annotation/lock behavior changes.

## Out of scope

- Single-file packages.
- Behavior changes, new intents, or config semantics.
- Splitting generated/build output.

## Notes

Split by responsibility (transport/session/state, config/schema/matching,
scenario/harness/fixtures) and keep package boundaries meaningful. Work is
parallelized in worktrees by module group; the orchestrator merges and runs the
full gate.
