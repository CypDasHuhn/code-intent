# Current State and Continuation Notes

As of 2026-10-25, after the V1 to V2 cutover (ticket 085). The repository
contains one engine at the repository root.

## Where the project is

CodeIntent is a Kotlin analysis engine plus an external Neovim client.

- The engine parses Kotlin, C, JavaScript, TypeScript, Vue, and C# through
  tree-sitter and requires a semantic provider (normally `kotlin-lsp` for
  Kotlin) for types.
- Detection ships as manifest packs with playbooks and seed defaults. The
  deterministic core runs a stratified scheduler and composes scaffold and
  derivative-use annotations.
- Project knowledge (function annotations, span overrides, pattern hypotheses,
  and materialized defaults) is persisted under `<root>/.cint/` and edited
  through one mutation API used by users, MCP agents, and fallback AI.
- The client speaks NDJSON JSON-RPC to a per-project `cint-server` daemon. The
  current protocol is 5; protocols 3-4 are supported for legacy replay.
- The batch `cint` CLI and `cint-mcp` agent server share `runtime` with the live
  server.

## Recently completed work

| ticket | result |
|---|---|
| 085 | Cut over to V2: removed V1, promoted the V2 tree to `analysis-engine/` (later moved to the repository root by 096), dropped temporary `-v2` executable names, unified just/CI/scripts/docs, and updated the renderer server path. |
| 084 | Frozen corpus, protocol, CLI, server, and MCP parity suite, with committed parity reports and defect baselines. |
| 088-094 | Detection, bookkeeping/validation, composition, candidate, service-init, non-Kotlin syntax, and IPC payload parity. |

The frozen V1 corpus under `frozen-v1-corpus/` remains read-only and is used by
the parity and syntax-provenance tests.

## Current behavior worth knowing

- `SEARCH` means querying a collection/repository where the result may be
  absent; `LOOKUP` retrieves a known-to-exist value from a static/preset source.
  `findById`/`getById`/`loadById` are `SEARCH`; `getByKey` is `LOOKUP`.
- Candidates are review-only. They never affect established annotations or
  composition; AI output is always candidate or proposal shaped.
- Pack defaults materialize once. Deleting a materialized default does not make
  it reappear on the next analysis.
- Function annotations propagate to call sites and chains deterministically;
  AI is not consulted per call site.
- The live server drops stale document versions and publishes monotonic
  analysis revisions. Config, suggest, restrictions, and candidate decisions
  return structured unsupported-operation errors until runtime exposes them.
- The external renderer's `server_cmd` defaults to `cint-server`, matching the
  installed V2 distribution.

## Open work

- **086 (implemented): version the external renderer protocol.** Define the explicit
  protocol version and compatibility contract.
- **087 (implemented): extract the Neovim renderer** into the sibling
  `code-intent.nvim` repository.
- **062 (todo): split host record ownership for legacy records.** The V2
  knowledge model owns this materialized data; the ticket remains in the queue.
- **017 (blocked): fixpoint metadata convergence.**
- Known residuals: native HTTP/TLS is JVM-only; grammar binaries cover x86_64
  Linux; non-Kotlin semantic providers are limited to configured commands or
  editor proxies.

## Handoff to another machine

1. Run `just install` from the repository root.
2. Ensure `cint` and `cint-server` are on `PATH`, or set the renderer's
   `server_cmd` to the installed distribution paths.
3. For Kotlin analysis, make `kotlin-lsp` available or configure
   `--provider-command` / the renderer's server command.
4. Export any fallback-AI key in the environment that starts the daemon.
5. Use `:CintServerRestart` after updating the engine or provider defaults.

## Resume commands

```sh
just build
just test-engine
just lint
# renderer tests: cd ../code-intent.nvim && ./tests/run.sh
just graph-guard
just parity
just smoke
just syntax-provenance
just size
just dir-size
```
