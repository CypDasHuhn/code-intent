---
name: V2 MCP repo init workflow
status: done
depends-on: [082]
reviewers: [correctness, architecture]
---

## Goal

Support an external agent-driven init pass over an entire repository using MCP tools and persistent checkpoints.

## Scope

- Materialize defaults when no knowledge exists.
- Expose analysis and knowledge snapshots for investigation.
- Pattern hypothesis proposal and conversion to function annotations.
- Checkpointing, resumability, and idempotent writes.

## Acceptance

- A scripted agent initializes a sample repo across multiple calls.
- Interrupted init resumes without duplicating knowledge.
- The workflow does not run an in-house AI harness.

## Out of scope

- The agent’s reasoning, prompt, or model choice.

## Notes

- This is the intended AI workflow; 077 is only fallback.
