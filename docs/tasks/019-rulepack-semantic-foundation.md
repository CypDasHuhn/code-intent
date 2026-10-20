---
name: rulepack semantic foundation
status: done
depends-on: []
reviewers: [correctness, api-fidelity]
---

## Goal

Give rulepacks semantic facts and emit builders so packs stop walking raw ASTs.

## Scope

- Add semantic roles to syntax profiles: declaration wrapper, annotation,
  modifier, comment, property declaration, write, call, guard.
- Add `View` facts: declarations (name, declaration node, annotations,
  preceding comment, scope, writes), writes, comments, enriched calls (path,
  receiver, type, trailing lambda), guard/return sites, scopes.
- Add `Emit` builders/helpers for node spans, declarations, evidence and byte
  offsets.
- Back everything with the existing `SyntaxModel`/CFG; no new parser layer.
- Add API contract tests and profile fixtures.

## Acceptance

- Packs can ask semantic questions without raw grammar kinds.
- No new detection logic in core.
- `just build`, `just test-engine`, `just lint` green.

## Notes

See `docs/rulepack-support-plan.md`. This is the foundation for 020–023.

## Contract note (v2)

`CINT_API_VERSION` is 2. This is a strict compatibility boundary because
`CallFact` gained `path`/`trailingLambda`/offset fields (changing its
constructor shape), and `View` gained `receiverType` plus the semantic fact
members. v1 packs are rejected as incompatible and must be recompiled. v2 adds
semantic `View` facts (declarations, writes, comments, scopes, guards/returns,
receiver types), semantic profile roles, richer call facts, `Emit` builders,
and `ScopeFact`/`GuardKind`/`WriteKind`. The loader compares `apiVersion` for
exact equality.

## Outcome (2026-10-20)

Semantic foundation landed: profile roles for declarations/annotations/
modifiers/comments/writes/guards/class bodies, semantic View facts, emit
builders, semantic call/guard/scope shapes, and per-language fixtures. API is
v2 with a strict compatibility gate and a contract note above. Reviews concur on
the core surface. Four write-attribution edge cases (loop binding names,
C#/Kotlin binding-name extraction, Kotlin for-condition resolution, and
object/companion body ownership) are deferred to 021, where declaration writes
are first consumed by a pack.
