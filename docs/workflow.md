# Workflow

How work is planned, executed, reviewed and committed for the rule/engine
restructure described in [architecture.md](architecture.md).

## Roles

The orchestrator is the primary session. All other roles run as depth-1
subagents; subagents never spawn subagents.

| Role | Runs as | Job | Writes |
|---|---|---|---|
| orchestrator | primary session | Owns the ticket queue, statuses, sequencing; drafts tickets; spawns workers; commits. | anything |
| implementor | `general` subagent | The only role that edits source. Implements one ticket, owns build/tests/format. | source + tests |
| reviewer | `general` subagent | Reads and reasons over one ticket's diff. Correctness, contracts, structure. | its report file only |
| scribe | `general` subagent | Maintains `docs/`. | `docs/` only |
| recon | `explore` subagent | Read-only reconnaissance. | nothing |

No named agents are configured in this environment, so the roles are enforced by
the orchestrator's prompts: an implementor prompt grants source edits and build
commands, a reviewer prompt forbids them. Never let one subagent play two roles
on the same ticket in the same round.

## Pipeline

Per ticket (`docs/tasks/NNN-slug.md`):

1. The implementor implements the ticket and leaves the tree green, reporting the
   exact commands and their results.
2. The reviewer reviews the diff: logic, state, data and contracts only. It
   writes `docs/reviews/<id>/<role>.md` and returns a one-paragraph summary.
3. The implementor addresses every finding: fix it, or defer it to a named
   ticket with a reason. Findings carry no severity labels; a finding is work.
4. Round 2 runs only if round 1 produced findings: resume the same sessions
   (`task_id`) and seed them with round-1 state. A reviewer never re-reports an
   addressed finding; it concurs or dissents. Zero findings closes the ticket.
5. Commit. Any acceptance criterion that cannot be automated goes to
   [manual-test.md](manual-test.md), naming the ticket.

Extra review dimensions join a ticket's `reviewers` list only when its surface
demands it — never all by default. The dimensions in use:

- `correctness` — logic, state, data, contracts, error paths. The default.
- `api-fidelity` — the frozen `api` surface and the annotation/artifact contract
  consumed by the renderer.
- `detection-fidelity` — rule behavior parity against the current engine and the
  golden fixtures.
- `architecture` — module boundaries, the core/rulepack line, and that no
  detection logic leaks back into `core`.

## Verification ownership

- The implementor is the only role that runs the build, tests and formatter.
  Reviewers assume a green handover; a red tree is a process problem, not a
  finding.
- Reviewers read and reason only. `git diff`/`git log`/`git status` are allowed;
  build-class commands are not.
- A suspected failure is a finding with a reproduction path; the implementor
  reproduces it.

Engine commands here are `just build`, `just test-engine`, `just test-lsp`,
`just test-nvim`, `just lint`, and `just install`. `just build` assembles the
engine without running tests; `test-engine` covers the deterministic suites
(including `host`, `server`, `rulepacks-tests`, `test-fixtures`, and
`integration-tests`). `test-lsp` is opt-in via `CINT_LSP_ROOT` and a running
`kotlin-lsp`.

Lint scope is Kotlin only: `just lint`/`just lint-fix` run ktlint over
`analysis-engine/**/src/**/*.kt`. The Lua renderer has no formatter gate; its
behavior is covered by `just test-nvim`. CI runs `just build`, `just
test-engine`, `just lint`, and `just test-nvim` with an explicit
`CINT_TESTING_GROUNDS`.

## Ticket format

`docs/tasks/NNN-slug.md` with YAML frontmatter:

```yaml
---
name: Human readable title
status: todo | in-progress | review | done | blocked
depends-on: [NNN]
reviewers: [correctness]
---
```

Body: goal, scope, acceptance criteria, out of scope, notes.
`depends-on` lists ticket ids that must be `done` first. Status is owned by the
orchestrator.

## Review reports

`docs/reviews/<ticket-id>/<role>.md`, one file per reviewer per ticket. Round
body: verdict, findings (location, problem, suggested fix), concurs/dissents. The
reply carries a one-paragraph summary so the implementor does not re-read files.

## Manual gate

Acceptance criteria that cannot be automated (live daemon cutover, renderer
parity under the live socket, golden-fixture spot checks) go in
[manual-test.md](manual-test.md) with status and, when unverified, a reason. A
ticket may be `done` while an entry is unverified, but the entry must exist and
name the ticket.

## Commits

- Conventional commits: `type(domain): description`, imperative, lowercase,
  ≤72 chars, no trailing period.
- One commit per unit of completed work; stage precisely, never `git add -A`.
- No authorship or tool trailers. Never push, merge or force-push unless asked.

## Definition of done

- Acceptance criteria met; build green.
- Reviews complete; every finding fixed or explicitly deferred to a named ticket
  with a reason.
- Non-automatable criteria recorded in [manual-test.md](manual-test.md).
- Ticket `status: done` and committed.

## Reference docs

[architecture.md](architecture.md) describes the implemented module boundaries
and pipeline. [intent-model.md](intent-model.md) describes the intent model and
[notes.md](notes.md) §20 is the current post-restructure state; earlier notes
sections are historical. [manual-test.md](manual-test.md) records non-automated
acceptance checks, and [README.md](README.md) is the documentation index.
