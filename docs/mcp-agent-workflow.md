# MCP agent workflow

This is the golden path for an external agent that classifies a repository
through `cint-mcp`. It is the sequence exercised by `McpGoldenPathTest` against
a real MCP JSON-RPC session, runtime, index, knowledge store, and intent
pipeline. The test uses a deterministic scripted semantic provider; the
workflow itself is the same when a managed or attached provider is configured.

## Tool order

1. Start `cint-mcp` with a root, an explicit knowledge path, a checkpoint path,
   and provider configuration. Use the same paths when restarting.

   ```sh
   cint-mcp --root /path/to/project \
     --knowledge /path/to/project/.cint/knowledge.json \
     --checkpoint /path/to/project/.cint/init-checkpoint.json \
     --provider-config /path/to/providers.json
   ```

2. Run the MCP `initialize` request and send `notifications/initialized`.
3. Call `init.begin`. The response reports `resumed`; on a fresh repository it
   is `false` and the checkpoint is `IN_PROGRESS`.
4. Call `repo.files` to get deterministic analyzable paths. Use the returned
   `absolutePath` values as `targetPaths` for later calls.
5. Call `analysis.symbols` with the target paths. Read declarations, calls, and
   imports, including spans and any receiver/declaring types the semantic
   provider supplied.
6. Call `analysis.run` with the same target paths for a baseline result.
7. For each intended annotation, call `knowledge.resolveSelector` with the
   selector and the target paths. Confirm `matched` contains the expected
   `FUNCTION` or `CALL` targets and that `unsupported` is empty.
8. Write annotations with `function_annotations.set`. Use the selectors that
   were dry-run in the previous step.
9. Call `analysis.run` again with `forceFullAnalysis: true`. The checkpoint
   skips unchanged paths by content hash; forcing makes the new knowledge
   visible in the same session.
10. Verify the intents at the declaration span and at every call span. For a
    restart, close the session, start a new `cint-mcp` against the same root,
    knowledge path, and checkpoint path, then call `init.begin` again. It
    reports `resumed: true` with the completed analysis steps. Read
    `knowledge.snapshot` or `function_annotations.list` to confirm the records
    survived without duplication, then rerun analysis as needed.

`knowledge.materializeDefaults`, `knowledge.suggestDefaults`, `init.status`, and
`init.complete` are optional lifecycle tools. Materialize defaults before
`init.complete`; the golden path leaves the checkpoint in progress so agent
writes are not treated as a completed init pass.

## Selector guidance

- Prefer the narrowest selector that still covers the declaration and its call
  sites.
- Internal function: use `qualifiedName`, optionally with `declaringType`,
  `signature`, or `receiverType`. `declaringType`/`receiverType` only match when
  the semantic provider supplied a receiver type fact for the call.
- Imported external function or extension: use `qualifiedName` plus
  `importPath` (for example `renderLabel` from
  `app.TextTools.renderLabel`). This works without receiver typing.
- Cross-file call: an annotation is matched directly against the call target by
  name, import, signature, receiver, or declaring type. Cross-file calls do not
  need the declaring file to be analyzed in the same request.
- Structural patterns (`token-prefix:`, `@annotation`, `cint:marker`,
  `call-chain`) are available where the selector matcher supports them. Dry-run
  them first.
- Use `span_overrides.set` only when a function contract cannot express a local
  exception for one span.

## Failure handling

- Tool failures are returned inside `tools/call` with structured error codes.
  Read `structuredContent.error.code` and `message`; do not assume an empty
  result.
- `analysis.run` fails with `PreconditionFailed` when a language provider is
  missing. Configure `--provider-config`, `--provider-command`, or the matching
  endpoint and retry. `repo.languages` shows resolved modes and missing fields
  without starting an LSP.
- `knowledge.resolveSelector` with no matches means the selector is too narrow or
  names the wrong symbol. Inspect `analysis.symbols` again.
- Unsupported `receiverType`/`declaringType` means the provider did not supply a
  receiver type at that call. Use an import, qualified name, or signature
  selector, or configure a provider that supplies the fact.
- A repeated `analysis.run` after a knowledge write can return the paths in
  `skippedPaths` with a null result because the init checkpoint resumes by
  content hash. Pass `forceFullAnalysis: true` when knowledge changed.
- After a restart, keep the workflow identity stable (the app default, or an
  explicit `workflowId`) so `init.begin` resumes the same checkpoint.

## Known limits

- Cross-file propagation is selector-based. There is no full cross-file callee
  identity or global call graph; semantic callee identity remains the follow-up
  tracked by the runtime/provider work in ticket 078.
- Receiver/declaring typing depends on semantic facts at receiver offsets. The
  default MCP index plan requests a type at offset 0 only, so production
  selector resolution should prefer name, import, or signature selectors unless
  the host supplies richer semantic requests. When receiver facts are present,
  `analysis.symbols` now reports them.
- The golden-path CI test is deterministic and does not require `kotlin-lsp`.
  For a manual real-LSP check, start `cint-mcp` against a Kotlin project with a
  working managed or attached provider and repeat the same tool order; exact
  receiver/declaring matches then depend on the provider.
- MCP does not add editor annotations, suggestion/review UI, additional
  transports, or automatic classification without an agent decision.
