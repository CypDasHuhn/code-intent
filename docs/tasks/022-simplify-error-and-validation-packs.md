---
name: simplify error-handling and validation packs
status: done
depends-on: [020]
reviewers: [correctness, detection-fidelity]
---

## Goal

Move error-handling and validation onto the support layer.

## Scope

- Error handling: configured calls/qualifiers, throws, trailing lambdas via
  call/emit helpers.
- Validation: guard/predicate/purity/comment certificate via guard/condition
  facts and emit helpers.
- Remove duplicated traversal and config boilerplate.
- Preserve parity; document any deliberate widening.

## Acceptance

- Packs use only `View` facts/config/emit builders.
- No grammar node kinds or manual scope walking in these packs.
- `just test-engine`, `just lint` green.

## Outcome (2026-10-20)

Error handling and validation now use enriched call/guard/comment facts and
emit builders; manual span/evidence construction and AST descent are gone.
`detectThrows` remains an alias for `throws`, and `commentKinds` is honored via
a semantic `CommentKind` shared by all comment paths. The comment fact shape
keeps legacy constructors via `@JvmOverloads`. Reviewers concur after four
rounds. `just build`, `just test-engine`, and `just lint` pass.
