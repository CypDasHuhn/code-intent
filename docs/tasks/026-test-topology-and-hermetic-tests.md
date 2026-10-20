---
name: test topology and hermetic tests
status: todo
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
