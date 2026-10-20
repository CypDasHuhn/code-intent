---
name: rulepack semantic foundation
status: todo
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
