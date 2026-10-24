---
name: V2 build skeleton and graph guard
status: todo
depends-on: []
reviewers: [correctness, architecture]
---

## Goal

Create the parallel V2 build under analysis-engine-v2 with the canonical module names, directory grouping, and an enforced dependency graph. No production analysis logic.

## Scope

- Create analysis-engine-v2 as a separate Kotlin Multiplatform/Native build; do not modify analysis-engine.
- Use the six top-level groups: contracts, analysis, sources, orchestration, apps, tests; keep Gradle project names canonical (:core, :packs, :index, :knowledge, :languages, :syntax-treesitter, :lsp, :intent-pipeline, :runtime, :ai, :ipc, :cli, :server, :mcp, :contracts).
- Add empty projects for all V2 modules and shared test modules.
- Add a graph guard that reads the declared project edges and fails on undeclared or reverse edges.
- Add justfile recipes for v2-build, v2-test, v2-cli, and v2-install without changing V1 recipes.
- Add a non-blocking V2 CI job until the skeleton is stable.

## Acceptance

- just v2-build assembles the empty V2 skeleton.
- The graph guard fails for an illegal edge and passes for the declared graph.
- V1 build and tests remain untouched.
- No module is named with a -v2 suffix and no V1 module is a V2 dependency.

## Out of scope

- Any parser, index, knowledge, runtime, IPC, MCP, or AI implementation.
- V1 renames or deletions.

## Notes

- This is the walking-skeleton foundation for every later V2 ticket.
- V1 remains frozen in the working tree and is the behavioral reference.
