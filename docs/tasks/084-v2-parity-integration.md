---
name: V2 parity and integration suite
status: done
depends-on: [066, 080, 081, 082, 083, 088, 089, 090, 091, 092, 093, 094]
reviewers: [correctness, detection-fidelity]
---

## Goal

Prove V2 against the frozen V1 corpus and the V2 protocol, CLI, server, and MCP surfaces.

## Scope

- Deterministic corpus parity with documented intentional differences.
- IPC transcript parity and renderer-visible behavior checks.
- CLI, server, and MCP integration tests.
- Performance and failure-path smoke checks.

## Acceptance

- Parity report identifies every intentional difference.
- All V2 integration suites pass on CI.
- No V1 module is required at runtime.

## Out of scope

- Cutover and renderer extraction.

## Notes

- Cutover is blocked by this ticket.
- Owns V1 intent-fact parity for the syntax adapter (072 delivered structural
  parity and deferred fact semantics here) and non-Kotlin cross-target syntax
  parity.
- Owns corpus parity for 069 pack defaults and 070 composition semantics.
- Extract the scripted LSP server into a shared test fixture and use it from
  pipeline/runtime integration tests (073 kept it lsp-test-only).
- Make the V1 syntax provenance check blocking (072 wired it non-blocking).
- Blocked by 088-094: this ticket must stay blocked until those parity
  follow-ups close. Do not treat the registered defect baseline as acceptance.
- Residual harness work is owned by 094: replace whole-payload allowlists for
  initialize/error frames with per-message rules, and extend renderer
  projection coverage beyond tags.annotations/languages to candidates, config,
  lifecycle, suggest, restrictions, and status.
