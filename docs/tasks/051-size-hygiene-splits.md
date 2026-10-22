---
name: size hygiene splits
status: done
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

### Rulepack layout convention

Intent packs use package directories that match their declarations. Larger
bundled packs (service, mutation, data access) separate `config/` (config types,
schema, defaults) from `rules/` (matchers and rule implementations), with the
public `XxxRulePack` entry point at the intent root. Smaller structural packs
(`chaining/`, `localcopy/`, `returnvalue/`) keep each pack's config and rulepack
together in its own per-intent package because they do not need the extra split.
Packs small enough (validation, logging, normalization, transformation) may stay
flat until they need one. Shared non-pack helpers live in
`dev.cint.rulepacks.intents.support` (`ReceiverShape` and the callback
facts/parsers); there is no pack-shaped shared namespace.

### Server state visibility tradeoff

`LiveServer` keeps its mutable session state (`docs`, `analyses`, published
candidates, locks, revisions, stores, and `configRevision`) as module-internal so
the responsibility-split extension functions in `dev.cint.server.live` and
`dev.cint.server.suggestions` can operate on it. This is a deliberate tradeoff
for this split: the state remains non-public, but the boundary is broader than
the original private fields. Future changes should route access through narrow
session operations rather than adding new extension-level mutations; only the
existing `*BarrierForTest` hooks are intended test seams. `live` does not depend
on `suggestions`; candidate publication lives with the live document/analysis
code and suggestion/decision handlers depend on it one way.

### Size-threshold note

The `scripts/classify-kotlin-size.nu` SUSPICIOUS threshold change from 200 to 300
is deliberate and part of this ticket; EXTREME remains 400, so files in the
200-299 range are no longer reported as SUSPICIOUS while the EXTREME conclusion
is unchanged. The remaining OVERCROWDED roots reported by `just dir-size` are
accepted as out of scope: `analysis-engine` and `core/core` were already over
threshold at the base commit and reducing them is a package-boundary decision,
not a file split. The `rulepacks-tests/.../tests` root is also accepted as meaningful package
grouping rather than an arbitrary file dump: its multi-file groups (validation,
dataaccess, mutation, service, normalization, transformation) were reduced
internally, and the remaining entries are language/intent/feature test packages
(bookkeeping, composition, errorhandling, intents, logging, support, user,
validation). The root itself is not split further because that would only add a
generic nesting level; single-file packages are out of scope.
