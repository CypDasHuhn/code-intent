---
name: config api and output cleanup
status: todo
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
