---
name: simplify logging pack
status: todo
depends-on: [020]
reviewers: [correctness, detection-fidelity]
---

## Goal

Reduce logging to heuristic predicates over call/type/config facts.

## Scope

- Use enriched call facts: path, receiver, receiver type, trailing lambda.
- Keep LSP fallback/veto and import-gated policy in the pack, backed by `View`
  type access.
- Move language defaults to config data; remove inline id literals and public
  rule exposure.
- Preserve parity over testing-grounds and oracle variants.

## Acceptance

- Logging rule is config + predicates, not call extraction/plumbing.
- No grammar node kinds in the pack.
- `just test-engine`, `just lint` green.
