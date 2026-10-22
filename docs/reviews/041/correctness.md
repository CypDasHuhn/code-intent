# Correctness review — ticket 041 (annotation lock hardening)

## Round 1

Verdict: The reported acquisition crash is fixed: both implementations treat a
lock that vanishes between the failed create and the age read as a retry, and
the tests exercise that path. The ownership token also protects the ordinary,
serialized release path. The hardening is not yet safe or live in the crash and
stall states the ticket names, though: an ownerless lock or guard is permanently
unrecoverable, the guard itself is a stale-stealable lease without a heartbeat,
so a stalled guard holder lets two writers mutate the lock concurrently, and the
release/steal checks are read-then-mutate. The heartbeat also cannot cover a
suspended lock holder or the acquire-to-block window, and there is no
cross-writer test. Findings 1-5 are work.

### Findings

1. **Ownerless lock and guard directories are never recovered; this also bricks
   an upgrade from the old protocol.**
   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:192-226`
   (`lockAgeMillis`, `stealLock`) and
   `nvim-renderer/lua/cint/ops/annotations.lua:70-76,98-114` (`lock_age_millis`,
   `steal_lock`).
   Problem: when the owner file is missing, `lockAgeMillis`/`lock_age_millis`
   falls back to the directory mtime and the caller decides the path is stale,
   but `stealLock`/`steal_lock` returns immediately when `readOwner` is null. A
   process that crashes (or is stopped) between `createDirectory`/`mkdir` and
   `writeOwner`/`write_owner`, or between deleting the owner and deleting the
   directory in `releaseOwned`/`release_owned`, leaves an ownerless lock. That
   lock and any ownerless `.guard` can never be stolen: every `add`/`save`/
   annotate then waits out the 5s acquire timeout (and the Kotlin release can
   throw from `finally`) until someone removes the directory by hand. The old
   protocol's stale locks were plain directories with no owner file, so
   upgrading onto a stale lock has the same permanent failure. Leftover
   `.stale-*` directories are inert but are never reclaimed, and a crash between
   the rename and the delete leaves one per steal.
   Suggested fix: make crash states recoverable. Either create the directory
   with its token in one step and rename it into place so a canonical lock
   always has an owner, or treat an ownerless path whose directory mtime is
   older than `STALE_MILLIS` as stealable (move it aside and delete it; the
   create-to-write window is already older than the threshold in that case).
   Free the canonical path on release/steal by renaming it to a token-scoped
   tombstone before deleting, so a crash leaves only an inert tombstone, and
   opportunistically reclaim stale tombstones. Add a test that pre-creates an
   ownerless lock and an ownerless guard older than `STALE_MILLIS` and asserts
   the next operation succeeds.

2. **The guard can be stolen from a live holder, and once two writers hold it
   the release/steal read-then-mutate is a real TOCTOU that can delete or evict
   a live successor's lock.**
   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:156-190,207-235`
   (`withGuard`, `stealLock`, `releaseOwned`) and
   `nvim-renderer/lua/cint/ops/annotations.lua:152-172,98-123` (`with_guard`,
   `steal_lock`, `release_owned`).
   Problem: the guard uses the same 10s mtime staleness and stale-steal path as
   the lock but, unlike the lock, has no heartbeat. A guard holder that is
   suspended (SIGSTOP, VM pause, long GC, suspend/resume) for more than 10s is
   treated as dead and another writer steals the guard. Both writers then
   believe they own the guard and mutate the lock metadata concurrently.
   Concretely, writer A in `releaseOwned(lock, tokenA)` reads
   `owner == tokenA` and is descheduled before deleting; writer B steals the
   stale guard, sees A's lock as stale, moves/deletes it, creates a new lock
   with `tokenB`, and enters the critical section; A resumes and, without
   re-reading the token, deletes `lockPath/owner` and `lockPath`, which are now
   B's. A third writer can then acquire the lock while B is still writing. The
   same shape exists in `stealLock` between the age read and the
   `readOwner`/move: a concurrent guard holder can apply an old age decision to
   a freshly created lock and evict a live owner. The guard's staleness is
   therefore not only a liveness concern; it defeats the ownership-token
   guarantee.
   Suggested fix: do not use a lease that can be stolen from a live holder for
   the guard. Use an OS advisory lock (released on process death) or a single
   atomic lock primitive for the metadata, or at minimum heartbeat the guard and
   re-validate the moved directory's owner and age inside the same critical
   section before deleting anything. Make release atomic relative to the path
   (rename to a token-scoped tombstone, verify the moved owner still matches,
   restore if not) instead of read-then-delete, and re-read the token
   immediately before the destructive step. Add a deterministic barrier test
   that pauses between the token read and the delete and between the age read
   and the steal.

3. **A live lock holder suspended beyond `STALE_MILLIS` is stolen and resumes
   writing; the heartbeat does not cover the acquire-to-heartbeat-to-block
   window and there is no fencing before the data write.**
   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:104-111,253-278`
   with `write` at `305-318`, and
   `nvim-renderer/lua/cint/ops/annotations.lua:195-227` with `save` at
   `305-323`.
   Problem: the heartbeat is only a lease refresher: it cannot run while the
   holder's process or thread is stopped, and the protocol treats a stale owner
   mtime as death. A holder paused for more than 10s inside `block()` is stolen,
   the successor enters, and the resumed original continues its
   load-modify-write, so two owners write the annotation file. There is also a
   narrow window between `acquire` returning (owner written) and
   `startHeartbeat` running; if the process is suspended there, the lock is
   stolen, `startHeartbeat` sees a mismatched token and returns, yet
   `withFileLock`/`with_annotations_lock` still runs `block()`. Neither language
   re-checks ownership after starting the heartbeat or immediately before the
   temp-file rename, so there is no fencing at the point the write commits.
   Suggested fix: prefer an OS advisory lock on the annotation file (released on
   process death, with no stale timeout), or add a fencing check: after starting
   the heartbeat and immediately before committing the data rename, verify the
   lock still carries this token and abort/fail the write if it does not.
   Re-validate ownership after `startHeartbeat` before entering the block as
   well. Add a test that keeps the critical section past `STALE_MILLIS` while
   the heartbeat is deliberately blocked and asserts the holder does not write
   after ownership is lost.

4. **Release failure semantics differ and can mask errors or leave the lock;
   deadline arithmetic lets a vanished guard bypass the deadline and makes
   crash recovery impossible in one call.**
   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:105-112,122-153,156-190`
   and `nvim-renderer/lua/cint/ops/annotations.lua:152-193,214-222,584-590`.
   Problem: in Kotlin the release runs in `finally` through
   `withGuard(releaseDeadline)`; if the guard cannot be acquired in 5s (for
   example because of finding 1), the `IllegalStateException` from the `finally`
   replaces any exception from `block()` and leaves the lock behind. Lua calls
   `with_guard` for release and discards its `nil, err` result, so a timed-out
   release is silent and the lock waits for the next 10s steal. Also,
   `withGuard` does `lockAgeMillis(guardPath) ?: continue` before checking the
   deadline or sleeping, so a guard that keeps disappearing spins without
   honoring the deadline (the lock path correctly retries with a 10ms sleep).
   Finally, `ACQUIRE_TIMEOUT_MILLIS` is 5s while `STALE_MILLIS` is 10s, so a
   crashed holder that still has an owner file cannot be recovered by a single
   call: the caller times out before the lock is old enough to steal, and only a
   later attempt can recover it. That undercuts the ticket's stale-recovery goal
   and is inconsistent between the two languages.
   Suggested fix: make release best-effort and non-throwing (preserve the
   original exception, report or retry a failed release), share one deadline
   through the whole acquire attempt including the guard, check the deadline
   before `continue`ing on a vanished guard, and either set the acquire timeout
   above `STALE_MILLIS` plus margin or retry the acquire once the stale
   threshold has passed and surface a clear lock-held error. Make Lua
   return/report the release failure instead of swallowing it.

5. **The tests do not pin the uncertain races and the required cross-writer
   coverage is missing.**
   Location:
   `analysis-engine/core/core/src/test/kotlin/dev/cint/core/user/UserAnnotationsFileTest.kt:102-207`
   and `nvim-renderer/tests/annotations_spec.lua:188-234`.
   Problem: the vanished-lock tests are good and genuinely exercise the fixed
   path. The stale-ownership tests, however, pass by construction:
   `staleLockStealLeavesSuccessorOwnedAndOldReleaseCannotDeleteIt` calls
   `releaseIfOwned(root, "old-owner")` after the steal is fully complete (a
   post-hoc token mismatch), and
   `lateReleaseAfterOwnershipChangeDoesNotDeleteSuccessor` replaces the lock
   directory from the test thread without holding the guard before the release
   runs. Neither creates the interleaving where a release reads its token and
   then a steal/replacement occurs, so neither would catch finding 2. There is
   no test for an ownerless lock/guard, a leftover stale tombstone, a guard
   stolen from a live holder, a heartbeat-delayed critical section, or a
   suspension between acquire and heartbeat. Most importantly, the ticket
   explicitly asks for cross-writer tests and its acceptance criterion is
   concurrent `UserAnnotationsFile.add` and nvim annotate/unannotate: the
   Kotlin test only contends Kotlin threads (and the existing
   `concurrentAddsPreserveEveryAnnotation` is fully serialized by the in-process
   `synchronized`), the Lua spec only runs Lua in one Neovim, and no test makes
   the two implementations contend on the same root. The Lua hook is also global
   and fires for the guard path, unlike Kotlin's `onAcquireConflict`, so a
   guard-contention test can accidentally delete a live guard.
   Suggested fix: add deterministic hooks/barriers around the token-read-to-delete
   and age-read-to-move windows and assert the successor's lock survives. Add
   ownerless lock/guard, guard-stall, and heartbeat/suspension tests. Add a
   cross-process test that runs the Neovim annotation path against a root while
   a Kotlin writer holds or steals the lock and asserts both records survive;
   make the Lua hook lock-path-specific as Kotlin's is. If real cross-process
   orchestration is out of scope, record it as a named follow-up in the ticket
   rather than claiming the acceptance criterion is met.

### Summary for the implementor

The targeted `NoSuchFileException` path and the basic token check are fixed, but
the new protocol still assumes a stale mtime means a dead process. An ownerless
lock or guard (including an old-protocol leftover or a crash between mkdir and
the owner write) is unrecoverable; the guard can be stolen from a live but
paused holder, so release's read-then-delete can remove a successor's lock; and
a suspended lock holder resumes writing without any ownership recheck. Release
also differs between Kotlin (can throw from `finally` and mask the original
error) and Lua (silently ignores the failure), and the acquire timeout is
shorter than the stale threshold. The tests pin the vanished-lock path but not
the release/steal TOCTOU or crash states, and there is no Kotlin-to-Lua
cross-writer test. Please make crash states recoverable (single-step token
creation or stealing ownerless stale directories), make the guard reliable or
heartbeat it and revalidate ownership atomically before destructive steps, add
a fencing/recheck before the data write, align release/timeout behavior, and add
the missing race and cross-writer tests.

### Concurs / Dissents

- Concurs with the vanished-lock fix: both implementations return a retry/null
  age rather than reading a deleted path, and the Kotlin and Lua tests reproduce
  a lock disappearing between the failed create and the age read.
- Concurs with the ownership-token check on the ordinary, serialized release
  path: a foreign token is left alone in both languages, and the tests check it.
- Concurs with the atomic-rename steal direction and the `moved == observed`
  verification for the serialized case; it is an improvement over the
  unconditional `rmdir`/delete in the old protocol.
- Dissents from closure on findings 1-5: the protocol is still not safe under a
  stalled guard or lock holder, is permanently blocked by ownerless crash
  states, and lacks the requested cross-writer coverage.

Static review only; no build, test, lint, or formatter command was run by this
role, per `docs/workflow.md`.

## Round 2

Verdict: The redesign resolves the Round 1 findings in the form they were
reported. Ownerless and legacy paths are now age-reaped, the normal-path guard
is gone, release uses a verified tombstone rename, live and suspended owners
with an owner record are no longer stolen, both writers fence before the data
rename, and the acquire timeout now exceeds the legacy staleness threshold. The
cross-writer deferral is acceptable per the workflow. Two genuinely new races
remain in the liveness/tombstone design: an ownerless lock can be stolen from a
creator that is suspended between `mkdir` and writing its owner, after which the
creator resumes and overwrites the successor's owner; and the stale steal can
move a live lock aside, with a non-atomic restore that can strand that live lock
or let a third writer enter. Findings 1-2 are work.

### Round 1 status

- Finding 1 (ownerless lock/guard unrecoverable): resolved. `isStale` falls back
  to path/owner age when the owner is missing, `stealLock` now accepts a null
  observed owner, and `reapInert` removes old guards and tombstones. Both new
  legacy/guard tests exercise recovery.
- Finding 2 (guard stale-steal / release TOCTOU): resolved in the reported
  form. The guard is removed from the normal path and release renames the lock
  to a token-scoped tombstone and verifies the moved owner before deleting. The
  replacement restore path has the new race in finding 2 below.
- Finding 3 (suspended holder resumes / no fencing): resolved for holders that
  have an owner record. Process liveness keeps a suspended holder's lock, and
  both writers recheck the token before the data rename. The pre-owner window
  is the new race in finding 1 below.
- Finding 4 (release masking, deadline arithmetic, Kotlin/Lua divergence):
  resolved. Kotlin wraps release in `runCatching`, Lua notifies on a restore
  failure, the acquire timeout is 15s against a 10s legacy threshold, and the
  guard `continue` path is gone.
- Finding 5 (race/cross-writer tests): resolved per language. The new tests
  cover ownerless/legacy recovery, dead/live/suspended owners, release/steal
  barriers, and vanished locks. Deferring genuine JVM-to-Neovim contention to
  ticket 050, with `m015` recording 041 as unverified and stating that the
  criterion is not automated, is an explicit named deferral with a reason and
  fits `docs/workflow.md`.

### Findings

1. **A suspended creator can have its ownerless lock stolen and then overwrite
   the successor's owner, entering the critical section beside it.**
   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:132-154`
   (`createDirectory` then `writeOwner` then verification) with `writeOwner` at
   `299-306`, and
   `nvim-renderer/lua/cint/ops/annotations.lua:265-293`
   (`acquire_annotations_lock`) with `write_owner` at `137-145`.
   Problem: after `createDirectory`/`fs_mkdir` succeeds but before the owner
   record is written, the writer has no liveness information in the lock. If it
   is suspended for more than `STALE_MILLIS` in that window, another writer
   sees an ownerless lock older than the threshold, steals it, deletes it, and
   creates a new lock at the same path with a new owner (and can enter its
   critical section). When the original creator resumes, `writeOwner` opens
   `lockPath/owner` for truncation and writes its own token into the
   successor's directory; `io.open(..., "w")` does the same in Lua. The
   creator's post-acquire verification then reads its own token and passes, so
   both writers are in the critical section. The write-failure cleanup is also
   unsafe: Kotlin's `deleteTree(lockPath)` and Lua's `delete_tree(lock)`
   unconditionally remove the path, which can delete the successor's live lock
   if the write fails after replacement. This is the one case where "a live or
   suspended holder is never stolen" does not hold: the creator is live but has
   not published ownership yet.
   Suggested fix: publish ownership atomically and never clobber an existing
   owner. Create the owner with `CREATE_NEW`/`O_EXCL` (Kotlin
   `StandardOpenOption.CREATE_NEW`, Lua `"wx"` or `fs_open` with
   `O_CREAT|O_EXCL`) and treat an existing owner as "lost this lock, retry";
   only delete the directory on write failure if the owner file was created by
   this attempt (or use an identity check), never unconditionally. An
   alternative is to build a token-named temporary directory containing the
   owner and atomically install it, then revalidate identity before entering.
   Add a hook between `mkdir` and the owner write, make the ownerless directory
   old, let a successor steal and enter, resume the creator, and assert the
   creator aborts and the successor's owner survives. The same test is needed
   in both languages.

2. **A concurrent stale steal can move a live lock aside, and the tombstone
   restore is not atomic, so a live owner can be stranded or overlapped.**
   Location:
   `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:147,198-220,222-249`
   (acquire staleness check, `stealLock`, `release`) and
   `nvim-renderer/lua/cint/ops/annotations.lua:288-289,216-235,237-263`
   (`acquire_annotations_lock`, `steal_lock`, `release_owned`).
   Problem: the staleness decision and `atomicMove(path, tombstone)` are not
   atomic, and `stealLock`'s staleness recheck happens only before its observed
   read and move. With two writers racing on the same stale lock, writer W2 can
   move the old lock aside, delete it, create a fresh lock L2, write its owner
   and enter; writer W1, whose stale decision/observed read applied to the old
   lock, then moves the canonical path, which now holds L2. W1 detects
   `moved != observed` and restores L2, but the canonical path is empty during
   that window. Two failures follow. If W2 finishes and releases inside the
   window, `release` reads no owner on the canonical path and returns
   `NOT_OWNER`; W1 then restores L2, leaving a live-owned lock at the canonical
   path that no one will release (W2's `finally` already ran), so the lock leaks
   for the lifetime of W2's process. If a third writer creates a lock while the
   path is empty, W1's restore move fails because the destination is occupied;
   the error is swallowed (`runCatching`/`pcall`), L2 is stranded in a
   tombstone, and the third writer enters while W2 is still in its critical
   section. `reapInert` will not reclaim that tombstone because its owner is
   alive, and W2's release only looks at the canonical path, so the stranded
   live lock is permanent. The current barrier tests
   (`releaseRestoresLockReplacedAfterTokenRead`,
   `stealRestoresLiveLockReplacedAfterStalenessRead`, and the Lua
   `release_root`/`steal_root` cases) always restore into a free path and never
   run the successor's release or a third writer during the window, so they pin
   restore success only.
   Suggested fix: serialize the stale transition with a primitive that cannot
   free the canonical path for a live lock. An OS advisory lock
   (`FileChannel.lock`/`flock`) for the metadata/steal is kernel-atomic and is
   released on process death, which also removes the separate ownerless case.
   Alternatively use an atomic exchange/sentinel that other writers treat as
   "lock held" so no one can create during the move. At minimum, make a failed
   restore a hard failure rather than best-effort (do not return success and do
   not let the acquire continue), and let a live owner's release also find its
   token-named tombstone so the lock cannot leak. Add tests that occupy the
   canonical path while a live lock is moved aside (restore collision) and that
   release the moved live owner during the window, asserting no overlap and no
   leaked lock.

### Concurs / Dissents

- Concurs that Round 1 finding 1 is resolved: ownerless and legacy lock, guard
  and tombstone paths are recovered by age, and both language tests cover it.
- Concurs that Round 1 finding 2 is resolved in its reported form: the
  normal-path guard is gone, and release uses a token-scoped tombstone with
  post-move owner verification. Finding 2 above is a new race in that
  replacement, not a re-report.
- Concurs with the liveness identity: `pid` plus the `/proc/<pid>/stat`
  start-time field is compared consistently in Kotlin (`fields[19]` after
  `comm`) and Lua (`fields[20]`), so PID reuse is rejected when the marker is
  available. Live and stopped processes are not stolen, and the dead-holder
  path is recovered immediately rather than after the age threshold.
- Concurs that Round 1 findings 3 and 4 are resolved: live/suspended holders
  with an owner record are protected, both writers fence before the temp rename,
  release no longer masks the block exception, and 15s acquire exceeds the 10s
  legacy age.
- Concurs that `reapInert` excludes the canonical lock and only reaps
  auxiliary entries whose owner is missing/old or dead, so it does not delete a
  live release tombstone. The flip side is that a live lock stranded in a
  tombstone by finding 2 is never reaped.
- Concurs that the Kotlin/Lua owner-record shape, token format, staleness rules,
  tombstone naming and fencing are at parity for the reviewed paths; Kotlin
  fences before writing the temp file as well as before the rename, which is
  stricter than Lua but not a divergence that can lose an annotation.
- Concurs that the cross-writer deferral is acceptable per `docs/workflow.md`:
  ticket 050 is a named follow-up with a reason, `m015` names 041 and records
  the criterion as unverified, and the implementor does not claim the
  cross-writer criterion is automated.
- Dissents from closure on findings 1 and 2.

### Residual observations (not filed)

- When the process-start marker is empty (non-`/proc` platform, restricted
  `/proc`, or a failed read), liveness degrades to PID only; PID reuse can then
  keep a dead holder's lock alive indefinitely. On the Linux `/proc` target the
  marker is present, so this is a portability caveat rather than a same-platform
  race.
- Process-level liveness intentionally makes a lock leaked by a hung live
  process or thread unrecoverable until that process exits, with no force-stale
  or operator path; this is the accepted cost of not stealing live holders.
- A live pre-upgrade client's ownerless lock is indistinguishable from a
  crashed legacy lock and is stolen after 10s, so a rolling mixed-version
  upgrade can still overlap with an old writer.

### Summary for the implementor

Round 1 is addressed: liveness-based staleness, ownerless/legacy reaping,
tombstone release, pre-write fencing, best-effort release, and the 15s timeout
are all present, and the cross-writer deferral to 050 with `m015` is a
legitimate named follow-up. Two new races need work. First, a creator suspended
between `mkdir` and writing its owner can have its ownerless lock stolen and
then resume and truncate the successor's owner; use `CREATE_NEW`/`O_EXCL` (or a
token-named temp directory installed atomically) and never delete the path
unconditionally on a write failure. Second, a stale steal can move a live lock
aside and the restore is a best-effort move with an empty canonical path in
between; a release in that window leaks the live lock, and a third writer can
make the restore fail and enter beside the live owner. Serialize the steal with
an OS advisory lock or an atomic exchange/sentinel, make a failed restore a hard
failure, and let release find its tombstone; add collision and
release-during-move tests for both languages. No Round 1 finding needs
re-reporting.

Static review only; no build, test, lint, or formatter command was run by this
role, per `docs/workflow.md`.

## Round 3

Verdict: Round 2 findings 1 and 2 are resolved in the ordinary protocol. The
pre-owner clobber is closed by exclusive owner publication (`CREATE_NEW` /
`wx`), the claim-visible check before publishing, and failure cleanup that no
longer deletes a foreign owner. The live-lock restore race is closed by the
fixed `.lock.steal` claim: a live claim blocks every acquirer, stale
transitions run only under the claim with a canonical staleness recheck, a
moved live canonical lock is restored or hard-fails, release finds its own
tombstone, and both writers fence before the data rename. The new tests pin
those paths. The only remaining hole is the claim-reaping TOCTOU the
implementor disclosed; I accept it as a documented residual rather than a
blocking finding because ordinary live holders never reach it and every
non-compound outcome hard-fails or retries. Zero findings; the correctness
dimension closes.

### Round 2 status

- Finding 1 (pre-owner clobber): resolved. Kotlin `publishOwner` uses
  `CREATE_NEW`, Lua uses `fs_open(..., "wx")`, both check for a claim before
  publishing, and the failure cleanup only deletes an owner that is absent,
  blank or this attempt's token. `creatorSuspendedBeforeOwnerWriteCannotClobberSuccessor`
  and the Lua `creator_root` case suspend the creator, let a successor acquire
  and publish, then assert the creator aborts without changing the successor
  token or entering the block.
- Finding 2 (live lock moved aside / non-atomic restore): resolved for the
  normal path. The fixed claim carries an owner record and all acquirers wait
  on a live claim; `tryAcquireClaim` rechecks the canonical lock under the
  claim; `stealCanonical` restores a moved live lock and hard-fails if it
  cannot; `release` looks up its own tombstone and hard-fails on an unexpected
  restore; the write/save fences recheck the canonical token before the
  rename. `staleStealClaimAllowsOnlyOneTransition`,
  `claimRechecksCanonicalAndDoesNotMoveLiveLock`,
  `liveClaimBlocksCanonicalLockCreation`, `failedReleaseRestoreIsHardFailure`,
  `releaseFindsItsOwnTombstone`, and the Lua `contention`, `claim_live`,
  `claim_block`, `hard` and `tombstone` cases cover those paths. The only
  remaining bypass is the claim-reaping residual below.

### Claim-reaping residual (accepted, not filed)

Location:
`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationsFile.kt:181-186,237-287,398-417`
(top-level claim reap, `tryAcquireClaim`, `reapStaleAux`, `reapInert`) and
`nvim-renderer/lua/cint/ops/annotations.lua:387-395,341-376,247-263,219-240`.

Problem: reaping a stale claim is a check-then-act sequence with no mutual
exclusion: `isStale(claim)` is evaluated, then `reapStaleAux` reads the owner
raw text and renames the claim aside. Two reapers can both observe the same
dead claim; one moves it, a claimant creates and publishes a new live claim,
and the second reaper then moves the live claim. The post-move `isStale` check
prevents deletion and attempts to restore it, but if another claimant has
occupied the claim path in the window the restore move fails and the live
claim is stranded. `reapInert` can similarly delete a stale tombstone that an
active reaper or stealer is using; the active operation then sees a missing
source and throws a spurious "failed to restore" hard failure.

Why this is accepted under the ticket acceptance: a live or suspended claim is
never stale (`isStale` uses process liveness, not age), so the ordinary live
holder path never reaps a live claim. Without the ABA above, the canonical
steal is serialized: while a live claim is at the path no writer creates, the
claimant rechecks canonical staleness under the claim, live canonical locks are
never stale, and a moved live lock is restored or hard-fails. Reaching two live
owners requires a compound cascade: a dead stale claim, two reapers, a live
claimant created in the reaping window, a restore collision, a claimant delayed
between its canonical recheck and move, another writer creating in the freed
window, and the pre-write fence's recheck-to-rename window. That is far outside
normal contention and requires a suspension at a specific point; every partial
path ends in a hard failure or a retry rather than a committed write. I
therefore treat it as a documented residual, not a blocker.

Recommendation: record this residual in 041 or `m015` (or a named follow-up) so
it is not lost. If a strict formal guarantee is wanted, replace claim reaping
with an atomic primitive (an OS advisory lock on the claim, or a
unique-generation compare-and-swap), revalidate the observed claim identity
immediately before the move, and treat a missing source tombstone as "already
reaped" instead of a restore failure. A direct test would need a hook inside
`reapStaleAux`; the current suite cannot force the ABA.

### Concurs / Dissents

- Concurs that Round 2 findings 1 and 2 are resolved; I do not re-report them.
- Concurs that, for the ordinary protocol, no two live holders enter the
  critical section: live/suspended lock and claim owners are never stale, a
  live claim blocks creation, stale transitions are under the claim with a
  canonical recheck, restore collisions hard-fail, release finds its own
  tombstone, and both writers fence before the rename. Strictly, the guarantee
  is conditional on the claim not being reaped from under a live claimant; that
  is the accepted residual above.
- Concurs with the new test coverage (pre-owner suspension, claim contention,
  live-lock recheck, live claim blocking creation, release restore hard
  failure, release tombstone, ownerless/legacy recovery, dead/live/suspended
  holders, vanished locks), and with the unchanged cross-writer deferral to
  ticket 050 and `m015`.
- No dissents; the correctness dimension closes.

### Residual observations (not filed)

- The claim-reaping TOCTOU above, including `reapInert` deleting an in-flight
  stale tombstone and the resulting spurious hard failure, is the only
  remaining route around claim serialization; it is accepted and should be
  recorded.
- As in Round 2, process liveness is only as good as the start marker: when
  `/proc` is unavailable or unreadable the code falls back to PID-only
  liveness, and a lock or claim leaked by a hung live process is unrecoverable
  until that process exits. On the Linux `/proc` target the marker is present.
- A live pre-upgrade client's ownerless lock is indistinguishable from a
  crashed legacy lock and is stolen after 10s, so a rolling mixed-version
  upgrade can still overlap with an old writer.

### Summary for the implementor

Round 2 findings 1 and 2 are closed: exclusive owner publication and the
claim-visible check stop the suspended creator from clobbering a successor; the
fixed claim, the canonical recheck under the claim, hard failure on an
unexpected restore, release-tombstone lookup, and the pre-write fence stop the
normal stale steal from moving a live lock or leaking it. The ordinary
live-holder guarantee now holds, and the new tests pin the paths. The one
remaining item is the disclosed claim-reaping check-then-act race: it can move
a newly created live claim aside and, in a compound multi-process interleaving,
re-enable the live-lock restore window; it requires a dead stale claim plus
concurrent reapers and delays, and every non-compound outcome is a hard failure
or retry, so I accept it as a documented residual under the ticket acceptance.
Please record it in 041/`m015` or a named follow-up, and consider an OS
advisory lock or unique-generation CAS if you want the strict formal guarantee.
No new blocking findings; the correctness dimension closes.

Static review only; no build, test, lint, or formatter command was run by this
role, per `docs/workflow.md`.
