# Correctness review — ticket 048 (local mutation and callback effect coverage)

## Round 1

### Verdict

Changes requested. The default-off mutable-declaration policy is coherent, the
motivating `NeighboursCli.publish` range is tagged as intended, and the
`Files::move` default establishes `DATA_MODIFY` while `notify` stays generic.
However, the declaration detector does not actually work for JavaScript or
TypeScript (and its C# keyword check cannot see `const`), and generic callback
invocations can still be emitted as `LOCAL_VALUE_MODIFY` candidates by the
mutation call loop, contrary to the ticket and the docs. The focused tests miss
both paths, and there is no combined Mutation+Service test pinning the durable
callback policy.

Baseline: read-only review of the uncommitted working tree on
`ticket/048-local-mutation` (base `269a385`), against the ticket, `docs/workflow.md`,
the 040/042/046 reviews, the mutation/service rules, `CallbackFacts.kt`, the new
fixture/snapshot, and the focused tests. No source edit, build, test, formatter,
or staging command was run.

### Findings

#### 1. Conditional/typed callback invocations can still become `LOCAL_VALUE_MODIFY` candidates

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:210-231`
(the call loop, especially the `callee in language.mutableOperations ||
isLocalCandidate(callee)` branch),
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/CallbackFacts.kt:65-73`.

Problem: `callbackParameterAt` is consulted by `DataModifyRule` and
`ServiceDelegationRule`, but not by `LocalValueModifyRule`. For a
function-typed parameter whose name is in the mutation vocabulary, the same call
is both a callback and a generic local-mutation candidate. For example:

```kotlin
fun run(mutate: (String) -> Unit) {
    mutate("x")
}
```

`mutate` is in `LOCAL_CANDIDATE_EXACT`, `persistence.candidate` is false, and the
bare call falls into the candidate branch at `:221-230`. Depending on the name,
`add`/`clear`/`replace`/`touch`/`increment`/`edit`/`modify` have the same result.
The full pipeline then reports `SERVICE_DELEGATION` and
`LOCAL_VALUE_MODIFY` on the same span. This contradicts
`docs/engine.md:164-165` and `docs/intent-model.md:218-219` ("Neither shape
becomes `LOCAL_VALUE_MODIFY` solely because the callee is a function parameter")
and the ticket's "generic callbacks are not silently painted as local
mutations".

Suggested fix: in `LocalValueModifyRule`'s call loop, skip calls for which
`callbackParameterAt(view, call) != null` before generic local-candidate
classification (the durable/generic callback decision already lives in the
mutation/service callback paths). Add a test with a callback named `mutate` (or
`add`) that asserts no `LOCAL_VALUE_MODIFY` candidate, plus the expected
`SERVICE_DELEGATION` candidate/`DATA_MODIFY` source.

#### 2. `mutableLocalDeclarations` is dead for JavaScript/TypeScript, and the C# keyword check is ineffective

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:525-539`
(`isMutableLocalDeclaration`),
`analysis-engine/core/core/src/main/kotlin/dev/cint/core/syntax/DeclarationFacts.kt:46-67`
(the declaration node is the property fact node),
`analysis-engine/languages/treesitter/src/main/resources/syntax/javascript.yaml:9-11`
and `csharp.yaml:9-11` (property kinds are `variable_declarator`).

Problem: the helper inspects `declaration.node.text`, but for JS/TS the
declaration node is a `variable_declarator` whose text is `count = 0`, not
`let count = 0`/`var count = 0`. Therefore
`text.startsWith("var ") || text.startsWith("let ")` is never true, and setting
`mutableLocalDeclarations: true` for JavaScript or TypeScript emits no
declaration tags at all. For C#, the node text is likewise `x = 0`, so the type
and `const`/`readonly` modifiers are not in the inspected text; the branch
effectively treats every C# local as mutable (only harmless because a `const`
local cannot be reassigned). The Kotlin branch works because
`property_declaration` includes `var` in its text. This contradicts
`docs/engine.md:154-157` ("mutable `var`/`let` local declarations") and makes the
per-language config semantics misleading. The tests only exercise Kotlin, so the
dead branches stay green.

Suggested fix: derive mutability from the declaration wrapper/parent
(`lexical_declaration`/`variable_declaration`/`local_declaration_statement`, the
profile's `declarationWrapperKinds`) rather than from the declarator/declaration
leaf text, and emit on the same wrapper the policy describes. Alternatively,
narrow `mutableLocalDeclarations` to Kotlin and update the docs/config. Add
config-on tests for JS/TS `let`/`var` (and `const` negative) and a C# mutable
local/`const` negative.

#### 3. Tests are not fencing the new risk paths

Location:
`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/mutation/MutationLocalValueModifyRuleTest.kt:82-106`
(the bookkeeping/copy test),
`:108-169`
(the durable/generic callback tests),
`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/service/ServiceDelegationRulePackTest.kt:265-308`,
and `analysis-engine/tests/integration-tests/src/test/kotlin/dev/cint/integration/host/PipelineSnapshotTest.kt:103-145`
(snapshot records source annotations only).

Problem:

- `configuredMutableLocalDeclarationsSkipPureCopyAndBookkeepingLocals` places
  `@Bookkeeping var skipped` at class scope. That is `DeclarationKind.PROPERTY`,
  so `isMutableLocalDeclaration` rejects it before the declaration loop; for
  bookkeeping, the test therefore exercises only the existing write path and
  never the bookkeeping-local or loop-counter declaration suppression. (Its
  `store_db` half does validly cover the local-copy declaration path.)
- No test uses a callback name that collides with the local vocabulary, so
  finding 1 is not fenced.
- No mutation test loads `ServiceRulePack`, and no service test asserts
  `DATA_MODIFY`; the snapshot captures only annotations, not candidates.
  Consequently the combined policy "durable callback is `DATA_MODIFY` and not a
  generic delegation candidate" (and the detection-fidelity split-set failure)
  cannot make any test fail.
- There is no config-on JS/TS/C# declaration test, so finding 2 stays green.

Suggested fix: move the `@Bookkeeping var` into the function, add a
`for (var i = ...)`-style declaration test that asserts suppression, add the
callback-name collision test from finding 1, add JS/TS/C# config-on cases, and
add a combined Mutation+Service test on a `Files::move` default asserting the
`DATA_MODIFY` source span and an empty `SERVICE_DELEGATION` candidate set (plus
the generic `notify` case).

#### 4. Docs overstate loop-counter and language coverage

Location: `docs/engine.md:154-165`, `docs/intent-model.md:211-219`.

Problem: `docs/engine.md` says mutable declaration tagging leaves "loop
counters" out, but `isLoopCounter` only suppresses writes inside `for`/`foreach`
loops. With `mutableLocalDeclarations` on, a `var i` used as a `while` counter is
tagged at its declaration, consistent with the existing policy that explicit
while-loop increments are local modifications, but inconsistent with this
sentence. The same docs claim `var`/`let` coverage despite the JS/TS dead branch
(finding 2), and "configured durable path/call" while service-side suppression
uses a hardcoded set (see detection-fidelity finding 2 below). These are
acceptance-level docs for the ticket.

Suggested fix: say "implicit `for`/`foreach` counters" or otherwise qualify the
loop-counter claim, and align the language/durability wording with the final
implementation after findings 2 and the detection-fidelity split-set finding are
resolved (or narrow the documented scope explicitly).

### Concurrence with the detection-fidelity report

I read `docs/reviews/048/detection-fidelity.md` and concur with its findings
rather than re-reporting them as new correctness findings:

- **Annotated-function parsing.** `CallbackFacts.callbackParameters` uses the
  first `(` in `function.text`, which includes Kotlin modifiers/annotations, so
  an annotated function such as
  `@Contract(pure = true) fun publish(move: (Path, Path) -> Unit = Files::move)`
  parses the annotation argument list and can silently miss both `DATA_MODIFY`
  and the generic candidate. This is the same root cause; it needs a
  parameter-list lookup anchored to the function name/parameter node.
- **Split durable-target sets.** `DataModifyRule` uses the configured
  `persistencePaths + persistenceCalls`, while `ServiceRulePack` uses the fixed
  `KNOWN_DURABLE_CALLBACK_TARGETS`; configured-only durable targets get both
  `DATA_MODIFY` and a `SERVICE_DELEGATION` candidate, and hardcoded-only targets
  such as TypeScript `fs.rename`/`fs.copyFile`, or a configured set that removes
  `Files.move`, leave the call untagged because service suppresses the
  candidate. A service-only plan has the same silence for `Files::move`.
  `containsConfiguredTarget` also requires a dot, so a configured bare
  `persistenceCalls` target is never durable despite the "path/call" wording.
  The two packs need one shared, config-driven predicate and combined tests.
- **JS/TS bare-reference defaults.** `isFunctionTyped` only recognizes `::`,
  `=>`, `->`, and `{`, so `function publish(move = Files.move)` or
  `const publish = (move = fs.rename) => ...` is not recognized as a callback at
  all. This is a real correctness gap for the multi-language feature.
- **SAM-interface callbacks.** `callbackParameterAt` rejects every receiver
  call, so the `Consumer<`/`Runnable`/`Function<` markers can never match
  `consumer.accept(x)`/`runnable.run()`. Either implement the SAM invocation
  mapping or narrow the marker set and docs.

I have no independent correctness objection to the default-off choice, the
Kotlin assignment tagging, the `LOCAL_COPY`/`BOOKKEEPING` suppression, or the
new fixture/snapshot beyond the issues above.

### Concurs / Dissents

- Concurs: `mutableLocalDeclarations` defaults to `false`, is exposed through
  the config schema, and Kotlin mutable locals with later effective writes are
  tagged when enabled while unwritten/`val` locals and pure-copy/bookkeeping
  writes are suppressed.
- Concurs: the motivating fixture's assignment writes are `LOCAL_VALUE_MODIFY`,
  `move(...)` is `DATA_MODIFY`, and `notify(...)` is not a local mutation under
  the default policy; the snapshot shows the intended Kotlin/fixture delta.
- Concurs: the existing write filtering (`assignmentPatterns`, `LOCAL_COPY`,
  bookkeeping, `for` counters) is preserved by the `effectiveWrite` extraction,
  and the 046 `LOCAL_COPY`/`ignorePureCopies` policy is still read rather than
  duplicated; the 042 compound-persistence/DataAccess behavior is untouched (the
  cross-pack risk is the durable-target split below).
- Concurs: the new declaration/callback loops are stateless per rule run and the
  `emitted` dedupe set is local, so I found no state or reentrancy issue.
- Concurs with detection-fidelity findings 1-4 (annotated parsing, split durable
  sets, JS/TS bare defaults, SAM invocations); the associated tests are missing
  or do not load the packs together.
- Dissents: findings 1 and 2 are unresolved behavior/config-semantics gaps in
  this diff, finding 3 is the missing regression fence for those and the
  cross-pack policy, and finding 4 leaves the ticket docs inaccurate.

### Summary

The mutable-local side is sound for Kotlin and the default-off choice is
well-supported: the fixture's writes are consistently tagged, `move` is
`DATA_MODIFY`, `notify` is not painted as a local mutation, and the existing
`LOCAL_COPY`/bookkeeping/loop-counter suppression is preserved. Two correctness
gaps remain in the new code. `LocalValueModifyRule` never consults
`callbackParameterAt`, so a callback parameter named `mutate`/`add`/`touch`/
`clear` can still emerge as a `LOCAL_VALUE_MODIFY` candidate on the same span as
its `SERVICE_DELEGATION` candidate, contradicting the documented contract. And
`isMutableLocalDeclaration` inspects the declaration leaf text, which for
JS/TS is `count = 0` rather than `let count = 0` (so the feature is dead) and for
C# cannot see `const`; only Kotlin is actually exercised. The tests do not catch
either path, and the bookkeeping-local test is vacuous because its declaration
is a class property. The detection-fidelity findings (annotated-function
parameter parsing, the split configured/hardcoded durable-target sets,
bare-reference JS/TS defaults, and SAM callbacks) are also unresolved; I concur
with them. Fixing the two independent correctness findings, adding the
cross-pack/language tests, and aligning the docs would close this dimension.

## Round 2

### Verdict

Changes requested, but the requested behavior is substantially resolved. The
callback/local split, the language-specific mutable-declaration detection, the
bookkeeping/loop-counter/callback-collision tests, the annotated-parameter
parsing, the shared durable policy through `optionalReads`, the JS/TS bare
defaults, and the SAM mapping are all implemented and mostly pinned. Two
new/unresolved items remain: the wrapper-based JS/TS/C# declaration emission
over-covers sibling declarators in multi-declarator statements, and the new
`Rule.optionalReads` member changes the frozen `api` surface without a contract
pin or documentation update (the architecture still lists optional dependencies
as a deferred non-goal and shows a stale stratum example).

Baseline: read-only review of the same uncommitted working tree, now including
the round-2 changes (`Contract.kt`, `RuleEngine`/`RuleScheduler`, new
`CallbackFacts` behavior, mutation declaration wrapper handling, and the new
tests/docs). No source edit, build, test, formatter, or staging command was run.

### Resolution status

- **Round-1 correctness finding 1 (callback names painted as local mutations) —
  resolved.** `LocalValueModifyRule` now skips calls for which
  `callbackParameterAt` is non-null, and
  `callbackNamesCollidingWithLocalVocabularyStayOutOfLocalMutation` pins the
  `mutate` case with no `LOCAL_VALUE_MODIFY` candidate and a
  `SERVICE_DELEGATION` candidate.
- **Round-1 correctness finding 2 (JS/TS dead, C# keyword check) — resolved for
  single-declarator declarations.** JS/TS/C# now resolve the declaration
  wrapper and the cross-language test covers `let`/`var`/typed C# positives and
  `const` negatives. The wrapper introduces the multi-declarator issue in new
  finding 1 below.
- **Round-1 correctness finding 3 (test fencing) — resolved.** The
  bookkeeping declaration is now a function-local, `for (var i ...)` suppression
  is covered, callback-name collision and combined mutation+service cases are
  present, and the cross-pack durable tests exercise the configured/removed
  target paths.
- **Round-1 correctness finding 4 (docs) — resolved.** `docs/engine.md` and
  `docs/intent-model.md` now qualify implicit `for`/`foreach` counters and
  describe the wrapper, callback, and optional-read behavior.
- **Detection-fidelity findings 1-4 — resolved in the implementation and
  tests.** Annotated functions use the callable parameter list, durable
  classification is shared through the optional `DATA_MODIFY` read (no hardcoded
  service set), bare JS/TS reference defaults are recognized, and SAM receiver
  calls map `run`/`accept`/`apply`/`test`/`call`/`get` to the function-typed
  parameter. The `fs.rename`/`copyFile` defaults are also added and tested.

### Findings

#### 1. Multi-declarator JS/TS/C# statements paint unwritten sibling locals

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:540-587`
(`mutableLocalDeclarationNode` and `declarationWrapper`),
`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/mutation/MutationLocalValueModifyRuleTest.kt:136-190`
(only single-declarator language cases).

Problem: for JS/TS/C#, `mutableLocalDeclarationNode` returns the outer
declaration wrapper (`lexical_declaration`, `variable_declaration`,
`local_declaration_statement`, or `export_statement`), while the loop iterates
per `DeclarationFact` and dedupes by wrapper span. A multi-declarator statement
therefore emits one `LOCAL_VALUE_MODIFY` annotation covering every declarator as
soon as any one of them has a later effective write. For example:

```javascript
function f() {
    let written = 0, untouched = 0;
    written = 1;
}
```

emits on `let written = 0, untouched = 0;`, including `untouched`, which has no
write. The same happens for C# `int written = 0, untouched = 0; written = 1;`,
and for a pure-copy declarator sharing a statement with a written one. This
contradicts `docs/engine.md`/`docs/intent-model.md` ("unwritten locals stay out",
"declarations used only for pure copies ... stay out") and means the new
language tests only pin the single-declarator path.

Suggested fix: when the wrapper covers more than one declarator, emit on the
specific `declaration.node` (the declarator) or otherwise narrow the span to the
written declarator; keep wrapper emission only for single-declarator statements
(or document statement-level emission and add a test that pins it). Add JS/TS
and C# multi-declarator positive/negative tests, including one written and one
unwritten sibling and one written plus one pure-copy sibling.

#### 2. `Rule.optionalReads` is an unpinned, undocumented change to the frozen API/contract

Location:
`analysis-engine/core/api/src/main/kotlin/dev/cint/api/Contract.kt:17-23`
(the new `optionalReads` member),
`analysis-engine/core/api/src/main/kotlin/dev/cint/api/Model.kt:5-19`
(the v5 KDoc records other additive surfaces but not this one),
`analysis-engine/core/api/src/test/kotlin/dev/cint/api/ContractTest.kt`
(no default/API pin for the new accessor),
`docs/architecture.md:84-89,104-140,360-366`.

Problem: the frozen `api` module gained a public `Rule` member. It is a JVM
default method (so existing rule implementations remain source/binary
compatible and no `CINT_API_VERSION` bump is required; the compiled class
confirms `getOptionalReads` is a default method), but the additive/no-bump
decision is not recorded next to the other additive surfaces in `Model`, and no
contract test pins that a `Rule` not overriding it yields `emptySet()`. The
architecture is also contradicted by the implementation: its `Rule` snippet has
no `optionalReads`, §4 describes only hard `reads` edges and still shows
`service:delegation` in stratum 0 with "no dependencies", and §10 still lists
"optional dependencies (unknown dependency is a hard error)" as a deferred
non-goal. With the mutation pack present, service delegation is now ordered
after `mutation:data-modify` through the optional read, so the worked example and
the plugin contract are stale.

Suggested fix: add a `ContractTest` assertion that a `Rule` implementation which
does not override `optionalReads` exposes an empty set (and, if desired, that the
accessor is a JVM default method), add the optional-read no-bump note to the v5
KDoc in `Model.kt`, and update `docs/architecture.md`'s `Rule` snippet, §4
optional-edge semantics/worked strata example, and §10 non-goals. Add a
service-only callback test (durable default without the mutation owner) so the
"service pack can be loaded alone" claim is pinned end-to-end.

### Residual observations (not filed)

- `CallbackFacts.matchingClose`/`splitTopLevel` still scan raw parameter text
  without skipping strings/comments. The annotated-function failure is fixed,
  but a default lambda/string containing an unbalanced `(` or `)` can still
  truncate or mis-split the parameter list. A focused "default containing a
  parenthesized string" test would close the remaining parser edge.
- `containsConfiguredTarget` still requires a dotted target, so a callback
  default that references a configured bare `persistenceCalls` name is not
  durable. The docs now say "qualified", so this is a documented boundary rather
  than an immediate defect.
- The service-only optional-owner-absent path is covered at scheduler level but
  not with a durable callback in an end-to-end Service-only analysis; the new
  full-pack tests cover the owner-present / configured / removed cases.

### Concurs / Dissents

- Concurs: Round-1 finding 1 is resolved; callback parameter names no longer
  leak into `LOCAL_VALUE_MODIFY`, and the combined test asserts both sides.
- Concurs: Round-1 finding 2 is resolved for the tested single-declarator
  shapes; JS/TS/C# mutable declarations now classify from the wrapper and the
  const/`for`-counter negatives are pinned.
- Concurs: Round-1 findings 3-4 and detection-fidelity findings 1-4 are
  addressed by the new tests and docs, including the shared optional-read
  durability policy, bare defaults, SAM mapping, and annotated functions.
- Concurs: `optionalReads` is defaulted safely as a JVM default method; existing
  packs do not need recompilation and the scheduler's optional-relation tests
  are non-vacuous for owner-absent and owner-present cases.
- Dissents: finding 1 (multi-declarator over-coverage) and finding 2
  (unrecorded/unpinned API contract plus stale architecture) are unresolved.

### Summary

Round 2 fixes the round-1 behavior findings: callbacks are no longer painted as
local mutations, JS/TS/C# mutable declarations work through the declaration
wrapper, the bookkeeping/loop-counter/collision/cross-pack tests are present,
annotated and SAM callbacks plus bare JS/TS defaults are classified, and the
durable policy is shared through an optional `DATA_MODIFY` read instead of a
hardcoded service list. The optional-read scheduler change is functionally
sound (absent owner falls back to a candidate, present owner orders the service
after mutation), and `optionalReads` is a JVM default so existing packs remain
compatible without an API bump. Two items remain: multi-declarator JS/TS/C#
statements currently tag the whole wrapper, so an unwritten sibling is painted
by a written sibling's annotation (finding 1), and the new public contract
surface plus optional-edge semantics are not recorded or pinned, with the
architecture still claiming optional dependencies are a non-goal and showing a
stale dependency-free service stratum (finding 2). Fixing or explicitly
deferring those two, with the suggested tests/docs, would close the dimension.

## Round 3

### Verdict

Changes requested for one new edge in the SAM mapping; the two Round 2 items
are otherwise resolved. Per-declarator emission now matches the policy and the
multi-declarator test is non-vacuous, and `Rule.optionalReads` is pinned by a
KDoc contract note, a `ContractTest` default/`isDefault` assertion, the v5
`Model` additive-surface note, and the updated architecture (no version bump;
the accessor is a JVM default method). The new SAM restriction is mostly right,
but its invocation-method selection uses a raw `->` substring before the SAM
wrappers, so a SAM interface whose type argument is a function type is mapped to
`invoke` and its real invocation method is missed.

Read-only review of the same uncommitted tree; no source edit, build, test,
formatter, or staging command was run.

### Resolution status

- **Round-2 finding 1 (multi-declarator over-coverage) — resolved.**
  `mutableLocalDeclarationNode` now returns the individual declarator for
  JS/TS/C# while using the wrapper only for mutability, and
  `multiDeclaratorMutationsEmitOnlyTheWrittenDeclarator` asserts the written
  declarator is tagged while the unwritten sibling is not. The cross-language
  single-declarator tests were adjusted to the declarator spans.
- **Round-2 finding 2 (`optionalReads` contract/docs) — resolved.**
  `Contract.kt` now documents hard `reads` versus optional reads,
  `Model.kt` records the additive no-bump decision, `ContractTest` asserts the
  empty default and the JVM default accessor, and `docs/architecture.md` now
  includes `optionalReads` in the `Rule` snippet, documents optional edges, fixes
  the service delegation stratum, and removes the stale deferred non-goal.
  The scheduler's absent-owner and owner-present tests remain non-vacuous.

### New finding

#### 1. SAM invocation-method selection misreads function-type type arguments

Location:
`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/CallbackFacts.kt:21-36`
(`CallbackParameter.invocationMethod`), used by `callbackParameterAt` at
`:73-90`.

Problem: `invocationMethod` checks `declared.contains("->")` before the SAM
interface markers. A declared SAM type whose type argument is a function type
therefore gets `invoke` instead of the interface method. For example:

```kotlin
fun publish(consumer: Consumer<(Path) -> Unit>) {
    consumer.accept { }
}
```

`declared` is `Consumer<(Path) -> Unit>`, the `->` branch wins, and
`invocationMethod` becomes `invoke`. `consumer.accept` is a receiver call, so
`callbackParameterAt` requires `invocationMethod == "accept"` and returns null:
no `SERVICE_DELEGATION` candidate is emitted, and a durable default such as
`Consumer { Files.delete(path) }` also loses its `DATA_MODIFY` classification.
The same applies to `Predicate<(Path) -> Unit>`/`.test`,
`Supplier<(Path) -> Unit>`/`.get`, and `Function<Path, (Int) -> String>`/
`.apply`. The new tests only use SAM types without function-typed arguments.

Suggested fix: determine whether the *outer* declared type is a Kotlin function
type (for example it starts with `(` or `suspend (`, or has a top-level arrow
outside angle brackets) before applying the SAM wrapper mapping, or parse the
outer generic name first. Add tests for a SAM interface with a function-type
argument and its method invocation, plus a function type returning a SAM type
(`((Path) -> Unit) -> Unit`) to prevent the reverse misclassification.

### Residual observations (not filed)

- `CallbackFacts.matchingClose`/`splitTopLevel` still scan raw parameter text
  without skipping strings/comments; the annotated-function path is fixed, but a
  default containing an unbalanced parenthesized string can still mis-split the
  parameter list.
- The `Runnable` marker is a bare substring, so a custom `RunnableConfig`-style
  type with `.run()` could be treated as a SAM callback; the new tests cover
  `Config` but not a similarly named non-SAM type. C# `Func`/`Action` delegates
  invoked through `.Invoke(...)` are also not mapped (direct invocation works).
- The SAM branches for `Supplier`, `Callable`, `Predicate`, `Function`,
  `UnaryOperator`, and the `Bi*` variants have no dedicated tests; the common
  `Consumer`/`Runnable`/function-type-`invoke` cases are covered.

### Concurs / Dissents

- Concurs: Round-2 finding 1 is resolved; per-declarator emission no longer
  paints unwritten siblings, and the new multi-declarator tests would fail
  against the previous wrapper emission.
- Concurs: Round-2 finding 2 is resolved; optional reads are documented and
  contract-pinned, the accessor is a safe JVM default, and the architecture no
  longer claims optional dependencies are out of scope.
- Concurs: the new SAM restriction correctly rejects `callback.apply`/`.run` on
  a Kotlin function type and a `Config` value, while mapping `Consumer.accept`,
  `Runnable.run`, and Kotlin function-type `.invoke` to their durable defaults.
- Dissents: the function-type-argument case in finding 1 is an unresolved
  false negative in the new SAM mapping.

### Summary

Round 3 resolves both Round 2 findings: mutable declaration emission is now
per-declarator with wrapper-derived mutability and real multi-declarator
coverage, and `Rule.optionalReads` is documented in the contract and
architecture, pinned by a `ContractTest`, and safely defaulted as a JVM default
method without an API bump. The optional-read scheduler behavior remains sound.
One new SAM edge remains: `invocationMethod` prefers any `->` substring over
the SAM interface marker, so a SAM parameter with a function-typed type argument
(such as `Consumer<(Path) -> Unit>`) is mapped to `invoke` and its `.accept`
call is silently missed, losing both the generic delegation candidate and any
durable `DATA_MODIFY` default. Fixing the outer-type classification and adding
the nested-function-type SAM tests would close the dimension; the remainder is
acceptable as documented residuals.

## Round 4

### Verdict

Concurs; no new blocking finding. The Round 3 SAM edge is resolved: outer SAM
simple names are resolved before the function-type arrow test, so
`Consumer<(Path) -> Unit>` maps to `accept` (and `Supplier`/`Predicate`/
`Function` equivalents map to their interface methods), while a Kotlin function
type still maps to `invoke`. Declared types without a function/SAM marker now
fall back to default/reference evidence, which covers Kotlin type aliases,
TypeScript function aliases, and C# custom delegates for direct invocation. The
new tests exercise each of these paths and would fail against the Round 3 code.
Per-declarator emission and `optionalReads` are unchanged and remain resolved.

Read-only review of the same uncommitted tree; no source edit, build, test,
formatter, or staging command was run.

### Resolution status

- **Round-3 finding (SAM type with function-type arguments) — resolved.**
  `CallbackParameter.invocationMethod` now derives the outer simple type from
  the declared type before checking function arrows and maps `Consumer`/
  `BiConsumer` to `accept`, `Supplier` to `get`, `Predicate`/`BiPredicate` to
  `test`, `Function`/`BiFunction`/`UnaryOperator` to `apply`, `Runnable` to
  `run`, and `Callable` to `call`; only an outer Kotlin function type falls back
  to `invoke`. `samTypesWithFunctionTypeArgumentsUseTheirInterfaceMethod` covers
  the four affected SAM shapes and `durableDefaultThroughSamTypeWithFunctionTypeArgumentEmitsDataModify`
  confirms the durable default still establishes `DATA_MODIFY` without a
  service candidate. The previous `callback.apply`/`callback.run` negative
  remains in place.
- **Alias/custom-delegate fallback — resolved for the claimed direct-invocation
  scope.** `isFunctionTyped` now falls back to `looksLikeFunctionDefault` when a
  declared type has no function/SAM marker, and `aliasedFunctionTypesKeepDurableCallbackDefaults`
  covers Kotlin `typealias`, TypeScript `type`, and a C# custom delegate with
  durable reference defaults. Receiver-SAM mapping remains declared-type-only,
  as documented.
- **Per-declarator emission and `optionalReads` — unchanged and still resolved.**
  The Round 3 declarator spans are still used, and the Round 3 API contract,
  `ContractTest`, KDoc/Model note, and architecture updates are unchanged.

### Residual observations (not filed)

- `outerTypeName` can still misidentify a function type whose generic
  parameter/return is a fully qualified SAM name: e.g.
  `(Path) -> java.util.function.Consumer<Path>` yields outer simple name
  `Consumer` and maps the function value to `accept`, so `callback.invoke(...)`
  is missed. Imported `Consumer<Path>` return types work (the text before `<`
  is `(Path) -> Consumer`), so this is limited to dotted/FQN inner types. A
  structural outer-type check or a fully-qualified reverse-case test would close
  it.
- `CallbackFacts.matchingClose`/`splitTopLevel` still scan raw parameter text
  without skipping strings/comments; a default containing an unbalanced
  parenthesized string can still mis-split the parameter list.
- C# `Func`/`Action` delegates invoked through `.Invoke(...)` are not mapped
  (direct invocation works), and the `Callable`/`UnaryOperator`/`Bi*` SAM
  branches still have no dedicated tests.

### Concurs / Dissents

- Concurs: Round 3 is resolved; the outer SAM marker wins over arrows inside its
  type arguments, function types still use `invoke`, and the alias/delegate
  fallback only applies when default/reference evidence exists.
- Concurs: the new SAM and alias tests are non-vacuous; the durable nested-SAM
  test would have failed against the Round 3 behavior.
- Concurs: per-declarator emission and the optional-read contract/docs are
  unchanged from the resolved Round 3 state.
- Dissents: none. Remaining items are documented edge limitations, not blocking
  findings for this ticket.

### Summary

Round 4 resolves the Round 3 SAM edge. `invocationMethod` now resolves the
outer declared SAM type before considering function arrows, so
`Consumer<(Path) -> Unit>` and the other SAM-with-function-argument shapes use
their real interface method, while Kotlin function types still map to `invoke`;
the new tests cover both the generic-candidate and durable-`DATA_MODIFY` paths.
Declared-type fallback now recognizes Kotlin type aliases, TypeScript aliases,
and C# custom delegates through default/reference evidence for direct
invocation, as documented, and per-declarator emission plus the optional-read
API contract remain unchanged and resolved. I found no new blocking finding; the
only remaining items are narrow type/parser edges (fully qualified SAM names
nested inside a function type, raw parentheses in defaults, and C# delegate
`.Invoke`/untested SAM branches), which are suitable as residuals or a future
ticket.


