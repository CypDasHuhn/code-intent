---
name: rulepack skeleton and config support
status: todo
depends-on: [019]
reviewers: [correctness, api-fidelity]
---

## Goal

Provide a declarative pack/rule skeleton and typed config decoding.

## Scope

- `SingleIntentPack`/matcher union helper for one intent per rule.
- Generic schema/defaults-based config decoding with unknown-key reporting.
- Language defaults as data resolved by `View.language()`.
- Shared pack test harness: parser dispatch, fixture access, intent/span
  assertions.
- Migrate `ConfigurableRulePack` and support helpers without changing pack
  behavior.

## Acceptance

- A new pack is mostly config + predicates.
- No per-pack config key-loop boilerplate remains.
- `just build`, `just test-engine`, `just lint` green.
