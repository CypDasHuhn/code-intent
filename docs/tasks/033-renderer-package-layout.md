---
name: renderer package layout
status: done
depends-on: [028]
reviewers: [correctness]
---

## Goal

Reduce `nvim-renderer/lua/cint` to <=5 direct files by grouping modules into
subpackages while preserving `require 'cint'` and command behavior.

## Acceptance

- `lua/cint` has <=5 direct files and no subdirectory >5 files.
- `just test-nvim` passes; public API/commands unchanged.

## Outcome (2026-10-20)

`nvim-renderer/lua/cint` now has 2 direct files (`init`, `commands`) plus
`core/`, `ops/`, `transport/`, `render/`, and `handlers/`, each <=5 files.
`require 'cint'`, commands, live transport, and renderer behavior are unchanged;
all require paths and docs updated. Reviewer concurs; `just test-nvim` passes.
