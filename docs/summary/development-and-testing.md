# Development, Testing, and Workflow

## Commands

| command | effect |
|---|---|
| `just build` | assemble engine modules |
| `just test-engine` | all deterministic engine tests plus the graph guard |
| `just test-lsp` | focused LSP provider tests |
| `just lint` / `just lint-fix` | ktlint check/autocorrect over Kotlin sources |
| `just graph-guard` | module graph guard |
| `just smoke` | smoke-test built `cint`, `cint-server`, and `cint-mcp` |
| `just install` | build CLI, live-server, and MCP distributions |
| `just cli` / `just server` / `just live` / `just tags` | run hosts/artifact |

CI runs build, engine tests, lint, graph guard, install, and smoke with an
explicit testing-grounds path. Renderer tests run in the external
`code-intent.nvim` repository against the installed engine.

## Test topology

Engine:

- `:contracts` — data model, serialization, ports, graph contracts.
- `:core` — scheduler, strata, fixpoint, composition, annotation store.
- `:default-rulepacks` — language-neutral manifests, playbooks, rule data, defaults, and catalog validation.
- `:index` — hashing, incremental cache, invalidation.
- `:knowledge`, `:knowledge-persistence` — store semantics and JSON/checkpoint
  adapters.
- `:languages`, `:syntax-treesitter`, `:lsp` — descriptor binding, grammar
  parsing, and semantic providers.
- `:intent-pipeline` — pack selection, knowledge resolution, propagation.
- `:runtime` — orchestration and lifecycle.
- `:ai`, `:ipc` — fallback proposals and protocol DTO/framing.
- `:cli`, `:server`, `:mcp` — app behavior.
- `:test-fixtures`, `:integration-tests` — shared fakes plus pack and
  end-to-end integration tests.

Renderer specs and cross-repo compatibility checks are listed in
[renderer-and-protocol.md](renderer-and-protocol.md#renderer-tests).

## Size hygiene

- Thresholds: SUSPICIOUS >=300, EXTREME >=400 (committed in
  `scripts/classify-kotlin-size.nu`).
- Prefer moving code by responsibility (transport/state, config/defaults,
  test scenario/harness/fixtures) rather than arbitrary chunks.
- `just size` and `just dir-size` report file and directory crowding.

## Ticket and review workflow

Roles from `docs/workflow.md`:

- **Orchestrator** — owns queue/status, spawns workers, merges/commits.
- **Implementor** — only role that edits source; runs build/tests/format.
- **Reviewer** — reads/reasons only; writes one report per dimension.
- **Scribe** — docs only.
- **Recon** — read-only exploration.

Pipeline per ticket: implement and leave green -> review -> fix every finding or
defer to a named ticket -> commit. Rounds are resumed sessions; reviewers do not
re-report addressed findings. Review dimensions: `correctness` (default),
`api-fidelity`, `detection-fidelity`, `architecture`.

## Tickets and status

- One file per unit: `docs/tasks/NNN-slug.md` with frontmatter
  `name/status/depends-on/reviewers`.
- Status is orchestrator-owned. `docs/tasks/README.md` is the queue table.
- Review reports live at `docs/reviews/<id>/<role>.md`.

## Git conventions

- Conventional commits: `type(domain): description`, imperative, lowercase,
  <=72 chars, no trailing period.
- Stage precisely; no `git add -A` for mixed trees; no secrets.
- Do not push/merge/force without request. Merges use `--no-ff` merge commits per
  ticket branch.
- Parallel work uses worktrees under `~/repos/code-intent-worktrees/`.
- Never commit `.cint/**` runtime state or generated build output.
