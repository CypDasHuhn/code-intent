---
name: host cutover: cli and server
status: todo
depends-on: [003, 010, 011]
reviewers: [correctness, architecture]
---

## Goal

Point the batch CLI and the live daemon at the new engine so they publish from
the scheduler/composition pipeline (architecture.md §1 publishing, §9 cli/server).

## Scope

- `cli` — batch host over the new engine; artifact writing uses the structured
  provenance.
- `server` — HTTP host and per-project Unix-socket daemon over the new engine;
  live publishes versioned annotations.
- Preserve the transport contract the renderer consumes, subject to 016.
- Reload stays a daemon restart; `:CintServerRestart` remains transparent.

## Acceptance

- `just install` produces working `cint` and `cint-server`; a batch run over
  `testing-grounds/` emits the same set of intents as before the restructure.
- The live daemon starts, analyzes a buffer, and publishes with no dependence on
  the old `Phase` pipeline.
- `just test-engine` and `just test-nvim` green.
- Any step requiring a live daemon is recorded in `manual-test.md`.

## Out of scope

- Renderer-side payload changes (016).

## Notes

- Split handlers (Vue `<script>` regions) must keep working through the new
  pipeline.
