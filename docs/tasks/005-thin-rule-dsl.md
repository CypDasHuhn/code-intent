---
name: thin rule dsl
status: done
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

## Outcome (2026-10-20)

Thin DSL lives in `:api` (`Dsl.kt`): infix `eq`/`ne`/`gt`/`lt`/`and`/`or` on an
invariant `DslValue<T>` projection so least-upper-bound widening cannot occur,
plus a `Predicate` lambda-over-`View` layer with infix composition. Concrete
`Condition` leaves are internal, keeping the IR replaceable. `where` and the
`predicate(...)` factory were removed as unrequested frozen-API surface.
Representative rule and negative `ne`/null cases covered. Both reviewers concur
in round 2. `just build` and `just test-engine` green.
