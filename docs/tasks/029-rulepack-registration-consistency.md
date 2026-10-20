---
name: rulepack registration consistency
status: todo
depends-on: [023]
reviewers: [correctness, architecture]
---

## Goal

Make pack identity/language registration uniform.

## Scope

- Decide one registration model for multi-language packs (service vs
  constructed/per-language).
- Remove host special-casing for error-handling where possible; centralize
  selection in one runtime host factory.
- Align `languages` sets (Vue split behavior) across packs.
- Remove test-only convenience APIs and inconsistent public rules/ids.
- Add a registration/identity contract test.

## Acceptance

- Every pack registers/selects the same way; no duplicate-owner surprises.
- Hosts use one selection path; `just test-engine`, `just lint` green.
