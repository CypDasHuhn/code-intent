---
name: scheduler stratification and fixpoint
status: todo
depends-on: [001, 002]
reviewers: [correctness]
---

## Goal

Implement the scheduler: a rule-level dependency graph whose currency is intent
names, topologically sorted into strata, with load-time errors for collisions,
unknown dependencies and cycles, and core composition as the terminal node
(architecture.md §4).

## Scope

- Resolve each `reads` intent name to its owning rule **after all packs
  register**; pack load order is irrelevant.
- Load errors: two rules owning the same intent (`§2`), a `reads` with no owning
  rule (`§4`), a cycle (`§4`, stratified-Datalog discipline).
- Topological strata; each stratum runs **once** against the frozen output of
  earlier strata; a rule may still run an internal monotone fixpoint if it reads
  its own output.
- Terminal node: core composition consumes the union of all source intents,
  names no rule and no pack, so adding or removing a pack can only add tags and
  never change composition.
- Termination invariant: monotone rules + acyclic rule-level dependencies + a
  finite intent universe ⇒ evaluation terminates; no stratum can oscillate and
  no retraction mechanism exists.

## Acceptance

- The worked example stratifies: stratum 0 `{logging, error-handling,
  bookkeeping}`, stratum 1 `{validation}`, terminal composition. (`§4` names
  `print`/`logger-interaction`/`logging-state` as separate nodes; by decision
  they are matchers inside the single `logging` rule.)
- Duplicate-owner, unknown-dependency and cycle each fail the load with a named
  error; pack order does not change the result.
- A self-reading rule converges under the internal fixpoint; a non-self-reading
  rule runs exactly once per settled upstream.
- `just test-engine` green.

## Out of scope

- Composition logic itself (010), pack loading transport (011).

## Notes

- Logging ownership is resolved (README #1): `print`/`logger-interaction`/
  `logging-state` are matchers inside one `logging` rule owning `LOGGING`, so
  stratum 0 holds a single logging node.
