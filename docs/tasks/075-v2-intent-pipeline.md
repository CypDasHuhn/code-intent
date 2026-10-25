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
- Owns the `RuleEvaluatorRegistry`: select evaluator implementations, bind 069
  playbooks into 070 `RulePlan`s, and fail on unbound or mismatched rules.
- Owns knowledge precedence/lookup composition over `KnowledgeSnapshot` (068
  intentionally left the pure lookup here).
- Use the 067 fakes; do not add fallback-AI behavior beyond an off-by-default
  hook (077 owns the implementation).
