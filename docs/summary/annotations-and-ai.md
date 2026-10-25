# Knowledge, Candidates, and AI

## Persisted knowledge

Project knowledge is versioned data owned by the `knowledge` module and stored
at `<root>/.cint/knowledge.json` by default. It contains:

- **Function annotations** — selector, intents, scope (`GLOBAL`, `FILE`, or
  `PROJECT`), provenance, confidence, status, timestamps, optional path,
  pattern id, and evidence.
- **Span overrides** — intent changes for a specific span.
- **Pattern hypotheses** — proposed intent sets with evidence, examples,
  counterexamples, status, and provenance.
- **Pack origins** — which pack/version materialized defaults, plus a
  materialized flag.

`KnowledgeChange` is the mutation surface: set/remove function annotations,
span overrides, and pattern hypotheses; materialize defaults; or apply a batch.
Users, MCP agents, and fallback AI all use this API. The store validates every
change, so there is no second AI-only store.

Pack defaults are seed data. They materialize once; later analyses consume the
stored annotation rather than re-applying the default.

## Function propagation

Function annotations resolve to call sites and call chains deterministically.
The selector supports qualified name plus optional import path, receiver type,
declaring type, signature, and structural pattern. Propagation does not call AI;
it reuses the persisted contract.

## Candidates

Rulepacks may emit review-only candidates where they are uncertain. Candidates
carry intents, source, confidence, span, reason, and optional provider/model
metadata. They never enter composition, the knowledge store, or the `reads`
graph. A user or agent decision promotes knowledge through the normal mutation
API.

## Fallback AI

Fallback AI exists for uninitialized repositories, recently changed code, CI,
tests, and offline environments. It returns proposals, and the pipeline applies
accepted results through the knowledge mutation API. It is off the deterministic
derivation path and is not the primary AI workflow.

## MCP

The primary AI workflow is an external MCP agent. `cint-mcp` exposes:

- `analysis.run`, `repo.status`, `repo.index`, `repo.languages`
- `knowledge.snapshot`, `knowledge.suggestDefaults`,
  `knowledge.materializeDefaults`
- function annotation, span override, and pattern tools

Configure non-Kotlin providers with `--provider-config PATH` or repeatable
`--provider-command LANGUAGE=COMMAND` flags; `--provider-mode` and
`--provider-endpoint` cover attached and editor modes. Kotlin keeps its managed
default. `repo.languages` lists resolved modes and configuration status without
starting an LSP, and `analysis.run` fails explicitly when a provider is missing.

MCP handlers call `runtime`; tool DTOs live in `contracts`. MCP is not the
renderer IPC protocol.
