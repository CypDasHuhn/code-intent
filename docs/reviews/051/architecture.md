# Architecture review — ticket 051 (size hygiene splits)

## Round 1

Verdict: The mechanical goal is largely met. No file in `analysis-engine` is
EXTREME (the largest is 386 lines), the large server, rulepack, core/user, API,
suggest, and test files are split along recognizable responsibility lines, the
frozen API FQNs remain unchanged, the `META-INF/services/dev.cint.api.RulePack`
descriptor still names the same classes, and module dependency direction is
unchanged. The split did, however, introduce package/directory mismatches in
several places, four different layout conventions for sibling rulepacks, a
duplicated dependency set in validation, broadened `LiveServer` state
visibility, and a new bidirectional package dependency between
`server.live` and `server.review`. These are structure and ownership problems,
not detection changes, but they should be fixed or explicitly deferred before
the ticket closes because they make the new layout misleading to navigate.

### Acceptance check

- `just size`: no EXTREME file. The largest files are `Contract.kt` and
  `CapabilityViewTest.kt` at 386 lines. At the committed HEAD threshold
  (`suspicious = 200`) there are more SUSPICIOUS rows, but still nothing over
  400. The working tree currently has an uncommitted change raising the
  SUSPICIOUS threshold to 300 (see finding 5).
- `just dir-size`: three OVERCROWDED rows remain: the `analysis-engine` root
  (5 files + 5 dirs), the `rulepacks-tests/.../tests` root (0 files + 9 dirs),
  and the `core/core` root (1 file + 8 dirs). The first and third were already
  over the threshold at the base commit and are not in the ticket's scope. The
  `rulepacks-tests` root was also already 9 directories at the base commit and is
  structurally unchanged; the crowding inside it (validation, dataaccess,
  mutation, service, normalization) was reduced. If its entries are treated as
  the single-file packages the ticket excludes, the result is acceptable, but
  that rationale should be recorded in the ticket note rather than left implicit
  in a still-OVERCROWDED row.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/chaining/ChainingRulePack.kt:1`,
   `.../intents/dataaccess/rules/DataAccessRulePack.kt:1`,
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/lock/AnnotationFileLock.kt:1`,
   `analysis-engine/core/core/src/test/kotlin/dev/cint/core/user/support/CrossWriterHarness.kt:1`,
   and `analysis-engine/core/host/src/test/kotlin/dev/cint/host/support/HostPipelineFixtures.kt:1`
   — the new directory hierarchy is not a package hierarchy.**

   Files were physically moved into `chaining/`, `localcopy/`, `returnvalue/`,
   `dataaccess/config/`, `dataaccess/rules/`, `user/lock/`, `user/support/`, and
   `host/support/`, but their package declarations remain the parent package
   (`...intents.structural`, `...intents.dataaccess`, `dev.cint.core.user`,
   `dev.cint.host`). The directories therefore suggest ownership boundaries that
   do not exist in the type system: imports and package navigation still show all
   files as siblings, subpackage-scoped visibility cannot be used, and a future
   contributor who creates `...dataaccess.rules.SomeHelper` will not actually be
   in the same package as the existing matchers. The service and mutation splits
   show the correct alternative: their files declare `.rules` and `.defaults` and
   match their directories.

   Fix: choose one form per area. Either declare the matching package and update
   imports (the service-loader descriptor names `DataAccessRulePack` and the
   structural packs, so those FQNs would also need updating), or keep the files
   flat in the package directory and rely on file names rather than fake
   subdirectories. Leaving a physical directory whose package is its parent is
   the one option that makes navigation worse than either alternative.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/`,
   `.../intents/mutation/`, `.../intents/dataaccess/`, `.../intents/structural/`,
   and `.../rulepacks/validation/` — sibling rulepacks now use four different
   split conventions, and shared non-pack helpers are placed inconsistently.**

   Service and mutation use real `rules/` and `defaults/` subpackages;
   data-access uses `config/` and `rules/` directories without package changes;
   structural uses per-pack directories without package changes; validation stays
   flat. The same kind of pack therefore has four different layouts, so "where
   does this rule live" is not predictable. Shared helpers also disagree:
   `ReceiverShape.kt` remains in `dev.cint.rulepacks.intents`, while the former
   `intents/CallbackFacts.kt` was split into a top-level `dev.cint.rulepacks.callback`
   package that is not a rulepack and reads like one.

   Fix: pick one convention for bundled packs (for example, every pack gets
   `config/`, `defaults/`, and `rules/` subpackages with matching declarations,
   or every pack stays flat) and apply it consistently. Put shared non-pack
   helpers in one clearly named place such as `rulepacks.intents.support` rather
   than mixing `intents` and a pack-shaped `callback` namespace. This is
   especially important before more packs are added, because the current layout
   gives no rule for where a new pack's code belongs.

3. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:14`
   with `.../validation/ValidationRule.kt:17` — the split duplicated the
   validation dependency set.**

   `MUTATION_INTENTS` is declared privately in both files with the same
   `DATA_MODIFY`, `LOCAL_VALUE_MODIFY`, and `BOOKKEEPING` contents. The pack uses
   it for `reads`; the matcher uses it for its gating/evidence logic. Before the
   split this was one declaration in one file. The two copies can now drift:
   changing the pack's declared dependencies without changing the matcher (or
   vice versa) silently changes what validation may read or how it classifies.

   Fix: keep one source of truth, for example an `internal val
   VALIDATION_READS` in `ValidationRule.kt` or a small shared validation file
   imported by both, and use it for both `reads` and the matcher. This is one of
   the places where the split was made by file size rather than by ownership.

4. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:50-87`
   with `.../server/live/*.kt` and `.../server/review/*.kt` — the file split
   widened almost all mutable server state from private to module-internal
   instead of extracting an owned state boundary.**

   The constructor's dependencies and every runtime map, executor, lock, cache,
   store, revision counter, and `configRevision` are now `internal` so that
   extension functions in `server.live` and `server.review` can reach them.
   Any class in the `server` module can now call `docs`, `analyses`,
   `publishedCandidates`, `candidateLocks`, `anchorLocks`, `sessionSkipped`,
   `configRevision`, etc. directly, bypassing the ordering and locking invariants
   the original file maintained. This is module-internal rather than public, so
   it is not an API leak, but it is a much weaker boundary than the one the
   original private fields provided. The same effect appears in the rulepack
   splits, where helpers such as `Evidence`, `Creation`, `initCallEvidence`,
   `bodyUnits`, and the data-access matcher helpers became `internal`; that is
   more defensible for small pack helpers, but it should be deliberate rather
   than an automatic consequence of moving code to another file.

   Fix: extract the shared state into an internal state/session object with
   narrow operations (`publish`, `removeDocument`, `recordDecision`, etc.) and
   let the `live`/`review` helpers call those operations. Keep only the existing
   explicit test hooks (`*BarrierForTest`) internal. Alternatively, put the
   helpers in the same file/package and keep the state private. If the current
   internal state is accepted as a deliberate tradeoff, say so in the ticket note
   and constrain future access by convention.

5. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:20`
   with `.../server/review/LiveServerCandidates.kt:8-11`,
   `.../server/review/LiveServerDecisions.kt:8-12`, and
   `.../server/review/LiveServerSuggestions.kt:5-8` — the new server packages
   depend on each other bidirectionally and do not match their contents.**

   `review` imports `live.DocState`, `DocumentKey`, `PublishedCandidate`,
   `relativePath`, `docsFor`, `republishUri`, and `nextAnalysisRevision`, while
   `live.publish` imports `review.publishCandidates`. That is a package-level
   cycle, not just a type-level one. The package names also do not describe the
   split: `review` contains suggestion execution and candidate publication as
   well as candidate decisions, while `live` contains analysis, publication, and
   config mutation/republish. A reader tracing `handleMessage` must follow
   extension functions across two mutually dependent packages, which is harder
   than the original one-file flow.

   Fix: make the dependency one-way. Put candidate publication with the live
   session/publish code and let the review/decision handlers call a single
   session operation, or move all `LiveServer` extension functions into one
   internal `server.session` package. If the two packages stay, name them by
   responsibility (for example `server.live` for document/analysis and
   `server.suggestions` for suggest/decision) and remove the `live -> review`
   import by having `publish` call a session method.

6. **`scripts/classify-kotlin-size.nu:3` (working tree) with the acceptance
   evidence for ticket 051 — the `just size` evidence is not reproducible from
   the committed tree.**

   The working tree changes `const suspicious = 200` to `300`, while the
   committed HEAD still has 200. The command used for the ticket therefore hides
   every 200-299 line file. The EXTREME conclusion is unaffected (the maximum is
   386 and the threshold is 400), but the "no EXTREME" result should be produced
   from the committed script, and a reviewer rerunning it on a clean checkout
   will see a different report. The directory report also still shows the
   `rulepacks-tests` root as OVERCROWDED (9 entries, unchanged from base), which
   needs either an explicit single-file-package exclusion note or one more
   grouping step.

   Fix: revert or commit the threshold change as its own deliberate change, then
   rerun `just size` from the tree that will be closed. Record in the ticket that
   the remaining `rulepacks-tests` root is accepted as single-file packages (or
   group those packages under a subpackage) so the acceptance is evidenced by
   the committed command output.

### Concurs / Dissents

- Concurs with the overall decomposition: the server split into config,
  document/analysis, candidates, decisions, and suggestions is substantially
  more navigable than the 963-line `LiveServer.kt`; the rulepack splits keep the
  pack, config, defaults, and rules visibly separate; the core/user lock split
  separates file I/O (`AnnotationFileStore`) from lock protocol
  (`AnnotationFileLock`/`AnnotationLockOwner`); and the suggest and test splits
  follow real responsibilities.
- Concurs that the frozen API surface and pack loading are preserved: package
  moves in `api` are file-level only, and the structural/data-access FQNs used by
  `META-INF/services` were deliberately kept stable. This is the reason the
  package/directory mismatch is a cleanup issue rather than a breaking change.
- Concurs that no detection logic was moved back into `core`; all pack split
  files remain under `:rulepacks`, and `:core` still does not depend on
  `:rulepacks`, `:suggest`, or a host.
- Dissents: findings 1-6.

### Residuals (not filed)

- The `analysis-engine` root and `core/core` root remain OVERCROWDED but were
  already over threshold at base and are not named in the ticket scope.
  `core/core` is 1 file plus 8 directories; reducing it would be a separate
  package-boundary decision, not a file split.
- `CallbackFacts`, `CallbackParameterParser`, and `CallbackTypeFacts` are now in
  a top-level `dev.cint.rulepacks.callback` package even though there is no
  callback rulepack; this is noted under finding 2 but may be acceptable as a
  shared-facts namespace if named `support`/`shared`.
- `LiveServerConfig.kt` contains publication helpers (`republishOpenDocs`,
  `republishUri`) in addition to config operations, and `LiveServerDocument.kt`
  contains both analysis and publication. The file names are close but not an
  exact responsibility boundary; this is part of finding 5's cohesion concern.
- No docs were updated in this diff. `docs/architecture.md` describes module
  dependencies rather than file layout, so the ticket does not require a change,
  but the new package conventions in finding 2 are undocumented.
- Static review only; no build, test, formatter, source edit, stage, or commit
  was performed by this role, per `docs/workflow.md`. The handover is assumed
  green.

## Round 2

Verdict: Findings 1-6 from Round 1 are resolved in the uncommitted fix diff. A
package/path scan now reports zero mismatches, the core/user package cycle is
broken through `core.user.layout.AnnotationLayout`, shared rulepack helpers are
in `intents.support`, validation has one read-set, server candidate publication
is in `live` with a one-way `suggestions -> live` dependency, and the retained
`LiveServer` state is documented as an explicit tradeoff. `just size` still
reports no EXTREME file, and `just dir-size` still reports only the three roots
recorded in the ticket. Two new documentation/convention issues remain: the
data-access entry point is under `.rules`, contrary to the ticket's stated
"entry point at the intent root" convention and the stale FQN in
`docs/data-access-intents.md`; and the ticket's justification for the remaining
`rulepacks-tests` root does not match the actual directory contents. The
implementation is otherwise sound and introduces no new package cycle,
visibility widening beyond the accepted tradeoff, or dependency-direction
problem.

### Finding status

1. **Resolved.** The new path/package scan reports no mismatches. Structural
   files now declare `.chaining`/`.localcopy`/`.returnvalue`, data-access
   declares `.config`/`.rules`, core/user declares `.lock`/`.support`/`.layout`,
   and host fixtures declare `.support`. The service-loader descriptor and test
   imports were updated to match. Concurs.
2. **Resolved.** Service and mutation have real `.rules`/`.defaults` packages;
   data-access has `.config`/`.rules`; structural packs have per-intent
   packages; shared callback and receiver helpers moved to
   `dev.cint.rulepacks.intents.support`. The new documentation issue about the
   data-access entry point is filed separately below rather than reopening this
   finding. Concurs.
3. **Resolved.** `VALIDATION_READS` is now a single internal declaration in
   `ValidationRule.kt:17`, used by both the pack's `reads` and the matcher. No
   duplicate remains. Concurs.
4. **Resolved as an accepted tradeoff.** The state remains module-internal, but
   the ticket note now records why and tells future work to route through
   narrow session operations. I found no new access beyond the existing
   `live`/`suggestions` helpers and test barriers. Concurs.
5. **Resolved.** Candidate publication is in `live/LiveServerCandidates.kt`;
   `LiveServerDecisions` and `LiveServerSuggestions` live in `suggestions` and
   import `live`, while no `live` file imports `suggestions`. The previous
   `live <-> review` package cycle is gone, and the package names now describe
   their responsibilities. Concurs.
6. **Resolved.** The threshold change to 300 and the three remaining
   OVERCROWDED roots are recorded in the ticket, and `just size` reports no
   EXTREME file (maximum 386 lines). The accuracy of the remaining-root note is
   filed below. Concurs with the size result.

### New findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/rules/DataAccessRulePack.kt:1`
   with `docs/tasks/051-size-hygiene-splits.md:60-64` and
   `docs/data-access-intents.md:107` — the data-access pack entry point
   contradicts the documented layout convention and the public registration
   doc still names the old FQN.**

   The convention note says larger packs place the public `XxxRulePack` entry
   point at the intent root, and service and mutation do exactly that. Data
   access instead puts `DataAccessRulePack` in `.rules`, so the public entry
   point now lives with the matcher implementations. The service descriptor was
   updated correctly, but `docs/data-access-intents.md` still claims the pack is
   `dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack`, which no longer
   exists. This leaves the one externally documented pack FQN wrong and the
   stated convention not actually followed.

   Fix: either move `DataAccessRulePack` to
   `dev.cint.rulepacks.intents.dataaccess` (and update the descriptor and
   `DataAccessConfigTest` imports) to match service/mutation and the stated
   convention, or amend the convention note and `docs/data-access-intents.md`
   to say the data-access pack intentionally stays with its rules and is
   `...dataaccess.rules.DataAccessRulePack`. Whichever is chosen, make the note
   and the registration doc agree with the descriptor.

2. **`docs/tasks/051-size-hygiene-splits.md:92-95` — the acceptance note for the
   remaining `rulepacks-tests` root does not match the actual directory.**

   The note says the root's entries are meaningful single-file test packages and
   lists eight names, omitting `errorhandling`. In the actual directory,
   `bookkeeping` has two files, `support` has three, `user` has two,
   `validation` has three subdirectories, and `intents` has seven
   subdirectories; only `composition`, `errorhandling`, and `logging` are
   single-file packages. The root is still OVERCROWDED with nine entries, so
   the "single-file packages" exclusion does not by itself justify accepting it.

   Fix: correct the note to list all nine entries and state the real reason the
   root is accepted (for example, the entries are cohesive package-level test
   groups and further nesting would not improve navigation), or add an explicit
   exception for this root. If the intention is to enforce the exclusion
   literally, group the non-single-file packages or reduce the root below the
   threshold instead.

### Concurs / Dissents

- Concurs with the Round 1 finding 1 resolution: the physical tree is now a
  real package tree, and the service descriptors and imports were updated with
  it.
- Concurs with the Round 1 finding 2 resolution: the rulepack layouts are now
  internally consistent and the shared helpers have one home; the only
  remaining discrepancy is documentation of the data-access entry point (new
  finding 1).
- Concurs with the Round 1 finding 3 resolution: validation reads have one
  source of truth.
- Concurs with the Round 1 finding 4 resolution as an explicitly documented
  tradeoff: the broader internal state is accepted, and the note constrains
  future access.
- Concurs with the Round 1 finding 5 resolution: candidate publication moved to
  `live`, `review` became `suggestions`, and the package dependency is now
  one-way.
- Concurs with the Round 1 finding 6 resolution: no EXTREME file remains and the
  remaining roots/threshold are recorded; new finding 2 is about the accuracy
  of that record, not the size result.
- Dissents: new findings 1-2.

### Residuals (not filed)

- `AnnotationLayout` is a public object in `dev.cint.core.user.layout` even
  though its only callers are in the same module. It could be `internal` to
  keep the new package from adding public surface; this is cosmetic because it
  exposes no more state than the previous public `DIRECTORY`/`FILE_NAME`
  constants.
- Static review only; no build, test, formatter, source edit, stage, or commit
  was performed by this role, per `docs/workflow.md`. The handover is assumed
  green.

Summary: Round 1 findings 1-6 are substantively addressed and the code now has
matching package directories, a single validation read-set, a one-way server
package dependency, documented internal state, and the same no-EXTREME size
result. Two small but real documentation/convention issues remain: the
data-access pack entry point and its public registration doc disagree with the
declared layout convention, and the ticket's explanation for the remaining
`rulepacks-tests` root misstates which entries are single-file. Fixing those
notes (or moving the data-access entry point) is enough to close; I do not see
a new architectural defect in the code itself.

## Round 3

Verdict: Both Round 2 findings are resolved, and I found no new architectural
finding. `DataAccessRulePack` is now at
`dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack`, matching service
and mutation and the ticket's stated convention; its config imports, the
service-loader descriptor, the rulepacks-tests imports, and the registration
doc all agree on that FQN. The ticket's `rulepacks-tests` note now describes the
remaining root as meaningful package grouping, names all nine entries including
`errorhandling`, and explains why it is not split further. `AnnotationLayout`
is internal. A full path/package scan still reports zero mismatches, `just size`
still reports no EXTREME file (maximum 386 lines), and `just dir-size` still
reports only the three accepted roots. The ticket can close.

### Finding status

1. **Resolved.** `DataAccessRulePack.kt:1` now declares
   `package dev.cint.rulepacks.intents.dataaccess`;
   `META-INF/services/dev.cint.api.RulePack` names
   `dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack`;
   `docs/data-access-intents.md:107` names the same FQN; and the config,
   matcher, test-fixture, and rulepacks-tests imports point at the matching
   `.config`/`.rules` packages. The private `DataAccessRule` implementation stays
   in the same file as the public pack, which is cohesive. Concurs.
2. **Resolved.** `docs/tasks/051-size-hygiene-splits.md:92-98` now records the
   remaining root as meaningful package grouping, lists all nine entries
   including `errorhandling`, notes that the multi-file groups were reduced
   internally, and explains that further nesting would only add a generic level.
   This is an accurate and sufficient acceptance rationale. Concurs.

### New findings

- None. The path/package scan reports zero mismatches, the server package graph
  is still one-way (`suggestions -> live`), the `core.user` cycle remains broken
  through the internal `AnnotationLayout`, and the retained `LiveServer` state is
  the documented tradeoff from Round 2 rather than a new widening. The
  data-access move introduced no duplicate entry point or stale import.

### Concurs / Dissents

- Concurs with the Round 2 finding 1 resolution: the data-access pack entry
  point, descriptor, imports, and registration doc are consistent again.
- Concurs with the Round 2 finding 2 resolution: the ticket note now describes
  the actual `rulepacks-tests` directory and gives a defensible reason for
  accepting it.
- Concurs with the size result: no EXTREME files, and the remaining OVERCROWDED
  roots are the explicitly recorded and accepted ones.
- Dissents: none.

### Residuals (not filed)

- Static review only; no build, test, formatter, source edit, stage, or commit
  was performed by this role, per `docs/workflow.md`. The handover is assumed
  green.

Summary: Round 2's two documentation/convention findings are fully addressed.
The data-access pack is back at the intent root with every descriptor, import,
and doc agreeing on the FQN; the rulepacks-tests acceptance note now matches the
directory and includes `errorhandling`; and `AnnotationLayout` is internal. A
repeat path/package scan is clean, the server and core/user dependency directions
are sound, and `just size` still shows no EXTREME files. I found no new issue,
so this review concurs and the ticket can close.
