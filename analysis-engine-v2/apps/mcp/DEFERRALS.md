# MCP deferrals

## Ticket 083: durable change proposals

`changes.propose` stores staged `KnowledgeChange.Batch` values in the MCP server
session and `changes.apply` submits them through `KnowledgeStore.proposeChange`.
Proposals are intentionally not durable yet: checkpointing, resumability, and
idempotent proposal ids belong to the repo-wide init workflow in ticket 083.
Proposal state is concurrency-safe within one server session, and failed applies
remain available for retry.
