---
name: V2 non-Kotlin syntax parity
status: todo
depends-on: [064, 065, 066, 072]
reviewers: [detection-fidelity, correctness]
---

## Goal

Close remaining JavaScript, TypeScript, Vue, C#, and C syntax/fact and span gaps.

## Scope

- Non-Kotlin annotation spans, provenance labels, and fact semantics.
- Vue script-region behavior beyond the current split parser.
- Syntax verification must cover parse cleanliness, deterministic syntax
  fingerprints, and cross-target construct coverage (functions, calls,
  returns, guards, properties), not only aggregate fact counts.

## Acceptance

- Non-Kotlin defects in the 084 parity report are resolved; the report assigns
  them to this ticket rather than a 084 sub-label.
- The syntax section of the committed report has no unexplained failures, and
  its stability and construct-coverage checks pass.
