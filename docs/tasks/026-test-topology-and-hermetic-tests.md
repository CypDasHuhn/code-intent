---
name: test topology and hermetic tests
status: done
depends-on: [020]
reviewers: [correctness, architecture]
---

## Goal

Place tests by ownership and remove environment-dependent fixtures.

## Scope

- Move core scheduler/composition tests to core (or a core-integration test
  module) using fake syntax nodes where possible.
- Move frontend host/rulepack integration tests to the integration module.
- Share test fixtures/parser dispatch; remove duplicate `TestAnalysis` and
  `CapabilityViewTest`.
- Make testing-grounds lookup hermetic or explicitly skipped.
- Make LSP test opt-in/configurable and non-silent.

## Acceptance

- Test modules reflect ownership; no backwards test-only dependencies.
- No hardcoded home-directory fixture paths.
- `just build`, `just test-engine`, `just lint` green.

## Outcome (2026-10-20)

Core scheduler/composition tests moved to core/integration-tests with fake or
real parser fixtures as appropriate; host/rulepack integration tests moved to
`:integration-tests`; `:test-fixtures` (java-test-fixtures) is the single shared
fixture source; treesitter no longer test-depends on rulepacks, and frontend/
rulepacks no longer test-depend on host. Fixture lookup is configurable and
skips explicitly when absent; the LSP test is opt-in via `CINT_LSP_ROOT` and
fails loudly when configured. A structural guard pins the test-module
boundaries. Reviewers concur after two rounds. `just build`, `just test-engine`,
and `just lint` pass.
