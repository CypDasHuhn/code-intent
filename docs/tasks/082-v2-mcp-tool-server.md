---
name: V2 MCP tool server
status: done
depends-on: [068, 078]
reviewers: [correctness, architecture]
---

## Goal

Expose V2 analysis and knowledge as MCP tools and resources for external agents.

## Scope

- MCP stdio server with tool/resource discovery.
- Tools: analysis.run, knowledge.snapshot, function annotations, span overrides, patterns, change proposal/apply.
- All mutations go through runtime/knowledge validation.
- Tests with a scripted MCP client.

## Acceptance

- An MCP client can list tools, analyze, read knowledge, and write a function annotation.
- MCP never imports core internals or adapters.
- Invalid writes return structured errors.

## Out of scope

- The repo-wide init workflow (083) and remote MCP transport.

## Notes

- MCP is the primary AI surface; in-house AI remains fallback.
