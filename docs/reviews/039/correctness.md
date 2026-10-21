# Correctness review — 039 service intents

Reviewed commit `3e5e4e6` (`feat(rulepacks): add service intent pack`) against
`main`. Read-only review: no build, tests, lint, or source edits were run.

## Verdict

Changes requested. The pack shape, ownership split, config surface, and overlap
policy are sound, and the candidate channel is used without leaking into
established output. The delegation thin-body test is too structural: it can
establish delegation for bodies that perform local work in the same statement,
and the controller/facade fallback establishes delegation for arbitrary thin
methods in those classes. Init detection is also language- and
declaration-position-limited: `new`/object-creation expressions are only
considered in variable/property initializers, so C#/JavaScript/TypeScript
construction in returns, assignments, and arguments is missed. The current tests
are Kotlin-only and do not cover the `new`-vs-call edge cases, configured
factory/qualifier paths, or the config-preservation bug below.

## Findings

### 1. `configure` discards the pack's existing config

**Location:** `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:36-37`

**Problem:** `configure(values)` builds `ServiceConfig.from(values)`, which starts
from defaults. `configureAdditive` correctly starts from the current config.
Constructing a pack with a non-default config and then applying project config
loses every value not repeated in the new override. This differs from
`SingleIntentPack` and the sibling multi-rule packs, which decode over the
current config.

**Reproduction shape:** `ServiceRulePack(configWithPrimary).configure(valuesWithSecondary)`
no longer recognizes `primary`.

**Suggested fix:** Use `ServiceRulePack(config.withOverrides(values))`, and add a
test that configures a pack built from non-default config and asserts both old
and new values remain active.

### 2. Thin-body detection treats a single statement as enough even when it contains local logic

**Location:** `ServiceRulePack.kt:86-97`, `260-269`

**Problem:** `isThin` only rejects multiple statements, writes, guards, and
tries. A single-statement body that wraps the forwarding call in additional local
work still qualifies, for example `return audit(service.find(id))`,
`return mapper.map(service.find(id))`, or `service.find(id).also { log(it) }`.
The forwarding call is nested inside a non-service call, but the rule emits
established `SERVICE_DELEGATION` at the inner service call. This contradicts the
acceptance criterion that a method which delegates and performs local logic is
not silently forced into delegation.

**Suggested fix:** Require the forwarding call to be the outermost call in the
unit, allowing only configured argument adaptation inside it. If another
non-configured call, lambda body, or transformation wraps the service call,
emit a candidate instead of an established annotation. Add negative tests for
`audit(service.find(id))` and `mapper.map(service.find(id))`.

### 3. The mixed-body ratio counts calls, not forwarding statements

**Location:** `ServiceRulePack.kt:66-97`, `272-275`

**Problem:** `mostlyDelegating(units.size, evidence.size)` uses the number of
matched calls as the numerator while `units` is the number of top-level
statements. Nested calls are counted separately, and one statement containing
two configured calls can count as two forwarding statements. This can promote a
body that is mostly local logic to a candidate, and it does not implement the
documented "at least half of its statements forwarding" policy. The candidate is
also emitted only for the first forwarding call, so multiple distinct forwarding
sites in one mixed body are not reviewable.

**Suggested fix:** Classify each top-level unit as forwarding, local, or mixed,
using only the outermost configured call per unit. Drive both the threshold and
the candidate span from that classification, and add nested-call and 2:1/1:2
ratio tests.

### 4. Controller/facade pattern establishes delegation for arbitrary thin methods

**Location:** `ServiceRulePack.kt:70-78`, `326-334`

**Problem:** When no call matches a service marker, any thin method whose
enclosing class ends with a configured controller/facade suffix is treated as
delegation using every outermost call. A controller or facade routinely contains
thin local helpers, validation, logging, and formatting. For example,
`class UserController { fun format(x: X) { render(x) } }` and
`class UserController { fun validate() { require(ok) } }` both emit established
`SERVICE_DELEGATION` with no service call involved. The positive test only
covers `worker.run(id)`.

**Suggested fix:** Restrict the fallback to calls that at least look like
configured service/facade boundaries, or emit a candidate when the receiver is
not service-like. Add negative tests for local formatting, validation, and
logging calls inside controllers/facades.

### 5. Hidden-init candidates are emitted for methods that already have established init evidence

**Location:** `ServiceRulePack.kt:148-166`

**Problem:** The hidden-helper loop skips lifecycle-named methods and
constructors, but not methods that already received annotation evidence. A
`@Bean fun client(): HttpClient { return HttpClient() }` receives the annotation
emit, the constructor-call emit, and then a `LOW_CONFIDENCE` candidate labelled
"hidden initialization helper" for the same method. More generally, any
non-lifecycle function containing a configured init call gets a candidate even
when the init call itself was established at the call span.

**Suggested fix:** Track declarations that received annotation or lifecycle
evidence and suppress the hidden-helper candidate for them. Consider requiring
the function body to be mostly init/wiring, rather than only containing one init
call, before proposing a helper candidate.

### 6. `new` construction is only detected in declaration initializers

**Location:** `ServiceRulePack.kt:123-146`, `189-202`

**Problem:** The C#/JavaScript/TypeScript call profiles expose only
`invocation_expression`/`call_expression` as call facts; `new_expression` and
`object_creation_expression` are not calls. `creationEvidence` is only invoked
for property/variable declaration initializers. Consequently
`return new HttpClient()`, `this.client = new HttpClient()`,
`Send(new HttpClient())`, and C# target-typed `new()` never emit `SERVICE_INIT`.
Kotlin constructor calls happen to be call facts, so the current Kotlin-only
tests hide the gap. The same declaration-initializer limitation applies to
JavaScript/TypeScript.

**Suggested fix:** Traverse the syntax tree for new/object-creation nodes in all
expression positions, or add a creation fact to the model, and deduplicate against
call evidence. Add C# and JavaScript/TypeScript tests covering return,
assignment, argument, and target-typed construction.

### 7. `NEW_TYPE` inspects initializer text instead of the creation node

**Location:** `ServiceRulePack.kt:200-202`, `233`

**Problem:** The regex is applied to the initializer's full text. It can match
inside a string literal (`val label = "new HttpClient()"`), inside a comment, or
inside a conditional/embedded expression, and it emits the whole initializer
span rather than the actual creation expression. That is both a false-positive
path and a span-accuracy problem.

**Suggested fix:** Find an actual `new_expression`/`object_creation_expression`
node within the initializer and emit that node. Require it to be the effective
initializer expression rather than any descendant text match.

### 8. Init spans are anchored to `call.site`, which can widen to an outer call

**Location:** `ServiceRulePack.kt:116-120`, `205-229`

**Problem:** Call-based init emits `call.emit`, which uses `call.site`. For a
chained call such as `HttpClient().apply { configure() }`, `call.site` can be the
outer chain including the lambda, so the `SERVICE_INIT` span covers more than the
construction or connection call. Lifecycle and DI emits use the whole
declaration, and `@Inject val client = HttpClient()` can produce nested/overlapping
`SERVICE_INIT` annotations at declaration and call spans.

**Suggested fix:** Emit init at the constructor/creation/connection call node
itself, define one span policy for annotation/lifecycle declarations (name or
signature versus whole declaration), and assert exact byte spans in tests.

### 9. Tests do not cover the per-language and constructor edge cases

**Location:** `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/service/ServiceDelegationRulePackTest.kt`, `ServiceInitRulePackTest.kt`, `ServiceOverlapTest.kt`

**Problem:** Every rule test is Kotlin. The per-language config claims are
unverified for C#/JavaScript/TypeScript, and those languages are exactly where
`new`/object-creation expressions are not call facts. There are no tests for
configured factory/init qualifiers, `new` in non-initializer positions, C#
target-typed `new()`, stray `new` text, delegation wrapping local logic,
controller/facade negatives, or exact spans. There is also no golden/Testing
Grounds fixture scenario.

**Suggested fix:** Add a small language matrix with C#, JavaScript, and
TypeScript construction/connection cases, negative controller/facade cases, and
span assertions. Add at least one fixture-based scenario if that is the intended
meaning of the acceptance criterion.

### 10. The pack `configure` test would pass even if overrides were dropped

**Location:** `analysis-engine/core/rulepacks/src/test/kotlin/dev/cint/rulepacks/ServiceConfigTest.kt:100-115`

**Problem:** `packConfigureUsesTypedOverridesAndAdditiveConfiguration` only
asserts `id` and `languages`, both of which are unchanged by dropping the
override. It therefore does not catch finding 1 or verify that `configure`
applies the typed values.

**Suggested fix:** Assert behavior through the configured pack (for example that
`primary.find(...)` emits delegation but `service.find(...)` does not), and add a
case that starts from a non-default config.

## Concurs / dissents

**Concurs:** The two-rule pack is the right shape: `service:delegation` owns
`SERVICE_DELEGATION`, `service:init` owns `SERVICE_INIT`, both read nothing, and
both share the same per-language config without cross-owning intents. The
overlap policy (delegation does not suppress SEARCH/DATA_MODIFY/ERROR_HANDLING)
is correctly implemented by keeping rules independent. Candidate emits use the
rule source id, stay out of established output, and default to `aiEligible`.
Leaving the pack unregistered is consistent with the sibling scaffold tickets and
the dedicated doc, subject to orchestrator sign-off on the acceptance wording.

**Dissents:** I dissent from treating any thin controller/facade call and any
single-statement wrapper as established delegation, and from treating service
construction as a declaration-initializer-only concern. Those choices produce
established false positives and miss common C#/JavaScript/TypeScript
construction forms; they should be corrected or explicitly documented as
candidate-only/out of scope before this ticket closes.

## Round 2

Reviewed commit `e68af02` (`fix(rulepacks): tighten service intent detection`)
against `3e5e4e6`. Read-only review: no build, tests, lint, or source edits were
run.

### Verdict

Changes requested. The round-1 findings are substantially addressed: configure
now preserves prior values, delegation requires a single whole forwarding
expression, mixed bodies are measured in top-level units, controller/facade
fallbacks are candidates, hidden helpers skip annotated/lifecycle functions,
init evidence suppresses delegation, generic connection/DI names are
receiver-gated, spans use the call or creation node, and cross-language tests
plus a fixture were added. The new creation-node pass nevertheless has two
correctness defects: a leading file comment disables all creation detection, and
nested constructions keep the innermost node instead of the outer configured
type. Constructor injection also still misses common parameter shapes. The loop
cannot close yet.

### Verification of round-1 findings

- **Configure preserve:** Concurs. `ServiceRulePack.configure` now uses
  `config.withOverrides(values)`, and both the config-level and pack-level tests
  verify that old values survive while new values apply.
- **Thin/mixed delegation:** Concurs for the reported cases. `analyzeUnit`
  rejects nested calls, trailing lambdas, local wrappers, chained operators, and
  multi-branch dispatch as established output; the mixed-body numerator counts
  top-level forwarding units and emits one candidate per forwarding site. The
  added tests cover the intended boundaries.
- **Controller fallback candidate-only:** Concurs. A thin call with no service
  evidence now receives a `LOW_CONFIDENCE` candidate, not an established
  annotation; local controller helpers are covered by negative tests.
- **Hidden-init suppression:** Concurs. Configured annotations and lifecycle
  spans are tracked, and the hidden-helper loop skips both. The `@Bean` test no
  longer produces a redundant hidden-helper candidate.
- **Creation scanning/spans:** Partially concurs. `new` is now found in return,
  assignment, and argument positions for JS/TS/C#, target-typed `new()` is
  handled for an explicit local declaration, and spans are the creation node.
  The leading-comment and nested-creation defects below remain.
- **Init/delegation separation:** Concurs. `delegationEvidence` returns null for
  calls with init evidence, and the added test asserts `client.connect()` is
  init-only.
- **Generic name candidates:** Concurs. Unqualified `connect`, `resolve`, and
  similar names are candidate-only; configured receiver names, qualifiers, or
  resolved types establish them. Use/teardown calls on init types stay untagged.
- **Cross-language tests:** Concurs with the added coverage. JS/C# delegation,
  JS/TS/C# non-initializer `new`, C# target-typed `new`, C#/Kotlin constructor
  injection, oracle receiver types, and a Kotlin fixture are present. The new
  scanner defects are not covered.

### Findings

#### 1. A leading file comment disables all creation detection

**Location:** `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:317`, `332-341`

**Problem:** `isLiteralOrComment` walks from each candidate node to the root and
returns true when any ancestor's trimmed text starts with `//`, `/*`, a quote,
or a backtick. If the file begins with a license or header comment, the root
source text itself matches, so every descendant is treated as a literal/comment.
A file beginning `// license` and containing `return new HttpClient()` therefore
emits no `SERVICE_INIT` for the creation. The new tests all start with code or
put comments after code, so this path is unverified.

**Suggested fix:** Determine literal/comment status from node kind, or stop the
ancestor walk at the enclosing declaration/root boundary and never treat the
whole source root as a literal. Add a test with a leading line comment and one
with a leading block/doc comment before a valid `new HttpClient()`.

#### 2. Nested creations keep the innermost node, and creation evidence is not function-scoped

**Location:** `ServiceRulePack.kt:323-329`, `281-307`

**Problem:** The `filterNot` in `creationNodes` removes a creation when another
creation is contained within it, so it keeps the innermost node. For
`new HttpClient(new HttpClientHandler())`, the outer `HttpClient` is discarded
and the inner `HttpClientHandler` remains; when the inner type is not configured,
no `SERVICE_INIT` is emitted even though the outer configured type was
constructed. Separately, `functionHasInitEvidence` and `mostlyInit` test
`within(node, function.node)` against a global creation list, so a creation inside
a nested local function can establish an outer lifecycle function as initialized
even when the outer function only declares the helper.

**Suggested fix:** Keep the outermost creation node, or keep all creation nodes
and let configured type matching decide, and scope creation evidence to the same
enclosing function/unit identity used for calls. Add tests for
`new HttpClient(new HttpClientHandler())` and for a lifecycle method that only
declares a nested helper containing a construction.

#### 3. Constructor injection still misses common parameter shapes

**Location:** `ServiceRulePack.kt:246-278`, `671-678`

**Problem:** The primary-constructor regex only matches a class/record name
immediately followed by `(`, so Kotlin's common
`class Foo @Inject constructor(private val service: UserService)` form is
missed. `PROPERTY_PARAMETER` requires `val` or `var`, so C# 12 primary
constructors and positional records such as
`record ApiClient(IHttpClientFactory Factory)` are missed. `splitParameters`
also splits on every comma, fragmenting generic parameter types such as
`Map<String, UserService>` or `Dictionary<string, IService>` before type
matching.

**Suggested fix:** Use syntax/parameter facts for constructor parameters where
possible, or add an annotation-tolerant primary-constructor pattern, a C#
primary/record parameter shape, and depth-aware parameter splitting. Add Kotlin
`@Inject constructor`, C# record/primary-constructor, and comma-containing
generic parameter tests.

### Concurs / dissents and loop status

**Concurs:** The round-1 correctness findings on configure preservation,
thin/mixed delegation, controller fallback, hidden-init suppression, init/
delegation separation, generic-name candidates, spans, and cross-language tests
are addressed. The delegation/init ownership split and overlap policy remain
correct.

**Dissents:** I dissent from closing while the creation scanner can be disabled
by a leading comment and can select an inner creation over the configured outer
construction, and while common annotation-based/C# primary-constructor injection
shapes are missed. These are correctness gaps in the newly added construction
path, not re-reports of the addressed round-1 findings.

**Loop status:** The loop cannot close yet. A round 3 should fix the creation
scanner selection/scoping defects and either fix or explicitly defer the
constructor-parameter gaps to a named ticket with the missing shapes recorded.

## Round 3

Reviewed commit `aca49e0` (`fix(rulepacks): harden service creation scanning`)
against `e68af02`. Read-only review: no build, tests, lint, or source edits were
run.

### Verdict

The round-2 creation-scanner findings are fixed: leading file comments no longer
disable scanning, nested constructions retain the configured outer creation,
creation evidence is scoped to the enclosing function, generic parameter lists
are split at the correct depth, and C# primary/record constructor parameters are
read. The round-1 findings remain addressed. Two issues remain before closure:
the previously reported Kotlin `@Inject constructor` shape is still neither
recognized nor explicitly deferred, and adding `Factory` to the shared
`serviceSuffixes` broadens established delegation to any factory-named receiver,
including non-service ones. The loop cannot close until those are fixed or
formally accepted/deferred.

### Verification

- **Leading-comment creation scan:** Concurs. `isLiteralOrComment` now stops at
  the enclosing declaration boundary instead of walking to the file root, and
  the new tests cover leading line and block comments for JavaScript plus a
  leading line comment for C#. The old root-text failure is removed.
- **Nested creation retention:** Concurs. The innermost-only `filterNot` was
  removed, so `new HttpClient(new HttpClientHandler())` retains and emits the
  outer `HttpClient`, and the test asserts that span. Retaining all creation
  nodes also means both can emit when both types are configured; that is
  acceptable under the earlier "keep all and let type matching decide" option.
- **Function-scoped creation evidence:** Concurs. `creationWithin` requires the
  creation's enclosing function to be the same function, and the nested-helper
  lifecycle test confirms that a construction inside a local helper no longer
  certifies the outer lifecycle method.
- **Constructor injection: generics and C# primary/record:** Concurs. Parameter
  splitting is depth-aware, property types are captured in full, type matching
  tokenizes generic arguments, and C# primary/record parameters are handled for
  the C# language. The new Kotlin/C# generic and C# primary/record tests cover
  the previously reported shapes.
- **Previous findings:** Concurs with the earlier verification of configure
  preservation, thin/mixed delegation, controller fallback candidate-only,
  hidden-init suppression, init/delegation separation, generic-name candidates,
  spans, and cross-language tests; `aca49e0` does not regress those paths.

### Findings

#### 1. Kotlin `@Inject constructor` is still missed and was not deferred

**Location:** `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:257`, primary-constructor regex at `:744`

**Problem:** `PRIMARY_CONSTRUCTOR` still requires the class/record name to be
followed immediately by the parameter list. The common Kotlin form
`class Foo @Inject constructor(private val service: UserService)` therefore does
not match, and Kotlin primary/secondary constructors are not `FunctionFact`s, so
the constructor-function loop cannot recover it. The round-2 finding asked for
this shape to be fixed or explicitly deferred; the current documentation narrows
the stated coverage to "Kotlin property parameters" but names no follow-up
ticket or reason.

**Suggested fix:** Allow annotations and the `constructor` keyword between the
class name and parameter list, or use syntax/parameter facts, and add a Kotlin
`@Inject constructor` test with a service-typed property parameter. If the
narrower scope is intentional, record an explicit deferral to a named ticket.

#### 2. `Factory` in the shared service suffixes makes factory receivers delegation

**Location:** `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceConfig.kt:172`, used for delegation at `ServiceRulePack.kt:612-616`

**Problem:** `Factory` was added to `COMMON_SERVICE_SUFFIXES` to support
`IHttpClientFactory` constructor injection, but the same suffix set drives
delegation receiver-name and receiver-type matching. A one-statement method such
as `fun log() { loggerFactory.info("x") }` now establishes
`SERVICE_DELEGATION` because `loggerFactory` ends with `Factory`; other
factory-named helpers (for example `widgetFactory.render()`) are treated the
same way. This reintroduces the broad non-service receiver false positive that
the controller fallback fix removed, outside controllers, and no test covers the
new factory delegation path.

**Suggested fix:** Keep `Factory` only in a wiring/constructor-injection suffix
set (or add the concrete factory types such as `IHttpClientFactory` to
`initTypes`/`serviceTypes`) instead of the shared delegation suffixes. Add a
negative test that a factory-named receiver performing logging or local work is
not established delegation.

### Concurs / dissents and loop status

**Concurs:** The requested round-3 fixes for leading-comment scanning, nested
creation retention, function-scoped creation evidence, and constructor
generics/C# primary/record parameters are correct, and the round-1 correctness
findings remain addressed.

**Dissents:** I dissent from closing while the prior Kotlin `@Inject
constructor` finding is neither fixed nor explicitly deferred, and while the
`Factory` suffix change produces a new established-delegation false positive for
factory-named receivers.

**Loop status:** The loop cannot close yet. A round 4 should fix the Kotlin
`@Inject constructor` form (or defer it to a named ticket) and confine `Factory`
matching to wiring rather than delegation, with negative tests. If the
orchestrator instead formally accepts the narrower Kotlin constructor scope and
the factory-receiver behavior as intended, those items may be recorded as
accepted limitations and the ticket can close.
