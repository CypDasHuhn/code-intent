---
name: renderer structural cleanup
status: done
depends-on: []
reviewers: [correctness]
---

## Goal

Reduce the renderer monolith and align its tests/docs with the live transport.

## Scope

- Split `lua/cint/init.lua` by concern (setup/profiles/rendering/commands).
- Exercise `live.lua` transport in tests or add an explicit integration test.
- Remove `SemanticCodePainter` naming leftovers and unplanned legacy fallbacks
  (or document a removal version).
- Fix `doc/cint.txt` TOC, config coverage, and palette claim; add stylua config.

## Acceptance

- `init.lua` is a thin entry point; `just test-nvim` green.
- No stale project-name/legacy hot-path paths.
