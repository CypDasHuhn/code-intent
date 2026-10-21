# Correctness review — ticket 045 (composition intent coverage)

## Round 1

Verdict: Not ready. The wiring is complete (both packs are registered and
discoverable), the declaration propagation does paint the motivating
`val uses = ...` statement, and the read-only change correctly moves from
"explicitly pure" to "no known write" with unknown-read-only at lower
confidence. However the read-only guard change certifies non-rejecting control
flow, the new initializer propagation bypasses the read-only gate, and
`DATA_TRANSFORMATION` establishes from a bare method name. Docs are also
missing despite being in scope, and the new negative paths are largely
untested. Read-only review of commit `211c896`; no build, tests, formatter, or
source edit was run.

### Findings

1. **Read-only and elvis validation ignore whether the exit actually rejects
   anything.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:201-218`
   (guard) and `:134-145` (elvis).

   `readOnly = !hasKnownWrites(condition)` is now sufficient to certify a
   guard, and `emitFor` never inspects `guard.exits` / the then-branch value.
   Any guard whose condition is not a write becomes `VALIDATION` at 0.5, so a
   cache-hit or normal-value guard such as
   `if (cache.get(key) != null) return cached` is now painted as validation even
   though it returns business data. `elvisEmits` has the same gap: it only
   checks that the RHS is an exit, not that it is a rejection, so
   `val x = maybe ?: return fallback` is `VALIDATION`. The acceptance cases
   (`?: continue`, `return false`, `return ""`) are exactly the rejection
   shapes, so this is broader than the ticket requires and contradicts
   `docs/intent-model.md`'s "validation means rejecting/skipping input" (the
   known gradient problem at `docs/intent-model.md:78-85`).

   Fix: for the read-only fallback (and for elvis), require an exit that is a
   rejection — bare `return`/`continue`/`break`, or a return expression in
   `rejectionSentinels` / a literal null/false/empty — while a normal-value
   return stays untagged or candidate. Keep the 0.5 confidence for the accepted
   read-only rejection form. Add a negative test for
   `if (find(key) != null) return found` and `?: return fallback`.

2. **Read-only blocking only sees syntactic write facts, and initializer
   propagation bypasses it entirely.**

   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/composition/DerivativeComposition.kt:154-177`
   and `:56-79`.

   `hasKnownWrite` only scans `ctx.writes` (assignment/increment nodes).
   Mutation calls such as `list.add(x)` or `map.put(k, v)` — which the mutation
   pack already classifies as `LOCAL_VALUE_MODIFY` — and persistence calls such
   as `repository.save(...)` (`DATA_MODIFY`) are not write facts, so they fall
   through to `DERIVATIVE_UNKNOWN_READ_ONLY_CONFIDENCE` and promote at 0.55. The
   acceptance says known writes/mutations still block promotion, not merely
   lower it. Separately, the new declaration-propagation loop at `:56-79` emits
   `Role.DERIVATIVE_USE` without calling `readOnlyConfidence`/`hasKnownWrite` at
   all, so `val x = run { counter++; repository.findAll() }` still inherits
   `SEARCH` from the initializer even though the first (read-based) loop would
   block it. The two derivative paths therefore disagree on the ticket's core
   policy.

   Fix: treat mutation/persistence intents found inside the initializer
   (`LOCAL_VALUE_MODIFY`, `DATA_MODIFY`, or whatever mutation intents the core
   is configured with) as known mutations, or add call-mutation facts; apply the
   same read-only gate to the propagation loop. Add tests for an initializer
   containing `list.add(...)`/`repository.save(...)` and for a write plus a
   classified call inside one initializer.

3. **`DATA_TRANSFORMATION` establishes on a callee name with no receiver or
   type evidence.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/transformation/TransformationRulePack.kt:40-61`
   with the name-only lists in
   `.../intents/transformation/TransformationConfig.kt:61-115`.

   Established output is produced for any call whose callee is in `calls`, with
   no qualifier or `view.receiverType(call)` check. Common names such as `map`,
   `filter`, `take`, `all`, `any`, and `none` are also ordinary domain methods,
   so `entity.map(handler)`, `permissions.all()`, or a custom `filter(...)`
   become established `DATA_TRANSFORMATION` at 0.9 with no collection evidence.
   This is inconsistent with the normalization/data-access/mutation packs, which
   require type/qualifier evidence before establishing and otherwise emit
   candidates, and with the ticket's "deterministic pack detection first; emit
   candidates for ambiguous transformation helpers" note. The existing negative
   test (`DataTransformationRulePackTest.kt:255-268`) only uses `forEach`/`size`/
   `isEmpty`, none of which are configured, so it cannot catch this.

   Fix: require receiver type/collection evidence (or a configured qualifier)
   before established output; emit a `CandidateEmit` when the receiver type is
   unknown/non-collection. Add a negative/ambiguous test with a non-collection
   receiver that defines `map`/`all`.

4. **`BOOL_COMBINATORICS` predicate prefixes accept unrelated call names.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/logic/BoolCombinatoricsRulePack.kt:144-167`.

   `classifiedPredicate` uses raw `startsWith`, and `distinctPredicates >= 2`
   is enough to establish. `"issue"` matches the `is` prefix and `"hashCode"`
   matches `has`, so `if (issue() && hashCode() > 0)` is established boolean
   combinatorics from two non-predicates. The configured threshold logic is
   otherwise sound (multiple operators, mixed operators, negated compounds,
   quantifiers), and the `a && b` candidate/simple-comparison exclusions match
   the ticket; this prefix collision is the remaining false-positive source.

   Fix: match `is`/`has` prefixes only at a word boundary (`isX`/`hasX`,
   uppercase or separator), or count only exact configured predicate calls
   toward `minimumPredicates`. Add a test using `issue()`/`hashCode()` and a
   negation-only guard.

5. **Docs are absent and now contradict the implementation.**

   `docs/intent-model.md:175-186` still says derivative use requires explicitly
   pure initializers, that unannotated calls/operators are assumed impure, and
   that there is no impure-local promotion. The new behavior promotes unknown
   call initializers at 0.55 and external field reads at 0.7, and declaration
   propagation inherits initializer intents. The intent table at `:141-142`
   also omits `all`/`any`/`none` from `DATA_TRANSFORMATION` even though they are
   configured as established transformations and overlaps with
   `BOOL_COMBINATORICS`/`VALIDATION`, and the client default tag list at
   `:419-423` omits both new intents. The ticket explicitly puts docs
   (definitions, overlap/precedence, read-only confidence policy) in scope, but
   the diff contains no docs change for `045`.

   Fix: update §3 with the read-only policy (writes/mutations block, unknown
   lowers to 0.55, external reads are allowed), document the
   BOOL/DATA_TRANSFORMATION/SEARCH/NORMALIZATION/VALIDATION overlaps, add the
   new intents to the default tag list, and pin the policy wording to the tests.

6. **Test coverage misses the new negative paths.**

   `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/validation/ValidationReadOnlyTest.kt`
   only checks `count++` and exit-shaped elvis; it has no non-rejection
   read-only guard/elvis case. `DataTransformationRulePackTest.kt` has no
   same-named non-collection method case. `CompositionIntentCoverageTest.kt`
   asserts the motivating fixture but not duplicate/anonymous derivative spans,
   a write-containing initializer, or a mutation-call initializer.
   `BoolCombinatoricsRulePackTest.kt` has no predicate-prefix collision or
   negation-only case. These are the exact paths where the implementation is
   currently too broad, so the suites stay green while the ticket acceptance
   ("writes/mutations still block", "simple/borderline forms behave") is only
   partially pinned.

   Fix: add the negative tests named in findings 1–4 and a duplicate-span
   assertion for a property whose reads and initializer both carry intents.

### Concurs / Dissents

- Concurs: registration is complete — both packs are in
  `META-INF/services/dev.cint.api.RulePack`, the four bundled registration/
  loading/migration/host lists include the new ids and intents, and the pipeline
  snapshot was regenerated with the new `Composition.kt` fixture. The client
  defaults also gain both tags.
- Concurs: the first derivative loop implements the requested read-only shift —
  unknown read-only is 0.55, explicit/pure/identifier/constant is 0.7, and
  syntactic writes block promotion (`newPipelineDoesNotPromoteKnownWrites` and
  `newPipelinePromotesUnknownReadOnlyInitializersWithLowerConfidence`).
- Concurs: initializer propagation paints the declaration and `merge` collapses
  same-role/same-span contributions, which is what makes
  `val current = node ?: continue` carry `{LOGGING, VALIDATION}` and
  `val uses = ...` carry `{SEARCH, DATA_TRANSFORMATION}` without duplicate
  annotations in the snapshot.
- Concurs: `BOOL_COMBINATORICS` is appropriately selective for the acceptance
  cases (simple comparisons and `values.isEmpty()` are excluded, `a && b` is a
  candidate, mixed/nested/quantified forms establish), and the cross-language
  helper configuration works in the tests.
- Dissents: findings 1, 2, and 3 are correctness/behavior gaps, not merely
  coverage; finding 5 is a scope/acceptance miss; finding 6 is the missing
  regression fence for 1–4.

## Round 2

Verdict: Still not ready, but the round-1 behavior is mostly addressed. The
rejection-exit gate works for the acceptance shapes, mutation/persistence
annotations now block both derivative paths, the initializer gate and confidence
are tightened, and the BOOL prefix boundary plus negative tests are in place. One
DATA_TRANSFORMATION evidence path still establishes without evidence, `throw`
guards silently lost validation, and the required docs are still absent, so the
loop cannot close yet. Read-only review of `7bda72d`; no build, tests, formatter,
or source edit was run.

### Findings

1. **DATA_TRANSFORMATION still establishes when the receiver type is unknown.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/transformation/TransformationRulePack.kt:81-88`.

   `hasReceiverEvidence` returns `true` when `view.receiverType(call)` is null:
   `val type = view.receiverType(call) ?: return true`. `NullOracle` is the
   default in batch/CLI composition, so every receiverful configured call
   (`entity.map`, `permissions.all`, a custom `filter`) still establishes at 0.9
   whenever the oracle is absent. The new test only covers a known
   non-collection receiver through `FixedOracle`
   (`DataTransformationRulePackTest.kt:279-296`); there is no unknown-type case.
   The round-1 requirement was candidate output for ambiguous receivers, and
   this path is the common offline one.

   Fix: when the type is unknown, emit a candidate unless a qualifier matches or
   the receiver is a known collection-producing/SEARCH expression; add a
   NullOracle test such as `entity.map { }` staying a candidate. If unknown is
   intentionally accepted for the motivating `repository.findAll().map` chain,
   document that rule and add a test pinning it so it is not a silent oracle
   dependency.

2. **`throw`-based rejection guards no longer get VALIDATION.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:209-243`.

   `hasRejectingExit` is gated by `view.isExit`, which recognizes only
   `return`/`continue`/`break` (the Kotlin/JS/C# exit keywords). A guard such as
   `if (receiverNode(call) == null) throw IllegalArgumentException()` was
   read-only VALIDATION in `211c896`; it is now dropped unless the condition is
   also a configured predicate, bookkeeping-based, or explicitly commented.
   Throwing on invalid input is a rejecting exit, and the ticket's validation
   semantics are "reaching/skipping input"; at minimum the exclusion needs to be
   intentional and documented.

   Fix: treat `throw`/`raise` in the then/else body as a rejecting exit (the
   condition may legitimately also overlap `ERROR_HANDLING`), or explicitly
   document and test that throwing guards are out of `VALIDATION`. Add a
   regression test for the receiver-guard/throw shape.

3. **The round-1 docs finding is still open.**

   `docs/intent-model.md:175-186` still says derivative use requires explicitly
   pure initializers and that unannotated calls/operators are impure, contradicting
   the new unknown-read-only 0.55 promotion, 0.7 external-read promotion, and the
   initializer/assignment propagation. The intent table at `:141-142` still omits
   the overlap/precedence wording requested for
   `DATA_TRANSFORMATION`/`BOOL_COMBINATORICS`, and the client default tag list at
   `:419-423` still omits both intents. `git diff 211c896..7bda72d -- docs` is
   empty, and no deferral to a named ticket was recorded.

   Fix: apply the docs update from round-1 finding 5 (read-only/confidence
   policy, overlap/precedence, default tags) or defer it explicitly to a named
   ticket with a reason.

### Concurs / Dissents

- Concurs: rejection-exit validation is correct for the accepted shapes.
  `elvisEmits` now requires `continue`/`break` or a sentinel return
  (`ValidationReadOnlyTest.nonRejectingReturnsStayOutOfValidation`), and
  `emitFor` requires a rejecting exit for the pure/read-only paths, so cache-hit
  and `?: return computed()` no longer become validation. The
  `predicate`/`explicit`/`bookkeeping` paths remain rejection-independent by
  design, which the ticket does not ask to change.
- Concurs: known write/mutation blocking is fixed in both derivative paths.
  `hasKnownWrite` now also checks mutation source annotations
  (`DATA_MODIFY`/`LOCAL_VALUE_MODIFY`/`BOOKKEEPING`) inside the value, and the
  property/assignment propagation loops both call `readOnlyConfidence`
  (`DerivativeComposition.kt:68-101,171-181`). The write, nested-mutation,
  direct-mutation, and mutation-call tests cover the round-1 gap.
- Concurs: initializer gate/confidence is tightened coherently. Propagation now
  requires read-only, uses the minimum source confidence, merges contributions
  with `minOf`, excludes lambda effects from inherited intents while still using
  them for write blocking, and adds assignment-span propagation. The fixture
  assertions (`uses` 0.9, `current` 0.55) and assignment test match the snapshot.
- Concurs: BOOL predicate prefixes now require a word boundary
  (`BoolCombinatoricsRulePack.kt:165-176`), with `issue() && hashCode()` staying a
  candidate, `isValid()/hasValue()` establishing, and negation-only guards
  staying untagged. This addresses round-1 finding 4.
- Concurs: registration/snapshot/scheduling remain consistent; validation's
  `reads` now includes mutation and bookkeeping, and the scheduler test covers
  the strata change.
- Dissents: round-1 finding 3 is only partially addressed because unknown
  receiver types still establish; round-1 finding 5 is unaddressed; the
  rejection gate introduces the `throw` regression in finding 2. The loop can
  close after finding 1 and finding 3 are fixed (or finding 3 is explicitly
  deferred) and finding 2 is either fixed or documented as intentional with a
  test.

## Round 3

Verdict: Not ready. The round-2 items are substantially improved: unknown
identifier receivers now stay candidates, `throw`/elvis rejection works for
Kotlin, and the Kotlin nested-loop/argument cases have tests. However the new
nested-scope boundary is wrong for unbraced single-statement bodies in
JS/TS/C#, `raise` is treated as a throw keyword in every language,
argument-effect filtering suppresses value intents too, one DATA_TRANSFORMATION
evidence shortcut remains, and the docs finding is still open. Read-only review
of `b17d45a`; no build, tests, formatter, or source edit was run.

### Findings

1. **Unbraced uses in JS/TS/C# lose their rejection exit.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:218-253`.

   `insideNestedScope` and `insideNestedLoop` start at `node.parent` and climb
   until `current.startByte == body.startByte`. For Kotlin, `thenBody` is a
   `control_structure_body` that gives that boundary. For JS/TS/C# the profile
   uses the `consequence` field (`ifThen: [consequence]`), so an unbraced
   `if (x) return false;`, `if (x) continue;`, or `if (x) throw ...;` has
   `body == node`. No ancestor shares that startByte, so the walk climbs to the
   enclosing function (scope) or enclosing loop (continue/break) and returns
   true, excluding the exit. Those guards therefore get no `VALIDATION` unless
   the condition is also a predicate/bookkeeping/explicit case. The new
   `continueInsideANestedLoopDoesNotRejectTheOuterGuard` test only exercises
   Kotlin and braced bodies.

   Fix: use containment rather than startByte equality (a nested loop/scope must
   lie within `body`; the enclosing loop/function must not count), and handle
   `node == body` explicitly. Add JS/TS/C# tests for unbraced
   `if (x) return false;`, `if (x) continue;`, and `if (x) throw ...;`.

2. **`raise` is treated as a throw keyword in languages that do not have it.**

   `ValidationRulePack.kt:255-259`.

   `isThrowExit` is text-based: `leadingKeyword(node) in setOf("throw",
   "raise")`. A JS/TS/C#/Kotlin call or identifier named `raise` therefore
   counts as a rejecting throw; `if (flag) raise()` marks the guard
   `VALIDATION`. It is unrelated to the parser's throw/raise statement kind.

   Fix: recognize throw/raise by language/node kind (e.g. `throw_statement`,
   `raise_statement`, Kotlin `jump_expression` throw) and gate `raise` to
   languages that actually use it. Add a test where `raise()` is an ordinary
   call.

3. **Argument-effect filtering also suppresses value intents.**

   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/composition/DerivativeComposition.kt:162-168,183-185`.

   `valueSourceAnnotations` drops every source annotation whose node is inside
   any argument kind. That is appropriate for the logging effect in the new
   test, but it also drops `SEARCH` and `DATA_TRANSFORMATION` when the value
   comes from an argument, e.g. `val users = wrap(repository.findAll())` no
   longer inherits `SEARCH`, contrary to "when an initializer has intents the
   declaration inherits them". The new
   `initializerPropagationIgnoresArgumentEffects` test passes a `SEARCH`
   argument and asserts no derivative at all, so it pins the over-filtering
   rather than effect-only suppression.

   Fix: filter only effect intents from arguments (or make the ignored intents
   configurable), or document that argument intents are intentionally not
   inherited and add a test showing a value-producing argument separately.

4. **The call-receiver shortcut still establishes without evidence.**

   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/transformation/TransformationRulePack.kt:83-91`.

   Unknown simple-identifier receivers now correctly become candidates, but
   `receiver.kind in CALL_RECEIVER_KINDS || receiver.text.contains('(')`
   treats *any* call result as collection evidence. `loadEntity().map { ... }`
   or `service.fetchRow().all { ... }` establishes at 0.9 with no type,
   qualifier, or SEARCH/transformation provenance. The motivating
   `repository.findAll().map` chain is covered by this shortcut, but so is every
   other factory/method call.

   Fix: require the receiver call to be classified as SEARCH/transformation or
   have a collection type/qualifier; otherwise candidate. Add a negative test
   for an unclassified call receiver.

5. **Docs are still absent.**

   `docs/intent-model.md:175-186` still describes the old explicitly-pure
   derivative policy and does not document the read-only confidence,
   argument/lambda effect filtering, or the new overlap/precedence rules; the
   client default list at `:419-423` still omits both intents.
   `git diff main...b17d45a -- docs` is empty and no deferral was recorded.

   Fix: apply the round-1 docs update or defer it explicitly to a named ticket.

### Concurs / Dissents

- Concurs: transformation evidence for unknown simple identifiers is fixed.
  `entity.map` with no oracle or qualifier is now a candidate, while the
  qualifier and known-collection paths remain established
  (`unknownReceiverTypesWithoutQualifiersStayCandidates`).
- Concurs: throw rejection is fixed for the tested Kotlin shapes, including
  elvis (`throwingRejectionGuardsAndElvisAreValidation`), and the snapshot now
  shows the C#/JS/TS throw guards as `VALIDATION` where their bodies are braced.
- Concurs: nested-loop `continue` scoping works for the tested Kotlin braced
  shape, and argument/lambda effect suppression works for the form the test
  covers.
- Concurs: the round-2 mutation/write blocking, initializer confidence, BOOL
  prefix boundary, and registration/snapshot changes remain intact.
- Dissents: findings 1 and 4 are correctness gaps in the new evidence/scope
  logic, finding 3 is an over-broad filter, finding 2 is a cross-language false
  positive, and finding 5 is still an acceptance miss. The loop cannot close
  until the code findings are fixed or explicitly deferred and the docs finding
  is handled the same way.

## Round 4

Verdict: The code findings from rounds 1–3 are addressed. Rejection-exit
scoping now handles unbraced bodies correctly across JS/TS/C# and excludes
nested-loop/nested-switch jumps; `raise` is no longer treated as a throw
keyword; argument/lambda filtering removes only effect intents while preserving
value intents; transformation evidence reads `SEARCH` and requires a classified
receiver or type/qualifier evidence; and the new tests pin each of these. The
only remaining blocker is the still-missing docs update, so the loop cannot
close yet. Read-only review of `99470d2`; no build, tests, formatter, or source
edit was run.

### Concurs / Dissents

- Concurs: unbraced guards are fixed. `insideNestedScope` and
  `insideNestedJumpScope` return early on `sameSpan(node, body)`, so a
  single-statement JS/TS/C# `if (x) return false;`, `continue;`, or `throw ...;`
  is not mistaken for a nested function/loop; the new
  `unbracedRejectingBodiesAreValidationAcrossLanguages` test covers all three
  languages and three rejection forms.
- Concurs: throw keywords are correct. `isThrowExit` now recognizes only
  `throw`, and `ordinaryRaiseCallsAreNotThrowRejections` confirms an ordinary
  `raise()` call is not validation. This removes the round-3 cross-language false
  positive.
- Concurs: argument effect filtering is now scoped correctly.
  `valueSourceIntents` strips only `LOGGING`/`ERROR_HANDLING`/mutation
  (`EFFECT_INTENTS`) for nodes inside lambdas/call arguments, while
  `initializerPropagationIgnoresArgumentEffects` now asserts the declaration
  keeps `SEARCH` and drops `LOGGING`.
- Concurs: transformation evidence/reads are sound. The pack declares
  `reads = {SEARCH}`, so the scheduler orders data-access first, and
  `hasReceiverEvidence` accepts a SEARCH-classified receiver, then a known
  type/qualifier; `unclassifiedCallReceiversStayCandidates` and
  `searchClassifiedCallReceiversEstablish` cover both sides. Declaring
  `SEARCH` makes the pack require the data-access owner, but that is the same
  bundled-pack pattern already used by validation/bookkeeping and is pinned by
  the updated test helpers.
- Concurs: nested switch `break` is handled. `break` checks loops and
  `SWITCH_KINDS`, while `continue` checks loops only, and
  `breakInsideNestedSwitchDoesNotRejectTheOuterGuard` pins the JS switch case.
- Dissents: docs remain unaddressed. `docs/intent-model.md:175-186` still
  describes explicitly-pure derivative use, the intent table/overlap and client
  default tag list are still stale, and `git diff main...99470d2 -- docs` is
  empty with no named deferral. Once that docs update (or an explicit deferral)
  lands, the loop can close.

## Round 5

Verdict: Concur. The docs finding is resolved by the scribe changes in the
working tree, and the self-read/fixpoint change and its tests are sound. No
remaining correctness finding. Read-only review of `3e945b7` plus the
uncommitted scribe docs; no build, tests, formatter, or source edit was run.

### Concurs / Dissents

- Concurs: the docs finding is resolved. `docs/intent-model.md` now documents
  the rejection-shaped validation rules and confidence ladder, the read-only
  derivative policy (`0.7` known / `0.55` unknown, writes and mutation intents
  blocking), declaration/assignment propagation and effect-vs-value filtering,
  the `BOOL_COMBINATORICS`/`DATA_TRANSFORMATION` definitions and thresholds, a
  concrete overlap/precedence section, the new bundled pack ownership, and both
  new default client tags. `docs/engine.md` and `docs/architecture.md` match the
  implemented validation, read-only, transformation evidence, registration, and
  self-read behavior. The 045 task notes record the implementation and keep the
  ticket in progress until this review closes; the orchestrator can now move it
  to done.
- Concurs: the `reads` change is intentional and safe. `TransformationRulePack`
  reads `SEARCH` plus its own `DATA_TRANSFORMATION`; `RuleScheduler` ignores the
  self-edge for strata (`owner == reader`), so there is no cycle, while
  `RuleEngine.settle` sees `owns in reads` and runs its internal monotone
  fixpoint. The established identity set only grows because receiver evidence
  comes from `SEARCH`/`DATA_TRANSFORMATION` intents that are added, not removed,
  and the existing generic self-reading convergence tests cover the engine
  mechanism.
- Concurs: the new scheduler test is sound. It asserts the transformation rule
  reads both intents and that the SEARCH owner is in an earlier stratum; a
  self-edge cycle would make `plan` throw, so the ordering assertion is
  meaningful.
- Concurs: the chained-transformation test is non-vacuous. The custom oracle
  returns a collection type only for the inner `values` receiver; the outer
  `filter` receiver therefore has no qualifier, type, or search evidence and can
  only become established after the inner `DATA_TRANSFORMATION` emission is
  read back from the previous fixpoint iteration. Asserting the outer source
  span and an empty candidate set correctly pins that behavior.
- No dissents. The loop can close once the scribe docs are committed and the
  ticket status is updated to done; no further code or docs work is required for
  this review dimension.
