# Architecture Review — Ticket 063

## Verdict: FINDINGS

1. **Cross-ticket edit to `contracts` (065-owned).** `contracts/.../facts/IndexTypes.kt:11,19`, `knowledge/KnowledgeSnapshot.kt:15,17`, `util/Errors.kt:41-86`, and `commonTest/.../SerializationTest.kt` are modified in the working tree. Ticket 063 is an empty skeleton with "No production analysis logic"; these are contract/API and serialization semantics owned by 065. Revert them from 063, or defer them to 065/a named ticket so 063's change set is limited to build and guard files.

2. **Module-to-directory mapping is triplicated.** `settings.gradle.kts:23-39`, `build.gradle.kts:230-250` (`ModuleLayout.directories`), and `graph-guard.json:2-20` each hand-maintain the module set. Drift is unchecked. Derive the mapping from Gradle's project registry/settings, and have the guard assert that `graph-guard.json` modules equal the declared projects; keep only allowed edges in JSON.

3. **Unregistered modules escape the guard.** `build.gradle.kts:251-260` reads only `config.modules`, while `settings.gradle.kts` is a separate list. `native-spike/` exists on disk but is in neither, so adding it to settings would bypass the graph until JSON is updated. Enumerate projects from Gradle and fail on any not in config; register or remove `native-spike`.

4. **Test-module edges are a second, undocumented source of truth.** `graph-guard.json:41-66` declares `integration-tests` depending on every module plus `test-fixtures`, but `module-graph.md:102-159` documents no test modules or edges. The guard also scans `testImplementation` identically to `implementation` (`build.gradle.kts:69-94`), so test-only edges in production modules are rejected without a stated policy. Add test modules and a test-edge policy to `module-graph.md`, or separate test configurations in the guard.

5. **The guard self-test lives in production build logic.** `build.gradle.kts:286-400` embeds a hand-rolled assertion harness in the root build and wires it into every `check`. This is test code in the production build script, not a test module or build-logic test, and `GraphGuardValidator` is not independently testable. Extract the validator into build-logic (included build) with real unit tests; keep the root task a thin invocation.

6. **Declared "must not depend on" constraints are unenforced.** `module-graph.md:143-159` forbids JVM types, filesystem, parser, LSP, and adapter classes in `core`, `index`, and others, but the guard only checks `project(...)` edges, and every module except `contracts` uses `kotlin("jvm")` (`build.gradle.kts:24-30`). `core` can freely import `java.nio.file` or adapter types. Make `core`/`index`/`runtime` KMP/native as the doc implies, or add forbidden-import checks and state that 063 enforces module edges only.

7. **Six-group layout and the flat tree conflict.** `docs/tasks/063-v2-build-skeleton.md:15` requires contracts/analysis/sources/orchestration/apps/tests groups, but only `apps/` and `tests/` are grouped; `module-graph.md:66-89` shows the flat tree now implemented. Amend the ticket to the documented flat tree or add the group directories, and record the decision in `module-graph.md`.

Summary: canonical project names, app/test nesting, declared module edges, and V1 isolation are correct, and no V2 module depends on a V1 artifact. The main architecture risks are duplicated module/layout truth, unguarded test-module policies, a test harness embedded in the production root build, and the gap between the doc's non-JVM boundary rules and the JVM-only skeleton; the working tree also crosses into 065's contracts surface.

## Round 2

1. **Finding 1 — DISSENT.** The contracts edits remain in the diff (`IndexTypes.kt:11,19`, `KnowledgeSnapshot.kt:15,17`, `Errors.kt:41-86`, `SerializationTest.kt`), and 065 is already committed at `ca01458`. Deferring to 065 without moving these out of 063's working tree still leaves 063 carrying 065's contract surface.
2. **Finding 2 — CONCUR.** `ProjectGraphCollector.collect` (`analysis-engine-v2/build.gradle.kts:53-80`) derives modules and dependencies from `root.allprojects`; `graph-guard.json` now declares only `allowedEdges`, and `ModuleLayout` is gone. The single-source requirement is met.
3. **Finding 3 — CONCUR.** The guard enumerates all Gradle projects and rejects unknown dependency modules and missing build files (`buildSrc/src/main/kotlin/v2/graphguard/GraphGuard.kt:51-73`). `native-spike` is outside `settings.gradle.kts`, so it is not a build module.
4. **Finding 4 — DISSENT.** `graph-guard.json:28-43` still declares `integration-tests` edges absent from `module-graph.md:102-159`, and all configurations are scanned, so `testImplementation` remains indistinguishable from production edges. The open question at `module-graph.md:424` is still unresolved; record the policy or the deferral ticket in the doc.
5. **Finding 5 — CONCUR.** The validator now lives in `analysis-engine-v2/buildSrc/src/main/kotlin/v2/graphguard/GraphGuard.kt` with JUnit tests at `buildSrc/src/test/kotlin/v2/graphguard/GraphGuardValidatorTest.kt`; the root task is a thin Exec (`build.gradle.kts:108-114`).
6. **Finding 6 — DISSENT.** The guard still checks only project edges, while `core/build.gradle.kts:1-3` and the root `subprojects` block (`build.gradle.kts:28-30`) keep production modules JVM-only. The doc's no-JVM/filesystem/parser rules remain unenforced.
7. **Finding 7 — CONCUR.** `docs/tasks/063-v2-build-skeleton.md:15` now records the flat tree from `module-graph.md`.

Round 2 verdict: findings 2, 3, 5, and 7 are closed; findings 1, 4, and 6 remain. The guard is now correctly layered and derives its module model from Gradle, but the diff still crosses into 065's contracts, test-edge policy is undocumented, and the JVM-only skeleton cannot enforce the declared non-JVM boundaries.

## Round 3

**CONCUR.** Finding 4 closes: `docs/tasks/063-v2-build-skeleton.md:37` records the all-configuration test-edge policy, marks the `integration-tests` edges intentional, and requires future test-only edges to update `allowedEdges`. Finding 6 closes by explicit deferral: `:38` assigns non-JVM boundary enforcement to `docs/tasks/070-v2-core-engine.md`, with the JVM-only empty-skeleton rationale. Finding 1 is now owned and dispositioned at `:39` (065 merges first; 063 drops its redundant contracts hunks). No new production or build code changed since Round 2; the diff is the same build/guard files plus 065's contracts hunks. Round 3 verdict: CONCUR.
