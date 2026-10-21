---
name: annotation lock protocol hardening
status: todo
depends-on: [036]
reviewers: [correctness]
---

## Goal

Harden the shared `.cint/user-annotations.json.lock` directory protocol used by
the Kotlin writer and the Neovim client so lock acquisition/release cannot
overlap critical sections.

## Scope

- Fix the acquisition race where a failed lock-directory creation followed by a
  holder release can throw `NoSuchFileException` on the mtime read.
- Fix stale-lock stealing so an original owner cannot later delete a successor's
  lock and overlap with it (ownership token / identity check on release).
- Mirror the same protocol in nvim.
- Add contention, stale-ownership, and cross-writer tests.

## Acceptance

- Concurrent `UserAnnotationsFile.add` and nvim annotate/unannotate never lose
  or overwrite annotations.
- Stale-lock recovery cannot let two owners enter the same critical section.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Replacing the file-based store with a database.
- Changing candidate decision semantics.

## Notes

Deferred from ticket 036 Round 5 correctness review. Files:
`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt`,
`nvim-renderer/lua/cint/ops/annotations.lua`.
