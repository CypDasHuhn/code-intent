---
name: Shared V2 fakes and test harness
status: todo
depends-on: [065]
reviewers: [correctness]
---

## Goal

Provide deterministic fakes for every major port so modules can be built and tested in parallel.

## Scope

- Fake syntax engine, semantic provider, language support, knowledge store, pack source, fallback AI, clock, and deadlines.
- Scriptable inputs and assertions for pipeline and runtime tests.
- Shared fixture loaders for the frozen corpus.

## Acceptance

- Each V2 port has a fake implementation.
- Fakes are deterministic and require no network, LSP, or filesystem state.
- At least one end-to-end fake path exists.

## Out of scope

- Real adapters or production persistence.

## Notes

- Keep fakes in tests; production modules must not ship test doubles.
