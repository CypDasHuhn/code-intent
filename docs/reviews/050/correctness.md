# Correctness review — ticket 050 (automated cross-writer annotation lock test)

## Round 1

Verdict: Not ready. The harness really does put a JVM writer and real headless
Neovim writers on one temporary root, and the final annotation-set assertions
would catch a lost record. The overlap proof is weaker than the ticket claims,
the forced-contention tests do not all prove contention, the encoder regression
test does not pin the `note` branch, and the failure paths can leak Neovim
processes. Findings 1-5 are work.

### Findings

1. **The overlap monitor can report an overlap on a clean hand-off, and it can
   miss a short real overlap, because the owner and witness snapshots are not
   atomic.**
   Location:
   `analysis-engine/core/core/src/test/kotlin/dev/cint/core/user/CrossWriterAnnotationLockTest.kt:400-416`
   (`LockOwnerMonitor.sample`), driven from `:157-185`.
   Problem: `sample()` reads the canonical `owner` pid and only then lists the
   witness directory. In between, a writer can release and another acquire. On
   a Kotlin-to-Neovim hand-off, the monitor can read the JUnit pid as owner,
   Kotlin releases, Neovim acquires and writes its witness, and the monitor then
   sees a witness whose pid differs from the stale owner read and records
   "overlapped". The same false positive can occur on a Neovim-to-Neovim
   hand-off; the intervals did not overlap. Conversely, a real overlap can be
   missed if it starts or ends between the two reads, or if the monitor is never
   scheduled while a witness exists. The test never asserts that the monitor
   sampled a witness at all, so a broken monitor would pass vacuously and only
   the final data check would remain.
   Suggested fix: make the snapshot consistent. Re-read the owner after listing
   the witnesses and compare only when the owner is unchanged across both reads,
   or have both writers append enter/exit records with pid, token and a
   monotonic timestamp and validate the intervals after the run. Track a
   positive-control count (samples with a witness, and/or one deliberately
   forced overlap) and assert it so the monitor itself cannot silently do
   nothing.

2. **The held-lock test does not prove that Neovim attempted and blocked on the
   lock; it proves only that Neovim had not written after a fixed sleep.**
   Location:
   `CrossWriterAnnotationLockTest.kt:42-62` and the Lua markers/hooks at
   `:516-539`.
   Problem: `started` is written after setup but before `CintAnnotate`, and the
   test does not install `before_conflict`. `awaitFile(started)` followed by
   `Thread.sleep(500)` and `process.isAlive` therefore only says Neovim had not
   persisted a note by then. A slow or delayed startup, or a regression that
   fails to wait but happens to write after the release, could still pass the
   held-lock check. The test never observes the lock-conflict path it is meant
   to exercise.
   Suggested fix: set the existing `before_conflict` lock hook (see
   `nvim-renderer/lua/cint/ops/annotations.lua:418-420,874-881`) to write a
   `blocked` marker, await that marker while Kotlin holds the lock, then assert
   the process is alive and no Neovim note exists. Keep `started` as a
   readiness marker rather than a contention witness.

3. **The concurrent test can run with little or no actual cross-writer
   contention, and it never asserts that any contention happened.**
   Location: `CrossWriterAnnotationLockTest.kt:161-176`.
   Problem: `start.countDown()` runs before the gate file is written. The eight
   Kotlin adds are serialized by the in-process `synchronized` monitor and are
   fast, while the Neovim workers poll the gate every 5 ms. All Kotlin adds can
   finish before any Neovim worker reaches `CintAnnotate`, which makes the
   "ordinary concurrent annotate/unannotate/add traffic" case effectively
   serial. The monitor only detects overlaps; it cannot detect the absence of
   contention, and no conflict counter is asserted.
   Suggested fix: coordinate a common start (write the gate first and then
   release the Kotlin start latch, or use a barrier both sides observe), and
   install conflict hooks/counters on both writers and assert at least one
   cross-writer conflict occurred. Recording Kotlin enter/exit witnesses as well
   would let the test prove an actual interleaving rather than a serial run.

4. **The encoder regression test does not pin the `note` trailing comma and
   never checks `createdAt`.**
   Location:
   `analysis-engine/core/core/src/test/kotlin/dev/cint/core/user/UserAnnotationsFileTest.kt:52-74`
   with the encoder at
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotations.kt:63-66`.
   Problem: the test covers a record with `note` plus `createdAt` and a record
   with neither. When both are present the comma after `note` is a required
   separator, not a trailing comma, so a regression that restored the old
   `note` branch (`append("\"note\":\"...\",")`) would still pass this test;
   only a `note`-only record produces `,"note":"...",}` and exercises the gap.
   The `createdAt` value is set but never asserted, so a regression that dropped
   the field would also pass. The `SnakeYAML` parser used by
   `UserAnnotationsJson.parse` is not a strict JSON validator, so the regex is
   the only strictness check.
   Suggested fix: add a note-only record (and a `createdAt`-only record) and
   assert no trailing comma in each, assert `createdAt` round-trips, and
   validate the encoded text with a real strict JSON parser if one is available
   rather than relying only on the regex and SnakeYAML.

5. **Spawned Neovim processes are not cleaned up on several early-failure
   paths.**
   Location: `CrossWriterAnnotationLockTest.kt:42-69` (held), `:98-107`
   (stale), `:140-183` (concurrent), and `:355-364` (`Harness.close`).
   Problem: in the held test, an assertion at lines 56, 57 or 62 can throw
   before `awaitProcess`, leaving the Neovim process alive while
   `Harness.close()` deletes the root underneath it. In the concurrent test the
   process list is built and all `ready` files are awaited before the
   `try/finally`, so a failure in `spawnNvim` or `awaitFile` leaks every process
   that started earlier (the workers also wait up to 15 s on a gate that will
   never be written). The stale-test process is likewise not covered by a
   `finally`. The close path then races live writers.
   Suggested fix: track every spawned process from creation and destroy them in
   a `finally` in each test, wrapping the spawn/ready loop rather than only the
   post-gate section, and await termination after `destroyForcibly`.

### Residual observations (not filed)

- The only cross-writer test is guarded by `assumeTrue` at
  `CrossWriterAnnotationLockTest.kt:197-205`. The dedicated CI step can
  therefore be green without running the test if `nvim` or the repository
  marker is unavailable. `just test-engine` already runs `:core:test`, so the
  extra `just test-cross-writer` CI step is also a duplicate run of the same
  timing-sensitive class. Consider a CI-only "require nvim" flag and one
  invocation.
- The harness uses `sleep` and `/proc`. CI is `ubuntu-latest` with Neovim
  stable, so this is portable there; on a non-Linux host with Neovim the start
  marker is empty and the stale helper waits five seconds before falling back
  to pid-only liveness.
- The encoder's `escape` still leaves other control characters
  (U+0000-U+001F other than `\n`, `\r`, `\t`) unescaped, so a note containing
  one would not be strict JSON. This is pre-existing and outside the
  trailing-comma fix, but the "strict JSON" guarantee is broader than the new
  test.
- The accepted ticket 041 stale-claim-reaping check-then-act residual remains
  real and is correctly recorded in the ticket and `m015`; the new test does not
  attempt to pin it.

Summary for the implementor: the cross-process plumbing, the stale-holder
setup, and the data-loss assertions are sound, but the test does not yet prove
what it claims about critical-section overlap. The monitor's non-atomic
owner/witness reads can both false-positive on a clean hand-off and miss a
short overlap, the held test only relies on a 500 ms timing check instead of
observing a lock conflict, and the concurrent test can finish its Kotlin writes
before any Neovim worker leaves the gate. The encoder fix is correct, but the
regression test misses the note-only trailing-comma case and does not assert
`createdAt`. Please make the witness snapshot consistent (or record enter/exit
intervals from both writers), add a `before_conflict` witness and a
common-start/conflict assertion, extend the encoder test with note-only and
createdAt-only records, and wrap every spawned process in failure-path cleanup.
No production lock-protocol change is requested by this review; the findings are
in the test harness and its pinning.

Static review only; no source edit, build, test, lint, or formatter command was
run by this role, per `docs/workflow.md`.

### Concurs / Dissents

- Concurs with the process orchestration and Lua path: `repoRoot` finds
  `nvim-renderer` from the Gradle project directory, the script is passed with
  `nvim --headless -l`, all required values are passed through environment
  variables, `cint.setup({ root = root })` makes `buffer_root` resolve to the
  temporary root, and `state.live_failed = true` keeps the run off the live
  daemon and network.
- Concurs with the stale-lock setup fidelity: the helper is a real process whose
  dead pid and `/proc/<pid>/stat` start marker are recorded, the test asserts
  `isStaleForTest` before each recovery, and Kotlin and Lua both read field 22
  (`fields[19]` zero-based / `fields[20]` one-based after `comm`), so the
  dead-holder path is genuine rather than an ownerless age fallback.
- Concurs that the final-set assertions catch lost annotations: the held test
  requires `kotlin-held`, `nvim-held` and `kotlin-saved`; the stale test requires
  both writers' records after each recovery; the concurrent test requires all
  annotated records and the removal of the pre-existing one. A lost update,
  clobbered file, or failed unannotate fails the run, and the Lua script also
  asserts its own post-write state.
- Concurs that the shared lock path is pinned for the normal case: if Neovim
  used a different path it would write inside the held window, and if Kotlin did
  not hold the path the held test would not block it.
- Concurs that the encoder fix itself is correct for every combination of
  optional `note`/`createdAt`: the `intents` array closes without a comma and
  optional fields are prefixed, so both-present, note-only, createdAt-only and
  neither are valid.
- Dissents from closure on findings 1-5.

## Round 2

Verdict: Ready; no findings. Findings 1-5 are resolved in the shape reported,
and the new interval/conflict machinery closes the gap between "the annotation
survived" and "the writers really contended on the same lock."

### Round 1 status

- Finding 1 (non-atomic owner/witness monitor): resolved. The sampling
  `LockOwnerMonitor` is gone. Both implementations now emit per-acquisition
  enter/exit records: Kotlin through `AnnotationLockObserver`
  (`UserAnnotationsFile.kt:14-24,172-178,230`) and Neovim through the
  `after_acquire`/`before_release` hooks (`annotations.lua:467-468,879-885`).
  `readIntervals` groups by writer+token, checks one enter and one exit with
  matching writer/pid/token and monotonic ordering, then `findOverlaps` checks
  all interval pairs. `assertIntervalValidatorRejectsOverlap` is a real
  positive control that the validator rejects a forced overlap, and the test
  asserts intervals exist for both writers. There is no longer a shared
  owner/witness snapshot that can tear across a hand-off.
- Finding 2 (held writer only timing): resolved. The Lua `before_conflict` hook
  now writes the `blocked` marker, and the held test awaits that marker while
  the Kotlin holder owns the lock before asserting Neovim is alive and has not
  written. The fixed sleep is gone, so the test observes the EEXIST path.
- Finding 3 (concurrent case may not contend): resolved. The test holds the
  Kotlin lock, opens the Neovim gate, waits for a Neovim conflict marker,
  releases the Kotlin start latch, waits for a Kotlin conflict marker, then
  releases the holder. Conflict markers are asserted for both sides, so the run
  cannot pass without each side having attempted the shared lock while the
  other held it.
- Finding 4 (encoder pin): resolved. The regression test now covers note-only,
  createdAt-only, both and neither, checks every encoded form with the
  trailing-comma regex and Jackson's strict parser, and asserts the optional
  field values/absence. The encoder fix itself is unchanged and correct.
- Finding 5 (failure-path cleanup): resolved. `ProcessTracker` records every
  spawned process, every test now terminates tracked processes in `finally`
  (including spawn/ready failures), the held and concurrent holder threads are
  daemons, the stale helper is cleaned in `finally`, and
  `failurePathTerminatesSpawnedNeovimBeforeRootCleanup` exercises the early
  failure path before root deletion.

### Findings

None.

### Residual observations (not filed)

- The interval comparison assumes the JVM's `System.nanoTime()` and libuv's
  `vim.uv.hrtime()` share an origin. On the Linux/CI target both are
  `CLOCK_MONOTONIC` since boot, so this is sound there; it is not a portable
  contract if the suite is later run on a platform where those clocks differ.
- `intervals.any { it.writer == "kotlin" }` is satisfied by the deliberately
  held Kotlin lock as well as by `add`, and `any { it.writer.startsWith("nvim-") }`
  by either Neovim acquisition. The final annotation sets still pin that every
  operation ran, but per-acquisition counts would make the interval-coverage
  claim stronger.
- `CINT_REQUIRE_NVIM=1` makes a missing Neovim fail in CI, but a missing
  `nvim-renderer` repository marker still reaches `assumeTrue` and would skip.
  The current Gradle working directory makes this stable in CI.
- The interval exit is recorded by `before_release`, so the interval covers the
  load/modify/write block rather than the release rename. That matches the
  critical section the test is asserting and does not reopen finding 1.

### Concurs / Dissents

- Concurs with the resolution of finding 1: per-writer interval records remove
  the non-atomic snapshot, and the forced-overlap control prevents the
  validator itself from passing vacuously.
- Concurs with the resolution of finding 2: the `blocked` marker proves Neovim
  reached the held lock, not merely that it was slow.
- Concurs with the resolution of finding 3: both implementations now contribute
  asserted conflict markers under a common held lock, so the concurrent case
  exercises real cross-process lock contention.
- Concurs with the resolution of finding 4: all four optional-field
  combinations are parsed strictly, and the previously unpinned note-only case
  now fails if the note branch regains its trailing comma.
- Concurs with the resolution of finding 5: process creation is tracked, early
  failure paths terminate before cleanup, and the dedicated cleanup control
  pins that behavior.
- Dissents: none. The correctness dimension closes for ticket 050.

Summary for the implementor: the Round 1 gaps are closed. The harness now proves
contention rather than inferring it from timing: the held case waits for a real
Neovim conflict marker, the concurrent case forces and asserts conflicts from
both sides, both writers emit enter/exit intervals that are checked for overlap,
and the validator has a forced-overlap control. The encoder regression test now
covers note-only, createdAt-only, both and neither through Jackson, and spawned
Neovim processes are tracked and terminated on failure paths. CI runs the class
once through `test-engine` with `CINT_REQUIRE_NVIM=1`, and the duplicate step is
gone. No new correctness finding was identified; the remaining notes are
portability/strength observations, not work. This dimension can close.

Static review only; no source edit, build, test, lint, or formatter command was
run by this role, per `docs/workflow.md`.
