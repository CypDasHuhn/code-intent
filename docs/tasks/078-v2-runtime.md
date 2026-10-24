---
name: V2 runtime and knowledge lifecycle
status: todo
depends-on: [068, 071, 074, 075, 076, 077]
reviewers: [correctness, architecture]
---

## Goal

Orchestrate knowledge initialization, index, languages, pipeline, and publication behind the public analyze API.

## Scope

- Runtime construction and lifecycle.
- Knowledge init/materialization and reload.
- Pack and index lifecycle.
- Pipeline invocation and result publication.
- No direct adapter imports, IPC, or MCP.

## Acceptance

- A file analyzes through runtime with real or fake sources.
- Initialization materializes defaults only when needed.
- Missing oracle and knowledge errors propagate to the caller.

## Out of scope

- CLI, server, and MCP process surfaces.

## Notes

- Runtime is the only analysis composition root.
