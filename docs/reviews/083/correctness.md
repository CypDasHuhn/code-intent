# Correctness review — ticket 083

Verdict: **FINDINGS**

1. **Checkpoint identity can discard prior state.** `FileInitCheckpointStore.load` returns `null` when the stored `workflowId` differs (`InitCheckpointStores.kt:76-78`), while `save` unconditionally overwrites the single file (`:82-119`); `Main.kt:53-55` derives the id from the absolute root. A moved repo, or any call supplying a different `workflowId`, starts a fresh checkpoint and erases steps/proposals. Fix: key checkpoint entries by workflow, or reject mismatched saves and use a stable repo id.

2. **Hypotheses can self-accept.** `KnowledgeInputs.patternHypothesis` honors client-supplied `status` (`KnowledgeInputs.kt:174`), and `patterns.propose` stores it (`McpRuntimeTools.kt:439-457`). An agent can propose `status: ACCEPTED` and immediately call `patterns.materialize` (`:491-505`), bypassing `patterns.validate` and the acceptance policy. Fix: force `SUGGESTED` on proposal; only validate transitions the status.

3. **`init.complete` bypasses lifecycle.** `init.complete` calls `ensureCheckpoint`, creating a checkpoint when none exists, and marks it `COMPLETE` regardless of unapplied proposals or missing steps (`McpRuntimeTools.kt:189-208,672-691`). It also accepts a checkpoint already `COMPLETE`. Fix: require an existing in-progress checkpoint, reject pending proposals/missing required steps, and reject or reopen changes after completion.

4. **Proposal identity is unstable and conflicts are unchecked.** `stableProposalId` hashes raw changes before `withServerTimestamps` (`McpRuntimeTools.kt:565-581,716-722`), so identical retries with different client timestamps/order mint new proposals. An explicit reused `proposalId` returns `idempotent` without comparing stored changes, silently dropping a different batch. Fix: canonicalize timestamps/order before hashing and verify explicit-id payloads or return a conflict.

5. **Applied state is not reconciled with knowledge.** `changes.apply` trusts only `checkpoint.appliedAt` (`McpRuntimeTools.kt:601-643`). If the knowledge file is missing or reset, applied proposals are never replayed; conversely, an old proposal containing removals can delete knowledge written after it was staged because removals have no revision check. Fix: persist a knowledge revision/timestamp with each proposal, replay idempotently on mismatch, and reject stale removals.

6. **Checkpoint updates are racy across sessions.** `InitCheckpointStore` exposes only `load`/`save` (`contracts/ports/InitCheckpointStore.kt:7-17`); `changes.propose` and `recordProgress` perform load-copy-save (`McpRuntimeTools.kt:584-590,699-713`). Two MCP processes on one repo can overwrite each other and lose proposals/steps. Fix: add a revision/compare-and-set or file lock and merge proposal/step additions.

7. **Repo status/index reports runtime, not repository, state.** `isInitialized` is `!closed` (`DefaultRuntime.kt:209-221`), so an empty repo reports initialized; `indexedPaths` is session-only (`:46,134`), so `repo.index` reports zero files after restart while `init.status` still lists analyzed paths. Fix: derive initialization from checkpoint/knowledge state and label or rehydrate index state.

The interrupted-init test does create a fresh runtime, store, and checkpoint reader, and asserts a stable annotation count and selector set, so it genuinely exercises restart/resume and duplication. It does not cover workflow-id collisions, self-accepted hypotheses, or a crash between knowledge persistence and checkpoint save. Overall the happy-path lifecycle works, but checkpoint identity, lifecycle guards, and proposal/apply reconciliation need correction before the workflow is trustworthy.

## Round 2

- **CONCUR** stable checkpoint identity/merge/lock: `Main.kt:54` uses a fixed id, `InitCheckpointStores.kt:97-143` merges per-workflow entries/revisions, and `:148-169` holds a file lock. Caveat: `McpInitWorkflowTest.kt:371` exercises sequential, not concurrent, stores.
- **CONCUR** `patterns.propose` forces `SUGGESTED` (`McpRuntimeTools.kt:479`) and `patterns.validate` guards transitions (`:507-519`). **DISSENT**: `changes.propose/apply` still decodes raw `SetPatternHypothesis` and applies it through `setPatternHypothesis` (`McpRuntimeTools.kt:626-627,727`; `KnowledgeChangeApplier.kt:28-30`), so `ACCEPTED` can bypass `patterns.validate`.
- **CONCUR** completion now requires an existing checkpoint, no pending proposals, and named steps (`McpRuntimeTools.kt:172-205`). Caveat: a no-op `defaults.materialize` records the step (`:340-345`), so `requiredInitSteps` (`:837-845`) can pass without materializing defaults.
- **CONCUR** canonical proposal ids: timestamps/order are normalized before hashing (`McpRuntimeTools.kt:641-642,889-953`) and explicit-id conflicts are rejected (`:650-659`).
- **DISSENT** reconciliation timestamps: `baseKnowledgeTimestamp`/`appliedKnowledgeTimestamp` are written (`McpRuntimeTools.kt:669,1079`) but never read. `proposalReflected` omits confidence/evidence for annotations and several hypothesis fields (`:962-1003`), so partially matching proposals are marked applied without applying them.
- **CONCUR** content-hash resume (`McpRuntimeTools.kt:248-290`); caveat: hashes ignore language/options. **CONCUR** `repo.index` union/status (`:782-811`; `DefaultRuntime.kt:219-235`), runtime-only defaults (`McpRuntimeTools.kt:324-337`), and `patternId`/evidence linkage (`KnowledgeInputs.kt:199-204`).

Verdict: **FINDINGS**. Most round-1 issues are materially fixed; the remaining acceptance bypass via `changes.apply` and false-positive reconciliation are correctness blockers. No new critical regression found.

## Round 3

- **CONCUR** policy bypasses: `changes.propose/apply` reject non-`SUGGESTED` hypotheses and `MaterializeDefaults` (`McpRuntimeTools.kt:643,710,988-1005`), and `KnowledgeChangeApplier.kt:29-45` rejects them again.
- **CONCUR** reconciliation: `appliedKnowledgeTimestamp` is read (`McpRuntimeTools.kt:718`), and confidence/evidence/scope/examples/counterexamples are compared (`:1021-1061`).
- **CONCUR** resume fingerprints include language and sorted options (`McpRuntimeTools.kt:257,891-909`).
- **DISSENT** the 8-thread checkpoint test cannot pass under the current lock: `FileChannel.lock()` (`InitCheckpointStores.kt:163`) throws `OverlappingFileLockException` across threads in one JVM, which `withLock` turns into failure; the 80-update assertion (`McpInitWorkflowTest.kt:416-459`) is likely flaky/failing. Add a JVM-level mutex or retry on overlap.
- **DISSENT** completion now records `defaults.materialize` only when the store was actually created (`McpRuntimeTools.kt:344-348`), but `requiredInitSteps` still demands that exact step (`:847-853`). A repo with pre-existing knowledge records `defaults.skipped`, so init can never complete there.

Verdict: **FINDINGS**. The policy and reconciliation fixes hold, but checkpoint concurrency and completion on existing knowledge remain incorrect.

## Round 4

- **CONCUR** checkpoint locking: per-path `ReentrantLock`, `FileChannel` lock, and `OverlappingFileLockException` retry (`InitCheckpointStores.kt:154-190`); the 8-thread test now synchronizes start and asserts 80 unique steps (`McpInitWorkflowTest.kt:417-464`).
- **CONCUR** defaults classification: `materialize/skipped/none/unmet` (`McpRuntimeTools.kt:344-350`); completion accepts the first three and rejects `unmet` (`:855-864`), fixing the existing-knowledge deadlock. Tests cover skipped and unmet (`McpInitWorkflowTest.kt:467-522`).
- **CONCUR** no-root limitation documented (`Main.kt:29-33`; `DEFERRALS.md:23-25`). Caveat: the stderr warning fires whenever `--root` is absent even when `--checkpoint` is supplied (`Main.kt:29`), so it can falsely claim in-memory state.

Verdict: **PASS** with the warning caveat. No new correctness blocker found.
