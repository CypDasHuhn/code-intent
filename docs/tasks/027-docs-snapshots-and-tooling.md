---
name: docs snapshots and tooling
status: done
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

## Outcome (2026-10-20)

Snapshots are now truthful and content-pinned: restored 014-tree pre-018
baselines plus a current post-018 baseline, with explicit provenance and no
“legacy engine” overclaim. Docs were reconciled with the module graph (`api`,
`rulepacks`, `rulepacks-tests`, `host`, `test-fixtures`, `integration-tests`),
loader, socket-only renderer, and lint recipes. Tooling now separates build from
tests, covers host/server/integration tests, fixes live/tags install/quoting,
and ignores `bin/` in size reports. Added CI workflow, `.gitattributes`, and a
docs index. Reviewers concur after two rounds.
