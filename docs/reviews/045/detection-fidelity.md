# Detection-fidelity review — ticket 045 (composition intent coverage)

## Round 1

Verdict: Changes requested. The central detections are present and the motivating
fixture is covered: `BOOL_COMBINATORICS` is selective, `DATA_TRANSFORMATION`
matches the configured collection operations (including `all` as the ticket
requires), elvis/early-continue and unknown-read-only guards become `VALIDATION`,
and declaration propagation paints the whole property span rather than only the
RHS. Several boundaries are still too loose, however: read-only is used as a
blanket validation certificate for every non-writing condition, initializer
propagation unions every nested source intent (including effects inside lambdas)
while missing plain assignment statements, and merging contributions takes the
maximum confidence across different intents. The transformation/local-mutation
overlap and the new read-only policy are not documented or pinned.

## Baseline

Reviewed `211c896` (`feat(composition): cover boolean and transformation
intents`) against `main` at `9e5a655`, with `docs/workflow.md`,
`docs/tasks/045-composition-intent-coverage.md`, `docs/intent-model.md`, the
changed rule packs/composition code, the focused tests, and the refreshed
pipeline snapshot. No build, test, lint, or formatter command was run, per the
reviewer role.

## Concurrences

- **Boolean selectivity.** `BoolCombinatoricsRule.eligibleRoot` requires a direct
  `&&`/`||`/`!` child or a configured quantifier call, and `established` requires
  at least two operators, mixed operators, a negated compound, a quantifier, or
  two distinct classified predicates. `if (x != null)`, `if (a == b)`, and
  `if (uses.isEmpty())` stay untagged; `a && b` is only a low-confidence
  `CandidateEmit`; `a && b || c`, `a && (b || c)`, `values.all { ... }`, and the
  motivating `isConstant(selected) || isIdentifier(selected) || ...` establish.
  Nested booleans under a non-quantifier call are intentionally not roots, and
  the `nestedBooleanInsideACallIsNotTheRootExpression` test pins that.
- **Transformation semantics.** `map`, `filter`, `flatMap`, `fold`, `reduce`,
  `distinct`, `sorted`, `groupBy`, and the other configured Kotlin/JS/TS/C#
  collection operations establish; `transform`/`process`/`reshape`/`convert` and
  their prefixes stay candidates. The motivating `.map` and `.all` both carry
  `DATA_TRANSFORMATION`, as the acceptance criterion explicitly requires.
- **Elvis and read-only guards.** Direct `?: continue`, `?: break`, and `?: return`
  right-hand exits become `VALIDATION`; known writes inside the elvis expression
  block the emit. `if (receiverNode(node) != null) return false` is `VALIDATION`
  at 0.5 while `uses.isEmpty()` is 0.7, so the confidence ordering is right.
- **Derivative read-only policy.** `readOnlyConfidence` returns `null` when a
  known write is inside the initializer, 0.7 for constant/identifier/side-effect-
  free/annotated-pure initializers, and 0.55 for unknown calls. External reads
  without calls (`config.message`) promote at 0.7, and unannotated calls promote
  at the lower confidence, which matches the ticket.
- **Declaration propagation placement.** The second `ctx.properties` loop tags
  the `PropertyFact.node` (the whole declaration), so `val uses = ...` and
  `val current = ...` carry intents on the declaration span, not just the
  initializer. The revised snapshot confirms this for `val uses`, `val selected`,
  and `val pure`.

## Findings

### 1. Read-only certifies every non-writing condition as `VALIDATION`

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:202-218`.

Problem: `readOnly = !hasKnownWrites(view, condition)` is added directly to
`validationGuard`, so any condition that contains no syntactic write is an
established `VALIDATION` emit with `certifiesReturn = true`, even when the guard
has no rejecting/validating shape. This reopens the gradient problem recorded in
`docs/intent-model.md` §2: a cache-hit guard (`if (cache.contains(key)) return
cached`) or an ordinary business branch (`if (current.kind == FOUND) return
current`) becomes `VALIDATION` merely because no `WriteFact` falls inside the
condition. Because the emit certifies returns, it also changes `ReturnComposition`
for those branches. The ticket asks for externally-reading guards such as
`receiverNode(call) != null` to survive, not for every read-only early exit to be
certified.

Suggested fix: use read-only as a confidence reducer only for guards that are
already validation-shaped (validation predicate, rejecting branch, bookkeeping,
or explicit comment), or emit a `CandidateEmit` when read-only is the only
evidence. Add negative tests for a cache-hit return and a found-value return.
Also distinguish “proven read-only” from “no known write” so an untracked
mutation call cannot silently certify a guard.

### 2. Initializer propagation over-tags nested effects and misses assignments

Location: `analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/composition/DerivativeComposition.kt:56-79` and `:132-142`.

Problem: `initializerSourceAnnotations` unions every `SOURCE_INTENT` annotation
whose span exactly matches the initializer or *any descendant*. A declaration is
therefore tagged with intents of effects nested inside lambdas or argument
expressions, not just the intents that shape the initializer value. Examples:
`val users = repository.findAll().onEach { log.info(it) }` makes the whole
declaration `{SEARCH, LOGGING}`, and `val saved = items.map { repository.save(it) }`
makes it `{DATA_TRANSFORMATION, DATA_MODIFY}` even though the declaration value
is not itself persisted. This loop also has no read-only/write check, unlike the
first derivative loop. Conversely, the propagation only iterates
`ctx.properties`, so plain assignment statements (`target = repository.findAll()
.map { ... }`) do not inherit the RHS intents at all; only the RHS calls are
painted. The acceptance wording covers the “declaration / assignment span”.

Suggested fix: derive declaration intents from the initializer’s value-bearing
root (`ctx.intentsAt(initializer)` / annotations covering the initializer span)
rather than unioning all descendants, or exclude annotations inside lambda bodies
and argument lists that are not part of the value chain. Apply the same
propagation to assignment `WriteFact`s (tagging the assignment statement span)
and add positive/negative tests for both cases.

### 3. Merging derivative contributions inflates confidence across intents

Location: `analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/composition/DerivativeComposition.kt:114-129`.

Problem: `merge` keys only on role and span, unions the intent sets, and keeps
`maxOf(previous.confidence, contribution.confidence)`. A high-confidence intent
therefore raises the confidence of unrelated intents on the same declaration. In
the motivating snapshot, `val current = node ?: continue` merges `LOGGING` at
0.55 with `VALIDATION` at 0.6 and reports one 0.6 contribution for both; a
high-confidence `SEARCH`/`DATA_TRANSFORMATION` source (1.0) similarly raises any
derived-intent contribution to 1.0. The annotation contract has only one
confidence, but the merge policy is choosing the most optimistic value instead
of a conservative one, so confidence stops being diagnostic for the weaker
intent.

Suggested fix: keep one contribution per intent (or per rule) instead of merging
different intent sets, or use the minimum confidence for a combined contribution.
Add a test asserting per-intent confidence where the sources differ.

### 4. Transformation overlap/mutation boundaries are over-broad and undocumented

Location: `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/transformation/TransformationRulePack.kt:41-61` and `TransformationConfig.kt:102-120`.

Problem: the rule establishes on callee name alone, with no receiver or type
evidence. That is acceptable for the well-known collection names, but the
JavaScript defaults include in-place mutators (`sort`, `reverse`, `splice`), and
`all`/`any`/`none` are reductions to a Boolean rather than shaping a value. These
are simultaneously plausible `LOCAL_VALUE_MODIFY`/`BOOL_COMBINATORICS` cases, yet
the pack establishes `DATA_TRANSFORMATION` in isolation. `docs/intent-model.md`
says `DATA_TRANSFORMATION` excludes “fetching data (`SEARCH`)” and only
generically allows overlaps; it does not record that `filter`/`groupBy` are
intentionally both `SEARCH` and `DATA_TRANSFORMATION`, nor the mutation/reduction
overlaps. A domain method named `map`/`filter` on a non-collection receiver is
also established.

Suggested fix: require a collection/receiver or configured type before
establishing generic names where the oracle is available, and make untyped
matches candidate-only. Document the `SEARCH`/`DATA_TRANSFORMATION` co-occurrence,
the reduction meaning of `all`/`any`/`none`, and the mutator overlap with
`LOCAL_VALUE_MODIFY`; pin each with overlap tests.

### 5. The read-only and overlap policy was not added to the docs

Location: `docs/intent-model.md:173-186`, `:162-167`, and `:420-423`.

Problem: the ticket scope requires docs for intent definitions, overlap/precedence,
and the read-only confidence policy, but the diff changes no docs. `intent-model`
§3 still says “Only explicitly pure initializers qualify”, that “There is no
impure-local promotion”, and that unannotated calls are “treated as impure”,
which directly contradicts the new read-only promotion. The client-default list
in §8 also omits `BOOL_COMBINATORICS` and `DATA_TRANSFORMATION` even though
`nvim-renderer/lua/cint/core/config.lua` now adds them, and the overlap paragraph
does not name the new `SEARCH`/`DATA_TRANSFORMATION` or
`BOOL_COMBINATORICS`/`DATA_TRANSFORMATION` cases.

Suggested fix: update §3 to the read-only rule and its confidence levels, add
the new intents to the §8 client defaults, and record the overlap/precedence
decisions (including the mutation/reduction caveats above) in §2.

## Round 2

Reviewed `7bda72d` (`fix(composition): tighten intent and read-only
boundaries`) against the round-1 baseline `211c896`. Verdict: findings 1-4 are
substantially addressed, but one round-1 finding is untouched and two boundary
gaps remain, so the loop cannot close yet.

### Concurrences

- **Read-only guard behavior (round-1 finding 1).** `pure`/`readOnly` now require
  a rejecting exit, so a non-rejecting `return computed()`, `return true`, or
  `return value ?: computed()` no longer certifies `VALIDATION`; the snapshot
  correctly drops validation from the TS throw guard and the ordinary
  `Scaffolds` guards while keeping `receiverNode(node) != null return false`
  at 0.5. Mutation calls are also blocked through the declared
  `DATA_MODIFY`/`LOCAL_VALUE_MODIFY`/`BOOKKEEPING` reads, and
  `mutationCallConditionsAreNotReadOnlyValidation` pins `values.add`.
- **Rejection semantics.** `continue`/`break`, bare `return`, and returns whose
  expression is in `rejectionValues` certify; `return false`/`null`/`0`/empty
  collection literals work, and elvis forms use the same gate. The new
  `nonRejectingReturnsStayOutOfValidation` test covers the important negatives.
- **Initializer propagation (round-1 finding 2, mostly).** Propagation now
  requires the value to be read-only, filters source intents inside lambda
  bodies, blocks mutation annotations anywhere in the value, and covers both
  property initializers and `WriteKind.ASSIGNMENT` spans. The `val users =
  findAll().onEach { log... }`, nested-mutation, and `target = ...` cases pin
  the intended behavior, and the declaration/assignment span is painted.
- **Confidence merge (round-1 finding 3).** `merge` now takes the minimum, so a
  high-confidence `SEARCH` can no longer inflate a lower-confidence `VALIDATION`
  or `LOGGING` intent on the same span; the motivating `val uses`/`val current`
  assertions reflect that.
- **BOOL selectivity.** The prefix-boundary fix removes `issue`/`hashCode`
  false positives, negation-only guards stay untagged, and the established
  complex/mixed/quantifier cases are unchanged. I concur with the current
  selectivity.
- **Transformation overlap (round-1 finding 4, mostly).** Bare configured calls
  are candidates, known non-collection receivers are candidates unless a
  qualifier is configured, and the `sort` overlap with `LOCAL_VALUE_MODIFY` is
  now pinned.

### Remaining findings

1. **Docs still do not describe the read-only or overlap policy (round-1
   finding 5).** Location: `docs/intent-model.md:162-167`, `:173-186`, and
   `:420-423`. No docs file changed in `211c896..7bda72d`; §3 still requires
   explicit purity and says unannotated calls are impure, §8 still omits
   `BOOL_COMBINATORICS`/`DATA_TRANSFORMATION`, and §2 still does not record the
   `SEARCH`/`DATA_TRANSFORMATION`, reduction, or mutator overlaps. Fix: update
   those sections to the implemented read-only/min-confidence/overlap policy.

2. **A `continue`/`break` inside a nested loop in the guard body counts as the
   guard's rejecting exit.** Location:
   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:209-227`.
   `insideNestedScope` excludes nested functions and lambdas but not loop nodes,
   so `if (receiverNode(call) != null) { for (item in items) { if (bad(item))
   continue }; consume(call) }` makes the outer read-only condition
   `VALIDATION` even though the `continue` belongs to the inner loop and the
   outer guard never rejects. Fix: treat loops (and `when`/`switch` scopes) as
   nested rejection scopes for `continue`/`break`, or bind a jump to its
   enclosing loop. Add a negative test for a nested-loop continue with a
   read-only outer condition.

3. **Initializer propagation still unions effects nested in non-lambda
   arguments.** Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/composition/DerivativeComposition.kt:154-163`.
   Filtering only `insideLambda` leaves argument effects, e.g. `val message =
   format(log.info("x"), repository.findAll())`, tagged on the whole declaration
   as `{LOGGING, SEARCH}` even though only the search shapes the value. Fix:
   derive declaration intents from the value-bearing root or ignore descendant
   annotations whose parent call is not on the value chain. Add a non-lambda
   argument-effect negative test alongside the lambda test.

4. **Unknown receiver types bypass the transformation evidence gate.** Location:
   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/transformation/TransformationRulePack.kt:81-89`.
   `view.receiverType(call) ?: return true` establishes a configured callee
   whenever the oracle has no type, so in an oracle-less/batch run a domain
   receiver such as `entity.map { ... }` is established with no qualifier or
   type evidence. The FixedOracle test covers a known non-collection but not the
   null-type path. Fix: treat a missing type as no evidence (candidate) unless a
   qualifier matches, or resolve the receiver chain's collection type; add a
   NullOracle test for `entity.map`.

Can the loop close: not yet. Finding 1 is a direct ticket acceptance omission
with no deferral, and findings 2-4 are small but reproducible detection gaps that
need either a fix or an explicit deferral to a named ticket.

## Round 3

Reviewed `b17d45a` (`fix(composition): close round 2 intent boundary gaps`)
against the round-2 baseline `7bda72d`. Verdict: the requested detection fixes
are present and the remaining gaps are narrow, but the loop still cannot close
because the docs finding remains untouched and two evidence/scope boundaries are
only partially fixed.

### Concurrences

- **Transformation evidence (round-2 finding 4, mostly).** A simple receiver
  with no qualifier and no resolved type is now a candidate
  (`entity.map` is pinned by `unknownReceiverTypesWithoutQualifiersStayCandidates`),
  known non-collection types stay candidates, and the motivating
  `repository.findAll().map`/`uses.all` still establish. Adding `uses` to the
  default qualifiers keeps the fixture independent of the oracle.
- **Throw rejection.** `isThrowExit` now recognizes `throw`/`raise` before the
  ordinary exit check, and both guard bodies and elvis right-hand sides accept a
  throw as rejecting; the new Kotlin test and the C#/JS snapshot additions show
  the intended `{VALIDATION, ERROR_HANDLING}` overlap. A caught throw inside a
  nested `try` is still counted, but that is an edge case outside the current
  guard shapes.
- **Nested loop exits.** `insideNestedLoop` correctly stops an inner-loop
  `continue`/`break` from certifying the outer guard while still allowing a
  `continue` that belongs to the guard's enclosing loop; the negative test pins
  the outer read-only condition and keeps the inner predicate validation.
- **Argument effects.** `valueSourceAnnotations` now excludes annotations inside
  call arguments as well as lambdas, and the new
  `initializerPropagationIgnoresArgumentEffects` test confirms
  `format(log.info(...), findAll())` leaves the declaration untagged. The
  motivating search/transformation initializers and assignment spans still
  propagate.
- **BOOL selectivity and initializer propagation.** The bool thresholds and
  prefix-boundary behavior are unchanged from the concurred round-2 state, and
  property/assignment propagation still requires read-only values, filters
  lambda/argument effects, and blocks mutation intents. The `val uses`/`val
  current`/`target = ...` snapshots and tests remain correct.

### Remaining findings

1. **The docs finding from rounds 1 and 2 is unchanged.** Location:
   `docs/intent-model.md:162-167`, `:173-186`, and `:420-423`. No docs file
   changed in `7bda72d..b17d45a`; §3 still requires explicit purity, §8 still
   omits `BOOL_COMBINATORICS`/`DATA_TRANSFORMATION`, and §2 still does not state
   the `SEARCH`/`DATA_TRANSFORMATION`, reduction, or mutator overlaps. Fix as
   previously described, or explicitly defer docs to a named ticket so the
   omission is tracked.

2. **`break` inside a nested `switch` still counts as the outer guard's
   rejection.** Location:
   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:40-49,217-253`.
   The new `insideNestedLoop` handles loops but not switch/when scopes, so a
   JS/C# guard body containing `switch (...) { case x: break; }` plus a
   read-only condition becomes `VALIDATION` even though the `break` exits only
   the switch. Fix: treat `switch_statement`/`switch_expression` (and analogous
   `when` where applicable) as nested jump scopes for `break`, or bind the jump
   to its nearest enclosing loop/switch. Add a JS or C# negative test.

3. **Call-chain receivers still bypass the transformation evidence gate.**
   Location:
   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/transformation/TransformationRulePack.kt:81-89`.
   `receiver.kind in CALL_RECEIVER_KINDS || receiver.text.contains('(')`
   accepts any call-shaped receiver without a qualifier or resolved collection
   type, so `entity.getChild().map { ... }` establishes through the same path
   that was meant to keep untyped receivers as candidates. Fix: accept a call
   receiver only when the chain's terminal receiver matches a qualifier or the
   call's own callee is a configured search/transformation, otherwise emit a
   candidate. Add the oracle-less call-chain case.

Can the loop close: not yet. The detection cases requested for round 3 now
behave as intended apart from the switch/`break` scope and call-chain evidence
residuals, but the docs finding is still unaddressed and the loop cannot close
until all three are fixed or explicitly deferred to named tickets.

## Round 4

Reviewed `99470d2` (`fix(composition): close round 3 rejection and evidence
gaps`) against the round-3 baseline `b17d45a`. Verdict: the rejection, scoping,
argument-effect, and receiver-evidence fixes are present and tested; two issues
remain, one of them the long-standing docs omission.

### Concurrences

- **Unbraced rejection.** The `sameSpan` checks now let a single-statement
  `thenBody` be inspected directly instead of being mistaken for a nested scope,
  and the JS/TS/C# test covers unbraced `return`, `continue`, and `throw` (the
  Kotlin unbraced forms were already covered elsewhere). This is correct.
- **Throw handling.** Only the `throw` keyword is treated as rejecting now;
  ordinary `raise()` calls are no longer false positives, and both guard bodies
  and elvis right-hand sides accept throws. The C#/JS snapshot updates preserve
  the intended `{VALIDATION, ERROR_HANDLING}` overlap. A throw inside a nested
  `try` whose catch handles it still counts as rejecting, but that edge is not
  part of the current guard shapes.
- **Nested switch/loop scoping.** `insideNestedJumpScope` correctly binds
  `continue` to loops and `break` to loops or switches, and the new JS test
  keeps a nested-switch `break` from certifying the outer read-only guard. The
  earlier nested-loop test remains valid.
- **Argument value/effect separation.** `valueSourceIntents` now retains value
  intents (`SEARCH`, `DATA_TRANSFORMATION`, ...) from arguments/lambdas while
  subtracting `DATA_MODIFY`, `LOCAL_VALUE_MODIFY`, `BOOKKEEPING`, `LOGGING`, and
  `ERROR_HANDLING`. The revised test correctly expects `SEARCH` and no
  `LOGGING` for `format(log.info(...), findAll())`. The effect set is hardcoded
  in core, so a future `USER_FEEDBACK`-style effect would still leak until added,
  but that is a forward-looking caveat rather than a current failure.
- **Transformation evidence.** Qualifier and oracle-type evidence now decide
  simple receivers, `intentsAt(receiver)` replaces bare call-shape acceptance,
  and the new tests keep `service.loadEntity().map` as a candidate while
  `repository.findAll().map` establishes from `SEARCH`. This resolves the round-3
  `entity.getChild().map` finding.
- **Reads-induced stratum.** Declaring `SEARCH` correctly puts data-access ahead
  of transformation so receiver `SEARCH` evidence is settled first; the updated
  tests supply `DataAccessRulePack` consistently.

### Remaining findings

1. **The docs finding from rounds 1–3 is still open.** Location:
   `docs/intent-model.md:162-167`, `:173-186`, and `:420-423`. No docs file
   changed in `b17d45a..99470d2`; §3 still states the old explicit-purity rule,
   §8 still omits `BOOL_COMBINATORICS`/`DATA_TRANSFORMATION`, and §2 still does
   not record the search/transformation/reduction/mutator overlaps. Fix or
   explicitly defer this to a named ticket; the loop cannot close without one
   of those outcomes.

2. **The `DATA_TRANSFORMATION` receiver check cannot see this rule's own
   emissions.** Location:
   `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/transformation/TransformationRulePack.kt:32,90-91`
   with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/scheduler/RuleEngine.kt:153`.
   The rule reads `SEARCH` but not its own `DATA_TRANSFORMATION`, so the engine
   settles it in one pass and `intentsAt(receiver)` never contains
   `DATA_TRANSFORMATION` produced by an earlier transformation call in the same
   chain. Chained transforms currently establish only through qualifier/type
   evidence, and the test suite does not exercise a chain that relies on
   transformation-intent propagation. Fix: add `DATA_TRANSFORMATION` to `reads`
   so the self-edge is ignored by the scheduler but enables the engine's
   fixpoint, or remove the unreachable branch; add a chain test with qualifiers
   disabled to pin the intended behavior. Also add a scheduler test for the
   induced search→transformation stratum, since the pack now hard-requires
   `DataAccessRulePack`.

Can the loop close: not yet. All round-4 detection behaviors concur, but the
docs omission and the transformation self-evidence/stratum test gap remain
unaddressed; they need fixes or explicit deferrals to named tickets.

## Round 5

Reviewed code commit `3e945b7` (`fix(transformation): read own intent for chained
emissions`) plus the scribe docs changes in the working tree. Verdict: both
round-4 findings are resolved; I concur and have no new detection findings.

- **Reads/self-fixpoint.** `TransformationRulePack.reads` now includes
  `SEARCH` and `DATA_TRANSFORMATION`. The scheduler ignores the self-edge, so
  the pack still lands after the search owner, while `RuleEngine` now fixpoints
  because `owns ∈ reads`. `schedulesAfterSearchAndCanConsumeItsOwnEmissions`
  pins the stratum order (search before transformation) and the read set, and
  `chainedTransformationsEstablishFromPriorTransformationEmissions` supplies an
  oracle only for the inner `values` receiver and disables qualifiers, so the
  outer `.filter` can only establish from the inner map's
  `DATA_TRANSFORMATION` emission. That correctly exercises the self-fixpoint,
  and the established/candidate assertions cover the result. The hard
  requirement on the `SEARCH` owner is now documented in
  `docs/architecture.md` and is satisfied by the bundled set.
- **Docs.** The scribe changes resolve the round-1–4 docs finding:
  `docs/intent-model.md` now states the read-only promotion and confidence
  policy, the rejection-shaped validation rules, the new client defaults, and
  the concrete SEARCH/transformation, bool/transformation,
  transformation/mutation, transformation/validation, and
  transformation/normalization overlaps; `docs/engine.md` and
  `docs/architecture.md` list the new packs and describe their strata and
  evidence rules; `docs/tasks/045-composition-intent-coverage.md` records the
  implementation and keeps the ticket in progress pending this review. The
  descriptions match the code's current behavior, including unbraced/nested
  rejection scoping and null-oracle candidate handling.

No remaining detection-fidelity finding. The code and docs now satisfy the
ticket's detection and documentation acceptance criteria; the loop can close
once these scribe docs are committed with the ticket.
