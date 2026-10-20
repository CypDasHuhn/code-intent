---
name: docs snapshots and tooling
status: todo
depends-on: [021, 022, 023]
reviewers: [correctness]
---

## Goal

Reconcile the written contract with the implemented system.

## Scope

- Relabel or regenerate the “pre-014” snapshots from a real baseline; fix
  manual-test/ticket claims.
- Reconcile root/engine README, architecture, workflow, notes, intent-model
  with current modules, loader, renderer, and lint recipes.
- Fix `just build` comment, wire server/LSP tests, lint scope, size scripts
  (`bin/`), and live/tags recipes.
- Add CI, `.gitattributes`, and a `docs/README.md` index.

## Acceptance

- No doc references deleted modules/paths; snapshot provenance is truthful.
- `just` recipes match their descriptions and cover hosts/LSP deliberately.
- CI exists and runs the intended suite.
