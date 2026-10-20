---
name: size hygiene after support refactors
status: done
depends-on: [019, 020, 021, 026, 029]
reviewers: [correctness]
---

## Goal

Bring the post-refactor tree back under the file/directory size thresholds.

## Scope

- Split new EXTREME files:
  - `treesitter/.../view/SemanticFactsLanguageTest.kt`
  - `core/.../syntax/DeclarationFacts.kt`
  - `rulepacks-tests/.../bookkeeping/BookkeepingRulePackTest.kt`
- Reduce newly overcrowded dirs:
  - `core/src/test/.../runtime/engine`
  - `api/src/main/.../api`
  - `integration-tests/.../integration/composition`
- Preserve behavior and coverage; no raw grammar kinds in rulepacks.

## Acceptance

- `just size` reports no EXTREME files.
- `just dir-size analysis-engine` reports no OVERCROWDED dirs.
- `just build`, `just test-engine`, `just lint`, `just test-nvim` pass.

## Outcome (2026-10-20)

Split the new oversized files (`SemanticFactsLanguageTest`, `DeclarationFacts`,
`BookkeepingRulePackTest`) and reduced the new overcrowded directories
(core engine tests, API package, integration composition, core syntax).
`just size` reports no EXTREME files and `just dir-size analysis-engine` reports
no OVERCROWDED directories; only cohesive SINGLE entries remain. `just build`,
`just test-engine`, and `just lint` pass.
