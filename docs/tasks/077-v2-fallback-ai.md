---
name: V2 fallback AI
status: todo
depends-on: [067, 068, 075]
reviewers: [correctness]
---

## Goal

Provide the secondary in-house AI path for uninitialized repositories and dirty code, using the same knowledge mutation API.

## Scope

- Minimal provider/session/transport behind a port.
- Only run for uninitialized or recently changed inputs selected by policy.
- Write proposals through KnowledgeStore/pipeline; no direct store access.
- Budgets, cancellation, and redaction.

## Acceptance

- Fallback AI can propose a function annotation or override on a dirty file.
- It is never the primary MCP workflow and never part of deterministic propagation.
- Failure leaves existing knowledge and analysis untouched.

## Out of scope

- A new prompt research program, MCP agent behavior, and in-house AI as the main product path.

## Notes

- Reuse V1 discovery machinery only where it fits; do not preserve it for its own sake.
- Add the fallback-AI port and its 067 fake; no such contract port exists yet.
