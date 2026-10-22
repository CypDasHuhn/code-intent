---
name: automated cross-writer annotation lock test
status: todo
depends-on: [041]
reviewers: [correctness]
---

## Goal

Automate the Kotlin-writer plus Neovim-writer contention case on one project
root so the shared `.cint/user-annotations.json.lock` protocol is exercised
across processes, not only per language.

## Scope

- Run a Kotlin `UserAnnotationsFile.add` writer and a headless Neovim
  `:CintAnnotate`/`:CintUnannotate` writer against the same root.
- Force contention with a held lock and with a dead-holder stale lock.
- Assert both annotations survive and no writer enters another writer's
  critical section.
- Keep the test independent of a live LSP daemon and network access.

## Acceptance

- `just test-engine` or a dedicated recipe runs the cross-process test in CI.
- Both writers observe the same lock path, owner record, token and liveness
  rules.
- The test fails if either writer loses an annotation under contention.

## Out of scope

- Replacing the file-based store with a database.
- Live daemon/editor round-trip coverage already tracked by manual test m012.

## Notes

Deferred from ticket 041 Round 1 because the engine test harness has no
JVM-to-Neovim subprocess orchestration. Ticket 041 keeps per-language coverage
and the same on-disk shape; this ticket closes the remaining automated
cross-process gap.
