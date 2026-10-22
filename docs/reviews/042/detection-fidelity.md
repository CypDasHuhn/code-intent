# Detection fidelity review — 042 intent pack fidelity hardening

## Verdict

Changes requested. The structural write-target, constructor-injection, factory,
persistence-typing, and read-prefix changes address the deferred 038/039/040
findings and are covered by focused tests. Moving `Factory` from the service
suffix set to a new init suffix set leaves one init path behind: `isDiReceiver`
still consults only `serviceSuffixes`, so a DI/resolver call on a factory-typed
receiver regresses from established `SERVICE_INIT` to candidate. The Kotlin
fixture assertion added for the annotated-constructor regression is also
satisfied by the pre-existing plain constructor, so the fixture does not pin
the new `@Inject` shape.

## Baseline

Reviewed the uncommitted diff against `docs/workflow.md`, the ticket, the
038/039/040 detection-fidelity reviews, the service/data-access/mutation docs,
the pack sources, and the testing-grounds fixtures. Static review only; no
source edit, build, test, or formatter command was run.

## Verification of the deferred findings

- 038: increment/decrement targets now come from the write node's children
  rather than from scanning target text for `+`/`-`. Direct targets of
  `counts[i - 1]++`, `statusById["x-y"]++`, and `--statusById["x-y"]` are
  excluded across Kotlin, JavaScript, TypeScript, and C#; nested reads such as
  `otherById[id]` and `statusById[id]` inside a structural target remain
  `LOOKUP`. The new tests cover both postfix and prefix forms and the nested
  read case.
- 039: annotation-qualified Kotlin primary constructors and TypeScript
  parameter properties now establish `SERVICE_INIT`, and plain TypeScript
  constructor parameters are explicitly negative-tested. `Factory` is now an
  init suffix, so `UserFactory()`/`new UserFactory()` establish init and are
  excluded from delegation, while thin `widgetFactory.create()` calls are
  candidate-only until configured. Finding 1 below is the remaining init gap.
- 040: generic `Session`, `Table`, and `Queue` are gone from the default
  persistence types, qualified `org.hibernate.Session` and `javax.jms.Queue`
  establish, and the in-memory suffix veto keeps generic
  `BlockingQueue`/`HttpSession`/`com.example.Table` out of both established and
  candidate `DATA_MODIFY`. The read filter now yields to configured
  persistence calls, so `findOneAndUpdate`/`findOneAndDelete` and configured
  read-prefixed wrappers reach the candidate/established path while
  `findByStatus` and `count` stay out.

## Findings

### 1. Factory-typed DI receivers regress from established init to candidate

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:489-496`
(`isDiReceiver`), with the suffix move at
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceConfig.kt:168-181`.

Problem: `Factory` was removed from `COMMON_SERVICE_SUFFIXES` and added to
`COMMON_INIT_SUFFIXES`. `isDiReceiver` still matches receiver types against
`language.serviceTypes`/`language.serviceSuffixes` only, unlike
`isConnectionReceiver` (`:477-487`) and `isWiringType` (`:657-669`), which now
include `language.initSuffixes`. As a result a DI/resolver call such as
`fun wire() { factory.getBean("client") }` where the oracle resolves the
receiver as `ServiceFactory` (or another `*Factory` type) was established
`SERVICE_INIT` before this diff and is now only a `SERVICE_INIT` candidate.
The receiver-name qualifiers do not compensate: `initQualifiers` matches the
path prefix `ServiceFactory.` case-sensitively, so a normal lower-case variable
such as `factory`/`serviceFactory` misses it. This is an unintended detection
change on the init side, not the intended delegation tightening.

Suggested fix: include `language.initSuffixes` in the receiver-type branch of
`isDiReceiver`, parallel to `isConnectionReceiver`, or otherwise gate factory
suffixes per rule consistently. Add a fixed-oracle test for a `*Factory`-typed
receiver calling `getBean`/`resolve`/`getInstance` that expects established
`SERVICE_INIT`, alongside the existing negative delegation test for
`factory.create()`.

### 2. Kotlin fixture assertion does not cover the annotated constructor

Location: `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/service/ServiceFixtureTest.kt:25`
with `testing-grounds/kotlin/ServiceIntents.kt:1,16`.

Problem: The fixture now contains both
`class UserController(private val userService: UserService)` and
`class AnnotatedUserController @Inject constructor(private val userService: UserService)`.
The added assertion is `init.any { it.contains("private val userService: UserService") }`.
The first, pre-existing class already emits exactly that text, so the
assertion passes even if the annotation-qualified constructor path regresses.
The dedicated `ServiceInitRulePackTest.kotlinAnnotatedConstructorInjectionEmitsInitWiring`
does cover the shape, but the fixture claim itself is not verified.

Suggested fix: give the annotated-constructor parameter a distinct name in the
fixture (for example `annotatedService: UserService`) and assert on that text,
or assert that two distinct `private val ...: UserService` parameters are
emitted and that the annotated one is present.

## Residuals (not findings)

- The golden pipeline snapshot fixture list is unchanged, so none of the new
  regression shapes appear in `pipeline-current.json`; coverage relies on the
  new unit and service-fixture tests. The acceptance criterion only requires
  the existing snapshot to stay green.
- Adding `Queue`/`Table`/`Session` to `IN_MEMORY_TYPE_SUFFIXES` suppresses any
  durable type whose simple name ends in those words (for example `SqsQueue` or
  `DynamoDbTable`) unless it is added to `persistenceTypes`; this is consistent
  with config-driven durable proof but is broader than exact generic names.
- The Kotlin `@Inject` constructor regex is not depth-aware for annotation
  arguments, so an annotation argument containing `)` (for example a nested
  call) can prevent the constructor from being matched. The new tests use a
  simple string argument.
- Static review only; the green handover is treated per `docs/workflow.md`.

## Concurs / Dissents

- Concurs: 038 is resolved. Write targets are selected from increment syntax,
  both postfix and prefix targets are excluded across the four languages, and
  reads nested inside structural targets remain `LOOKUP`.
- Concurs: 039 resolves the Kotlin `@Inject` and TypeScript parameter-property
  shapes, and the factory boundary is now init-only for construction with
  explicit evidence required for thin factory delegation.
- Concurs: 040 resolves the generic persistence types and read-prefix ordering.
  Qualified durable types establish, generic in-memory types do not leak into
  `DATA_MODIFY`, configured compound mutations reach the candidate channel, and
  genuine reads still stay out.
- Concurs: apart from the intended suffix relocation and in-memory veto, no
  unrelated detection behavior changed.
- Dissents: finding 1 (a factory-typed DI receiver loses established init) and
  finding 2 (the Kotlin fixture assertion is vacuous for the annotated
  constructor).

## Summary

The diff correctly implements the substantive detection work for ticket 042:
structural increment/decrement targets no longer produce `LOOKUP` while nested
reads remain eligible, Kotlin `@Inject` and TypeScript parameter properties
produce `SERVICE_INIT`, factory construction is init rather than delegation,
thin factory calls require explicit evidence, generic `Queue`/`Table`/`Session`
types no longer establish `DATA_MODIFY`, and configured read-prefixed mutations
are classified while genuine reads stay out. Two issues remain. `isDiReceiver`
was not updated to include the new init suffixes, so DI/resolver calls on
factory-typed receivers regress from established init to candidate, which is an
unintended detection change on the init side. The Kotlin fixture assertion for
the annotated constructor is masked by the pre-existing plain constructor and
does not actually guard the new shape. Fixing the suffix use and strengthening
the fixture assertion, with the suggested tests, closes the remaining gaps.

## Round 2

### Verdict

Concurs. No findings. The round-1 findings are resolved, and the round-2
hardening does not introduce a detection regression on the reviewed surfaces.

### Resolution status

- Round-1 finding 1 (factory-typed DI receivers lost established init) is
  resolved. `isDiReceiver` now matches receiver types against
  `serviceSuffixes + initSuffixes`, parallel to `isConnectionReceiver` and
  `isWiringType` (`ServiceRulePack.kt:489-498`). The new
  `factoryTypedDiReceiversAreEstablishedInit` test uses an oracle-backed
  `com.acme.ServiceFactory` receiver for `getBean`/`resolve`/`getInstance` and
  asserts established `SERVICE_INIT` with no init candidates, while
  `serviceFactory.create()` remains non-delegation and candidate-only.
- Round-1 finding 2 (Kotlin fixture assertion masked by the plain constructor)
  is resolved. `testing-grounds/kotlin/ServiceIntents.kt` now uses a distinct
  `private val annotatedService: UserService` parameter, and
  `ServiceFixtureTest` asserts that exact text, so the fixture cannot pass from
  the pre-existing plain constructor alone. The TypeScript fixture and its
  parameter-property/factory assertions are present.
- The additional DataAccess work is additive and consistent with the ticket:
  `compoundMutationCalls` is a typed per-language config field with
  replace/additive decoding, defaults cover `findOneAndUpdate`/
  `findOneAndDelete`/`findAndModify`/`getAndIncrement`, and
  `DataAccessRule.isAmbiguous` excludes those names before the read-prefix
  fallback. The full DataAccess+Mutation overlap test configures `getPersist`
  additively on both packs and pins compound mutations as `DATA_MODIFY` while
  `findByStatus`, `count`, and `get` stay out of `DATA_MODIFY`. Genuine reads
  are not in the compound default set, so the exclusion does not swallow
  ordinary `find`/`get` reads.

### Findings

None.

### Concurs / Dissents

- Concurs: the `@Inject`/TypeScript parameter-property and factory-boundary
  behavior remains intact after the DI suffix fix.
- Concurs: the structural increment/decrement target fix still excludes direct
  targets while preserving nested `LOOKUP` reads.
- Concurs: generic `Session`/`Table`/`Queue` handling and the configured
  read-prefixed mutation ordering are unchanged from round 1 and still covered.
- Concurs: the new compound-mutation exclusion is opt-in/additive for project
  wrappers, defaulted for known names, and does not remove genuine reads from
  SEARCH/LOOKUP.
- Concurs: no unrelated detection behavior changed in the round-2 delta.
- Dissents: none.

### Summary

Round 2 resolves both round-1 findings: factory-typed DI/resolver calls are
again established `SERVICE_INIT` through the init suffixes, with a new
oracle-backed test and the desired negative delegation behavior, and the Kotlin
fixture now uses a distinct annotated-constructor parameter that the fixture
test actually asserts alongside the present TypeScript fixture. The extra
DataAccess compound-mutation configuration is additive, adds no LOOKUP/SEARCH
ambiguity for the configured names, and is covered by a full DataAccess+Mutation
overlap test that keeps `findByStatus`, `count`, and `get` out of `DATA_MODIFY`.
I found no new or unresolved detection-fidelity findings in this delta; the
detection loop can close.
