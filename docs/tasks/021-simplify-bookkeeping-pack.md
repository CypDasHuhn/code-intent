---
name: simplify bookkeeping pack
status: todo
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
