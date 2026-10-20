# Correctness review — ticket 033 (renderer package layout)

## Round 1

Verdict: The package move is content-preserving and the acceptance criteria are
met. The eleven old top-level modules are gone, and each replacement is
byte-identical to its HEAD source except for updated `require` paths. The
resulting tree has two direct files (`init.lua`, `commands.lua`) and no
subdirectory above five files (`core` 5, `handlers` 5, `ops` 4, `render` 1,
`transport` 1). Every internal and test require was updated, there are no stale
`cint.<old-module>` references left, and `require 'cint'` still exposes the same
export map, aliases, and setup entry point. `commands.lua` and the plugin are
untouched and depend only on that facade. The live transport is byte-identical;
only its `require` name and the test import changed. The documentation path
update covers the two moved client files named in `intent-model.md`.

### Findings

- None.

### Concurs / Dissents

- Concurs: the moves preserve module content. Diffing each `HEAD:nvim-renderer/lua/cint/*.lua`
  against its new path shows only require-line edits for `api`, `config`,
  `live_ops`, `profile_ops`, `render_ops`, `setup`, and `render`; `colors`,
  `profiles`, `state`, and `live` are byte-identical. New file line totals
  (1810) equal the deleted totals, so no code was dropped or duplicated.
- Concurs: all old paths are removed, so there is no duplicate module instance
  (for example no leftover `cint/render.lua` alongside `cint/render/init.lua`).
  `require("cint.render")` still resolves through the new `render/init.lua`, as
  Neovim searches `lua/<module>/init.lua`; `tests/minimal_init.lua` prepends the
  nvim-renderer root after `run.sh` changes into it, so the test loader resolves
  the directory module correctly.
- Concurs: the require graph is complete and acyclic in the same way as before.
  `init.lua:1-7` imports `core.api`, `core.config`, `core.profiles`,
  `core.state`, `ops.setup`, `ops.live_ops`, `ops.profile_ops`, and
  `ops.render_ops`; those modules import each other under the same new names.
  The handler modules and `render/init.lua` use `cint.core.colors`, and
  `tests/live_spec.lua:1` uses `cint.transport.live`. A repository-wide search
  finds no `require("cint.api|colors|config|live|live_ops|profile_ops|profiles|render_ops|setup|state")`
  or equivalent path references.
- Concurs: the public `require 'cint'` contract is unchanged. `init.lua`'s
  `expose`/`alias` table and `M.setup` are the same as before the move, and the
  state aliases still point at the single `core/state.lua` instance. The command
  surface is unchanged because `commands.lua` and `plugin/cint.lua` were not
  modified and invoke only facade methods.
- Concurs: live behavior is unchanged. `transport/live.lua` is byte-identical to
  the old `live.lua`, `ops/live_ops.lua` differs only in require paths, and
  `tests/live_spec.lua` was updated to the new import. Socket naming, framing,
  methods, callbacks, and connection state are untouched.
- Concurs: docs list the new client paths (`docs/intent-model.md:422-424`).
  Remaining mentions of the old names are historical/planning text
  (`docs/tasks/028-renderer-structural-cleanup.md`) or review records, not
  current layout claims.
- No dissent.

### Residual observations (not filed)

- The new `render/` and `transport/` directories each contain one file, so
  `scripts/classify-directory-size.nu` will label them `SINGLE`. That does not
  violate this ticket's explicit "no subdirectory >5 files" criterion, but it is
  the remaining size-hygiene signal in the new layout.
- The moved package directories are currently untracked in this worktree; the
  handover commit must stage those directories explicitly so the move is not
  committed as deletions only. The ticket still lists `depends-on: [028]` while
  `docs/tasks/028-renderer-structural-cleanup.md` remains `todo`, although its
  structural outputs are present in `HEAD`; status reconciliation is
  orchestrator-owned.

No build, test, formatter, or source edit was run by this role; the handover is
assumed green per `docs/workflow.md`.
