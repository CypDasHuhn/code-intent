---
name: V2 function contracts and call-site propagation
status: todo
depends-on: [068, 070, 075]
reviewers: [correctness, architecture]
---

## Goal

Resolve function annotations and span overrides during analysis and propagate symbol intent to call sites and chains deterministically.

## Scope

- Resolve FunctionAnnotation by selector and scope.
- Apply SpanOverride with higher precedence.
- Propagate declaration intent to calls and composed chains.
- Compose structural modifiers such as CHAINING and DATA_TRANSFORMATION.
- Expose evidence, provenance, and confidence to result shaping.

## Acceptance

- A SEARCH function makes its call chain SEARCH plus structural modifiers.
- An override wins over the function contract for that span.
- No AI is called during propagation.

## Out of scope

- Knowledge storage (068), MCP writes, AI inference.

## Notes

- This is the main underclassification fix; prioritize corpus coverage over clever rules.
