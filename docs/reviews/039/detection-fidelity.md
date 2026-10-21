# Detection fidelity review — 039 service intents

## Verdict

Changes requested. The pack has the right overall shape — two independent,
source-only rules, per-language config, and a genuine established/candidate
split — but several established classifications do not yet honor the ticket's
central boundary between responsibility transfer and construction/wiring. In
particular, "thin" delegation is too permissive, controller/facade matching
paints non-service calls, lifecycle names alone are established init, and
`SERVICE_INIT` and `SERVICE_DELEGATION` overlap on connection/setup calls. The
candidate-only cases are also less conservative than the docs claim.

## Baseline

Reviewed `3e5e4e6` (`feat(rulepacks): add service intent pack`) against `main`
at `e37f0c4`, with `docs/workflow.md`, `docs/tasks/039-service-intents.md`,
`docs/intent-model.md` §2, `docs/service-intents.md`, the pack sources, and the
added tests. There is no previous engine implementation of `SERVICE_*`, so
fidelity is judged against the ticket, the intent model's include/exclude
boundaries, and the existing fact/profile semantics. No build or test was run,
per workflow.

## Findings

### 1. `isThin` accepts single-statement bodies that add local logic

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:260`
and `:86`.

Problem: `isThin` only checks `units.size == 1` and the absence of writes,
guards, and try blocks. It does not check that the forwarding call is the only
effect. A chained transform such as `service.find(id).filter { ... }.map { ... }`,
a trailing-lambda wrapper, or a `when`/`switch` dispatch with several service
calls all qualify. For `when`/`switch`, the guard facts do not include the
branch node, and `forwarding` selects only the first matched call, so a
multi-branch delegation is partially painted as established. This contradicts
the acceptance criterion that a method which delegates and performs local logic
must become a candidate rather than be forced into delegation.

Suggested fix: require exactly one forwarding effect in a thin body, with no
other calls beyond configured argument adaptation. Treat extra calls, lambdas,
and `when`/`switch` branches containing local logic as candidates. Add negative
tests for chained transforms, trailing lambdas, and multi-branch dispatch.

### 2. Controller/facade fallback tags arbitrary calls, not just configured services

Location: `ServiceRulePack.kt:70` and `:326`.

Problem: when no call matches the service configuration, every outermost call
in a thin method of a class whose name ends with a controller/facade suffix
becomes evidence. Consequently a local transform such as
`fun normalize(name: String) = name.trim().uppercase()` inside a
`...Controller` is established `SERVICE_DELEGATION`, even though no service or
component responsibility is transferred. Local helper functions declared inside
a controller inherit the same class-suffix behavior. The existing
`controllerPatternEmitsDelegationWithoutAServiceReceiverName` test codifies the
positive case but has no negative local-helper case.

Suggested fix: restrict the fallback to calls whose receiver name or resolved
type matches `serviceNames`/`serviceTypes`/`serviceSuffixes`, or to calls whose
path matches a configured delegation target. Otherwise emit a candidate. Add
tests for pure local transforms and local functions inside controller/facade
classes.

### 3. The mixed-body threshold counts calls, not statements, and disagrees with the docs

Location: `ServiceRulePack.kt:272`; `docs/service-intents.md:34`.

Problem: `mostlyDelegating(units.size, evidence.size)` compares statement count
to the number of calls with delegation evidence. The documentation says a body
qualifies when "at least half of its statements" forward configured service
calls. A single statement with nested calls
(`service.save(service.find(id))`) contributes two evidence entries for one
statement, so a body that is not mostly delegation can become a candidate. The
opposite can also happen when one statement contains several independent
forwarding calls.

Suggested fix: count distinct body units that contain forwarding evidence, or
count outermost forwarding calls normalized per unit. Align the doc and add
boundary tests for nested calls and multiple calls in one statement.

### 4. `SERVICE_DELEGATION` and `SERVICE_INIT` overlap on setup/connection calls

Location: `ServiceRulePack.kt:116` and `:205` for init evidence;
`ServiceRulePack.kt:315` for delegation receiver evidence;
`ServiceConfig.kt:239`, `:263`, `:275` for the defaults.

Problem: `fun connect() { client.connect() }` is established `SERVICE_INIT`
from three directions (lifecycle name, `connect` in `initCalls`, `connect` in
`connectionCalls`) and established `SERVICE_DELEGATION` because `client` is in
`serviceNames`. The same span is painted with both intents. With an oracle,
`httpClient.get(url)` similarly becomes init through the any-call-on-init-type
branch while delegation still matches the `httpClient` receiver. The ticket and
intent model explicitly exclude construction/wiring from delegation and
delegation from init.

Suggested fix: make delegation skip calls that have configured init or
connection evidence, or share a single boundary predicate between the two
rules. Add an overlap test asserting `client.connect()` is init only, and one
for a call on a configured init type.

### 5. Lifecycle names alone produce established init

Location: `ServiceRulePack.kt:148` and `:179`; test
`ServiceInitRulePackTest.kt:55`.

Problem: any function named `init`, `initialize`, `setup`, `configure`,
`bootstrap`, `wire`, or `connect` is established `SERVICE_INIT` even when the
body has no service/resource construction, connection, or DI evidence. The test
`lifecycleMethodIsInitWithoutCallEvidence` deliberately establishes
`fun initialize() { work() }`. That can be business-data initialization, which
the ticket explicitly excludes from `SERVICE_INIT`, and it makes the intent
name-driven rather than lifecycle-evidence-driven.

Suggested fix: treat a lifecycle name alone as a candidate unless the function
contains configured construction/connection/DI evidence or is in a configured
bootstrap context. Keep the configured name list, but do not let it alone
certify established init. Update the test accordingly.

### 6. Generic init calls and "any call on an init type" are too broad

Location: `ServiceRulePack.kt:205`; defaults in `ServiceConfig.kt:189`–`:276`.

Problem: `diCalls` and `connectionCalls` are matched by callee alone, and the
receiver-type branch accepts any call on a configured init type. Without an
oracle that means `resolve(path)`, `getInstance()`, `window.connect()`, or
`driver.getConnection(...)` can be established init regardless of receiver.
With an oracle, `httpClient.get(url)`, `connection.close()`, and `server.stop()`
are also established init because the receiver type matches, even though they
are use/teardown rather than construction or setup. The docs describe init as
construction, client creation, connection setup, wiring, and bootstrap, not any
method on a resource.

Suggested fix: gate generic callee names by receiver/qualifier/type, and limit
the type branch to configured setup/construction calls or a setup-method subset.
Broad names such as `resolve`, `getInstance`, and `connect` should produce
candidates when unqualified. Add negative tests for use and teardown calls.

### 7. Constructor-injected services are not detected as wiring

Location: `ServiceRulePack.kt:123`–`:187`.

Problem: dependency wiring is detected only through DI annotations and
`diCalls`. A common Kotlin/C# constructor injection such as
`class UserController(private val service: UserService)` produces no
`SERVICE_INIT`, despite the scope calling out dependency wiring and per-language
DI patterns. The pack currently recognizes annotated fields but not unannotated
constructor parameters or primary-constructor properties.

Suggested fix: detect constructor parameters and injected properties whose
types match configured service/init types, emitting established init when the
DI shape is unambiguous and a candidate otherwise. Add Kotlin and C# tests.

### 8. `new` construction outside a declaration initializer is missed in JS/TS/C#

Location: `ServiceRulePack.kt:189`–`:203`; language profiles list only
`call_expression`/`invocation_expression` under `call`.

Problem: `new_expression` (JavaScript/TypeScript) and
`object_creation_expression` (C#) are not `CallFact`s. Construction is found
through declaration initializers plus the `new` regex, so `return new HttpClient()`
or `use(new HttpClient())` produces no `SERVICE_INIT`. Kotlin happens to work
because its constructor invocation is a call expression. This is a per-language
fidelity gap in the "construction" part of the ticket.

Suggested fix: expose object-creation facts in the view or scan construction
nodes directly instead of relying on declaration initializer text. Add JS/TS/C#
construction tests, including a non-initializer position.

### 9. DI annotations with arguments are not recognized

Location: `ServiceRulePack.kt:171`; annotation matching in
`analysis-engine/core/api/src/main/kotlin/dev/cint/api/Facts.kt:225`.

Problem: `DeclarationFact.hasAnnotation` rejects an actual annotation that
contains arguments unless the configured pattern matches exactly. Common DI
forms such as `@Bean(name = "client")` or
`@Autowired(required = false)` therefore do not match `Bean` or `Autowired`,
and no `SERVICE_INIT` is emitted. The value of the annotation is irrelevant to
the DI boundary, so this is a false negative.

Suggested fix: match configured annotation names against the annotation's name
with arguments removed, or add a configured "annotation with arguments" form.
Add tests for parameterized DI annotations.

### 10. Pure adaptation with a local binding is candidate-only

Location: `ServiceRulePack.kt:237`–`:270`.

Problem: a thin adapter such as

```kotlin
fun find(id: String): User {
    val key = id.trim()
    return service.find(key)
}
```

has two body units and one forwarding call, so it becomes a candidate even
though it only adapts an argument before forwarding. The ticket describes thin
forwarding/argument adaptation as deterministic delegation. This is
conservative but leaves a common call-through pattern without established
coverage.

Suggested fix: allow pure local bindings whose reads flow only into the
forwarding call to count toward a thin body, or explicitly document that only a
single forwarding statement is deterministic and keep the candidate behavior.
Add a test pinning the chosen semantics.

### 11. No service fixture, host coverage, or oracle coverage

Location: diff (no `testing-grounds` change); `docs/service-intents.md:1`–`:4`
and `:56`–`:63`.

Problem: the acceptance criteria call for fixtures, config, docs, and tests.
Config, docs, and unit tests are present, but there is no service fixture and
the pack is intentionally not in `META-INF/services`, so there is no host
output, integration test, or golden/snapshot comparison. The unit tests use
`NullOracle`, so the `serviceTypes`/`initTypes` receiver-type branches are never
exercised. Given that those branches are where several false positives live,
the current test suite substantially under-verifies the pack.

Suggested fix: add a `testing-grounds` service fixture and either register the
pack with a host/integration test and manual-gate entry, or defer registration
to a named follow-up ticket and record the fixture/manual check there. Add
`MapOracle` tests for receiver-type delegation and init matching either way.

## Concurs

- The two rules are source-only with empty `reads`, so they do not consume or
  suppress `SEARCH`, `DATA_MODIFY`, or `ERROR_HANDLING`. The overlap tests
  correctly exercise co-occurrence on the same call and mixed bodies with
  established inner intents.
- Per-language config with typed replace/additive decoding, unknown-language
  and unknown-key rejection, and defaults for Kotlin, JavaScript, TypeScript,
  and C# matches the existing pack conventions.
- Unconfigured constructors are excluded from init, and the hidden-helper
  candidate path is a reasonable use of the candidate channel.
- The documentation's registration note and overlap policy accurately describe
  the intended scaffold state.

## Dissents

- I do not concur that a controller/facade suffix alone is enough to establish
  delegation for any thin call.
- I do not concur that a lifecycle method name alone is enough to establish
  init.
- I do not concur that init/setup calls should also be established delegation;
  the two intents should be separated at the call boundary.
- The mixed-body threshold should be defined in units of statements (or the
  documentation should say calls), because the current code and docs disagree.

## Round 2

### Verdict

The round-1 findings are substantially addressed. Delegation now requires a
single whole forwarding expression, mixed bodies are measured in statement
units, controller/facade fallbacks are candidate-only, init is excluded from
delegation, generic connection/DI names are receiver-gated, lifecycle names are
candidate-only, annotations with arguments and non-initializer `new` positions
are covered, and the fixture plus oracle tests are present. However, the new
creation-node pass has two selection/scoping defects, and constructor injection
still has language and parameter-shape gaps. The loop cannot close yet.

### Verification of round-1 findings

Concurs with the round-1 fixes: the delegation analysis now rejects nested
calls, trailing lambdas, local wrappers, chained operators, and multi-branch
dispatch as established output; the mixed-body numerator is the count of
top-level units containing forwarding evidence; the controller fallback emits
only candidates; `delegationEvidence` rejects init evidence so
`client.connect()` is init-only; unqualified `connect`/`resolve`/`getInstance`
become candidates unless a configured receiver/type qualifies them; and
lifecycle names without strong evidence are candidates. The added tests cover
the corrected boundaries, annotations with arguments, classic constructor
injection, and `new` in return/assignment/argument positions. The fixture and
oracle additions also address the previous coverage finding.

### Findings

#### 1. A leading file comment disables all creation detection

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:332`
used by `creationNodes` at `:310`.

Problem: `isLiteralOrComment` walks from the candidate node all the way to the
root and returns true if any ancestor's trimmed text starts with `//`, `/*`, a
quote, or a backtick. When a file begins with a license or header comment, the
root source text itself starts with that comment, so every descendant is
treated as literal/comment. A file beginning `// license` and containing
`return new HttpClient()` therefore emits no `SERVICE_INIT` for the creation.
The new tests and fixture all start with code, so this path is unverified.

Suggested fix: decide literal/comment status by node kind (comment or string
literal), or stop the ancestor walk at the enclosing declaration/root boundary
instead of testing arbitrary ancestor text prefixes. Add a fixture or test with
a leading line comment and a block/doc comment.

#### 2. Nested creations keep the innermost node, and creation evidence is not function-scoped

Location: `ServiceRulePack.kt:323` and `:281`.

Problem: the `filterNot` keeps the innermost creation because it discards a
candidate when another candidate is contained within it. For
`new HttpClient(new HttpClientHandler())` the outer `HttpClient` is removed
and the inner `HttpClientHandler` remains; if the inner type is not in
`initTypes`, no init is emitted at all. Separately, `functionHasInitEvidence`
and `mostlyInit` use the global creation list with only `within(node,
function.node)`, so a creation inside a nested local function can establish an
outer lifecycle function as initialized even though the outer function only
declares the helper.

Suggested fix: keep the outermost creation node (or all creation nodes and let
type matching decide), and filter creation evidence with the same enclosing
function/unit identity used for calls. Add nested-construction and
nested-helper tests.

#### 3. Constructor injection still misses C# primary/record parameters and generic parameter lists

Location: `ServiceRulePack.kt:246` and `:632`, with the parameter regexes at
`:672` and `:673`.

Problem: `PRIMARY_CONSTRUCTOR` can extract class parameters, but
`PROPERTY_PARAMETER` requires `val` or `var`. C# 12 primary constructors and
positional records do not use `val`/`var`, so parameters such as
`class ApiClient(IHttpClientFactory factory)` or
`record ApiClient(IHttpClientFactory Factory)` are not emitted as wiring. The
classic C# constructor test does not cover this shape. In addition,
`splitParameters` splits on every comma, so a Kotlin or C# parameter with a
generic type containing a comma, such as `Map<String, UserService>` or
`Dictionary<string, IService>`, is fragmented and lost before the property
regex can match it.

Suggested fix: use syntax/parameter facts for constructor parameters, or at
least add depth-aware parameter splitting and recognize C# primary/record
parameter shapes. Add tests for both languages with a comma-containing generic
parameter.

### Loop status

The loop cannot close while these findings remain. Findings 1 and 2 are
creation-scanner correctness fixes in the pack; finding 3 needs either the
constructor-parameter extraction fix or an explicit deferral to a named
follow-up ticket with the C# primary/record and generic-parameter cases
recorded.

## Round 3

### Verdict

The creation-scanning fixes are correct. Leading file comments no longer
suppress scans; nested constructions retain the outer node; creation evidence is
scoped with `sameFunction`; and the C# primary/record and comma-containing
generic constructor tests cover the round-2 cases. I still cannot close the
loop: the constructor fix added `Factory` to the suffix set shared with
delegation, which creates a new delegation false positive, and constructor
injection remains unimplemented for TypeScript parameter properties even though
TypeScript is a supported pack language.

### Verification of round-2 findings

Concurs with the creation fixes: `isLiteralOrComment` now stops at the enclosing
declaration instead of climbing into a leading file comment; `creationNodes`
keeps all creation nodes rather than discarding an outer node when an inner one
exists; `creationWithin` uses `sameFunction` so a helper-local `new` does not
certify an outer lifecycle method; and the added tests exercise line/block
header comments, nested constructions, nested helpers, C# primary/record
constructors, and generic parameter lists containing commas. The existing
delegation/init boundary behavior remains intact: whole-expression forwarding,
unit-based mixed bodies, candidate-only controller fallbacks, init exclusion
from delegation, and receiver-gated generic connection/DI calls.

### Findings

#### 1. `Factory` was added to the shared service suffix set and now establishes delegation

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceConfig.kt:162`
and `:172`; consumed by `ServiceRulePack.kt:612` and `:616`.

Problem: `Factory` was added to `COMMON_SERVICE_SUFFIXES` to make
`IHttpClientFactory` constructor wiring match. The same suffix set drives
delegation receiver/type matching. A thin method such as
`fun build() = WidgetFactory.create()` now establishes `SERVICE_DELEGATION` on
the factory call. That is construction/factory work, which the ticket places
under `SERVICE_INIT`; the docs also state that init/setup calls are not emitted
as delegation. The change therefore fixes constructor coverage by broadening
the delegation boundary, and no test pins the new side effect.

Suggested fix: separate wiring suffixes from delegation suffixes, or add
`Factory` only through a constructor/init-specific configuration field (or
`initTypes`/`initQualifiers`) so factory construction does not automatically
become delegation. Add a negative delegation test for a thin `*Factory` call.

#### 2. TypeScript parameter-property constructor injection is not covered

Location: `ServiceRulePack.kt:246` and the parameter extraction at `:744` and
`:745`.

Problem: the class-level path requires `PRIMARY_CONSTRUCTOR` to match a class
parameter list directly after the class name, and the fallback
`PROPERTY_PARAMETER` requires Kotlin `val`/`var`. A supported TypeScript class
using the common parameter-property form
`constructor(private readonly service: UserService) {}` is missed: the class has
no primary parameter list in that position, the constructor is a
`method_definition` named `constructor`, and the C# branch does not run for
TypeScript. The generic and C# tests added in round 2 do not cover it, and the
new docs only claim Kotlin and C# constructor forms.

Suggested fix: recognize TypeScript constructor parameter properties
(`constructor(private readonly service: UserService)`) and emit `SERVICE_INIT`
on the parameter, or explicitly defer TypeScript constructor injection to a
named ticket and record the limitation. Add a TypeScript test either way.

### Loop status

The loop cannot close while these two findings remain. Both are in the
constructor/init side of the pack rather than the creation scanner; finding 1
should be fixed before closing because it reintroduces a delegation false
positive, and finding 2 should be fixed or explicitly deferred with a named
ticket.
