---
name: typed pack config
status: done
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

## Outcome (2026-10-20)

Typed pack config is wired through `ConfigurableRulePack` and
`ProjectConfig`/`ProjectConfigLoader`: per-pack sections keyed by pack id plus a
framework-owned `core:` policy section. Defaults apply without a file, partial
language overrides replace only supplied fields, and unknown/unverified pack keys
and invalid values are reported. The API addition is additive/opt-in at
`CINT_API_VERSION = 1` and pinned by a contract test. Logging is fully migrated;
error-handling, bookkeeping and validation values move in 013. Both reviewers
concur.
