# Correctness review — ticket 002 (annotation store and provenance)

## Round 1

Verdict: The store mechanics are right. `AnnotationStore` keys each rule's whole
output, swaps it every iteration, unions intents across rules, keeps every
per-contributor entry, sorts provenance deterministically, and has no `remove`
method; the artifact and live payloads drop the concatenated rule string and emit
the structured `provenance` array. Two gaps remain at the boundary between the
store and the rules it serves: the mutate/replacement/clear-dirty surface is
handed to `RuleBlock.apply`, so a rule can still erase or reclassify another
rule's contribution, and the replacement key (`RuleBlock.id`) is not unique for
legal configs, so per-rule replacement can silently discard an intent's output.

### Findings

1. **analysis-engine/core/src/main/kotlin/dev/cint/core/engine/Engine.kt:147-163 (with `RuleBlock.apply` at :28-34 and the loop at :233-240) — the rule-facing context exposes store mutation, so "no removal API" is not enforced.**
   `AnalysisContext` still has public `replaceRule(rule, contributions)` and
   `clearDirty()`, and the same instance is passed to every `RuleBlock.apply`.
   A rule can therefore call `ctx.replaceRule("<other-rule-id>", emptyList())`
   to delete another rule's output, or `ctx.replaceRule("<other-rule-id>",
   listOf(...))` to reclassify it, and a phase's later blocks cannot restore an
   earlier phase's erased contributions (SOURCE finishes before
   DERIVATIVE/SCAFFOLD run). `ctx.clearDirty()` inside `apply` also clears dirty
   set by earlier blocks in the same iteration, so `runPhase` can exit the
   fixpoint early. `AnnotationStoreTest.hasNoRemovalApi:122` and
   `oneRuleCannotShrinkAnotherContribution:57` only exercise the store directly;
   the new `replaceRule` API is exactly as powerful as the removed `remove` and
   is reachable from rules, so the acceptance claim ("no removal API; a test
   asserts one rule cannot shrink another's contribution") does not hold at the
   interface rules actually receive.
   Fix: keep per-rule replacement framework-private. Give rules a read-only
   context (the eventual `View`) and move `replaceRule`/`clearDirty` off the
   object passed to `apply` (e.g. an internal `AnalysisStore`/mutation handle
   owned by `AnalysisEngine`, or at minimum `internal` members that cannot be
   reached through `RuleBlock`). Add a test with a block that calls the old
   mutation surface for another rule's id and with `clearDirty`, and assert the
   other contribution survives and the fixpoint still runs.

2. **analysis-engine/core/src/main/kotlin/dev/cint/core/engine/Engine.kt:236 and analysis-engine/core/src/main/kotlin/dev/cint/core/rules/Blocks.kt:719-743 — the replacement key is not unique, so two intents reusing a block type overwrite each other.**
   `IntentBlocks.build` creates one block instance per `(intent, blockName)` and
   every `PrintBlock`/`LoggerInteractionBlock`/`LoggingStateBlock`/`ValidationBlock`/
   `ErrorHandlingBlock` instance returns the same fixed `id` ("print",
   "logger-interaction", ...). The engine calls `ctx.replaceRule(block.id, ...)`,
   so a config with two intents that both list, say, `print` produces two blocks
   with id `print`; the second `replaceRule` removes the first's entire output,
   and only the last intent's contributions survive. The old `emit` path unioned
   both (same span) or kept both (different spans), so this is a data regression
   for custom configs, which are a supported path (`loadIntentConfig`, CLI/server
   `--config`). The bundled `intents.yaml` happens not to trigger it only because
   no block name is reused.
   Fix: give every configured block a unique identity before it reaches the
   store, e.g. qualify `id` with the owned intent (`"print:LOGGING"`) or key the
   replacement by the block instance/owned intent rather than a bare shared
   name; add a config-level test with two intents sharing one block type that
   asserts both intents' contributions and provenance entries remain.

### Concurs / Dissents

- Concurs: `AnnotationStore.annotations()` unions intents and keeps one
  provenance entry per contributor, including byte offsets, evidence, source and
  confidence, and `provenanceRoundTripsEveryContributorField` and
  `mergesUnionAndKeepsBothProvenanceEntries` pin that.
- Concurs: whole-output replacement drops stale fragments when a rule changes
  its shape (`changedOutputShapeLeavesNoStaleFragments`,
  `changedSplitShapeReplacesTheWholePreviousContribution`), and an empty output
  removes only that rule's bucket (`oneRuleCannotShrinkAnotherContribution`).
- Concurs: ordering is deterministic (`normalize` plus the final
  `compareBy(startByte, endByte, role)`), and intents are emitted sorted, so the
  emitted JSON is stable for equal inputs.
- Concurs: the concatenated `rule` string is gone from `Artifact.render` and
  `AnnotationPayload.annotationJson`; both publish the full `provenance` array
  and the updated tests assert it.
- Dissents: "no removal API" and "monotone rules" are stated as invariants of the
  store, but the API the rules receive still lets any rule rewrite or clear any
  other rule's bucket (finding 1).
- Dissents: the per-rule replacement contract assumes a globally unique rule id
  (§2/§3), but `RuleBlock.id` is fixed per block class, not per configured rule
  (finding 2).

### Observations (not filed)

- `ValidationBlock` no longer emits its `Role.SCAFFOLD` `validation-guard-block`
  contribution (Blocks.kt:296-304 in HEAD). This is a real output change, not a
  store refactor: for a validation guard whose body has no `IntentScaffoldBlock`
  scaffold (e.g. an explicit `cint:validation` guard whose exit is `break`/
  `continue`, or equal branches with no real intent), the whole-if scaffold that
  the old engine kept is now absent. Removing it is consistent with composition
  owning scaffolds and avoids a whole-guard scaffold coexisting with split
  ranges under the new no-cross-rule-removal semantics, but no test pins the new
  output; a follow-up (010/016) should confirm the renderer does not need it.
- `AnalysisEngine.runPhase` keeps the legacy `first || readsAnnotations` gate, so
  this ticket does not change which blocks re-run; correctness of that gate is
  owned by 003/004, and the review only checked that the dirty flag it depends on
  is set/cleared coherently.

## Round 2

Verdict: Both round-1 findings are addressed. Rules now receive a read-only
`RuleContext` while replacement, dirty tracking and the store itself are owned by
`AnalysisEngine`, and the store key is per block instance rather than the
non-unique `RuleBlock.id`. Store semantics, provenance merging and deterministic
ordering are unchanged from round 1 and still correct.

### Findings

- F1: concurs — `RuleBlock.apply` now takes `RuleContext`
  (`analysis-engine/core/src/main/kotlin/dev/cint/core/engine/Engine.kt:29-62`),
  which exposes only facts and annotation reads. `replaceRule`, `clearDirty` and
  `isDirty` were removed from `AnalysisContext` and moved to the private store;
  `runPhase` owns the store and performs replacement
  (`Engine.kt:252-269`). `RuleBoundaryTest.ruleContextIsReadOnlyAndFixpointStillRuns`
  reflects over the runtime context for `replaceRule`/`clearDirty`/`isDirty`/
  `emit`/`emitAll`/`remove`, asserts none are present, and checks a two-pass
  block still receives a second application, so the fixpoint/dirty path is
  exercised. No rule-reachable mutation path remains.
- F2: concurs — `AnalysisEngine` assigns each block instance a unique key
  (`"${block.id}#$index"`, `Engine.kt:228-231`) and `runPhase` replaces by that
  key (`Engine.kt:264`). `RuleBoundaryTest.twoIntentsSharingABlockTypeKeepBothContributions`
  builds two intents that both configure `print` and asserts the call-site
  annotation carries both intent sets and two `print` provenance entries, so the
  former overwrite is now covered.
- New findings: none.

### Residual observation (not a finding)

- The `ValidationBlock` scaffold-removal observation from round 1 is unchanged
  and still has no test pinning the output for guards that `IntentScaffoldBlock`
  does not scaffold; it remains deferred to 010/016 rather than a 002 defect.
- The new block keys are positional rather than semantic, but they are
  deterministic for a given `blocks` list and never leave the engine, so this is
  not a correctness issue.
