---
name: simplify bookkeeping pack
status: done
depends-on: [020]
reviewers: [correctness, detection-fidelity]
---

## Goal

Rewrite Bookkeeping as the pilot for the semantic-fact model.

## Scope

- Remove grammar-kind tables, parent climbing, comment scanning, scope
  detection, and manual span/evidence construction from the pack.
- Use only `View` declaration/write/comment facts, config, and emit builders.
- Preserve fixture parity and magic-comment/annotation behavior.
- Target under ~80 lines for the rule.

## Acceptance

- No raw node-kind strings or AST traversal in the pack.
- Parity tests green; `just test-engine`, `just lint` green.

## Notes

Also resolve the write-attribution edge cases deferred from 019: loop binding
names, C#/Kotlin binding-name extraction, Kotlin for-condition resolution, and
object/companion body ownership.

## Outcome (2026-10-20)

Bookkeeping is now a ~74-line semantic-fact pack: property declarations,
annotations, magic-comment blocks, writes, and emit builders replace grammar
kind tables, parent climbing, comment scanning, and manual spans. The deferred
019 write-attribution cases (loop bindings, C#/Kotlin names, Kotlin for
conditions, object/companion ownership, destructuring/defaults/computed keys)
are resolved. Reviewers concur after four rounds. `just build`,
`just test-engine`, and `just lint` pass.
