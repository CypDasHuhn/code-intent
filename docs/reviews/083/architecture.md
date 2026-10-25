# Architecture review — ticket 083

Verdict: **FINDINGS**

1. **Checkpoint identity is machine-path-derived and single-slot.** `Main.kt:50-52` uses the absolute root as `workflowId`; `InitCheckpointStores.kt:76-78,188-193` returns null on mismatch and saves one document. Repo moves lose resume state; an explicit id overwrites the prior checkpoint. Fix: use a stable id or key entries and reject collisions.

2. **Skip state ignores content and duplicates status.** `McpRuntimeTools.kt:217-231` skips checkpointed paths without a content hash/epoch, so edited files are never reanalyzed; `DefaultRuntime.kt:46,134,185,218` keeps a separate in-process `indexedPaths` that is empty after restart while `init.status` reports paths analyzed. Fix: persist hash/epoch, skip only on match, and choose one status source.

3. **Repo-wide analysis records nothing.** `McpRuntimeTools.kt:215-248` with no `targetPaths` passes empty `targets`; `recordProgress` (`:693-714`) no-ops, so whole-repo init cannot resume. Fix: persist paths from the analysis/runtime result or require targets.

4. **Proposal ids are unstable.** `McpRuntimeTools.kt:565-575,716-722` hashes client timestamps before normalization, so identical retries create new proposals; explicit-id reuse with different content returns `idempotent` unverified. Fix: canonicalize before hashing and verify explicit ids.

5. **Client defaults bypass the pack boundary; validation is duplicated.** `McpRuntimeTools.kt:273-291` accepts arbitrary `PackDefault`, allowing minted `PACK_DEFAULT` provenance; `KnowledgeInputs.kt:181-208` duplicates `KnowledgeValidation.kt:40-48` and is unused. Fix: materialize only runtime-suggested defaults/validate pack identity, and delete the dead parser.

6. **File adapter ownership is duplicated in the app.** `InitCheckpointStores.kt:82-118,196-207` reimplements atomic write/move already in `knowledge-persistence/.../KnowledgeStoreFiles.kt:46-90`, so CLI/server cannot share durable checkpoints. Fix: move the adapter into a persistence module behind the port.

7. **Pattern conversion loses linkage and is not idempotent.** `McpRuntimeTools.kt:491-541` records no hypothesis evidence/link, does not constrain targets to scope, and repeat conversions overwrite timestamps and later edits. Fix: carry evidence/provenance, validate targets, and make re-conversion a no-op.

Boundaries otherwise hold: MCP imports only `contracts`/`runtime` plus JSON/IO, no `core`, adapter, or `ai` imports; materialization goes runtime → knowledge; no in-house AI harness runs. The inline scripted client in `McpInitWorkflowTest` is not a reusable integration fixture, so 084 must extract it. Findings 1-7 should be resolved before 084 parity and 085 cutover.

## Round 2

CONCUR 1 — `Main.kt:118` now uses the stable `"repository"` id; `knowledge-persistence/.../InitCheckpointStores.kt:49-70,112-140` keys by workflow id, merges via `update`, and adds revision plus file lock/atomic write. Residual: a no-root invocation still selects the in-memory store (`:246-256`), so default MCP restarts lose state.

CONCUR 2 — `contracts/ports/Runtime.kt:50-52`, `DefaultRuntime.kt:107-115`, `Index.kt:36-40`, `InMemoryIndex.kt:398-408`, and `McpRuntimeTools.kt:238-290` persist and compare `analyzedHashes`. Runtime and checkpoint path sets still coexist, but `repo.index` reports both and labels the source (`:776-798`).

CONCUR 3 — empty `targetPaths` is rejected (`McpRuntimeTools.kt:237-241`) and required in the schema (`McpCatalog.kt:60`).

CONCUR 4 — `canonicalChanges` normalizes timestamps/order before hashing and comparing (`McpRuntimeTools.kt:641-654,881-940`); explicit-id conflicts return `AlreadyExists`.

DISSENT 5 — the dedicated handler now uses `runtime.suggestDefaults` (`McpRuntimeTools.kt:324-334`) and the client parser is gone, but `changes.propose` still decodes arbitrary `MaterializeDefaults` (`:622-660`) and `changes.apply` executes it, so `PACK_DEFAULT` provenance can still be minted. Enforce this in knowledge/mutation validation, not only the tool handler.

CONCUR 6 — the adapter and shared `AtomicFileWrites` moved to `knowledge-persistence`; MCP imports no adapter and `graph-guard.json` is unchanged. Residual: `module-graph.md:96-98,230-231` still describes `knowledge-persistence` as knowledge-JSON-only, and `runtime/InitCheckpointStores.kt:6-15` now re-exports the checkpoint factory.

DISSENT 7 — `patternId`/evidence and no-op reconversion are present (`KnowledgeInputs.kt:180-207`, `McpRuntimeTools.kt:544-616`), and FILE/path targets validate; however `changes.propose` still accepts `SetPatternHypothesis` with `ACCEPTED`, bypassing `patterns.validate`, so conversion policy lives only in the tool wrapper.

## Round 3

CONCUR 5 — `McpRuntimeTools.kt:643,710,988-1005` rejects non-`SUGGESTED` `SetPatternHypothesis` and all `MaterializeDefaults` during propose and apply; `KnowledgeChangeApplier.kt:29-45` enforces the same at the mutation layer. `DefaultRuntime.materializeDefaults` still delegates directly to the store, so legitimate materialization is unaffected.

CONCUR 7 — the accepted-pattern bypass is closed: `patterns.validate` writes directly (`McpRuntimeTools.kt:529`), while generic change proposals cannot carry `ACCEPTED`/`ESTABLISHED` hypotheses; conversion retains `patternId`, evidence, and no-op reconversion.

CONCUR 6 — `module-graph.md:95-99,230-235` now documents checkpoint persistence and the runtime facade; `runtime/InitCheckpointStores.kt:5-11` carries the KDoc. MCP still imports no core, adapter, or AI packages, and the graph guard is unchanged.

Residual: a no-root MCP invocation still selects the in-memory checkpoint store (`knowledge-persistence/.../InitCheckpointStores.kt:255-263`); document or fix before 085.
