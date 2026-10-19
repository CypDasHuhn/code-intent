---
name: typed pack config
status: todo
depends-on: [006]
reviewers: [correctness, api-fidelity]
---

## Goal

Replace per-language global config with a typed `Config` owned by each rulepack,
plus a single project config file (architecture.md §8).

## Scope

- Each rulepack declares a typed `Config` with defaults.
- One project config file supplies values keyed by pack id, plus a `core:`
  section for framework-wide policy (purity annotations, LSP toggles).
- Values are validated against the owning pack's schema; unknown packs or keys
  are reported.
- Language-specific detection values are pack config, not duplicated per language
  in one global file.

## Acceptance

- A pack's defaults apply with no config file; values override per pack id;
  unknown pack/key values are reported.
- `core:` policy is read by the framework, not by packs.
- `just test-engine` green.

## Out of scope

- Deleting `intents.yaml` (014).

## Notes

- `intents.yaml` is retired; rule structure never lives in config again (`§8`).
