---
name: MCP repo discovery and selector validation
status: todo
depends-on: [097]
reviewers: [correctness, architecture]
---

## Goal

Give an MCP agent a first-class way to discover analyzable files and symbols,
and to validate a knowledge selector before writing it.

## Scope

- Add a read-only `repo.files` (or `analysis.sources`) tool that lists
  analyzable paths under the repository root with language/extension filters,
  deterministic ordering, and documented ignore behavior.
- Add an `analysis.symbols` (or `repo.symbols`) tool that returns a compact
  inventory for target paths: declarations, calls, imports, receiver/declaring
  types, spans, and language. It must not require the agent to parse a full
  `AnalysisResult`.
- Add `knowledge.resolveSelector`: input a function/span selector plus target
  paths or root; return matched targets (path, span, names, kind) and explicit
  unsupported reasons. It must be read-only and never mutate knowledge.
- Add tests over a small fixture repo for file listing, symbol inventory,
  exact selector matches, and unsupported `receiverType`/`declaringType`
  reasons.
- Update the MCP tool documentation and the intended agent workflow.
- Keep the tools bounded and deterministic; no unbounded repo scans without
  filters.

## Acceptance

- An MCP client can list analyzable files, inspect symbols for a path, and
  dry-run a selector before writing it.
- Unsupported selectors return the same actionable reason that analysis would
  report, without requiring a write.
- Tests cover matches, no-matches, and unsupported cases.
- `just build`, `just test-engine`, `just lint`, `just graph-guard` pass.

## Out of scope

- New derivation semantics, cross-file callee identity, provider/LSP
  configuration, or editor UI.
- Automatic classification without an agent decision.

## Notes

- This is the discovery/validation half of the MCP golden path.
- Keep the tool output compact; the full analysis facts remain available via
  `analysis.run`.
