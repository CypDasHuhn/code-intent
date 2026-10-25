---
name: V2 packs, manifests, and seed defaults
status: todo
depends-on: [065]
reviewers: [correctness, architecture]
---

## Goal

Implement manifest-based packs that supply playbooks and seed FunctionAnnotation defaults without owning a user config shape.

## Scope

- Manifest and playbook schema; pack discovery without classpath scanning.
- RulePackSource implementation for bundled packs.
- Seed defaults for logging, data access, and existing intent domains.
- No per-pack LoggingConfig-style user surface.

## Acceptance

- Packs expose defaults as PackDefault/FunctionAnnotation-compatible data.
- A 068 integration test can materialize those defaults without pack-specific config.
- Packs contain no classloader, ServiceLoader, or user-facing config schema.
- Unknown packs degrade with explicit errors.

## Out of scope

- Rule evaluation, scheduler, and knowledge persistence.

## Notes

- Packs suggest; the project materializes.
- Use the frozen V1 pack behavior as the parity source.
- Deliberate contract change owned by 069: remove the untyped `RuleSpec.config` map from the 065 port. It was a per-pack user-configuration surface; 075 will replace it with typed plan/binding data.
