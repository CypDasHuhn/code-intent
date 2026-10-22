# Correctness review — 042 intent pack fidelity hardening

## Baseline

Read-only review of the uncommitted working-tree diff implementing
`docs/tasks/042-intent-pack-fidelity-hardening.md`. I read the pack sources,
configs, tests, fixtures, and the 038/039/040 review context. No source edit,
build, test, formatter, stage, or commit command was run.

## Verdict

Changes requested. The substantive work is present and the new behavioral tests
are mostly non-vacuous: structural increment/decrement targets are fixed,
Kotlin `@Inject` and TypeScript parameter properties establish `SERVICE_INIT`,
factory construction is init rather than delegation, generic persistence types
are no longer durable, and configured read-prefixed mutations now reach the
mutation path. Three issues remain: the factory/init suffix split was not applied
to DI receiver matching, compound read/write mutation calls still get read
candidates from the data-access pack with no overlap test, and the new Kotlin
fixture assertion does not actually pin the annotated-constructor path.

## Findings

### 1. Factory/init suffix split is incomplete for DI receivers

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:489-496`
(`isDiReceiver`), with the suffix move at
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceConfig.kt:168-181`
and `:270-284`. Compare the updated init-side type checks in
`ServiceRulePack.kt:477-487` (`isConnectionReceiver`) and `:657-669`
(`isWiringType`).

Problem: `Factory` was removed from `COMMON_SERVICE_SUFFIXES` and added to
`COMMON_INIT_SUFFIXES`. `isConnectionReceiver` and `isWiringType` were updated
to union `language.initSuffixes`, but `isDiReceiver` still checks only
`language.serviceTypes` and `language.serviceSuffixes`. A DI/resolver call on a
factory-typed receiver therefore regresses from established `SERVICE_INIT` to
candidate-only. For example, with a fixed oracle resolving the receiver to
`ServiceFactory`:

```kotlin
fun wire() {
    serviceFactory.getInstance()
}
```

Before this diff `initCallEvidence` saw `getInstance` in `diCalls`, and
`isDiReceiver` matched the receiver type through `serviceSuffixes` containing
`Factory`; after this diff the same call returns the candidate-only evidence.
Receiver-name qualifiers do not compensate because `initQualifiers` matches path
prefixes case-sensitively, so a normal lower-case variable like `serviceFactory`
does not match `ServiceFactory`.

Suggested fix: include `language.initSuffixes` in the receiver-type branch of
`isDiReceiver`, parallel to `isConnectionReceiver`:

```kotlin
typeMatches(receiverType, language.serviceTypes, language.serviceSuffixes + language.initSuffixes)
```

Alternatively, keep factory types out of DI intentionally and pin that choice
with a negative test/documentation. Add an oracle-backed test for a
`*Factory`-typed receiver calling `getBean`/`resolve`/`getInstance`.

### 2. Compound read/write mutations still surface as SEARCH/LOOKUP candidates

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:202-211`
(`isAmbiguous`), used by `search` at `:80` and `lookup` at `:98`; the new
mutation behavior is in
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:299-324`.
The new tests
`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/mutation/MutationDataModifyRuleTest.kt:353-405`
instantiate only `BookkeepingRulePack`, `MutationRulePack`, and
`LocalCopyRulePack`, so the full-pack overlap is not exercised. See also
`docs/data-access-intents.md:87`.

Problem: `findOneAndUpdate`, `findOneAndDelete`, and configured read-prefixed
wrappers such as `getPersist`/`getAndIncrement` now emit `DATA_MODIFY`
candidates, but `DataAccessRulePack.isAmbiguous` still treats the same callees
as ambiguous reads because they start with `find`/`get`. In a full bundled
analysis the same span therefore receives `DATA_MODIFY` plus `SEARCH` and
`LOOKUP` candidates. That contradicts the data-access documentation line saying
the pack "does not model calls that both query and mutate", and the ticket's
overlap acceptance is not tested because the new mutation tests exclude the
data-access pack. Genuine reads such as `findByStatus` and `count` remain
correctly excluded from `DATA_MODIFY`; the problem is the inverse: compound
mutations are still presented as reads.

Suggested fix: add a compound-mutation exclusion to the data-access ambiguity
fallback (for example skip ambiguity candidates for callees containing
`AndUpdate`, `AndDelete`, `AndReplace`, `AndIncrement`, `AndDecrement`, or
`OrCreate`, or expose a configured exclusion set), or explicitly document the
co-occurrence as intended. Either way, add an overlap test using both
`DataAccessRulePack` and `MutationRulePack` for `findOneAndUpdate`,
`findOneAndDelete`, and a configured `getPersist` wrapper, asserting the
intended DATA and read candidate sets.

### 3. Kotlin service fixture assertion is vacuous for `@Inject`

Location:
`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/service/ServiceFixtureTest.kt:25`
with `testing-grounds/kotlin/ServiceIntents.kt:1,16`.

Problem: the fixture contains both a pre-existing plain primary constructor,
`class UserController(private val userService: UserService)`, and the new
annotated constructor, `class AnnotatedUserController @Inject constructor(private
val userService: UserService)`. The added assertion only checks that some
`SERVICE_INIT` span contains `private val userService: UserService`. The plain
constructor already produces that exact text, so the fixture assertion passes
even if annotation-qualified constructor recognition regresses. The dedicated
`ServiceInitRulePackTest.kotlinAnnotatedConstructorInjectionEmitsInitWiring`
does cover the shape, but the fixture does not.

Suggested fix: use a distinct parameter name for the annotated constructor in
the fixture (for example `injectedService: UserService`) and assert on that
text, or assert that two distinct `*: UserService` parameters are emitted so the
annotated class is required.

## Residuals (not findings)

- `MutationRulePack` applies `isInMemoryType` only on the candidate path and
  only after `persistentReceiver`; a durable receiver-name suffix
  (`*Client`/`*Template`/`*Producer`) can therefore still override a resolved
  in-memory type. This is consistent with treating those names as durable proof,
  but the precedence is worth keeping in mind.
- The Kotlin `PRIMARY_CONSTRUCTOR` regex is not depth-aware for annotation
  arguments containing `)`, so such an annotation can prevent a primary
  constructor from matching.
- `testing-grounds/typescript/ServiceIntents.ts` is untracked in the worktree;
  it is required by the new fixture test and must be staged with the change.

## Concurs

- Structural increment/decrement targets are now selected from the write node's
  children rather than from `+`/`-` scanning, so `counts[i - 1]++`,
  `statusById["x-y"]++`, and prefix `--statusById["x-y"]` do not produce
  `LOOKUP`; nested reads inside the target still do. The per-language positive
  and negative tests are non-vacuous.
- Kotlin annotation-qualified primary constructors and TypeScript constructor
  parameter properties emit `SERVICE_INIT` on the parameter, and plain
  TypeScript constructor parameters are a genuine negative.
- Factory construction (`UserFactory()`/`new UserFactory()`) is established
  `SERVICE_INIT` and excluded from delegation; thin
  `widgetFactory.create()` remains candidate-only until configured, and the
  tests pin both directions.
- Qualified `org.hibernate.Session`, `javax.jms.Queue`, and `jakarta.jms.Queue`
  establish persistence, while `BlockingQueue`, `HttpSession`, and
  `com.example.Table` do not establish or candidate `DATA_MODIFY`; the
  configured read-prefix bypass preserves `findByStatus`/`count` as non-mutating.
- The mutation tests assert both established output and candidate emptiness for
  the new in-memory negatives, and the configured-wrapper test verifies the
  candidate channel.
- `initSuffixes` is wired through the typed config schema, defaults, and
  additive/replacement configuration, and the new service tests are otherwise
  meaningful.

## Dissents

- Dissent from closing while factory-typed DI receivers lose established init
  because `isDiReceiver` was not updated with the new suffix set.
- Dissent from leaving compound read/write mutations double-classified as
  SEARCH/LOOKUP and DATA without an overlap test or an explicit documented
  decision.
- Dissent from accepting the Kotlin fixture assertion as coverage for the new
  `@Inject` constructor shape.

## Summary

The implementation is directionally correct and much better tested than the
deferred state: the structural write target, Kotlin/TypeScript injection,
factory boundary, durable typing, and read-prefix ordering all move the packs in
the intended direction, and most new tests genuinely fail without the changes.
Two real gaps remain in pack behavior: removing `Factory` from the shared
service suffixes was not propagated to `isDiReceiver`, so factory-typed DI
receivers regress from established `SERVICE_INIT` to candidate, and the
data-access ambiguity fallback still paints compound mutations such as
`findOneAndUpdate`/`findOneAndDelete` and configured `get*` wrappers as read
candidates alongside the new `DATA_MODIFY` classification, with no full-pack
overlap test. In addition, the new Kotlin fixture assertion is masked by the
pre-existing plain constructor and does not guard the `@Inject` path it claims
to cover. Fixing the suffix use, deciding and testing the compound-mutation
overlap, and making the fixture parameter distinct would close the correctness
loop.

## Round 2

Reviewed the updated uncommitted working tree, focusing on the
`isDiReceiver` change, the new `compoundMutationCalls` path, the new overlap
test, and the fixture corrections. Read-only review: no source edit, build,
test, formatter, stage, or commit command was run.

### Verdict

Changes requested. All three round-1 findings are resolved in the way described,
and the new tests for them are non-vacuous. One new default/classification
mismatch remains in the compound-mutation handling: several names now excluded
from read ambiguity by default are not classified by the mutation pack's default
configuration, so those calls become silent rather than becoming mutations.

### Resolution status

1. **`isDiReceiver` missing `initSuffixes` — resolved.** `isDiReceiver` now
   unions `language.initSuffixes`, parallel to `isConnectionReceiver` and
   `isWiringType`. `factoryTypedDiReceiversAreEstablishedInit` uses an oracle
   resolving `com.acme.ServiceFactory` and asserts established `getBean`,
   `resolve`, and `getInstance` output with no `SERVICE_INIT` candidates; the
   same test keeps `serviceFactory.create()` candidate-only for delegation. The
   test fails against the previous `isDiReceiver`, so it is non-vacuous.
2. **Compound mutations producing read candidates — resolved for the reported
   shapes.** `DataAccessLanguageConfig.compoundMutationCalls` is typed,
   additive, and excluded in `isAmbiguous`.
   `compoundMutationsDoNotEmitReadAmbiguityInTheFullPack` loads DataAccess and
   Mutation together, verifies `findOneAndUpdate`, `findOneAndDelete`, and a
   configured `getPersist` are `DATA_MODIFY` with no SEARCH/LOOKUP source or
   candidate text, and verifies `findByStatus`/`count`/`get` stay out of
   `DATA_MODIFY`. The test would fail without the exclusion, so it is
   non-vacuous. The overlap is now explicitly owned by mutation for these calls.
3. **Kotlin fixture assertion — resolved.** The fixture parameter is renamed to
   `annotatedService` and the assertion now checks
   `private val annotatedService: UserService`, which only the annotated
   constructor produces. The TypeScript fixture is present and its assertions
   are backed by the TypeScript parameter-property and factory tests.

### New finding

#### 1. Default compound-mutation exclusions are broader than the mutation pack's default classification

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessConfig.kt:173-187`
(the new `COMPOUND_MUTATION_CALLS` defaults) and
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:202-211`
(the early `isAmbiguous` return). Compare the mutation defaults in
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationConfig.kt:435-489`
and the local candidate vocabulary in
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:40-65,435-443`.

Problem: the data-access defaults now suppress SEARCH/LOOKUP ambiguity for
`findOneAndReplace`, `findOneAndIncrement`, `findOneAndDecrement`,
`findOneAndModify`, `findAndModify`, `getAndUpdate`, `getAndIncrement`, and
`getAndDecrement`, but none of those names is in the mutation pack's default
`persistenceCalls` (JavaScript/TypeScript add only `findOneAndUpdate`,
`findOneAndDelete`, and `replaceOne`, and Kotlin adds none), and none is in the
local candidate vocabulary. With both packs registered, a call such as
JavaScript `model.findOneAndReplace(...)` or `model.findAndModify(...)`, or
Kotlin `atomic.getAndIncrement()`, now gets no SEARCH/LOOKUP candidate and no
DATA/LOCAL candidate: the read filter in `MutationRulePack` rejects it because
it starts with `find`/`get`, and the data-access pack no longer surfaces it
because of the new compound default. This contradicts the updated docs statement
that compound mutation calls are excluded "so the mutation pack owns their
classification" (`docs/data-access-intents.md:92`) and regresses the round-1
behavior from a read candidate to no intent for those names. The new overlap
test does not cover any default compound name beyond the two configured in the
mutation pack, so this mismatch is unverified.

Suggested fix: align the two default vocabularies. Either add the remaining
default compound names to the mutation pack's per-language persistence calls (and
cover the `getAnd*` atomic forms as local candidates where appropriate), or
restrict `COMPOUND_MUTATION_CALLS` to names the mutation pack classifies by
default and require projects to add the rest to both configs. Add a full-pack
test for at least `findOneAndReplace`/`findAndModify` and `getAndIncrement` that
asserts a mutation candidate rather than silence.

### Concurs / Dissents

- Concurs: round-1 finding 1 is resolved; factory-typed DI receivers are
  established init again, and factory delegation remains candidate-only.
- Concurs: round-1 finding 2 is resolved; the new compound exclusion and
  full-pack overlap test correctly remove read ambiguity for
  `findOneAndUpdate`, `findOneAndDelete`, and configured `getPersist` while
  preserving genuine reads.
- Concurs: round-1 finding 3 is resolved; the fixture now pins the annotated
  constructor and the TypeScript fixture covers the new shape.
- Concurs: the new config field is typed, additive, documented, and covered at
  the config and rule levels.
- Dissents: the default compound list should not suppress reads for names the
  mutation pack does not classify by default; the current defaults can turn a
  compound mutation into a silent call.

### Summary

Round 2 resolves the three round-1 findings: `isDiReceiver` now includes
`initSuffixes` with an oracle-backed positive test, compound mutations are
excluded from read ambiguity with a full DataAccess+Mutation overlap test, and
the Kotlin fixture assertion now actually pins the annotated constructor. One
new issue remains: the new `compoundMutationCalls` defaults are broader than the
mutation pack's default persistence/local vocabularies, so names such as
`findOneAndReplace`, `findAndModify`, and `getAndIncrement` lose their previous
read candidates and receive no mutation candidate either, leaving those calls
silent. Aligning the two default lists (or narrowing the data-access defaults to
names the mutation pack classifies) would close the remaining gap.

## Round 3

Reviewed the updated uncommitted working tree, focusing on the new
`DEFAULT_COMPOUND_MUTATION_CALLS` alignment, the cross-language config test, the
default-list full-pack test, and the preserved `getPersist`/genuine-read
negatives. Read-only review: no source edit, build, test, formatter, stage, or
commit command was run.

### Verdict

Changes requested. The round-2 silence finding is resolved for ordinary
unqualified receivers: every default `compoundMutationCalls` name is now also a
default mutation persistence call, and the new config-level and full-pack tests
verify the alignment. One new boundary problem remains from that alignment:
`getAndUpdate`/`getAndIncrement`/`getAndDecrement` are local atomic operations,
not durability evidence, so making them default persistence calls classifies an
in-memory atomic mutation as a `DATA_MODIFY` candidate; conversely, a configured
compound call on a mutable-named receiver is still suppressed before the
configured return, so the "every default exclusion is classified" claim is not
true for those receivers.

### Resolution status

- **Round-2 finding (default compound names became silent) — resolved for the
  default/unqualified receiver path.** `DEFAULT_COMPOUND_MUTATION_CALLS` is now
  appended to `DEFAULT_PERSISTENCE_CALLS`, so every default
  `compoundMutationCalls` entry is present in every language's
  `persistenceCalls` defaults. `defaultCompoundMutationCallsAreMutationPersistenceCalls`
  checks the cross-language config invariant, and
  `everyDefaultCompoundMutationCallIsOwnedByMutationPack` exercises each default
  name through the full DataAccess+Mutation packs and asserts a
  `DATA_MODIFY` candidate plus no SEARCH/LOOKUP output. Both tests are
  non-vacuous for the `model` receiver they use. The configured `getPersist`
  overlap case and the `findByStatus`/`count`/`get` negatives remain intact.
- The previous round's `isDiReceiver`, fixture, and structural-write findings
  remain resolved; the round-3 delta does not disturb them.

### New finding

#### 1. The persistence alignment misclassifies local `getAnd*` calls and still leaves mutable-receiver compound calls silent

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationConfig.kt:7-19,96`
(the local atomic names added to `DEFAULT_COMPOUND_MUTATION_CALLS` and then to
`DEFAULT_PERSISTENCE_CALLS`),
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:299-324`
(`candidate` ordering), `:435-468` (`isLocalCandidate`,
`IN_MEMORY_TYPE_SUFFIXES`), and
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationConfig.kt:306-320`
(`DEFAULT_MUTABLE_RECEIVER_NAMES`). See also the new test at
`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/dataaccess/DataAccessOverlapTest.kt:131-160`.

Problem: `getAndUpdate`, `getAndIncrement`, and `getAndDecrement` are
conventionally local atomic operations (`AtomicInteger`, `AtomicLong`, caches),
not durable persistence. Adding them to the default persistence calls means
`counter.getAndIncrement()` with an oracle resolving `AtomicInteger` now emits a
`DATA_MODIFY` candidate: `persistentReceiver` is false, `AtomicInteger` is not
in `IN_MEMORY_TYPE_SUFFIXES`, `counter` is not in
`DEFAULT_MUTABLE_RECEIVER_NAMES`, and the configured-call fallback returns true.
The new `everyDefaultCompoundMutationCallIsOwnedByMutationPack` test asserts
exactly this for `model.getAndIncrement(value)`, so it pins a persistence
classification for an in-memory mutation rather than the local classification
required by `docs/intent-model.md:180-181` and the 040 in-memory acceptance.

The opposite gap remains for receivers whose names are in
`DEFAULT_MUTABLE_RECEIVER_NAMES`: `cache.getAndIncrement()` has `configured`
true, but `candidate` returns false at the mutable-receiver veto before the
configured return, and `LocalValueModifyRule` does not classify `getAnd*`
because it is neither a mutable operation nor a local candidate. The same is
true for `items.findAndModify(...)` and similar mutable-named receivers. Those
calls are still silent, so the round-3 claim that every default exclusion is
classified as `DATA_MODIFY` (established or candidate) only holds for receiver
names outside the mutable-name veto. The new full-pack test uses `model` and
does not cover either the atomic type or the mutable-name receiver.

Suggested fix: separate durable compound names (`findOneAndUpdate`,
`findOneAndDelete`, `findOneAndReplace`, `findOneAndIncrement`,
`findOneAndDecrement`, `findOneAndModify`, `findAndModify`) from local atomic
ones. Keep the durable names in the default persistence calls, and either keep
`getAndUpdate`/`getAndIncrement`/`getAndDecrement` out of the persistence
defaults and classify them as `LOCAL_VALUE_MODIFY` candidates (for example by
adding the exact names to the local candidate vocabulary), or add the atomic
types to the in-memory veto and route them to local classification. Add
full-pack tests for an oracle-resolved `AtomicInteger.getAndIncrement()` and for
`cache.getAndIncrement()`/`items.findAndModify(...)` that assert the intended
LOCAL or DATA outcome rather than silence or a DATA candidate on an in-memory
receiver. The cross-language config test should then compare only the durable
compound names against persistence calls, with the local names checked against
the local candidate vocabulary.

### Concurs / Dissents

- Concurs: the round-2 finding is resolved for default receivers; the config
  alignment test and the default-list full-pack test are non-vacuous and cover
  all languages/languages defaults.
- Concurs: configured `getPersist` and the `findByStatus`/`count`/`get`
  negatives remain as intended after the alignment.
- Concurs: the earlier `isDiReceiver`, fixture, structural-write, and
  persistence-typing work remains intact in the round-3 delta.
- Dissents: adding local atomic `getAnd*` operations to default persistence
  calls moves an in-memory mutation into `DATA_MODIFY`, while the mutable-name
  veto still leaves the same compound names silent for `cache`/`items`
  receivers; the new tests do not cover either case.

### Summary

Round 3 fixes the round-2 silence finding for ordinary unqualified receivers by
adding every default `compoundMutationCalls` name to the mutation pack's default
persistence calls, with a cross-language config invariant and a full-pack
per-name test, and it preserves the configured `getPersist` and genuine-read
negatives. One boundary issue remains: the alignment treats local atomic
`getAndUpdate`/`getAndIncrement`/`getAndDecrement` operations as persistence, so
an `AtomicInteger.getAndIncrement()` becomes a `DATA_MODIFY` candidate (and the
new test pins that), while a mutable-named receiver such as `cache` still
suppresses the configured candidate and leaves the call silent. Separating
durable `findAnd*` names from local `getAnd*` names, classifying the latter as
local candidates, and adding atomic/mutable-receiver tests would close the
remaining correctness gap.

## Round 4

Reviewed the updated uncommitted working tree, focusing on the durable/local
compound split, the local-candidate vocabulary, the mutable-receiver bypass, the
new full-pack tests, and the documentation changes. Read-only review: no source
edit, build, test, formatter, stage, or commit command was run.

### Verdict

Concurs. The round-3 boundary finding is resolved, the new tests cover the
atomic/mutable-receiver cases that were missing, and I found no new or
unresolved correctness finding in this delta. The correctness loop can close.

### Resolution status

- **Round-3 finding — resolved.** `DURABLE_COMPOUND_MUTATION_CALLS` remains in
  `DEFAULT_PERSISTENCE_CALLS`, while `LOCAL_ATOMIC_MUTATION_CALLS`
  (`getAndUpdate`, `getAndIncrement`, `getAndDecrement`) is removed from
  persistence defaults and added to `LOCAL_CANDIDATE_EXACT`. Both groups remain
  in data-access `compoundMutationCalls`, so neither produces SEARCH/LOOKUP
  ambiguity. `PersistenceMatch.candidate` now computes
  `configuredCompound = configured && isReadCall(callee)` and lets a configured
  read-prefixed call through the mutable-receiver veto only in that case, so
  `items.findAndModify(...)` becomes `DATA_MODIFY` while ordinary `cache.save`
  remains vetoed.
- The new `defaultCompoundMutationCallsAlignWithMutationVocabularies` test pins
  the durable/local split across all four languages, including that local
  atomic names are in `LOCAL_CANDIDATE_EXACT` and not in persistence calls.
  `durableDefaultCompoundMutationCallsAreOwnedByMutationPack` and
  `localAtomicDefaultCompoundMutationCallsAreLocalOnly` exercise every default
  name through the full pack set, and `atomicIntegerGetAndIncrementIsLocalValueModify`
  plus `mutableNamedAtomicAndDurableCompoundCallsAreClassified` cover the
  oracle-resolved `AtomicInteger`, `cache.getAndIncrement`, and
  `items.findAndModify` cases. These assertions would fail against the round-3
  behavior, so the regression coverage is non-vacuous.
- The previously resolved structural-write, injection, factory, persistence
  typing, and read-prefix findings remain intact. The configured `getPersist`
  overlap and the `findByStatus`/`count`/`get` negatives are unchanged. The
  documentation now states the durable/local split and the cross-pack
  configuration requirement correctly.

### Findings

None.

### Concurs / Dissents

- Concurs: durable `find*And*` names are persistence defaults and classify as
  `DATA_MODIFY`; local atomic `getAnd*` names are local candidates and classify
  as `LOCAL_VALUE_MODIFY`; both remain read-ambiguity exclusions.
- Concurs: the mutable-name veto now yields for configured read-prefixed
  compound calls while preserving the ordinary `cache.save` negative.
- Concurs: the new config and full-pack tests are meaningful and cover the
  exact boundary that failed in round 3.
- Concurs: no unrelated behavior changed in the round-4 delta.
- Dissents: none.

### Summary

Round 4 resolves the final round-3 boundary issue by keeping durable `find*And*`
names as persistence defaults and classifying atomic `getAnd*` names as local
candidates, while both remain excluded from SEARCH/LOOKUP ambiguity. The
mutable-receiver veto now yields only to configured read-prefixed compound
calls, the new cross-language config and full-pack tests cover the durable,
local-atomic, `AtomicInteger`, `cache`, and `items` cases, and the previously
resolved injection, factory, structural-write, persistence-typing, and
read-prefix findings remain intact. I found no new or unresolved correctness
finding; the ticket's correctness review can close.
