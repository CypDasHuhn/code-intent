# Development, Testing, and Workflow

## Commands

| command | effect |
|---|---|
| `just build` | assemble engine modules |
| `just test-engine` | deterministic engine tests (includes cross-writer lock test) |
| `just test-nvim` | headless Neovim renderer specs |
| `just test-lsp` | opt-in shared kotlin-lsp oracle test (`CINT_LSP_ROOT`) |
| `just test-cross-writer` | focused JVM+Neovim annotation lock test |
| `just lint` / `just lint-fix` | ktlint check/autocorrect over Kotlin sources |
| `just lsp-fix` | apply first Kotlin LSP action per diagnostic |
| `just size` | file line-count classes (SUSPICIOUS >=300, EXTREME >=400) |
| `just dir-size` | directory crowding report |
| `just install` | build CLI and live-server distributions |
| `just cli` / `just server` / `just live` / `just tags` | run hosts/artifact |

CI runs `just build`, `just test-engine`, `just lint`, and `just test-nvim` with
an explicit testing-grounds path. `CINT_REQUIRE_NVIM=1` makes the cross-writer
test fail instead of skip when Neovim is missing.

## Test topology

Engine:

- `:api:test` — frozen contract, config schema, DSL.
- `:core:test` — scheduler, composition, store, oracle, user annotations,
  cross-writer lock test.
- `:rulepacks:test` — pack-local config and behavior.
- `:suggest:test` — candidate validation/grouping/privacy/providers.
- `:host:test` — host pipeline, config/overlay, loader.
- `:treesitter:test` — parser/syntax profiles, purity.
- `:frontend:test` — language frontends, split handling.
- `:cli:test`, `:server:test` — hosts and live protocol.
- `:rulepacks-tests:test` — cross-pack/rulepack behavior and fixtures.
- `:integration-tests:test` — composition, view, host snapshots, split.
- `:test-fixtures:test` — shared fixture compilation.

Renderer specs are listed in
[renderer-and-protocol.md](renderer-and-protocol.md#tests).

## Size hygiene

- Current `just size`: **no EXTREME files**; largest files are ~386 lines.
- Thresholds: SUSPICIOUS >=300, EXTREME >=400 (committed in
  `scripts/classify-kotlin-size.nu`).
- Accepted OVERCROWDED roots: `analysis-engine`, `core/core`, and the
  `rulepacks-tests` package root; single-file packages are intentionally out of
  scope. Everything else should be split by responsibility.
- Prefer moving code by responsibility (transport/state, config/rules/defaults,
  test scenario/harness/fixtures) rather than arbitrary chunks.

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

Non-automatable acceptance belongs in `docs/manual-test.md` with ticket id and
status. A ticket may be done with an unverified manual entry if the reason is
recorded.

## Tickets and status

- One file per unit: `docs/tasks/NNN-slug.md` with frontmatter
  `name/status/depends-on/reviewers`.
- Status is orchestrator-owned. `docs/tasks/README.md` is the queue table.
- Current queue: 017 blocked; no unblocked work. 023/028 file frontmatter is
  stale (`todo` while README says done).
- Review reports live at `docs/reviews/<id>/<role>.md`.

## Git conventions

- Conventional commits: `type(domain): description`, imperative, lowercase,
  <=72 chars, no trailing period.
- Stage precisely; no `git add -A` for mixed trees; no secrets.
- Do not push/merge/force without request. Merges in this repo use `--no-ff`
  merge commits per ticket branch.
- Parallel work uses worktrees under `~/repos/code-intent-worktrees/`.
- Never commit `.cint/**` runtime state or generated build output.

## Manual gate focus

Unverified live paths remain: editor/daemon restart for user annotations
(`m009`, `m010`), live AI candidate review/decision persistence (`m012`),
real DeepSeek through the live daemon (`m014`), and live oracle wall-clock
latency (`m016`). Cross-writer annotation locking (`m015`) is automated.
