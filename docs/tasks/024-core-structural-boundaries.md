---
name: core structural boundaries
status: done
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

## Outcome (2026-10-20)

Core no longer owns syntax profile resources: `SyntaxProfiles` and SnakeYAML
moved to `:treesitter`, with core keeping only the profile model. The
`facts.view` ↔ `runtime.engine` and `scheduler` ↔ `composition` cycles are
broken; `Composition` consumes source intents rather than `TerminalNode`, and
`RuleContext` lives in `runtime.context`. Host config/pipeline/logger policy
moved to a new `:host` module; `ProjectConfigLoader` moved there too, removing
the residual config→rulepack→scheduler→config cycle. `RulePackLoader.onFailure`
is required, and host injects the stderr reporter. A structural guard test
checks resource ownership, module dependency sets, forbidden imports, and core
package acyclicity. Reviewers concur; docs still need the `:host` module entry
(027). `just build`, `just test-engine`, and `just lint` pass.
