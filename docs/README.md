# Documentation

Index of the written contract for CodeIntent.

## Current

These documents describe the implemented V2 engine. The V1 corpus remains a
read-only parity reference.

- [architecture.md](architecture.md) — implemented module boundaries, index and
  pipeline flow, knowledge model, runtime, and hosts.
- [engine.md](engine.md) — engine modules, languages, packs, knowledge, runtime,
  hosts, and development commands.
- [intent-model.md](intent-model.md) — intents, sources, derivative use,
  scaffolds, and the intent vocabulary.
- [ai-intents.md](ai-intents.md) — V1 historical: secondary emits, AI candidate
  inference, review/persistence, and protocol.
- [ai-annotation-session.md](ai-annotation-session.md) — V1 historical:
  expression-level annotation sessions, AI restrictions, verdict reuse, and
  review UX (058–061).
- [workflow.md](workflow.md) — roles, ticket/review pipeline, verification
  commands, manual gate, and commit rules.
- [manual-test.md](manual-test.md) — non-automated acceptance checks and their
  status.
- [notes.md](notes.md) — session knowledge dump; earlier sections are historical.
- [rulepack-support-plan.md](rulepack-support-plan.md) — rulepack semantic
  foundation and sequencing plan.
- [data-access-intents.md](data-access-intents.md) — search and lookup pack,
  configuration, classification, registration, and overlap policy.
- [service-intents.md](service-intents.md) — service delegation and init pack,
  configuration, candidates, registration, and overlap policy.

## Rewrite

- [rewrite/module-graph.md](rewrite/module-graph.md) — project layout,
  dependency graph, interaction contracts, and the native-spike exception.
- [rewrite/knowledge-model.md](rewrite/knowledge-model.md) — persisted function
  annotations, span overrides, materialized pack defaults, deterministic
  propagation, and the MCP-first AI workflow.
- [rewrite/native-spike.md](rewrite/native-spike.md) — pointer to the native
  spike findings.
- [rewrite/knowledge-store-format.md](rewrite/knowledge-store-format.md) —
  persisted knowledge snapshot shape.

## Work tracking

- [tasks/README.md](tasks/README.md) — ticket queue, statuses, dependencies, and
  resolved decisions.
- [tasks/](tasks/) — one file per unit of work.
- [reviews/README.md](reviews/README.md) — review report format.
- [reviews/](reviews/) — one directory per ticket, one report per reviewer.

## Summary

- [summary/README.md](summary/README.md) — compact continuation map and domain
  summary; start with [summary/current-state.md](summary/current-state.md).

## Repository

- [../README.md](../README.md) — project overview, components, flow, quick start.
- [ipc/PROTOCOL.md](ipc/PROTOCOL.md) — external renderer wire protocol,
  compatibility matrix, handshake, and published schema.
- [../analysis-engine/ipc/PROTOCOL.md](../analysis-engine/ipc/PROTOCOL.md) —
  engine-side protocol implementation notes.
- `code-intent-nvim` (sibling repository) — Neovim client setup, commands,
  profiles, shards, handlers, and tests.
