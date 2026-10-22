---
name: automated cross-writer annotation lock test
status: done
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

`CrossWriterAnnotationLockTest` now spawns real `nvim --headless` processes
against a temporary project root while the JUnit process writes through
`UserAnnotationsFile.add`/`save`. It covers a held lock, dead-holder stale
recovery by both writers, and ordinary concurrent annotate/unannotate/add
traffic. Both writers append lock enter/exit records with pid, token and a
monotonic timestamp, and the test rejects any overlapping intervals. A forced
holder also produces asserted lock-conflict markers from both writers, so the
run cannot pass without real contention. Run it on its own with
`just test-cross-writer`; `just test-engine` already includes it, so CI invokes
it once through that recipe. CI sets `CINT_REQUIRE_NVIM=1` so a missing Neovim
fails instead of skipping; local runs skip when `nvim` is unavailable.

The cross-writer run exposed a Kotlin encoder bug: `UserAnnotationsJson.encode`
left a trailing comma after `note`/`createdAt`, which Neovim rejects as invalid
JSON. The encoder is fixed and `UserAnnotationsFileTest` pins note-only,
createdAt-only, both and neither records through Jackson's strict JSON parser.

Remaining limitation: the accepted ticket 041 stale claim-reaping
check-then-act window still needs an OS advisory lock or a unique-generation CAS
for a strict formal guarantee. This test also does not cover the live
daemon/editor round trip, which stays in manual tests m009 and m012.
