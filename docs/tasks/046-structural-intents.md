---
name: structural intents: return value, chaining, local copy
status: todo
depends-on: [037, 038, 040, 045]
reviewers: [correctness, detection-fidelity]
---

## Goal

Add three structural intents that describe shape rather than domain effect:

- `RETURN_VALUE` — an explicit return of a computed value.
- `CHAINING` — a fluent/chained call sequence.
- `LOCAL_COPY` — copying a parameter into a field/local.

They are additive to other intents; co-occurrence is expected.

## Scope

- New `structural` rulepack (or focused rules under `intents/`) with one rule
  per intent and typed config.
- `RETURN_VALUE`: return statements with an expression; emit on the return span
  and/or returned expression. Must co-exist with `VALIDATION`, `SEARCH`,
  `DATA_MODIFY`, etc.; never replace them. Bare `return` does not qualify.
- `CHAINING`: fluent call chains with at least two links, e.g.
  `Factory.For().Use().Build()`; the outer chain span is the primary emit.
  Boolean `||` chains are *not* `CHAINING` (use `BOOL_COMBINATORICS`).
  Overlaps with `SERVICE_INIT`, `SERVICE_DELEGATION`, `DATA_TRANSFORMATION` are
  allowed.
- `LOCAL_COPY`: assignment/copy from a parameter to a field or local, e.g.
  `_db = db`, `this.x = x`; not general local mutation and not persistence.
  Co-exists with `LOCAL_VALUE_MODIFY` only if that is explicitly policy; prefer
  suppressing generic local mutation for pure parameter copies.
- Candidate emits for ambiguous chain/copy shapes; deterministic first.
- Tests for each intent, co-occurrence, and false positives.
- Docs: intent definitions, overlap/precedence, and the scratchpad examples.

## Acceptance

- `return _adminUserId.Value;` carries `RETURN_VALUE` (plus any other intents).
- `DataAccessServiceFactory.For…().Use…().Build()` carries `CHAINING` (and
  `SERVICE_INIT` where applicable).
- `_db = db;` with `db` a parameter carries `LOCAL_COPY`; a general field
  mutation does not.
- Boolean OR chains do not emit `CHAINING`.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Whole-program aliasing or dataflow.
- A new role; all three are source intents.
- Replacing `LOCAL_VALUE_MODIFY` or `SERVICE_INIT`.

## Notes

- `RETURN_VALUE` is intentionally redundant with domain intents; sets are the
  right model.
- `CHAINING` is about syntax shape, not responsibility transfer.
- Parent/child intent taxonomy is a separate concept; do not encode `HELPER`
  here.
