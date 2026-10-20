---
name: core structural boundaries
status: todo
depends-on: []
reviewers: [correctness, architecture]
---

## Goal

Remove hidden layer violations and package cycles inside core.

## Scope

- Move syntax-profile resource loading out of core (treesitter/frontend owns
  profile resources or profiles are injected).
- Break `core.facts.view` ↔ `core.runtime.engine` cycle.
- Break `core.runtime.scheduler` ↔ `core.runtime.composition` cycle.
- Decide and narrow host policy in core (`HostConfig`/`HostPipeline`/LSP
  policy) or isolate it behind a runtime host boundary.
- Add a structural guard test for forbidden dependencies.

## Acceptance

- No hidden core→treesitter runtime resource dependency.
- No package cycle between the named packages.
- `just build`, `just test-engine`, `just lint` green.
