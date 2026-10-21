---
name: structural intents: return value, chaining, local copy
status: done
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

## Implementation notes (2026-10-21)

- `return-value:return-value`, `chaining:chaining`, and `local-copy:local-copy`
  are registered in bundled discovery and included in the host registration,
  loading, migration, test-pack lists, and the pipeline snapshot. Each owns
  exactly one intent and reads no other intent; mutation local-value-modify
  reads `LOCAL_COPY`.
- `RETURN_VALUE` emits on the returned expression, excludes bare and bare
  labeled returns, and excludes literal-only returns unless `includeLiterals`
  (alias `allowLiterals`) is enabled. Labeled returns with a value emit the
  value expression from the real CFG/`exitExpression` path.
- `CHAINING` requires exact receiver links and at least `minimumLinks` (default
  2), emits once on the outer call, excludes direct `||` operands and
  parenthesized OR receivers, and emits a low-confidence candidate for
  conditional/ternary bases. Index/argument/binary bases contribute only real
  call links.
- `LOCAL_COPY` covers assignments plus declaration and class/constructor
  property initializers. Established copies use field-like LHS, non-field RHS,
  and case-sensitive prefix/underscore normalization; declared properties are
  excluded as sources and field/self copies do not establish. Parameter names
  are parsed from the enclosing function as heuristic evidence, with candidate
  fallback for field/self, ambiguous owner-qualified, and differently named
  local shapes.
- Mutation suppresses generic local mutation at established copies by default
  (`ignorePureCopies: true`); setting it false restores co-occurrence. The
  suppression reads the actual `LOCAL_COPY` annotation, so configured prefixes
  are honored.
- Docs updated: `docs/intent-model.md`, `docs/engine.md`,
  `docs/architecture.md`. The nvim README does not enumerate the default tag set.
- Status remains `in-progress` until the review loop closes.


## Outcome (2026-10-21)

Added `RETURN_VALUE`, `CHAINING`, and `LOCAL_COPY` as additive structural
source intents. RETURN_VALUE covers value-bearing returns (including labeled
returns) and coexists with domain intents. CHAINING requires exact receiver-call
links, rejects boolean OR chains and fabricated index/argument links, and
candidates conditional receivers. LOCAL_COPY uses field-like LHS + non-field
identifier RHS with exact normalized matching, supports class/constructor
declaration copies, and is suppressed against `LOCAL_VALUE_MODIFY` by the
mutation pack's default-on `ignorePureCopies` policy. All packs are registered,
snapshot/default-tag integrated, and reviewed to closure; build, test-engine,
lint, and test-nvim pass.
