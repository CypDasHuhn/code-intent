# Correctness review — ticket 051 (size hygiene splits)

## Round 1

Verdict: The production splits preserve behavior. I compared the base and HEAD
declarations and method bodies for the moved server code, rulepack packs/configs/
defaults/rules, `api` config support, suggest providers/transport, and the
core/user lock protocol. Class and function moves are faithful, wrapper/delegation
paths retain the original semantics, the frozen `api` FQNs are unchanged, and the
`UserAnnotationsFile` lock/write/release behavior is equivalent. The test split
is almost complete, but one test method was dropped instead of moved.

### Findings

1. **`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/service/init/`
   (split from `.../intents/service/ServiceInitRulePackTest.kt:282`) — a test was
   lost in the service-init split.**

   The old `ServiceInitRulePackTest.unconfiguredConstructorsAndPlainCallsAreNotInit`
   has no counterpart in `ServiceInitRulePackTest.kt`,
   `ServiceInitCreationRulePackTest.kt`, or `ServiceInitWiringRulePackTest.kt`, and
   a repository-wide search for the method name finds nothing. The old test
   asserted that an unconfigured `Helper()` construction and plain
   `helper.run()` / `data.clear()` calls produce neither established
   `SERVICE_INIT` source nor candidates. Its coverage is not duplicated by the
   surviving lifecycle/candidate tests, so this is lost regression coverage, not
   a rename.

   Fix: restore the original test method and assertions in the split service-init
   tests (the creation/negative-behavior file is a reasonable home), then rerun
   `just test-engine`.

### Summary

Apart from the missing service-init test, the split appears behavior-preserving:
the server helpers were moved without logic changes, the rulepack configs and
default values are identical, `CandidateGroup`/`CandidateDocument`/
`SuggestionOptions` and the provider types remain the same types under their new
`model`/`provider` packages with all in-repo callers updated, and the
`UserAnnotationsFile` lock protocol still uses the same tokens, stale detection,
tombstone handling, observer hooks, and write verification. The suggest package
moves are source-incompatible only for out-of-repo consumers; the ticket
explicitly permits package moves and no in-repo contract break was found, so I
did not file them as a finding. I found no other lost test methods or changed
assertions.

Concurs / Dissents: **Dissent** — one finding (lost
`unconfiguredConstructorsAndPlainCallsAreNotInit` test) needs to be restored;
otherwise I concur that the production behavior and remaining test coverage are
preserved.

## Round 2

Verdict: The round-1 finding is resolved. The restored
`ServiceInitCreationRulePackTest.unconfiguredConstructorsAndPlainCallsAreNotInit`
matches the removed base test body exactly, and a full comparison of every
committed test method body against the current worktree found no other lost or
changed test. The package moves, validation-read de-duplication, shared-helper
move, and server `live`/`suggestions` split preserve behavior: only package and
import lines changed in the data-access/structural/rulepack-helper moves, the
validation read set is still the same three intents, candidate publication moved
with its body unchanged, and the lock write path still checks ownership before
and during the atomic write. Two non-behavioral contract/documentation issues
remain.

### Findings

1. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/layout/AnnotationLayout.kt:5`
   — the new layout holder expands the `:core` public API unnecessarily.**

   `AnnotationLayout`, its `DIRECTORY`/`FILE_NAME` constants, and `path()` are
   public, but every use is inside the same module (`UserAnnotationsFile`,
   `AnnotationFileLock`, and the tests). This is a new public surface introduced
   while fixing package/directory alignment, and the ticket's acceptance calls
   for no public API changes. `UserAnnotationsFile.DIRECTORY`/`FILE_NAME` remain
   the intended public constants.

   Fix: make `AnnotationLayout` and its members `internal` (or inline the two
   constants into `UserAnnotationsFile`); the public `UserAnnotationsFile`
   constant values keep the same values and ABI.

2. **`docs/data-access-intents.md:107` — the documented registration FQN is now
   stale.**

   The sentence still names
   `dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack`, while the class now
   lives at `dev.cint.rulepacks.intents.dataaccess.rules.DataAccessRulePack` and
   the service descriptor was updated accordingly.

   Fix: update the documented FQN to the new package, or explicitly defer the
   one-line doc correction to a named docs follow-up.

### Summary

The restored test, package alignment, validation read de-duplication, shared
helper relocation, and one-way server split with `suggestions` depending on
`live` are behavior-preserving; the production method-body comparison against
HEAD and the test-body comparison against HEAD found no semantic drift, and the
service descriptor now names the moved rulepack classes correctly. What remains is the
newly public `AnnotationLayout` helper and one stale documented data-access FQN;
neither affects runtime behavior, but both are loose ends from this round.

Concurs / Dissents: **Dissent** on the two contract/documentation findings above;
I concur that the round-1 test-coverage finding is fixed and that the split
changes preserve behavior.

## Round 3

Verdict: Both round-2 findings are resolved, and I found no new correctness
issue. `AnnotationLayout` is now `internal`, so the new layout holder no longer
expands the `:core` public surface, while `UserAnnotationsFile.DIRECTORY`/
`FILE_NAME` keep the same public constant values. `DataAccessRulePack` is back at
`dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack`, matching the
intent-root convention and the data-access documentation; the service descriptor
and all in-repo imports were updated to the root FQN, with config and matcher
imports consistently split between `.config` and `.rules`. The rulepacks-tests
root note now describes the retained meaningful package groups rather than
claiming they are all single-file packages.

### Finding status

- Round-1 finding: resolved (restored service-init test remains present and
  matches the original).
- Round-2 finding 1 (new public `AnnotationLayout`): resolved.
- Round-2 finding 2 (stale documented data-access FQN): resolved.

No new findings.

### Summary

The round-3 fixes are limited to visibility, package location, imports, the
service descriptor, and the ticket note. Rechecking the current worktree against
HEAD still found no lost or changed test bodies; the only production body changes
remain the already-reviewed `UserAnnotationsFile`/lock delegation refactor, which
preserves ownership checks and atomic write behavior. The data-access rulepack
entry point and its config/matcher imports now match the documented and recorded
layout, and no stale references to the previous `.rules` entry-point FQN remain.

Concurs / Dissents: **Concurs** — all prior findings are addressed and the round-3
diff introduces no behavioral, contract, or coverage regression.
