# Detection-fidelity review — ticket 046 (structural intents)

## Verdict

Changes requested. `RETURN_VALUE` is semantically right and additive, and the
single-call side of `CHAINING` is held, but `CHAINING` accepts non-chain calls
hidden in index/argument expressions and does not actually enforce the boolean
OR boundary. `LOCAL_COPY` avoids general mutation and persistence shapes, but it
is a name-shape heuristic rather than a parameter-copy detection: it establishes
field/local same-name copies, including case variants and field self-copies, and
misses declaration/local copies. The `LOCAL_VALUE_MODIFY` suppression also
diverges from the local-copy pack's configured prefixes.

## Baseline

Reviewed `e999413` (`feat(structural): add return value chaining local copy
intents`) against `main` at `951f616`, with `docs/workflow.md`,
`docs/tasks/046-structural-intents.md`, `docs/intent-model.md` §2–§3/§8, the
three structural packs and configs, the mutation change, the focused tests, the
refreshed pipeline snapshot, and the renderer defaults. No build, test, lint, or
formatter command was run, per the reviewer role.

## Concurrences

- **`RETURN_VALUE` is additive and correctly bounded.** Bare `return`s are
  skipped, literal-only returns are gated by `includeLiterals`, and the emit
  lands on the returned expression, so it co-exists with `SEARCH`/`VALIDATION`
  on the same span (as the co-occurrence test and snapshot show) rather than
  replacing them. The literal gate uses `isConstant`, so reference-free compound
  expressions (`1 + 2`, JS array/object literals) are also treated as literals;
  that is close to the documented "literal only" boundary, but worth pinning.
- **`CHAINING` avoids single calls and property access.** `Factory.For()`,
  `service.execute()`, and `service.dependency.execute()` do not emit, while
  two-plus call chains establish on the outer call. Ambiguous receiver shapes are
  deterministically demoted to `CandidateEmit`, and outermost-chain selection is
  stable.
- **`LOCAL_COPY` rejects the listed non-copy shapes.** `user.name = "x"`,
  `count = count + 1`, and `repository.save(db)` do not establish because the RHS
  must be a bare identifier and the callee/assignment shape must match.
- **Overlap plumbing exists.** `LOCAL_VALUE_MODIFY` suppression is configurable
  (`ignorePureCopies`) and the tests cover the default suppression and the
  opt-out. `SEARCH` boundaries for `RETURN_VALUE` and `LOCAL_COPY` are pinned,
  and the snapshot shows `CHAINING` co-occurring with `SEARCH` and
  `DATA_TRANSFORMATION`.

## Findings

### 1. `CHAINING` fabricates links from calls inside index/argument expressions

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/ChainingRulePack.kt:91-94`
and `:106-113`.

Problem: `chainFor` picks the largest call *anywhere inside* the receiver, not
the call that produces the receiver's value, and `isDirectLink` accepts any
trailing `)`, `]`, `;`, or whitespace. For `a[b()].c()` the receiver of `c` is
`a[b()]`; `b()` is the only contained call and the trailing text is `]`, so
`b()` -> `c()` becomes established `CHAINING`. The same happens for
`(a + b()).c()` and `[foo()].map { }.first()`; `b()`/`foo()` are index/argument
subexpressions, not fluent links. Separately, `isDirectLink` computes
`offset` in bytes but calls `receiver.text.substring(offset)` with a char
index, so any non-ASCII receiver is misclassified (usually to candidate) and the
`offset > text.length` guard hides it.

Suggested fix: require `next` to be the receiver's value-producing root (walk
the receiver's navigation/callee path, or allow only syntactic wrapper
parentheses and require the contained call to cover the receiver after wrapper
stripping). Reject calls nested under indexing, argument lists, and binary
expressions. Compare byte spans rather than mixing byte offsets with
`String.substring`. Add negative tests for `a[b()].c()`, `(a + b()).c()`,
`[foo()].map { }.first()`, and a non-ASCII receiver chain.

### 2. The boolean-OR exclusion is receiver-text based, and its test is vacuous

Location: `ChainingRulePack.kt:90`; test
`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/structural/ChainingRulePackTest.kt:58-72`.

Problem: the rule only checks whether the current receiver's text contains
`||`. Consequently `a.foo().bar() || c.baz().qux()` emits established `CHAINING`
for both operand chains, because neither receiver contains the `||`. Conversely
`(a || b).foo().bar()` suppresses the whole chain just because the boolean is
inside the receiver. The test uses one call per operand (`a.isEmpty()` and
`b.isEmpty()`), so no chain would be found even if the `||` check were removed;
it does not pin the acceptance criterion "Boolean OR chains do not emit
`CHAINING`".

Suggested fix: decide the policy at the enclosing boolean expression/statement
level and skip chains that are operands of `||` (or document that operand chains
are independent and adjust the ticket/doc). Add tests with two-call operands on
both sides of `||`, plus a chain whose receiver is a parenthesized boolean.

### 3. `LOCAL_COPY` does not verify a parameter source and folds case

Location: `LocalCopyRulePack.kt:102-114` and `:150-158`.

Problem: "established" is just `normalize(left) == normalize(right)` with a raw
text inequality check. There is no declaration or parameter resolution, so
`this.x = x` establishes even when `x` is a field/property rather than a
parameter, `_db = this._db` establishes as a field self-copy, and `_db = db`
establishes when `db` is a local. `normalize` also lowercases, so `x = X` and
`db = DB` establish as copies. These are false positives against the documented
"with parameter source" boundary. The bare-identifier RHS gate does keep calls
and persistence shapes out, so the problem is over-capture of same-named
field/local copies, not general mutation.

Suggested fix: resolve the RHS identifier to an enclosing function parameter
(extend the fact/`View` surface if parameters are not exposed), compare exact
identifiers after removing only configured prefixes/leading underscores, and
exclude same-name field self-copies. If parameter resolution is out of scope for
this ticket, emit only a candidate when the source cannot be proven to be a
parameter, and align the doc. Add negative tests for field-to-field copies,
implicit `this` sources, and case variants.

### 4. `LOCAL_COPY` misses declaration initializers and differently named local copies

Location: `LocalCopyRulePack.kt:64-65` and `:116-124`.

Problem: only `WriteKind.ASSIGNMENT` nodes with operator `=` are considered.
`val copy = param` is a declaration initializer, not a write fact, so it gets no
`LOCAL_COPY`; `copy = param` with a non-field-like target also gets nothing,
because it neither normalizes equal nor passes `isFieldLike`. The intent meaning
covers a parameter copied into a "field or local", so the common local case is
under-detected and untested.

Suggested fix: include variable declaration initializers and allow a candidate
for local targets when the RHS resolves to a parameter; or narrow the documented
meaning to assignment-to-field. Add positive tests for `val copy = param` and
`copy = param`.

### 5. Mutation suppression ignores the local-copy pack's configured prefixes

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:176`
with `LocalCopyRulePack.kt:28-29`.

Problem: `isEstablishedLocalCopy` always constructs
`LocalCopyMatch(LocalCopyLanguageConfig())`, so it uses the default prefixes and
never sees the local-copy pack's configured language. With
`local-copy: { prefixes: [store_] }`, `store_db = db` establishes `LOCAL_COPY`,
but the mutation rule does not recognize it as a pure copy; a local target then
still establishes `LOCAL_VALUE_MODIFY`, and a field target gets a candidate.
`configuredPrefixesExtendNormalization` loads only `LocalCopyRulePack`, so the
divergence is not covered. The cross-pack helper also couples mutation to the
structural pack.

Suggested fix: share the resolved local-copy predicate/config, or have the
mutation rule suppress based on actual `LOCAL_COPY` annotations/configured
prefixes. Add a combined `MutationRulePack` + `LocalCopyRulePack` test with a
custom prefix and both `ignorePureCopies` settings.

### 6. `CHAINING` + `SERVICE_INIT` co-occurrence is unverified

Location: `ChainingRulePackTest.kt:33-56`; `testing-grounds/kotlin/ServiceIntents.kt`.

Problem: the acceptance criterion requires the factory chain to carry
`CHAINING` (and `SERVICE_INIT` where applicable), but no test loads
`ServiceRulePack` with `ChainingRulePack`, and the service fixture has no
factory-style chain. The snapshot only demonstrates the `SEARCH` /
`DATA_TRANSFORMATION` overlap, so the required service overlap is not pinned.

Suggested fix: add a combined-pack test with
`DataAccessServiceFactory.For().Use().Build()` asserting both intents on the
outer chain (with the service config used by the fixture/test), or add the chain
to `ServiceIntents.kt` and refresh the snapshot. If live parity remains manual,
record it in `docs/manual-test.md` naming ticket 046.

## Round 2

Verdict: Changes requested. The exact-link rewrite fixes the Round 1 fabricated
links, the OR-operand and non-ASCII cases are now pinned, declaration/local
copies and the `SERVICE_INIT` overlap are covered, and labeled Kotlin returns no
longer emit `RETURN_VALUE`. The loop cannot close yet: ambiguous `CHAINING`
shapes are now dropped instead of candidated, the OR ancestor walk reaches too
far, `LOCAL_COPY` still has no parameter proof and still folds case, and the
mutation overlap resolution removes suppression rather than making it policy.

### Concurrences (Round 1 items)

- **Fabricated chain links are fixed.** Requiring the next call to have the
  receiver's exact node/site span (`ChainingRulePack.kt:92-104`, `:115-121`)
  rejects `a[b()].c()`, `(a + b()).c()`, and `[foo()].map { }.first()`; byte
  offsets are no longer used as char indices, and the tests cover Kotlin,
  JavaScript, and non-ASCII receivers.
- **OR operands and boolean receivers are handled.** `isBooleanOrOperand` stops
  the previously missed `a.foo().bar() || c.baz().qux()` case, and
  `isBooleanOrExpression` keeps `(a || b).Use().Build()` and `||` inside
  arguments out of `CHAINING`.
- **Declaration/local copies and field/self copies.** `declaration` now catches
  `val _db = db`, and `classify` requires a field-like LHS distinct from a
  non-field-like RHS, so `this._db = _db` and `_x = _x` no longer establish;
  differently named local copies become candidates as intended.
- **Overlap tests.** `chainingCoexistsWithServiceInit` exercises the factory
  chain with `SERVICE_INIT`; `SEARCH`/`RETURN_VALUE`/`LOCAL_COPY` co-occurrence
  and `DATA_MODIFY` exclusion are still asserted. The mutation divergence from
  Round 1 finding 5 is gone because the suppression code was removed.
- **`RETURN_VALUE` label handling.** `return@forEach` is skipped and the new
  test keeps labeled and scalar literal returns silent.

### Residual findings

#### 1. `CHAINING` candidates are now unreachable, and ambiguous chains are dropped

Location: `ChainingRulePack.kt:96-112`.

Problem: a member is added only when `exact` matches the receiver span, and
`isDirectLink` is the same exact-span test, so every built chain is `direct` and
the `CandidateEmit` branch is dead. The former ambiguous shape
`(flag ? Factory.For() : Other).Use().Build()` now returns `null` (contained but
not exact) and its old candidate test was deleted. The ticket still asks for
"candidate emits for ambiguous chain/copy shapes; deterministic first", so
dropping them is a regression in the intent contract, not just a test change.

Suggested fix: separate an off-path contained call (reject: `a[b()].c()`) from
an ambiguous chain base such as a conditional/cast/parenthesized expression
(candidate), and emit `CandidateEmit` for the latter; restore the ternary test.
If no `CHAINING` candidates are wanted, revise the ticket and intent-model
wording instead of leaving dead candidate code.

#### 2. The OR ancestor walk suppresses chains nested inside an OR operand

Location: `ChainingRulePack.kt:123-129`.

Problem: `isBooleanOrOperand` climbs every parent until it finds any ancestor
with a `||` child. For `foo(builder.For().Use().Build()) || fallback`, the inner
chain is inside a call argument of the left OR operand, yet the ancestor walk
reaches the `||` and suppresses `CHAINING` even though the chain itself is not a
boolean operand. The new argument test covers `||` inside the chain's arguments,
not a chain inside an operand's arguments/lambda.

Suggested fix: stop the ancestor walk at argument-list, lambda, and statement
boundaries, or suppress only when the chain root is the immediate operand of the
`||` (possibly through navigation/call parents). Add a test with a chain inside
a call argument on one side of `||`.

#### 3. `LOCAL_COPY` established still does not prove a parameter source and still case-folds

Location: `LocalCopyRulePack.kt:135-152` and `:150-158` (normalize).

Problem: the field/self guards remove some false positives, but "established"
is still name-shape based: `this.x = x` establishes when `x` is a local or
property, `_db = db` establishes when `db` is a local, and `_db = DB` or
`x = X` establishes because `normalize` lowercases. None of these prove the
documented "with parameter source". Conversely, `classify` now returns a
candidate for every simple `local = identifier` assignment, so unrelated local
reassignments become `LOCAL_COPY` candidates. Parameter resolution remains
absent.

Suggested fix: resolve the RHS identifier to an enclosing function parameter
(extend the fact/`View` surface if necessary), and emit only a candidate when
the source cannot be proven to be a parameter. Compare exact identifiers after
prefix/underscore normalization rather than lowercasing, and add negative tests
for `_db = DB`, a local RHS, and an implicit property RHS. Consider narrowing
candidates to field-like targets or declaration copies if noise matters.

#### 4. Mutation co-occurrence is now unconditional, not explicit policy

Location: `MutationRulePack.kt:173-183`;
`LocalCopyRulePackTest.kt:185-186`.

Problem: deleting `ignorePureCopies` removed the config divergence, but it also
removed the ticket's preferred precedence. With the bundled default packs,
`_db = db` now emits both `LOCAL_COPY` and `LOCAL_VALUE_MODIFY`, and the test
asserts that co-occurrence. The ticket says `LOCAL_COPY` should co-exist with
`LOCAL_VALUE_MODIFY` only when that is explicitly policy and that suppression is
preferred for pure parameter copies, so the default is on the wrong side of the
documented boundary.

Suggested fix: restore suppression as a mutation-side policy that defaults to
on (without hardcoding the structural pack's default prefix set), or have the
mutation rule read the actual `LOCAL_COPY` intent/spans and suppress those
writes. Keep an explicit opt-out for projects that want both, and test both
default and opt-out with non-default local-copy prefixes.

The remaining Round 1 findings are resolved or adequately addressed; closing
requires the four items above, especially the candidate contract and mutation
precedence.

## Round 3

Verdict: Concur. The four Round 2 residuals are addressed, the acceptance
behaviors are pinned, and the loop can close. The observations below are
non-blocking follow-ups, not reasons to hold the ticket.

### Concurrences

- **`CHAINING` direct, OR, candidate, and base semantics.** Exact node/site
  spans keep only real links, so `a[b()].c()` still emits nothing while
  `a[b()].c().d()` and `(a + b()).c().d()` count only the two real call links.
  `isBooleanOrOperand` now checks the enclosing `||` after argument/lambda
  boundaries, so direct OR operands are suppressed while chains inside operand
  arguments and Kotlin lambdas survive. The ternary receiver is a candidate
  again and the candidate path is reachable; index/argument, non-ASCII, and
  service-overlap tests are present.
- **`LOCAL_COPY` semantics.** Established now requires a field-like LHS, a
  non-field-like RHS, exact normalized case, and a source that is not a declared
  property/local. That fixes the Round 1 field-to-field, self-copy, property
  source, and `DB`/`db` case variants, while declaration and differently named
  local copies stay candidates. Parameter names are parsed per enclosing
  function, and the `declared`/parameter distinction is used to stop local or
  property sources from establishing.
- **Mutation suppression policy.** `ignorePureCopies` is default-on, the mutation
  rule reads the actual `LOCAL_COPY` intent, configured prefixes are honored,
  and the opt-out restores co-occurrence. The focused tests cover default
  suppression with non-default prefixes and the `false` policy.
- **`RETURN_VALUE` labels.** Bare `return@label` is excluded with an anchored
  regex, labeled returns with values are still emitted, and the
  labeled/scalar-literal test plus the manual `ReturnFact` test cover both
  sides. Literal filtering and co-occurrence with `SEARCH`/`VALIDATION` remain
  intact.

### Non-blocking observations

- `OPERAND_BOUNDARIES` includes `lambda`, which matches Kotlin/C# lambda kinds
  but not the JavaScript/TypeScript `arrow_function`/`function_expression`
  kinds. A chain inside an arrow used directly as an OR operand
  (`factory || (() => builder.For().Use().Build())`) is still suppressed. Adding
  `function`/`arrow` (or a profile-derived lambda check) and one JS/TS test
  would close the cross-language gap.
- `LOCAL_COPY`'s parameter proof remains approximate: a same-named loop/catch
  binding, imported/global identifier, or annotation-argument parsing failure
  can still establish or miss. This is narrow and candidate fallback covers
  declared sources; if strict function-parameter-only semantics are required,
  resolve the RHS binding rather than only excluding declared properties.
- The mutation rule now hard-reads `LOCAL_COPY`, so mutation requires the
  LocalCopy owner in the plan. This follows the existing
  `data-transformation` → `SEARCH` bundled-dependency convention; if arbitrary
  subset plans are a supported contract, that dependency should eventually be
  optional.

No blocking findings remain for ticket 046.
