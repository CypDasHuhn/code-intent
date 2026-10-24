---
name: V2 intent pipeline
status: todo
depends-on: [065, 067, 069, 070]
reviewers: [correctness, architecture]
---

## Goal

Build the pipeline that selects packs, constructs the execution plan, calls the core, and shapes analysis output.

## Scope

- Pack selection and execution plan construction.
- Knowledge inputs for annotations and overrides, initially behind a port.
- Core invocation and candidate collection.
- Fallback AI hook, disabled by default.

## Acceptance

- Pipeline runs end to end with fakes and no parser/LSP/IO.
- Pack selection is deterministic and testable.
- AI is not consulted on the deterministic path.

## Out of scope

- Function propagation (076), fallback AI implementation (077), runtime lifecycle.

## Notes

- Do not duplicate core evaluation rules in the pipeline.
