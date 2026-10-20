---
name: config api and output cleanup
status: done
depends-on: [019, 024]
reviewers: [correctness, architecture]
---

## Goal

Remove dead/stale surface and unify output/config paths.

## Scope

- Remove dead config model (`IntentConfig.intents`/`IntentDefinition`) and the
  language shim in `HostConfig`.
- Collapse config loading to one path with clear strict/tolerant semantics.
- Unify artifact and server annotation serialization under one schema/model.
- Decide/remove dead API: `NodeAnchor` production path, `definitionAt`/
  `SymbolFact`, test-only DSL, unused scheduler surface, dead `LineIndex`/
  dirty API.
- Remove stale `PublicEngineSurfaceTest` assertion.

## Acceptance

- One serializer, one config loader, no dead public surface.
- Existing artifact/live contracts preserved (or deliberately versioned).
- `just build`, `just test-engine`, `just lint` green.

## Outcome (2026-10-20)

Config and output paths are unified: `IntentConfig` is language + purity only,
the `HostConfig` language shim and dead flags are gone, the artifact and live
payload share one annotation serializer, and non-finite confidence is quoted as
valid JSON. Dead API (scheduler surface, DSL, `definitionAt`/`SymbolFact`,
`LineIndex`, dirty store, stale surface assertion) was removed; `NodeAnchor`
remains the reserved 030 wire field, unpopulated by the engine and contract
pinned. `detectThrows` remains a compatibility alias. API is v3 with a strict
compatibility gate and a source contract note. Reviewers concur. `just build`,
`just test-engine`, `just lint`, `just test-nvim` pass.
