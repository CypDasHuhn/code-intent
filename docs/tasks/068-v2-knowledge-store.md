---
name: V2 knowledge store and materialization
status: todo
depends-on: [065]
reviewers: [correctness, architecture]
---

## Goal

Implement the persisted semantic knowledge layer: function annotations, span overrides, pack defaults, pattern hypotheses, provenance, and mutation.

## Scope

- Selector identity for qualified name, import, receiver, declaring type, signature, and structural pattern.
- Set/remove function annotations and span overrides with provenance, status, confidence, and timestamps.
- Materialize pack defaults only when no store exists; never overwrite project knowledge.
- Versioned in-memory representation first; define the materialized file shape.
- Conflict, duplicate, and stale-selector handling.

## Acceptance

- Users, MCP agents, and fallback AI can express writes through one mutation API.
- Materialization preserves existing edits and records provenance.
- Tests cover override precedence, duplicate selectors, and invalid writes.

## Out of scope

- Analysis derivation, AI prompting, MCP transport, and SQLite.

## Notes

- Function annotations and span overrides are the durable product data.
- The exact file layout remains an open decision; keep it isolated behind the store.
