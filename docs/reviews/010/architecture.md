# Architecture review — ticket 010 (core composition terminal)

## Round 1

Verdict: The new composition lives in `core`, is invoked after the strata, and
consumes only source-intent contributions exposed by `TerminalNode`; it emits
only `SCAFFOLD`/`DERIVATIVE_USE`, imports no parser/rulepack module, and contains
no logging/error/bookkeeping detection. The core/rulepack line and the terminal
node contract are therefore sound. Two identity/provenance issues remain:
composition mints contributor ids in the global `<packId>:<localName>` rule-id
namespace, which is not reserved, so a plugin can forge them (the test does); and
return categorization changes its contributor id based on whether the validation
pack was loaded, so composition provenance identity is pack-dependent rather than
stable. The legacy composition blocks still existing alongside the new path is
expected to be removed by 014 and is recorded as a residual, not a finding.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:29-32` — composition contributor ids are rule ids in the plugin namespace, and the namespace is not reserved.**
   Composition labels its output `core:return-validation`, `core:return-untagged`,
   `core:derivative-use` and `core:scaffold`, i.e. values shaped exactly like
   `<packId>:<localName>` rule ids (`architecture.md §3`), even though no such rules
   exist in the scheduler. Nothing in `RuleScheduler.rulesOf`/`ownersOf`
   (`core/scheduler/RuleScheduler.kt:95-122`) reserves the `core` pack id or rejects
   a rule with an id beginning `core:`, so a plugin can register `core:scaffold`
   without a load error. `Annotation.rule` is the first provenance rule
   (`api/Model.kt:47-49`), so a plugin's `SOURCE_INTENT` and core
   composition's `SCAFFOLD` can carry the same `rule` string; only the role
   distinguishes them. The test at
   `treesitter/src/test/kotlin/dev/cint/treesitter/CompositionTerminalTest.kt:342-374`
   demonstrates exactly this collision and treats it as acceptable, while
   `:337` asserts core ownership by the forgeable `startsWith("core:")` prefix.
   This weakens the "composition-only roles" provenance contract: core ownership
   is a naming convention, not a structural invariant, and the terminal now
   indirectly names/occupies the rule-id space.
   Suggested fix: give composition contributors an identity outside the plugin
   rule-id namespace (a separate contributor kind, a reserved non-rule prefix, or
   a reserved `core` pack id enforced by the scheduler), and change the tests to
   assert that identity/role structurally rather than via `startsWith("core:")`.
   A plugin rule using the reserved value should fail to load, not silently share
   the label.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:29-30, 131-144` — return categorization encodes the result in two different contributor ids, making provenance identity depend on the loaded validation pack.**
   `returnContributions` chooses `core:return-validation` when the return is on a
   validation guard's true edge and `core:return-untagged` otherwise, then chooses
   the matching intent. The same terminal node therefore changes its `rule`
   identity when the validation pack is added or removed, even though the ticket
   asks composition to add tags rather than change identity and
   `architecture.md §6` treats structured provenance as the stable answer to "why
   did this fire". A single composition contributor (for example a stable
   `core:return-category`) emitting `VALIDATION`/`UNTAGGED` intents would keep the
   contributor identity constant while still deriving the category from the CFG
   and source intents; the test at
   `CompositionTerminalTest.kt:435-441` currently pins the split ids.
   Suggested fix: use one stable contributor id for return categorization and let
   only the intent set vary; update the test to assert the stable contributor and
   the category intents.

### Concurs / Dissents

- Concurs: composition is in `:core` (`core/src/main/kotlin/dev/cint/core/composition/Composition.kt`)
  and depends only on `:api` plus core packages; it does not import `treesitter`,
  `frontend`, `lsp`, `rulepacks`, or any plugin implementation.
- Concurs: `TerminalNode` carries only `sourceIntents`
  (`core/scheduler/RuleScheduler.kt:62-64`), names no rule or pack, and
  `Composition.compose` uses that set to select the source-intent union
  (`Composition.kt:88-92`). Composition receives no `Rule`/`RulePack` and cannot
  branch on producer identity.
- Concurs: composition emits only `Role.SCAFFOLD` and `Role.DERIVATIVE_USE`
  (`Composition.kt:135-143, 195-235, 280-403`); `Rule.toContribution` hardcodes
  `Role.SOURCE_INTENT` (`api/Contract.kt:16-27`), so plugins cannot produce or
  remove composition roles. The `pluginRuleIdCannotProduceOrRemoveComposition`
  test confirms the role boundary (apart from the id collision in finding 1).
- Concurs: no detection logic leaked into core composition. The code references no
  logging/error/bookkeeping rule or pack; `VALIDATION` enters only as a source
  intent on the guard condition (`Composition.kt:146-159`), and the remaining
  return logic is CFG traversal plus rejection sentinels, which ticket 010 assigns
  to core composition.
- Concurs: pack order and pack split are irrelevant to the result; the source
  filter is by intent, not by producer id. `compositionIgnoresProducerRuleAndPackIdentity`
  and `packOrderAndSplitDoNotChangeComposition` in `CompositionTerminalTest.kt`
  exercise that property.
- Dissents: composition contributor identity is not structurally separated from
  plugin rule ids (finding 1).
- Dissents: return provenance identity changes with the validation pack rather
  than remaining a stable terminal contributor (finding 2).

### Residuals (not filed as findings)

- `core/.../rules/Blocks.kt` still contains `ReturnBlock`, `DerivativeUseBlock`
  and `IntentScaffoldBlock`, and `AnalysisEngine` still runs them, so two
  composition implementations and two return-role conventions (`SOURCE_INTENT`
  in legacy, `SCAFFOLD` in the new path) coexist. Ticket 014 is the named
  deletion point; it must land with the parity contract, and the parity test's
  current dependency on `AnalysisEngine`/`IntentBlocks` should disappear with it.
- `VALIDATION_INTENT = "VALIDATION"` is the one pack-owned intent name known to
  core. It is required by this ticket's return rule, but intent names are declared
  open (`architecture.md §2`); if a pack may rename or replace validation, this
  needs a reserved core intent or a config-declared relation rather than a literal.
- `Composition` is built on the legacy `RuleContext`/`AnalysisContext` and
  `IntentConfig` types. That is core-internal and acceptable here, but 012/013/014
  must either keep those core types alive or migrate composition with the rest of
  the engine.
- The composition test lives in `:treesitter` because it needs the parsers and
  fixtures; it is test-only and does not violate the production module boundary.

## Round 2

Verdict: Both round-1 findings are addressed. Composition contributor ids are now
centralized in `CoreContributors` and the scheduler rejects plugin rules under the
reserved `core:` prefix with a named load error, so the name space is structural
rather than a convention; return categorization now emits one stable
`core:return-category` contributor and varies only its intents. Composition
remains in core, consumes only terminal source intents, and emits only
`SCAFFOLD`/`DERIVATIVE_USE`. No new architecture finding.

### Findings

- F1: concurs — `CoreContributors` (`core/src/main/kotlin/dev/cint/core/CoreContributors.kt:3-8`)
  defines the reserved prefix and the only contributor ids composition uses, and
  `RuleScheduler.rulesOf` rejects any pack rule whose id starts with
  `CoreContributors.PREFIX` via `ReservedContributorId`
  (`core/src/main/kotlin/dev/cint/core/scheduler/RuleScheduler.kt:49-54, 104-110`).
  The collision is now a load error rather than a silently shared label; the
  scheduler test `reservedCoreContributorRuleIdFailsWithNamedError`
  (`core/src/test/kotlin/dev/cint/core/scheduler/RuleSchedulerTest.kt:113-123`)
  and the pipeline test `pluginRuleIdCannotUseTheCompositionNamespace`
  (`treesitter/src/test/kotlin/dev/cint/treesitter/CompositionTerminalTest.kt:359-375`)
  pin it. `compositionOutputIsCoreOwnedScaffoldOrDerivative` now checks membership
  in the declared core-contributor set instead of the forgeable prefix
  (`CompositionTerminalTest.kt:342-357`).
- F2: concurs — `Composition.returnContributions` now uses the single
  `CoreContributors.RETURN_CATEGORY` id for every return and varies only the
  intents (`core/src/main/kotlin/dev/cint/core/composition/Composition.kt:30, 131-143`);
  the old `RETURN_VALIDATION_RULE`/`RETURN_UNTAGGED_RULE` split is gone.
  `returnCategoryIsDerivedFromTheCfgAndValidationSourceIntents` and the
  sentinel test assert the stable id while reading category from the CFG and
  validation source intents (`CompositionTerminalTest.kt:393-511`).
- New findings: none.

### Concurs / Dissents

- Concurs: the reserved-prefix check runs before duplicate-id resolution
  (`RuleScheduler.kt:104-112`), so the error is deterministic and independent of
  pack order; `Scheduler.schedule` still delegates to `plan`, so every load path
  enforces it.
- Concurs: reserving `core:` does not add a rulepack dependency to the scheduler;
  `CoreContributors` is a core-local constant object, and rulepacks still depend
  only on `:api`.
- Concurs: the new pipeline tests cover the reserved namespace at both scheduler
  and `RuleEngine` levels, and the composition ownership assertions no longer
  rely on string prefixes.
- Concurs: the terminal contract is unchanged — `TerminalNode` still carries only
  `sourceIntents`, and composition still receives no `Rule`/`RulePack` and filters
  sources by intent (`Composition.kt:88-92`).
- Concurs: composition still emits only `SCAFFOLD`/`DERIVATIVE_USE`, and no new
  logging/error/bookkeeping detection or producer-name branching was introduced.
- Dissents: none.

### Residuals

- The legacy `rules/Blocks.kt` composition path and its `return-validation`/
  `return-untagged` contributor labels remain until ticket 014; the round-1
  residual is unchanged and is not re-filed.
- `VALIDATION_INTENT` remains the one pack-owned intent literal in core; still
  required by the ticket and unchanged from round 1.
- `core:` is now reserved in code but the reservation is not stated in
  `architecture.md §3`; a short note there (or in the pack-authoring docs) would
  keep third-party pack authors from discovering it only at load time.
- `Composition` still uses the legacy `RuleContext`/`AnalysisContext` and
  `IntentConfig`; 012/013/014 still need to preserve or migrate those core types.
