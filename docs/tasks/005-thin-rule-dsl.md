---
name: thin rule dsl
status: todo
depends-on: [001, 004]
reviewers: [correctness, api-fidelity]
---

## Goal

Provide the thin Kotlin-embedded DSL over `View` that rules use
(architecture.md §7): ordinary Kotlin, lambda predicates allowed,
comparisons/logic as infix functions.

## Scope

- Infix `eq`, `ne`, `gt`, `lt`, `and`, `or` returning DSL values. Kotlin pins
  `==` to `equals(Any?): Boolean` and does not allow overloading `&&`/`||`, so
  the named forms keep rule logic type-safe.
- Predicate lambdas over `View` facts; no reification requirement, no CEL, no
  Datalog engine.
- Keep the IR deliberately thin so it can be replaced later without rewriting
  rule semantics; no separate `dsl` module split now.

## Acceptance

- A representative rule can be written using only the DSL and `View`, with infix
  comparisons and lambda predicates.
- Rule logic does not fall back to `==`/`&&`/`||` as the DSL path.
- No construct blocks a future reification into data-shaped predicate leaves
  (reification itself is deferred, `§10`).
- `just test-engine` green.

## Out of scope

- IR reification / CEL predicates (`§10`).

## Notes

- `§9` allows folding `dsl` into `api` initially; confirm the placement chosen
  in 001.
