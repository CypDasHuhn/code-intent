# Correctness review — ticket 006 (logging rulepack)

## Round 1

Verdict: The three internal matchers reproduce the legacy logging behavior on the
covered paths. `print`, `logger-interaction`, and `logging-state` mirror
`PrintBlock`, `LoggerInteractionBlock`, and `LoggingStateBlock` in matcher order,
receiver/qualifier gating, oracle fallback/veto, source, confidence, evidence, and
emitted span; argument evaluation order and the `language.methods.isNotEmpty()`
guard are preserved; configured call names and qualifiers are matched with the
same exact/`endsWith` rules. The rule owns only `LOGGING`, declares no reads, and
returns a pure contribution list. Two issues remain: factory-property discovery
uses a broader node-containment fallback than the legacy call-path lookup, which
can bypass the oracle veto, and the implementation expands the frozen `View`
contract without recording that API decision.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/LoggingRulePack.kt:217-229` — `callPath` can classify a property as a logger factory from a contained call rather than the initializer's own call.**

   The first lookup is an exact `node` span match, but the fallback accepts any
   call whose node is contained in the initializer:
   `calls.firstOrNull { it.node.startByte >= node.startByte && it.node.endByte <= node.endByte }`.
   The legacy code called `ctx.syntax.callPath(initializer)`, which resolves the
   path of that expression and does not promote an arbitrary nested call to the
   property's initializer path. With a non-call initializer such as
   `private val logger = if (debug) LoggerFactory.getLogger("x") else fallback`,
   the new fallback returns `LoggerFactory.getLogger` and puts `logger` in
   `loggerSymbols`. A later `logger.info(...)` then matches
   `call-on-logger-symbol` at confidence 0.8 without consulting the oracle, so a
   resolved non-logger type -- which should veto the `receiver-name` heuristic --
   is never seen. This is a behavior divergence and a false-positive path, not
   just a refactor.

   Suggested fix: resolve the initializer exactly as the legacy call-path lookup
   did (accept only the call/site whose span corresponds to the initializer, or
   add a `View` fact for the initializer call path backed by
   `SyntaxModel.callPath`), and add a regression test with a non-call initializer
   containing a factory call plus a `FixedOracle` resolving the receiver to a
   non-logger type; assert no logging hit.

2. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Contract.kt:32-39` and `analysis-engine/api/src/main/kotlin/dev/cint/api/Facts.kt:79-83` — the frozen `api` surface is expanded without a recorded contract decision.**

   Ticket 006 consumes three new `View` members (`language`, `typeAt`,
   `guardConditions`) and a new public `TypeRef` fact type. `View` is the frozen
   plugin contract from ticket 001, architecture §5 does not list these members,
   and architecture §9 says the `api` surface changes only deliberately and with
   versioning. The additions also force every non-core `View` implementation
   (including the test doubles updated here) to add members, and `TypeRef`
   duplicates the core `TypeFact` shape (`fqName`, `source`, `confidence`).
   Nothing in the ticket or architecture records whether language access, oracle
   type access, and loop/guard fact access are now part of the permanent plugin
   contract or are implementation helpers.

   Suggested fix: either record the deliberate contract extension (update the
   ticket/architecture §5 and state the versioning consequence), or keep these
   helpers out of the frozen `View` and expose them through a narrower
   pack-facing facility. If `TypeRef` is retained, state why the existing fact
   shape is not reused or moved onto `api`.

### Concurs / Dissents

- Concurs: the `print` matcher is behaviorally equivalent to `PrintBlock`: same
  configured call names, same receiver-in-qualifier gate, same
  `call.site` emission, source `treesitter`, confidence 1.0, and same trailing
  lambda span expansion via `CallFact.site`.
- Concurs: the `logger-interaction` heuristic order matches legacy
  (`call-on-logger-symbol`, `receiver-name`, `qualified-static`,
  `name-import-gated`); receiver-name and import-gated matches consult the
  oracle, a resolved non-matching type vetoes, an unresolved type falls back,
  and factory/qualifier matches skip the oracle. Source and confidence are taken
  from the type fact on typed matches, as before.
- Concurs: `logging-state` uses the same configured guard names, the same
  case-insensitive substring test, the same guard spans, and the same
  `treesitter`/0.7 values. `guardConditions()` is backed by the existing
  `SyntaxModel.guardConditions`, including loop conditions.
- Concurs: the rule contributes only `setOf("LOGGING")`, its `reads` is empty and
  it never calls annotation/intent accessors, and the matcher union contains no
  store mutation.
- Concurs: the pack declares one rule at id `logging:logging`, and the pack-local
  Kotlin/JS/TS/C# defaults match the corresponding `intents.yaml` values for
  calls, qualifiers, methods, types, factories, receiver names, guard names, and
  imports.
- Dissents: finding 1 (factory-property discovery is broader than the legacy
  call-path semantics and can bypass the oracle veto).
- Dissents: finding 2 (frozen `api` expansion is unrecorded and unversioned).

### Residuals

- The parity test covers `testing-grounds/` only; no golden fixture directory or
  `tags.json` fixture exists in this worktree, so the acceptance criterion
  mentioning golden fixtures is not exercised here. That is detection-fidelity
  scope, not a re-review of this correctness pass.
- `LoggingConfig.forLanguage` silently falls back to the Kotlin config when a
  language key is absent. The default map covers every supported language and
  pack `languages` is derived from its keys, so this is not reachable through the
  default pack, but a partial custom config would apply Kotlin detection to
  another language rather than yielding no configuration.
- `BundledRulePacks.packs()` is still empty; pack discovery/registration is
  ticket 011 and host cutover is 015, so this is not a 006 defect.
- The C# pack keeps the legacy empty `types` list; consequently an LSP-resolved
  C# logger type cannot positively match and a resolved non-logger type vetoes
  the `_logger` name heuristic. This preserves current behavior and belongs to
  the config migration/detection-fidelity tickets, not this change.

## Round 2

Verdict: F1 is addressed. `callPath` now accepts only the call whose node span
exactly matches the initializer, and the new regression case covers the
contained-call/non-call-initializer path with an oracle veto. The fixture parity
loop now also runs deterministic logger and non-logger oracles, and the dead
`vue` config entry has been removed. F2 is not addressed in the tree: the `api`
surface is still expanded and no ticket, architecture, or API-version record was
added. No new correctness defect was found.

### Findings

- F1: concurs — `LoggingRulePack.kt:208-219` resolves only an exact
  `it.node` span match and returns null instead of selecting a contained call, so
  a non-call initializer such as an `if` expression no longer promotes a nested
  `LoggerFactory.getLogger(...)` to the property's factory path.
  `nonCallInitializerFactoryCallDoesNotBypassOracleVeto`
  (`LoggingRulePackTest.kt:243`) validates old and new engines under
  `FixedOracle(Service)`; the new engine no longer reaches
  `call-on-logger-symbol` without the oracle. The exact-match behavior still
  covers the normal factory initializers in `testing-grounds/`.
- F2: dissents — the round-1 contract finding is not addressed in the worktree.
  `Contract.kt:32-39` still adds `language`/`typeAt`/`guardConditions` to the
  frozen `View`, `Facts.kt:79-83` still adds `TypeRef`, and neither
  `docs/tasks/006-logging-rulepack.md` nor `docs/architecture.md` records the
  extension or its versioning consequence. A round-2 prose note is not an
  in-tree contract record; if the extension is deliberate, add the decision to
  the ticket/architecture (and state why `TypeRef` is needed rather than reusing
  the existing fact shape) or keep these helpers outside the frozen `View`.
- New findings: none.

### Concurs / Dissents

- Concurs: the parity test now exercises `NullOracle`, a resolved logger type,
  and a resolved non-logger type against legacy for every fixture
  (`LoggingRulePackTest.kt:108-119,164-181`), which covers the oracle
  fallback/veto and source/confidence propagation paths that round 1 identified
  as untested.
- Concurs: removing the `vue` entry and excluding `vue` from the pack languages
  matches the split-region analysis path; Vue regions continue to use their
  `javascript`/`typescript` configs, and the pack declaration test pins the
  absence (`LoggingRulePackTest.kt:136-146`).
- Concurs: F1's fix does not change the other matchers, the exact legacy
  behavior for ordinary factory initializers, `reads`, ownership, or the pure
  emit path.
- Dissents: F2 remains as described above.
- Residual: `docs/manual-test.md` still has no 006 row for the golden-fixture
  deferral described in the round-2 handover. That is a scribal/detection-
  fidelity follow-up rather than a new correctness defect, but the acceptance
  gap is not yet recorded where `workflow.md` requires it.

## Round 3

Verdict: F2 and the golden-fixture deferral are now recorded in the tree. The
ticket's Contract note documents the additive `View` members and `TypeRef` as a
deliberate frozen-API extension, names external implementers, and assigns the
versioning compatibility gate to 011. `docs/manual-test.md` m003 names ticket 006
and records the unverified golden-fixture replay with the reason. No source
changed in this round, and no new correctness finding was introduced.

### Findings

- F2: concurs — `docs/tasks/006-logging-rulepack.md` now has a Contract note
  recording `View.language`, `View.typeAt`, `View.guardConditions`, and `TypeRef`
  as additive API, noting that external `View` implementations must implement
  them and that the 011 pack-compatibility check is the version gate. It also
  explains `TypeRef` as the API projection of core `TypeFact`. This is the
  in-tree contract record the round-2 dissent asked for.
- F3 (golden fixtures): concurs — `docs/manual-test.md` adds m003 for ticket 006,
  marks it unverified, and states that no golden fixtures or `tags.json` are
  present in the worktree or its history while `testing-grounds/` parity remains
  automated. This satisfies the workflow's manual-gate recording for the
  non-automatable acceptance item.
- New correctness findings: none.

### Concurs / Dissents

- Concurs: the F2 contract decision is durable in the ticket, does not change
  runtime behavior, and correctly defers the versioning gate to 011 rather than
  claiming the frozen surface is unchanged.
- Concurs: the m003 deferral is accurately scoped to the missing golden fixtures;
  it does not substitute for the automated `testing-grounds/` parity that the
  test already performs.
- Concurs: no matcher logic, factory/initializer resolution, oracle handling,
  ownership, reads, or error-path behavior changed in this round.
- Dissents: none.
- Residual, not a finding: architecture §5 still shows the earlier `View` shape;
  the ticket note is an explicit recorded refinement, so this is a documentation
  sync item for a later architecture/API pass rather than a 006 correctness
  defect.
