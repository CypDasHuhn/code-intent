---
name: rulepack skeleton and config support
status: done
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

## Contract note (v2)

The support surface is additive at `CINT_API_VERSION = 2`: `Matcher`,
`matcherUnion`, `SingleIntentPack`, `ConfigValues`, `ConfigField`,
`ConfigSchema`, `ConfigDecodeResult`, and `LanguageConfig`. `ConfigSchema` has a
throwing `decode` and a non-throwing `decodeResult`; custom decoders (including
language-keyed schemas) return collected errors. Logging uses
`LanguageConfig.schema` as the first-class language-keyed config path.

## Outcome (2026-10-20)

Pack skeleton, schema-driven config decoding, language-keyed configs, and the
shared pack test harness landed. Logging uses the same schema path as the other
packs; dead config wrapper shims were removed. Reviewers concur after three
rounds. `just build`, `just test-engine`, and `just lint` pass.
