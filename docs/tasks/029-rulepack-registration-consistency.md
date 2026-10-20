---
name: rulepack registration consistency
status: done
depends-on: [023]
reviewers: [correctness, architecture]
---

## Goal

Make pack identity/language registration uniform.

## Scope

- Decide one registration model for multi-language packs (service vs
  constructed/per-language).
- Remove host special-casing for error-handling where possible; centralize
  selection in one runtime host factory.
- Align `languages` sets (Vue split behavior) across packs.
- Remove test-only convenience APIs and inconsistent public rules/ids.
- Add a registration/identity contract test.

## Acceptance

- Every pack registers/selects the same way; no duplicate-owner surprises.
- Hosts use one selection path; `just test-engine`, `just lint` green.

## Outcome (2026-10-20)

Bundled packs now use one service-registration model: error handling is a single
multi-language `error-handling` pack with language-keyed config, and all bundled
packs share the same four-language set (Vue is split). Host selection is one
explicit `HostRuntime` path with no constructed-pack special case. Legacy
`error-handling-<language>` config is migrated by a bundled-layer resolver after
discovery, without hijacking third-party ids; invalid sections are isolated and
reported as `INVALID_PACK_CONFIG`. Resolver wiring is explicit in CLI/server;
no global ServiceLoader resolver. Reviewers concur after four rounds.
`just build`, `just test-engine`, and `just lint` pass.
