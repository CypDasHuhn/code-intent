# Detection-fidelity review — ticket 048 (local mutation and callback coverage)

## Round 1

### Verdict

Changes requested. The default-off mutable-declaration policy and the
`NeighboursCli.publish`-shaped fixture behave as intended: assignment writes are
tagged, `val`/unwritten locals are not flooded, loop/bookkeeping/pure-copy
suppression still holds, and `move(...)` establishes `DATA_MODIFY` while
`notify(...)` stays a candidate. The callback-classification helper has three
concrete fidelity gaps (annotated functions, JS inferred callback defaults, and
the split durable-target sets), and SAM-interface callbacks are unreachable
because receiver calls are rejected.

### Baseline

Reviewed the uncommitted diff against `docs/workflow.md`, the ticket, the 040/046
detection-fidelity reviews, `MutationRulePack`/`MutationConfig`/`ServiceRulePack`,
the new `CallbackFacts.kt`, the focused tests, the Kotlin fixture, the refreshed
pipeline snapshot, and the docs changes. Static review only; no source edit,
build, test, lint, or formatter command was run, per the reviewer role.

### Concurrences

- **Fixture writes are consistently tagged.** In the `NeighboursCli.kt` fixture,
  `jsonTemp = ...` and `htmlTemp = ...` establish `LOCAL_VALUE_MODIFY`; `move(...)`
  establishes `DATA_MODIFY`; `notify(...)` is not a local mutation. The snapshot
  shows exactly these established annotations (plus the pre-existing derivative
  `DATA_MODIFY` from the `move` arguments), and no other fixture entry moved.
- **Default-off declaration policy.** `mutableLocalDeclarations` defaults to
  `false` in `MutationLanguageConfig` and is asserted in `MutationConfigTest`;
  the snapshot has no declaration `LOCAL_VALUE_MODIFY`. When enabled, the test
  covers `var` declarations with later writes while excluding `val`,
  unwritten locals, and collection operations, which is the intended policy.
- **Suppression still works.** `effectiveWrite` preserves the pre-existing
  `assignmentPatterns`, `LOCAL_COPY`, bookkeeping, and loop-counter filters, and
  the declaration path reuses it. The pure-copy/bookkeeping test and the
  default `ignorePureCopies` path keep local-copy suppression intact.
- **No unrelated established detection moved.** `DataModifyRule` and
  `ServiceRulePack` only add new established/candidate classifications;
  `effectiveWrite` is a behaviour-preserving extraction. The snapshot diff adds
  only the new fixture entry and its provenance name.

### Findings

#### 1. Annotated functions make callback parsing read the annotation arguments

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/CallbackFacts.kt:75-84`
(`callbackParameters`, especially `text.indexOf('(')`), used by
`callbackParameterAt` at `:65-72`.

Problem: the function node's text includes its modifiers/annotations. This is
visible in the existing snapshot: the Bookkeeping declaration span for
`@Bookkeeping\n    var skipped = 0` covers the annotation, and the PurityAssist
function nodes are `@Pure\nfun pureText...` and
`@Contract(pure = true)\nfun contractedText...`. Consequently the first `(` in
`function.text` belongs to the annotation whenever it has arguments. A fixture
shape such as
`@Deprecated("publish") fun publish(move: (Path, Path) -> Unit = Files::move) { move(a, b) }`
parses `"publish"` as the parameter list, never sees `move`, and the `move(a, b)`
call silently gets neither `DATA_MODIFY` nor a `SERVICE_DELEGATION` candidate.
The same happens for `@Suppress(...)`, `@Command(...)`, and similar annotations.

Suggested fix: locate the parameter list from the function's syntax children
(the profile's parameter-list/formal-parameter kind, or the declaration's
`parameter_list` field) instead of the first `(` in the full text, or strip the
leading modifier/annotation prefix before parsing. Add a test with an annotated
`publish` carrying a durable callback default.

#### 2. Durable-callback classification uses two different target sets

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/service/ServiceRulePack.kt:103-110`
and `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/CallbackFacts.kt:42-63,86-99`,
versus `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:165-173`.

Problem: `DataModifyRule` decides durability from the configured
`persistencePaths + persistenceCalls`, but `ServiceDelegationRule` skips generic
candidates using the fixed `KNOWN_DURABLE_CALLBACK_TARGETS` set. The sets
diverge. A callback default on a configured-but-not-hardcoded target, for
example C# `create: Func<string, FileStream> = File.Create`,
`File.AppendAllText`, `fs.appendFile`, or `fs.promises.writeFile`, emits
established `DATA_MODIFY` and also a `SERVICE_DELEGATION` candidate, directly
contradicting `docs/engine.md`/`docs/intent-model.md` ("does not become a generic
callback candidate"). Conversely, if a project removes `Files.move` from
`persistencePaths`, `ServiceDelegationRule` still suppresses the candidate
because the hardcoded set contains it, while `DataModifyRule` no longer emits
`DATA_MODIFY`, so the call becomes silently untagged. The duplicate set will
also drift as more targets are configured.

Suggested fix: share one config-driven durable-callback predicate between the
two rules (pass the resolved `MutationLanguageConfig` into the service
side, or move the predicate into a shared helper). Add a test for a configured
durable default that is absent from the hardcoded set (asserting `DATA_MODIFY`
only), and a test where a known target is removed from configuration (asserting
the candidate/`DATA_MODIFY` policy is still coherent).

#### 3. JS/TS callback defaults that are bare references are not recognized

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/CallbackFacts.kt:12-17`
(`isFunctionTyped`) and `:125-127` (`looksLikeFunctionDefault`), used at `:72`.

Problem: `FUNCTION_DEFAULT_MARKERS` only contains `::`, `=>`, `->`, and `{`. A
plain JavaScript (or type-inferred TypeScript) callback parameter with a bare
reference default has no type and does not match those markers, so
`isFunctionTyped` is false. For example:
`function publish(move = Files.move) { move(a, b); }` or
`const publish = (move = fs.rename) => { move(a, b); }`. The invocation is then
missed entirely, even though `KNOWN_DURABLE_CALLBACK_TARGETS` explicitly lists
`Files.move`/`fs.rename`, and generic JS callbacks with such defaults never
reach the candidate channel. This is a language-specific hole in an otherwise
multi-language feature.

Suggested fix: treat a bare (optionally qualified) identifier default as a
possible function reference, at least when it matches a configured durable
target, or infer the callback type from the default form. Add a JavaScript test
and a TypeScript inferred-type test asserting `DATA_MODIFY` for a bare
`Files.move`/`fs.rename` default.

#### 4. SAM-interface callbacks are unreachable because receiver calls are rejected

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/CallbackFacts.kt:65-73`
(`callbackParameterAt` returns `null` when `call.receiver != null`), with the
unused-in-practice SAM markers at `:19-36`.

Problem: `isFunctionTyped` recognizes `Consumer<`, `Supplier<`, `Predicate<`,
`Callable<`, `Runnable`, `Function<`, and similar types, but those values are
invoked as methods on a receiver (`consumer.accept(x)`, `runnable.run()`,
`function.apply(x)`). `callbackParameterAt` rejects every receiver call, so those
parameters can never be matched. A Kotlin callback parameter declared as
`consumer: Consumer<Path> = Consumer(Files::delete)` produces no candidate and no
`DATA_MODIFY` for `consumer.accept(path)`, even though the markers imply support.
For the four bundled languages this makes most Java-style SAM markers dead code.

Suggested fix: resolve the call receiver back to an enclosing function parameter
and recognize the standard single-method invocation (`run`/`accept`/`apply`/
`test`/`call`/`get`), or narrow the supported contract to direct function-type
invocation, remove the unreachable markers, and document the boundary. Add a
positive test for a `Consumer`/`Runnable` parameter if SAM support is intended.

### Residuals (not findings)

- The generic callback loop emits `SERVICE_DELEGATION` candidates for pure
  value callbacks too; the fixture's `stage("json")`/`stage("html")` calls are
  candidates in the full pipeline even though `stage` is a transformer. This is
  consistent with the broad candidate policy, but the fixture snapshot cannot
  show it because candidates are excluded from the snapshot.
- With `mutableLocalDeclarations` enabled, the declaration path relies on
  `effectiveWrite` for `for`-loop counter suppression; the tests only cover
  `while` counters (which intentionally emit), so an explicit
  `for (var i = 0; ...)` declaration test would pin the "loop counters stay
  suppressed" claim.
- `DataModifyRule`'s `containsConfiguredTarget` only matches targets containing
  `.`, so a default referencing a configured bare persistence call (for example
  `repo::save` where `save` is in `persistenceCalls`) is not durable. This is
  consistent with requiring durable evidence but is narrower than the docs'
  "configured durable path/call" wording.

### Concurs / Dissents

- Concurs: the mutable-declaration policy is explicit, default-off, covered by
  focused tests, and does not flood `val`/unwritten declarations.
- Concurs: the motivating write/`move`/`notify` fixture shape and its snapshot
  entry reflect the intended default behavior, and no unrelated established
  detection moved.
- Concurs: `LOCAL_COPY`, bookkeeping, pure-copy, and loop-counter suppression
  remain intact for writes.
- Dissents: findings 1-4 (annotated-function parsing, split durable sets,
  JS bare-reference defaults, and SAM callback invocation) are unresolved
  detection-fidelity gaps.

### Summary

The diff implements the intended default-off mutable-local policy cleanly: the
fixture's assignments are tagged, `move` is `DATA_MODIFY`, `notify` is a
candidate rather than a local mutation, and `val`, unwritten locals, loop
counters, bookkeeping, and pure copies stay suppressed with `LOCAL_COPY`
suppression preserved; the snapshot and docs changes are limited to the intended
fixture and policy. The callback classification is not yet reliable outside the
exact unannotated Kotlin fixture: annotated functions parse the annotation
argument list instead of the real parameters, service-side durable suppression
uses a hardcoded set that diverges from the mutation config (producing both
`DATA_MODIFY` and a delegation candidate for some durable defaults, or neither
when config differs), bare-reference JS/TS callback defaults are not recognized
as function-typed, and SAM-interface callbacks are excluded by the
receiver-call guard. Fixing those parsing/classification paths and adding the
suggested cross-language and annotated-function tests would close the detection
loop; the mutable-declaration and fixture-write portions are otherwise sound.

## Round 2

### Verdict

Changes requested. All four Round 1 findings are addressed, and the annotated,
configured-durable, JS/TS, and SAM paths now have focused tests. The SAM fix
introduces a new false-positive path (method-name-only mapping over a
default-derived function type), and the new JS/TS/C# declaration wrapper
over-tags multi-declarator statements that contain an unwritten sibling.

### Resolution status

- Round 1 finding 1 (annotated function parsing) is resolved. `parameterText`
  selects the syntax parameter-list child (`CallbackFacts.kt:150-173`), so
  `@Deprecated("publish") fun publish(...)` parses `move` rather than the
  annotation argument; `annotatedFunctionCallbackParametersAreParsed` pins it.
- Round 1 finding 2 (split durable sets) is resolved. `DataModifyRule` stays
  config-driven, `ServiceDelegationRule` declares `optionalReads = {DATA_MODIFY}`
  (`ServiceRulePack.kt:71`), the scheduler makes that dependency optional
  (`RuleScheduler.kt:132-146`), and the service callback loop skips calls that
  already carry `DATA_MODIFY` (`ServiceRulePack.kt:101-110`). The configured-add
  and configured-remove tests both pin coherent outcomes.
- Round 1 finding 3 (bare-reference JS/TS defaults) is resolved.
  `looksLikeFunctionDefault` accepts a qualified reference default
  (`CallbackFacts.kt:176-181`), and the JS declaration/arrow and TS inferred
  cases are tested with `Files.move`/`fs.rename`.
- Round 1 finding 4 (SAM invocations unreachable) is resolved in direction:
  receiver calls can now map through `SAM_INVOCATION_METHODS` and the
  `Consumer`/`Runnable` durable-default tests pass. The mapping itself is the
  source of new finding 1 below.

### New findings

#### 1. SAM receiver mapping proves invocation from the method name alone

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/CallbackFacts.kt:12-17`
(`isFunctionTyped`), `:56` (`SAM_INVOCATION_METHODS`), `:72-83`
(`callbackParameterAt`), and `:176-181` (`looksLikeFunctionDefault`).

Problem: a receiver call is treated as a callback invocation when its callee is
one of `run`/`accept`/`apply`/`test`/`call`/`get`, the receiver's last identifier
matches a parameter name, and that parameter is "function typed". Function-typed
includes a bare/dotted default even when an explicit non-function type is
declared. This conflates Kotlin scope functions and value receivers with SAM
calls:

```kotlin
fun publish(callback: (Path) -> Unit = Files::delete) {
    callback.apply { }   // stdlib apply, does not invoke callback
}
```

`callback.apply { }` now establishes false `DATA_MODIFY` (the default is a
durable target), and `callback.run { }` does the same. Similarly
`fun configure(config: Config = Config.DEFAULT) { config.apply { } }` becomes a
false `SERVICE_DELEGATION` candidate because `Config.DEFAULT` is treated as
function evidence, and the same default-based typing makes `x.get()` on a value
parameter with a reference default a candidate. `apply`/`run` are generic scope
functions in Kotlin; they are only SAM invocation methods for specific interface
types, not for arbitrary function-typed parameters.

Suggested fix: gate receiver-SAM matching on SAM type evidence and map the
method per interface (`Consumer`→`accept`, `Runnable`→`run`, `Supplier`→`get`,
`Callable`→`call`, `Predicate`→`test`, `Function`→`apply`; Kotlin function types
use only direct invocation/`invoke`). Also use the default-only typing fallback
only when the declared type is absent (or the default is a function literal), so
an explicit `Config`/`Task` type is not overridden by `Config.DEFAULT`. Add
negative tests for `callback.apply { }`/`callback.run { }` on a function type and
`config.apply { }` on a value type, plus a positive `.invoke`/SAM test.

#### 2. JS/TS/C# wrapper emission paints unwritten multi-declarator siblings

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:201-208`
and `:540-590` (`mutableLocalDeclarationNode`/`declarationWrapper`).

Problem: the JS/TS and C# branches climb to the outermost declaration wrapper,
which may contain several declarators. With `mutableLocalDeclarations` enabled,
`let changed = 0, untouched = 0; changed = 1;` emits one
`LOCAL_VALUE_MODIFY` on the whole `let` statement because `changed` has an
effective write, even though `untouched` has none; the same happens for
`int changed = 0, untouched = 0;` in C#. This contradicts the documented
"unwritten locals stay out" behavior, and the wrapper-span dedup merges the two
names into one annotation.

Suggested fix: use the wrapper only when it contains a single declarator, or
emit on each written `variable_declarator` (optionally spanning from the
`let`/`var`/type token to that declarator). Add a multi-declarator test with one
written and one unwritten name for JS/TS and C#.

### Concurs / Dissents

- Concurs: Round 1 findings 1-4 are addressed, and the new annotated,
  configured-durable, JS/TS reference-default, for-counter, and SAM tests cover
  the intended shapes.
- Concurs: the default-off mutable-declaration policy, fixture writes,
  `LOCAL_COPY`/bookkeeping/loop suppression, and snapshot/doc changes remain
  correct; no unrelated established detection moved in the snapshot.
- Dissents: new findings 1 (method-name-only SAM mapping over default-derived
  typing) and 2 (multi-declarator wrapper over-tagging) are unresolved.

### Summary

Round 2 closes the Round 1 gaps: annotated functions now parse their real syntax
parameter list, durable classification is shared through an optional
`DATA_MODIFY` read instead of a hardcoded service set, bare-reference JS/TS
defaults are recognized, and SAM receiver calls are classified with focused
tests. Two new detection-fidelity issues remain. The SAM mapping treats
`run`/`apply`/`get` as invocation proof for any function-typed or
default-typed parameter, so Kotlin scope functions on callbacks with durable
defaults emit false established `DATA_MODIFY` (and value parameters with
reference defaults emit false delegation candidates); the fix is interface-aware
method mapping plus restricting default-based typing to untyped parameters. The
JS/TS/C# declaration wrapper also tags an entire multi-declarator statement when
only one declarator is written, painting unwritten siblings; emitting per
written declarator (or only single-declarator wrappers) closes that. With those
two corrected, the ticket's detection surface is otherwise consistent.

## Round 3

### Verdict

Changes requested. Both Round 2 findings are resolved: emission is now per
written declarator, and SAM receiver mapping requires a declared SAM interface
type plus its method-specific invocation, so `run`/`apply` on plain function or
value parameters no longer imply durability. One narrow regression remains from
the declared-type preference: function-typed parameters expressed through a
type alias or custom delegate are no longer recognized, so a durable default on
them is silently untagged.

### Resolution status

- Round 2 finding 1 (method-name-only SAM mapping) is resolved.
  `CallbackParameter.invocationMethod` is declared-type-driven and
  interface-specific (`CallbackFacts.kt:21-35`), and receiver calls require
  `invocationMethod == callee` (`:91-100`). Kotlin function types map only to
  `invoke`, so `callback.apply { }`/`callback.run { }` and
  `config.apply { }` on a value type no longer emit; `callback.invoke(...)`
  establishes `DATA_MODIFY` with its durable default. The new tests cover both
  the negative scope-function shapes and the positive `invoke` case.
- Round 2 finding 2 (multi-declarator over-tagging) is resolved.
  `mutableLocalDeclarationNode` still uses the wrapper only to read the
  `let`/`var`/`const`/`readonly` qualifier, then returns the individual
  `declaration.node` (`MutationRulePack.kt:540-570`), so
  `let written = 0, untouched = 0; written = 1;` tags only `written`. The new
  JS/TS/C# multi-declarator test pins it.
- The snapshot is still limited to the intended `NeighboursCli.kt` entry, and
  the docs now describe per-declarator emission, interface-aware SAM mapping,
  and declared-type preference. No other established detection moved.

### New finding

#### 1. Declared-type preference drops function-typed aliases and custom delegates

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/CallbackFacts.kt:12-19`
(`declaredType`/`isFunctionTyped`) and the resulting miss in
`callbackParameterAt` at `:91-103`.

Problem: when a declared type exists, `isFunctionTyped` now returns only
`FUNCTION_TYPE_MARKERS.any { ... }` and never falls back to the default. A
parameter whose declared type is a local alias or custom delegate therefore
becomes non-callback even when its default is the only evidence the detector
needs:

```kotlin
typealias Move = (Path, Path) -> Unit
fun publish(move: Move = Files::move) { move(a, b) }
```

The type text is `Move`, so the `->` marker is absent and `move(a, b)` gets
neither `DATA_MODIFY` nor a `SERVICE_DELEGATION` candidate. The same happens for
a C# custom delegate (`MoveHandler move = Files.Move`) and a TypeScript alias
(`type Move = (a: string) => void`, `move: Move = fs.rename`). Before the
declared-type preference these were recognized via the durable default, and the
acceptance says a callback whose known/default implementation is durable is
`DATA_MODIFY` or a documented candidate; here it is silently dropped. The
Round 2 false-positive fix does not require this miss: `invocationMethod`
already stays null for non-marker declared types, so allowing the default
fallback again would not re-enable `.run`/`.apply` scope calls.

Suggested fix: when `declaredType` is present but has no marker, fall back to
`looksLikeFunctionDefault(default)` (or at least to a default that matches a
configured durable target) for `isFunctionTyped`, while keeping
`invocationMethod` declared-type-only so the SAM/scope false positives stay
fixed. Add positive tests for a Kotlin typealias, a C# custom delegate, and a
TypeScript function alias, each with a durable `Files`/`fs` default asserting
`DATA_MODIFY` and no delegation candidate.

### Concurs / Dissents

- Concurs: Round 2 findings 1 and 2 are resolved by the interface-aware method
  mapping and per-declarator emission, with focused positive/negative tests.
- Concurs: the default-off mutable-declaration behavior, fixture writes,
  `LOCAL_COPY`/bookkeeping/loop suppression, optional `DATA_MODIFY` dependency,
  snapshot, and docs remain correct; no unrelated established detection moved.
- Dissents: new finding 1 (type alias/custom delegate durable callbacks are
  silently missed) is unresolved.

### Summary

Round 3 fixes the two Round 2 problems: SAM receiver calls now require a
declared SAM interface and its matching method (with Kotlin function types
mapped to `invoke`), so stdlib `run`/`apply` on callbacks or value parameters no
longer imply durability, and JS/TS/C# mutable declarations now emit on the
individual written declarator instead of painting unwritten multi-declarator
siblings. The remaining detection gap is a side effect of the declared-type
preference: a function-typed parameter written through a type alias or custom
delegate (Kotlin `typealias`, C# `delegate`, TypeScript function alias) has a
non-marker type string, so its durable default (`Files::move`/`fs.rename`) is
ignored and the call is silently untagged, contrary to the durable-callback
acceptance. Falling back to the default heuristic when the declared type has no
marker, while keeping `invocationMethod` declared-type-gated, plus alias/delegate
tests, would close the ticket; all other reviewed detection behavior is
consistent.

## Round 4

### Verdict

Concurs. The Round 3 alias regression is fixed, outer SAM types now win over
nested function-type arrows for receiver invocation, and the new tests cover
Kotlin typealiases, TypeScript function aliases, C# custom delegates, and
SAM-of-function-type arguments. No new or unresolved detection-fidelity finding
remains; the ticket can close from this dimension.

### Resolution status

- Round 3 finding (aliases/custom delegates silently missed) is resolved.
  `isFunctionTyped` now falls back to `looksLikeFunctionDefault` when a declared
  type exists but carries no function marker
  (`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/CallbackFacts.kt:14-20`).
  Kotlin `typealias Move = ... = Files::move`, TS `type Move = ... = fs.rename`,
  and a C# custom delegate default `File.Move` now establish `DATA_MODIFY` and
  stay out of the delegation candidates; `aliasedFunctionTypesKeepDurableCallbackDefaults`
  covers all three.
- Outer SAM precedence is correct. `invocationMethod` derives the outer type name
  and maps the interface method, only falling back to Kotlin function-type
  `invoke` when the outer type is not a SAM
  (`CallbackFacts.kt:22-35,37-87`). `Consumer<(Path) -> Unit>` therefore maps to
  `accept`, not `invoke`; `Supplier`, `Predicate`, and `Function` cases are
  covered by `samTypesWithFunctionTypeArgumentsUseTheirInterfaceMethod`, and the
  durable nested-default case is covered by
  `durableDefaultThroughSamTypeWithFunctionTypeArgumentEmitsDataModify`.
- The Round 2 scope-function protection still holds: declared non-SAM value
  types and plain function types do not receive a receiver-SAM method, so
  `callback.run { }`/`callback.apply { }` and `config.apply { }` remain
  unclassified (`samReceiverMappingRequiresSamInterfaceEvidence`).
- The snapshot still contains only the intended `NeighboursCli.kt` addition, and
  the docs now describe outer-SAM precedence and alias/delegate fallback. No
  unrelated established detection moved.

### Observations (not findings)

- Receiver invocation remains declared-type-only for aliases: `move.invoke(...)`
  on a Kotlin typealias, or a typealias to a SAM interface with no function
  arrow/default evidence, is still not derivable from text alone. This is a
  bounded, documented limitation of the direct-invocation alias fallback.
- The C# custom-delegate case is exercised as a syntax shape; the classifier is
  text-based, so this is coverage of the declared-type fallback rather than a
  claim that a method-group optional parameter default is valid C#.

### Concurs / Dissents

- Concurs: Round 3 finding is resolved, including the three language shapes and
  the durable/candidate split.
- Concurs: outer SAM markers take precedence over nested function arrows, while
  plain function types still map only to `invoke` and value types receive no
  receiver-SAM method.
- Concurs: default-off mutable declarations, fixture writes, `LOCAL_COPY`/
  bookkeeping/loop suppression, optional `DATA_MODIFY` dependency, snapshot, and
  docs remain consistent with the ticket.
- Dissents: none.

### Summary

Round 4 resolves the Round 3 alias regression: function-typed parameters whose
declared text is a Kotlin typealias, TypeScript function alias, or C# custom
delegate now fall back to their default/reference evidence, so a durable
`Files`/`fs` default still establishes `DATA_MODIFY` while receiver-SAM mapping
stays declared-type-gated and the Round 2 scope-function false positives remain
closed. Interface-aware mapping now uses the outer type name before checking
Kotlin function arrows, so `Consumer<(Path) -> Unit>` invokes `accept` rather
than `invoke`, with tests for `Consumer`, `Supplier`, `Predicate`, and
`Function` plus a durable nested-default case. The remaining observations are
bounded text-only limitations (receiver invocation through a typealias, and the
syntactic C# delegate shape), not regressions, and no unrelated established
detection moved in the snapshot. I concur; zero findings and the ticket can
close.
