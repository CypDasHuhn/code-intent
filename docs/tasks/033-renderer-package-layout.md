---
name: renderer package layout
status: in-progress
depends-on: [028]
reviewers: [correctness]
---

## Goal

Reduce `nvim-renderer/lua/cint` to <=5 direct files by grouping modules into
subpackages while preserving `require 'cint'` and command behavior.

## Acceptance

- `lua/cint` has <=5 direct files and no subdirectory >5 files.
- `just test-nvim` passes; public API/commands unchanged.
