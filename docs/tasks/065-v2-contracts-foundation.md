---
name: V2 contracts foundation
status: todo
depends-on: [063]
reviewers: [correctness, architecture]
---

## Goal

Define the stable V2 vocabulary and ports before implementations diverge.

## Scope

- Core data: intents, spans, facts, candidates, annotations, function annotations, span overrides, pack defaults, pattern hypotheses, provenance, confidence, status.
- Ports: SyntaxEngine, SemanticProvider, LanguageSupport, KnowledgeStore, RulePackSource, IntentPipeline, Runtime, IPC/MCP transports.
- Pure identity, hashing, and selector helpers with no platform IO.
- Deadlines, cancellation, and explicit error/Unavailable shapes.

## Acceptance

- contracts compiles for commonMain and nativeMain.
- Contract tests cover serialization boundaries and invalid inputs.
- No java.*, Path, socket, process, or classloader types leak into contracts.

## Out of scope

- Store, parser, provider, runtime, and app implementations.

## Notes

- Pack defaults are data; do not reintroduce per-pack user config schemas.
- Knowledge writes must be expressible for users, MCP agents, and fallback AI.
