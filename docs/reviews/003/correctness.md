# Correctness review — ticket 003 (scheduler stratification and fixpoint)

## Round 1

Verdict: The scheduling core is sound: `plan` resolves `reads` after collecting
all packs, produces deterministic named duplicate-owner/unknown-dependency/cycle
errors, Kahn-sorts the rule graph into frozen strata, models the terminal node
without naming rules or packs, and the engine's self-reading fixpoint and
one-run-per-settled-upstream behavior match the acceptance tests. Four
correctness gaps remain around the plugin boundary and the fixpoint guard:
empty-intent emits are accepted, duplicate rule ids silently overwrite each
other, the reserved `UNTAGGED` name can be owned, and the fixed iteration cap can
reject a valid convergent rule.

### Findings

1. **analysis-engine/core/src/main/kotlin/dev/cint/core/scheduler/RuleEngine.kt:103-112 (`require` at :108) — empty-intent emits are accepted and can create an intent-less annotation.**
   `require(emit.intents.all { it == rule.owns })` is vacuously true for an
   empty set. A rule returning `Emit(intents = emptySet(), ...)` is stored as a
   source contribution with no intents; `AnnotationStore.annotations()` only
   requires non-empty provenance, so it produces an `Annotation` with
   `intents = emptySet()`. `AnalysisContext.coverage` on that exact span then
   returns `Coverage(emptySet())` rather than `Coverage(setOf(UNTAGGED))`,
   differing from "no contribution" and letting a plugin effectively hide an
   untagged fact. That contradicts §2's "a rule emits only the intent it owns"
   and the no-removal contract.
   Fix: require the emit set to be exactly the owned intent
   (`emit.intents == setOf(rule.owns)`), which also rejects empty emits; add a
   test that an empty or foreign-intent emit fails.

2. **analysis-engine/core/src/main/kotlin/dev/cint/core/scheduler/RuleScheduler.kt:77-90 and RuleEngine.kt:44-63 — duplicate rule ids are not load errors, and the engine then silently drops one rule's output.**
   Rule ids are contractually globally unique (§3), but `ownersOf` checks only
   intent collisions; `rulesOf` merely sorts. Two packs can register rules with
   the same `id` and different `owns`, and the plan accepts both. The engine keys
   `settled` (`RuleEngine.kt:44`) and each temporary `AnnotationStore` by
   `rule.id`, so in one stratum `settledStratum` inserts two entries with the
   same key and the second overwrites the first; across strata the later rule
   replaces or removes the earlier rule's contributions. The graph treats them
   as distinct nodes while storage treats them as one, so annotations disappear
   without any error.
   Fix: validate all rule ids as globally unique in `plan`/`rulesOf` and throw a
   named load error (or key execution by rule identity and enforce uniqueness at
   load), with a test using two packs that share an id but own different intents.

3. **analysis-engine/core/src/main/kotlin/dev/cint/core/scheduler/RuleScheduler.kt:67-90 and :44-46 — `UNTAGGED` can be owned and read as a normal intent.**
   §2 reserves `UNTAGGED` and says no rule owns it. A rule with
   `owns = UNTAGGED` loads successfully, lands in `TerminalNode.sourceIntents`,
   and can be resolved as the owner of another rule's `reads = {UNTAGGED}`. That
   turns the reserved marker into an ordinary graph currency, lets it enter
   terminal composition as a real source intent, and can confuse the
   `hasRealIntent`/`hasUntagged` distinction downstream.
   Fix: reject `rule.owns == UNTAGGED_INTENT` as a named load error; the
   corresponding `reads` then fails as an unknown dependency. Add scheduler tests
   for an owned `UNTAGGED` and for a read of it.

4. **analysis-engine/core/src/main/kotlin/dev/cint/core/scheduler/RuleEngine.kt:19 and :92-99 — the arbitrary 10,000-iteration cap can reject a valid convergent rule, while non-monotone oscillation is only detected by exhausting it.**
   The stated termination argument is finite-lattice monotone convergence, but
   the implementation neither enforces monotonicity nor derives a bound from the
   finite output universe. A monotone rule that adds one annotation per iteration
   (like `chain` in `RuleEngineTest` without its `coerceAtMost(3)`) over a source
   with more than 10,000 candidate facts will fail with `IllegalStateException`
   even though convergence is guaranteed; conversely an oscillating rule is
   allowed to run 10,000 times before failing. The cap is also a plain `check`
   rather than a named non-convergence error.
   Fix: derive the bound from the possible annotation facts (candidate spans ×
   intents), enforce that a self rule's successive output only grows (or track
   seen normalized states and fail on a repeat), and raise a named
   non-convergence error. Add a test with a small injectable bound or a rule
   whose finite chain exceeds a low configured margin.

### Concurs / Dissents

- Concurs: `rulesOf` sorts every rule before owner resolution, so pack order
  cannot change strata, relation order, which duplicate owner is reported, or
  which unknown dependency is reported; `packOrderDoesNotChangeThePlan` and
  `packOrderDoesNotChangeLoadErrors` pin the split/reversed cases.
- Concurs: Kahn stratification excludes only self edges, so a self-reading rule
  stays in its dependency stratum and `settle` runs its internal fixpoint, while
  non-self rules run once; `settledStratum` is merged only after the whole
  stratum, so same-stratum rules see the frozen earlier output.
- Concurs: duplicate owner, unknown dependency, and cycle each throw a named
  `SchedulerException` subtype, and `findCycle` returns a deterministic cycle
  path with the repeated start node.
- Concurs: `TerminalNode` carries only `sourceIntents`, so it names no rule and
  no pack, and the terminal-union test confirms pack addition is additive;
  executing composition is correctly left to 010.
- Dissents: the four findings above.

## Round 2

Verdict: F1-F3 are addressed cleanly and their new tests exercise the failure
paths. F4 is only partly addressed: oscillation now fails fast with a named
`NonConvergentRule` via repeated-state detection, but the replacement default
bound is still a heuristic based on the number of syntax nodes rather than the
finite annotation universe, and it can reject a valid monotone rule. No other
new correctness defect was found.

### Findings

- **F1: concurs** — `RuleEngine.contributionsOf` now requires
  `emit.intents == setOf(rule.owns)` (`RuleEngine.kt:126-129`), so both empty and
  foreign intent sets are rejected before a contribution reaches the store.
  `emptyIntentEmitFails` and `foreignIntentEmitFails`
  (`treesitter/.../RuleEngineTest.kt:113-161`) pin both cases, and the
  empty-`Coverage`/untagged-hiding path is closed.
- **F2: concurs** — `RuleScheduler.rulesOf` sorts first and rejects adjacent
  duplicate ids with `DuplicateRuleId` before owner resolution
  (`RuleScheduler.kt:95-105`), so the scheduler no longer produces two graph
  nodes that the engine and store collapse under one id.
  `duplicateRuleIdFailsWithNamedError` covers the two-pack case; because the
  check runs on the sorted list, pack order cannot change the result.
- **F3: concurs** — `ownersOf` rejects `owns == UNTAGGED_INTENT` with
  `ReservedIntentOwner` (`RuleScheduler.kt:107-120`), and a `reads` of
  `UNTAGGED` now falls through to `UnknownDependency` when no legal owner
  exists. `reservedUntaggedOwnerFailsWithNamedError` and
  `readingReservedUntaggedFailsAsUnknownDependency` cover both paths, and the
  terminal can no longer receive the reserved name through a legal plan.
- **F4: dissents** — the repeated-state set is the right mechanism for
  oscillation and the named `NonConvergentRule` is an improvement, but the
  default bound at `RuleEngine.kt:103` / `:119` is still
  `tree.root.descendants().count()`. That is not the size of the finite output
  universe: a rule may emit annotations at spans that are not one-to-one with
  syntax nodes (the tests themselves use `indexedEmit` with arbitrary line
  indices, not node spans), and a rule can add a distinct contribution per
  iteration. A valid monotone chain on a small source can therefore require more
  changes than the tree has descendants and will throw
  `NonConvergentRule: iteration bound ... exceeded` despite converging. The new
  `convergentChainBeyondLowInjectedBoundFailsWithNamedError` test only proves
  that an injected low bound fails; it does not pin that the default bound is
  safe. Suggested fix: derive the default from the possible contributions the
  rule can produce (for example, source span positions × the one owned intent,
  including the offset byte range), or remove the arbitrary default and rely on
  repeated-state detection plus an explicitly configured safety bound
  (`maxIterationsPerRule`). Add a test using the existing tiny source where the
  chain length exceeds the tree's descendant count.

### Concurs / Dissents

- Concurs: the new `NonConvergentRule` path reports both repeated states and
  bound exhaustion, and repeated-state detection cannot false-positive a
  deterministic pure rule: if a full annotation state recurs, the next output is
  the same as before, so the rule would cycle rather than converge.
- Concurs: the duplicate-id and reserved-intent checks run after sorting, so
  their named errors remain deterministic under pack-order changes.
- Concurs: the engine still runs non-self rules once and self-reading rules
  against their own intermediate output; the exact own-intent check does not
  alter stratification or view scoping.
- Dissents: F4 remains as described above.

## Round 3

Verdict: The false-positive half of F4 is fixed: the node-count heuristic is gone,
`convergentChainBeyondTreeDescendantCountConverges` proves a valid chain can run
past the tree size, and repeated-state detection still catches exact cycles with
a named error. The remaining half is not fixed: `maxIterationsPerRule` defaults
to `null` and no production caller configures it, so a deterministic rule whose
output keeps changing without repeating now runs forever and `seen` grows without
bound. I dissent on F4 for that remaining default termination gap; the scheduling
and emit-contract changes from round 2 remain correct.

### Findings

- **F4: dissents** — `RuleEngine.kt:103-116` relies solely on repeated exact
  annotation states when no limit is supplied, and the default is
  `maxIterationsPerRule = null` (`:27`). Repeated-state detection catches a
  cycle, but not divergence: a self-reading rule can add one fresh annotation per
  iteration without ever repeating. For example, a rule that returns
  `view.annotations("CHAIN").size + 1` emits with increasing indices, or varies
  evidence at one span, produces a distinct annotation state every time; the
  loop never hits a repeat, `seen` grows, and the engine never terminates. The
  finite intent universe bounds intent names, not spans or evidence, and `Emit`
  spans are not validated against the source. The new
  `convergentChainBeyondTreeDescendantCountConverges` test confirms the old
  false-positive is gone but does not cover this non-repeating case.
  Suggested fix: make a safety limit required (or default it to a sound finite
  domain, e.g. validated source positions × the owned intent), or enforce a
  finite annotation domain for self-reading rules; keep the optional configured
  limit as an additional guard. Add a test with a non-repeating increasing rule
  that a configured limit stops, and document/host-wire the default.
- New findings: none beyond the remaining F4 termination gap.

### Concurs / Dissents

- Concurs: removing the tree-descendant default eliminates the false
  `NonConvergentRule` for a converging chain longer than the tree, and the new
  chain test pins that behavior.
- Concurs: repeated-state detection is sound for deterministic pure rules; if a
  full annotation state recurs, the next run produces the same state, so the
  engine correctly reports a cycle rather than a false convergence.
- Concurs: the optional `maxIterationsPerRule` is validated positive, is checked
  after a change, and reports a named `NonConvergentRule`; the earlier F1-F3
  fixes and their tests are unchanged and still correct.
- Dissents: the default remains unbounded, so termination is still not guaranteed
  for a non-repeating self-reading rule.

## Round 4

Verdict: F4's unbounded-default gap is addressed, but the replacement cap is not a
correct bound on the engine's annotation state space. A valid monotone rule can
therefore be rejected. This is a new finding, not a re-report of F4.

- Concurs: F4's default is now finite. `fixpointLimit` supplies a default when
  `maxIterationsPerRule` is null, the explicit override still takes precedence,
  and the failure uses the named `NonConvergentRule`. The long-chain test also
  confirms the previous node-count cap no longer rejects a chain longer than the
  syntax-tree descendant count.
- Concurs: F1-F3 remain addressed and their tests are unchanged.
- New finding F5: `RuleEngine.kt:121-124` computes
  `N * (N + 1) / 2 + 1`, which is the number of non-empty byte intervals plus
  one. It omits the `N + 1` zero-length intervals `[p, p]`; `Emit` permits
  `startByte == endByte` and `AnnotationStore` groups annotations by byte
  interval without rejecting them. A valid monotone rule that adds one
  zero-length annotation per iteration can need `(N + 1)(N + 2) / 2` changes,
  but the cap is `N * (N + 1) / 2 + 1`. For a one-byte source, the cap is 2 while
  there are three valid byte intervals (`[0,0]`, `[1,1]`, `[0,1]`), so a rule
  adding them one at a time throws `NonConvergentRule` with
  `derived iteration limit 2 exceeded` even though it converges. The same
  undercount occurs because annotations retain contributions with distinct
  evidence, source, confidence, or span at the same byte interval; such a
  contribution changes `AnnotationStore.annotations()` without adding a new byte
  interval, so the interval count cannot bound the number of state changes.
- The non-repeating test does not cover this gap: `indexedEmit` places later
  intervals outside the one-byte source, so it tests growth beyond the source
  rather than growth through the complete valid interval universe. The
  long-chain test likewise uses out-of-range intervals and stops well below the
  derived cap.
- Suggested fix: include zero-length intervals when deriving the interval count,
  e.g. `(N + 1)(N + 2) / 2`, and either make fixpoint state identity depend only
  on byte interval and intent or explicitly bound/validate the other contribution
  fields. Add tests for zero-length spans and for distinct evidence at one span.
- Residual, not a finding: even the corrected interval count is quadratic in the
  source size, so for large files the default guard is finite but impractically
  large; the configured override remains the practical bound.

## Round 5

Verdict: the zero-length part of F5 is fixed, but F5 is not fully resolved.
Dissent on F5 for the remaining same-interval contribution case; no additional
correctness finding beyond that.

- Concurs: `RuleEngine.kt:121-123` now uses the full inclusive interval universe
  `(N + 1)(N + 2) / 2 + 1`, which covers all `[start, end]` with
  `0 <= start <= end <= N`. The new zero-length test adds `[0,0]`, `[1,1]`, and
  `[0,1]` one at a time and converges, so the specific zero-length false
  positive from F5 is addressed.
- Dissents on F5: the cap is still only a bound on byte intervals, not on the
  annotation states used by the fixpoint check. `AnnotationStore` retains
  contributions with distinct evidence, source, confidence, or span even when
  their byte interval is identical, and `annotations()` includes that provenance
  in the state observed by `seen`. A valid monotone rule can therefore add one
  new contribution at an existing interval per iteration without increasing the
  interval count; for a one-byte source the cap is 4, so a rule that adds five
  distinct evidence values at `[0,0]` and then stabilizes is rejected with
  `derived iteration limit 4 exceeded`. The new zero-length test uses the same
  evidence for every span, so it does not exercise this path.
- Suggested fix: either make fixpoint state identity depend only on byte interval
  and intent, or bound/validate the remaining contribution fields. Add a test
  that changes evidence or span at one fixed interval.
- No new finding beyond the unresolved second clause of F5.

## Round 6

Verdict: F5's false-positive case is addressed by making the termination domain
the finite identity set, but the new identity-stable stopping rule is unsound
when a rule reads fields excluded from that identity. This is a new finding.

- Concurs on F5: `AnnotationIdentity` is now `(role,startByte,endByte,intents)`,
  so same-interval evidence changes no longer grow the termination domain or
  trip the cap. The full inclusive interval cap remains as a defensive bound,
  and the configured limit and zero-length tests are still covered.
- Concurs: the shrink check reports `NonMonotoneRule` when the identity set loses
  an element, and the latest output is applied before returning. The derived cap
  and the new evidence-only test behave as intended for identity-only state.
- New finding F6: `RuleEngine.kt:107-123` returns as soon as the identity set is
  unchanged after applying `output`, without running the rule against that
  latest output. `AnnotationIdentity` intentionally omits evidence, source,
  confidence, and span, but a rule's `View` exposes the full `Annotation`
  including those fields. A rule can therefore produce output whose identity is
  stable while changing evidence in a way that affects its next run. Concretely,
  on `"x"` a rule emits `[0,0]` with evidence `"a"`; on the next run it emits
  `[0,0]` with evidence `"b"`; the engine sees the same identity and returns.
  A rule that would add `[1,1]` when it sees evidence `"b"` is never run, so the
  returned annotations are not a fixpoint. The `evidenceOnlyChangesAtStableIdentityConverge`
  test encodes this early-stop behavior rather than checking that evidence-
  triggered growth is reached. The same issue applies to span/source/confidence
  changes, and an evidence-only shrink with stable identity is not rejected as
  non-monotone.
- Suggested fix: make the self-fixpoint view expose only identity-relevant
  fields, or require full normalized output stability (and full monotonicity),
  or otherwise guarantee that a rule's identity output is a function of identity
  state alone. Add a test where a new identity is emitted only after a non-
  identity field changes.
- No other new findings.

## Round 7

Verdict: F6 is addressed. The new full-output stability requirement removes the
identity-stable early return, and the evidence-triggered identity test exercises
the previously missed path. A new cap false-positive remains.

- Concurs on F6: convergence now requires `store.replaceRule(rule.id, output)` to
  return false, which compares the full normalized contribution list, not only
  the identity set. Evidence-only changes therefore keep iterating until the
  output stabilizes, and `evidenceChangeTriggeringNewIdentityIsReached` confirms
  that an identity added only after evidence changes is reached. Identity
  monotonicity and the named `NonMonotoneRule`/`NonConvergentRule` errors remain
  intact.
- New finding F7: `RuleEngine.kt:107-123` counts every normalized output change,
  including evidence/source/confidence/span-only changes, against a limit still
  derived from the identity interval universe. The cap is therefore no longer a
  bound on the convergence domain and can reject a valid rule whose full output
  does stabilize. On a one-byte source the cap is 4; a self-reading rule with a
  single fixed zero-length identity that appends evidence until length 5 and
  then repeats that value makes five changes and is rejected with
  `NonConvergentRule` at iteration 5, although its identity set never grows and
  its full output would be stable on the next run. The new
  `evidenceStabilizesAtStableIdentity` test uses only three evidence changes and
  stays under the cap, so it does not cover the boundary. Identity growth plus
  evidence changes can exhaust the same cap even earlier relative to the number
  of identities.
- Suggested fix: apply the derived interval cap only to identity growth and use
  a separate configured/full-output limit for evidence churn, or otherwise make
  the evidence-stabilization bound independent of the interval-derived cap. Add
  a test whose evidence chain exceeds the derived limit but stabilizes.
- No other new findings.
