---
name: MCP classification golden path
status: todo
depends-on: [098, 099]
reviewers: [correctness, architecture]
---

## Goal

Prove one complete MCP agent journey on a real fixture repository: initialize,
discover, analyze, classify internal and external symbols, rerun analysis, and
verify that persisted knowledge changes the result across a restart.

## Scope

- Add a fixture repository with:
  - an internal function with a clear intent,
  - a call site in another file,
  - an external library/API call with an import/receiver that can be classified.
- Add an end-to-end MCP test that:
  1. starts `cint-mcp` with root, knowledge, checkpoint, and provider config;
  2. calls `init.begin` and discovers files/symbols;
  3. calls `analysis.run`;
  4. writes a `function_annotations.set` for the internal function and the
     external call using supported selectors (validated with
     `knowledge.resolveSelector`);
  5. reruns `analysis.run` and verifies the intents at the declaration and call
     site;
  6. restarts the MCP process and verifies the knowledge survived, init can
     resume, and no duplicate records were created.
- Fix only the gaps exposed by this scenario. If the scenario requires
  cross-file callee identity or richer external typing, either implement the
  minimal neutral fact needed or record a named follow-up ticket; do not hide
  the gap.
- Write the agent workflow in `docs/mcp-agent-workflow.md`: exact tool order,
  selector guidance, failure handling, and known limits.
- Wire the end-to-end test into `just test-engine`/CI with a deterministic
  scripted semantic provider; document any manual real-LSP check.

## Acceptance

- The scripted end-to-end scenario passes through the real MCP server, runtime,
  knowledge store, and pipeline.
- The internal and external annotations are visible at the expected spans after
  the rerun.
- Knowledge persists across an MCP restart and init resumes without duplicates.
- The workflow doc matches the tested tool sequence.
- `just build`, `just test-engine`, `just lint`, `just graph-guard` pass.

## Out of scope

- Editor annotations, AI suggestion/review UI, and restrictions.
- New languages, MCP transports, or protocol changes.
- Automatic classification without agent decisions.

## Notes

- This is the acceptance gate for the MCP use case; 098 and 099 supply the
  missing discovery and provider pieces.
- Use a deterministic provider/fixture in CI; do not make the test depend on a
  machine-installed language server.
