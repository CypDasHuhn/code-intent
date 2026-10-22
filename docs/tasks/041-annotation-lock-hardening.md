---
name: annotation lock protocol hardening
status: done
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

## Residual (accepted)

Staleness uses holder liveness (pid plus a process-start marker), so a live or
suspended holder is never stolen. The ordinary protocol serializes stale
transitions under a fixed `.lock.steal` claim. The remaining accepted residual
is the claim-reaping check-then-act window: two reapers can both observe a dead
claim, one moves it, a new live claim is published, and the second reaper can
move the live claim aside. The post-move staleness check prevents deletion; a
failed restore hard-fails rather than committing a write. Reaching two live
owners additionally requires a compound cascade across several process
suspensions, and every partial outcome is a hard failure or retry. A strict
formal guarantee would need an OS advisory lock or a unique-generation CAS.

Cross-writer JVM/Neovim contention remains tracked by ticket 050 and manual test
m015; it is not claimed as automated here.
