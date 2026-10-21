# 040 correctness review

## Verdict

Not ready. The pack shape, rule ownership, language-scoped config, candidate
channel use, and the persistence-wins guard in the local rule are sound, but the
pack is not registered with the engine, so none of this behavior reaches a real
analysis. The bookkeeping precedence duplicates the bookkeeping pack's config,
and the persistence defaults misclassify several in-memory operations while
missing common durable `put`-style writes. Fixtures, golden output, and docs are
also absent despite being in acceptance.

## Findings

1. **The mutation pack is not discoverable.**

   `analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`
   lists only logging, error handling, bookkeeping, and validation.
   `RulePackLoader` discovers packs from that service file, so `MutationRulePack`
   is never loaded by the host; `DATA_MODIFY`/`LOCAL_VALUE_MODIFY` are absent from
   real analyses, their config descriptors never appear, and a `mutation` config
   section is reported as an unknown pack. All tests construct the pack
   explicitly, so they do not expose this.

   Fix: add `dev.cint.rulepacks.intents.mutation.MutationRulePack` to the service
   file, add mutation to the bundled registration contract tests
   (`BundledRulePackRegistrationTest` ids/intents and the exact
   `BundledRulePackLoadingTest` list), and regenerate/update the pipeline
   fixtures and snapshots that will now gain assignments and persistence calls.

2. **BOOKKEEPING precedence hardcodes the bookkeeping pack's config.**

   `MutationRulePack.kt:277-293` suppresses bookkeeping writes only when a
   declaration literally has `Bookkeeping` or the `bookkeeping` magic comment.
   `BookkeepingConfig` permits both names to be renamed
   (`BookkeepingRulePack.kt:17-38`). With a configured `annotation: Tracked`, the
   bookkeeping pack emits `BOOKKEEPING` while the mutation pack still emits
   `LOCAL_VALUE_MODIFY` for the same writes, directly violating the stated
   precedence. The local rule declares `reads = emptySet()` and does not read the
   established intent, so it cannot honor the owning pack's config.

   Fix: declare `reads = setOf(BOOKKEEPING_INTENT)` and suppress local writes
   covered by `view.annotations(BOOKKEEPING_INTENT)`, or share the effective
   `BookkeepingConfig` rather than duplicating defaults. Refine the range check
   to the annotations the bookkeeping pack actually emits; the current whole
   declaration range also suppresses calls in a bookkeeping declaration's
   initializer. Add tests with a renamed annotation, a renamed magic comment,
   and `ignoreBookkeeping = false`.

3. **Receiverless persistence calls are established unconditionally.**

   `MutationRulePack.kt:199-213`: once a callee matches a configured name,
   `val receiver = call.receiver ?: return true` makes bare `save`, `update`,
   `delete`, `remove`, `insert`, `send`, `write`, `commit`, `flush`, etc. emit
   `DATA_MODIFY` at 0.9 confidence even when they are local helper/static
   functions. This fails the acceptance check that the same call shape used only
   in memory does not emit, and the test suite only covers a receiverful `cache`
   case (`MutationDataModifyRuleTest.kt:70-82`).

   Fix: require a persistence qualifier/type for generic names when no receiver
   is present; route bare calls to the candidate channel unless the exact/dotted
   path is configured as persistence. Add bare-call positive and negative tests.

4. **Persistence defaults trade in-memory false positives for durable misses.**

   `MutationConfig.kt:148-177` includes `Collection`, `Stream`, `Writer`, and
   `Queue` as persistence types, and `MutationConfig.kt:103-146` includes broad
   receiver suffixes such as `stream`, `writer`, `file`, `queue`, and `table`.
   `PersistenceMatch.matchesType` (`MutationRulePack.kt:262-273`) suffix-matches,
   so `ByteArrayOutputStream`, `StringWriter`, and `MutableCollection` register
   as persistent. For example `out.write(...)`, `writer.append(...)`, or
   `items.remove(...)` (with `remove` in `persistenceCalls`) emit
   `DATA_MODIFY` and suppress `LOCAL_VALUE_MODIFY`, even though these are
   in-memory mutations. Conversely, `put` is in `mutableOperations`
   (`MutationConfig.kt:202-256`) but absent from `persistenceCalls` and
   `persistencePrefixes`, so a durable `bucket.put(...)` or `table.put(...)` is
   emitted as `LOCAL_VALUE_MODIFY`; `putObject`, `putItem`, `upload`, and
   `copyObject` produce no `DATA_MODIFY` candidate at all.

   Fix: narrow or qualify the in-memory type/qualifier defaults, and add the
   durable `put`/upload/copy API names to persistence config or treat a
   persistence-qualified receiver as persistence before the mutable-operation
   check. Add tests for in-memory streams/collections and a persistence-receiver
   `put`.

5. **`assignmentPatterns` cannot distinguish increment from decrement.**

   `MutationRulePack.kt:174-182` maps both `++` and `--` to
   `WriteKind.INCREMENT`, and accepts the write if either `increment` or
   `decrement` is configured. A user who excludes `decrement` still gets
   decrements, and vice versa. The only test
   (`MutationLocalValueModifyRuleTest.kt:112-130`) removes both together.

   Fix: either document `increment` as covering both operators and remove the
   misleading `decrement` alias, or inspect the operator text and keep the two
   patterns distinct. Add a test for each individual pattern.

6. **Candidate precision and test coverage are thin.**

   `localCandidate` (`MutationRulePack.kt:184-193`) accepts any callee that
   starts with a listed verb, so `setup`, `setVisible`, `addEventListener`, and
   `applyPlugin` all become `LOCAL_VALUE_MODIFY` candidates, while every
   candidate is `aiEligible = true`. This is non-destructive, but it can flood
   the review channel with name noise and there is no test pinning or narrowing
   it. The tests also do not cover `persistenceTypes`, `persistencePaths`
   overrides, additive overlay behavior, candidate spans, magic-comment
   bookkeeping, loop-counter variants (`for`, `idx`/`n`), or non-Kotlin
   specifics beyond smoke checks.

   Fix: constrain prefix matching or use `aiEligible`/rationale for low-signal
   names, and add the missing config/fixture/span tests.

7. **Fixtures and docs required by the ticket were not added.**

   The diff adds no fixture or golden snapshot changes and no docs. Acceptance
   requires fixtures/config/docs/tests. `docs/engine.md:19` and
   `docs/architecture.md:47,263` still name only the four old bundled packs, and
   `docs/architecture.md:255` says all bundled packs use `SingleIntentPack`,
   which this multi-rule pack does not. Add mutation to the bundled-pack and
   config documentation, describe its two intents and fields, and add fixture
   coverage.

## Concurs / dissents

- Concurs: one pack owning two distinct rules is the right shape; config is
  shared at pack level with language-scoped fields, and both replace and
  additive configuration paths are implemented correctly; candidates use
  `CandidateEmit` with pack-domain reason/source and stay out of established
  annotations; the local rule checks established persistence before emitting;
  basic established spans use `call.site`/`write.node`; overlap with
  `SERVICE_DELEGATION` is shown on the same call.
- Dissents: the pack is unreachable in production, BOOKKEEPING precedence is
  duplicated rather than owned, persistence defaults violate the in-memory
  non-detection requirement, and the acceptance-level fixture/docs/test work is
  incomplete.

## Round 2

Verdict: findings remain; the loop cannot close yet. Read-only review of
`465b5c9`; no build or tests were run.

Addressed and concurred:

- Registration is now correct: the service provider entry, bundled id/intent
  sets, loading and host registration tests, `testPacks()`, the
  `kotlin/Mutation.kt` fixture, the reordered snapshot, and the bundled-pack docs
  all include mutation. The pack is reachable through `RulePackLoader` and the
  snapshot now pins established and candidate-adjacent behavior.
- BOOKKEEPING precedence now reads `BOOKKEEPING` established annotations
  (`reads = setOf(BOOKKEEPING_INTENT)`, `bookkeepingSpans`) and matches exact
  write spans. The renamed-annotation, renamed-magic-comment, and
  `ignoreBookkeeping = false` tests cover the config ownership correctly.
- Receiverless calls are no longer established without proof; they become DATA
  candidates unless a configured path establishes them. The positive and
  negative tests cover this.
- Increment/decrement now inspect the operator text and have separate tests.
- Local write scope is conservative: only declaration-local variables are
  established, while member/external assignments are candidates. This is
  consistent with the ticket's candidate-heavy note.
- `put` now wins on a proven durable receiver (`bucket.put`, `kafkaTemplate.send`)
  and in-memory collection mutations stay local; the in-memory stream/collection
  regression is covered.

Remaining findings:

1. **Receiverful persistence wrappers still disappear.**

   `PersistenceMatch.candidate` (`MutationRulePack.kt:323-340`) only falls back
   to configured persistence calls when `call.receiver == null`. For a receiverful
   call it ends at the reduced `DATA_CANDIDATE_PREFIXES` (`MutationRulePack.kt:39-55`),
   which no longer contains `send`, `write`, `flush`, `commit`, `store`, or
   `sync`, and no longer has the previous exact-`persistenceCalls` fallback.
   Consequently `handler.send(...)`, `handler.write(...)`, `handler.flush(...)`,
   and `handler.commit(...)` with an unresolved receiver produce neither a DATA
   candidate nor a local one. This contradicts `docs/engine.md:105-107` ("bare
   configured calls and unknown wrappers stay candidates") and the ticket's
   wrapper/hidden-persistence scope.

   Fix: after the mutable-receiver/mutable-operation guards, treat any callee in
   `persistenceCalls` or matching `persistencePrefixes` as a DATA candidate, not
   just the narrow `DATA_CANDIDATE_PREFIXES` set. Add tests for `handler.send`,
   `handler.write`, and `handler.flush`.

2. **Narrowed persistence still loses durable ORM/queue/table cases.**

   Removing `Session`, `Queue`, and `Table` from the default types/qualifiers and
   keeping `put` in `mutableOperations` leaves three concrete gaps:

   - a typed Hibernate `Session.save(...)` is vetoed by
     `if (type != null && !matchesType(...)) return false`
     (`MutationRulePack.kt:334-335`) and emits nothing;
   - `table.put(...)` is a LOCAL candidate because `table` is no longer a
     qualifier and `put` is a mutable operation, so the DATA precedence the
     ticket asks for still misses it;
   - `queue.send(...)` is silent because `queue` is in
     `mutableReceiverNames` (`MutationConfig.kt:284-296`) and `send` is absent
     from the DATA candidate prefixes.

   These are configured/deterministic persistence cases in the ticket's
   ORM/queue scope, not just ambiguous names.

   Fix: let a configured persistence call remain a candidate even when the
   resolved type is not in `persistenceTypes`; do not let broad mutable receiver
   names suppress a persistence verb; and either restore durable type/qualifier
   defaults (`Session`, `Queue`, or `table` with a type guard) or document that
   they must be configured. Add typed `session.save`, `table.put`, and
   `queue.send` tests.

3. **Persistent receivers make every call a DATA candidate.**

   `persistentReceiver` returns true for any call whose receiver matches a
   persistence qualifier, durable suffix, or type (`MutationRulePack.kt:343-352`).
   `candidate` then returns true before examining the callee
   (`MutationRulePack.kt:336`). Therefore `repository.findByStatus(...)`,
   `repository.count()`, and `repository.existsById(...)` all become
   `DATA_MODIFY` candidates, and `MutationOverlapTest.searchAndDataModifyCoexistIndependently`
   does not assert candidate absence to catch it. Candidates are review-only, but
   this is exactly the read/wrapper noise the candidate channel is meant to
   triage.

   Fix: require a mutation-shaped callee (persistence prefix/name, or at least
   exclude obvious read prefixes such as `find`, `get`, `query`, `load`, `read`,
   `count`, `exists`, `select`) before emitting a DATA candidate from receiver
   proof alone. Add a finder-candidate test.

4. **Local candidate matching lost case-insensitive coverage.**

   `localCandidate` (`MutationRulePack.kt:295-301`) is now case-sensitive.
   `LOCAL_CANDIDATE_EXACT`/`LOCAL_CANDIDATE_PREFIXES` are camelCase, so C#
   methods such as `handler.UpdateProfile(...)`, `handler.Mutate(...)`, and
   `handler.ApplyChanges(...)` produce no LOCAL candidate. The old matcher was
   case-insensitive and this pack is registered for C#.

   Fix: compare prefix/exact names case-insensitively while preserving the word
   boundary (for example lower-case the first character before the boundary
   check), or add PascalCase forms. Add a C# local-candidate test.

5. **Prefix loop counters are still painted.**

   `writeTarget` (`MutationRulePack.kt:446-451`) only recognizes a leading
   identifier, so `++i` / `--i` return null and `isLoopCounter` does not suppress
   them. `++i` inside a loop is therefore emitted as established
   `LOCAL_VALUE_MODIFY`, failing the "without painting every loop counter"
   requirement; only postfix forms are tested.

   Fix: recognize prefix `++`/`--` before extracting the target, and add prefix
   increment/decrement loop tests for at least Kotlin and C#.

6. **Docs/tests are still incomplete for the new surface.**

   `docs/engine.md` documents the pack and rules but its config example still
   shows only logging/error-handling/validation; it never documents
   `persistenceCalls`, `persistencePaths`, `persistenceQualifiers`,
   `persistenceTypes`, `persistencePrefixes`, `mutableOperations`, `mutableTypes`,
   `mutableReceiverNames`, or `assignmentPatterns` for mutation. The new fixture
   is Kotlin-only, and the new tests do not cover the receiverful wrapper
   candidates, finder false candidates, PascalCase local candidates, or prefix
   loop counters above.

   Fix: extend the config documentation and add the targeted tests/fixtures.

Concurs/dissents:

- Concurs: registration/integration, BOOKKEEPING intent precedence, receiverless
  candidate handling, increment/decrement split, variable-versus-member local
  write scope, in-memory stream/collection exclusion, and the `bucket.put` /
  `kafkaTemplate.send` precedence fix.
- Dissents: candidate recall for receiverful persistence wrappers, candidate
  precision on persistent receivers, over-narrowed typed ORM/queue/table
  persistence, case-sensitive local candidates, and prefix loop-counter
  suppression. The remaining issues are behavioral regressions or gaps in the
  round-1 findings, so another focused round is needed before closing.

## Round 3

Verdict: the loop can close. No findings. Read-only review of `4528dc6`; no
build or tests were run.

Verified:

- Persistent-receiver read noise is fixed. `candidate` now rejects read-shaped
  callees before receiver proof (`isReadCall`, `MutationRulePack.kt:306,413-432`),
  while established matches still win first. The new test covers
  `findByStatus`, `count`, `exists`, and `get`, and the SEARCH overlap test now
  asserts no DATA candidates. The receiver-proof path only emits a candidate for
  a mutation-shaped callee (`mutationShaped`, `MutationRulePack.kt:334-340`).
- `persistencePrefixes` is wired through `configuredCandidate`
  (`MutationRulePack.kt:325-332`) for receiverless calls, proven persistent
  receivers, and unresolved receiverful calls. The custom-prefix test and the
  `send`/`write`/`flush`/`commit` wrapper test pin it. The only intentional
  exclusion is `updateProfile`, which stays a local candidate because it matches
  a local wrapper name without an exact persistence call; the test expects that.
- Receiverful wrappers now yield DATA candidates, including calls with resolved
  non-persistence types; the previous type veto is replaced by an in-memory type
  veto, so a typed handler wrapper still reaches the configured-call/prefix
  check.
- Typed ORM/queue/table precedence is restored: `Session`, `Table`, and `Queue`
  are persistence types (`MutationConfig.kt:157-181`), and `session.save`,
  `table.put`, `queue.send` are established under a type oracle. Untyped
  `queue.send` is at least a DATA candidate now that `queue` is no longer a
  mutable receiver name. `sendMessage`/`convertAndSend` and the durable
  Client/Template suffix path cover messaging.
- Local wrapper matching is case-insensitive with a word-boundary check
  (`isLocalCandidate`, `MutationRulePack.kt:434-443`); the C# test covers
  `UpdateProfile`, `Mutate`, and `MergeState`.
- Prefix loop counters are handled: `writeTarget` strips leading `++`/`--`
  before extracting the target (`MutationRulePack.kt:498-507`), and both Kotlin
  and C# prefix tests verify suppression.
- Registration integration remains consistent with `465b5c9`: the service
  provider entry, bundled id/intent tests, host registration test, `testPacks()`,
  snapshot fixture, and bundled docs are unchanged and still include mutation.
  The behavior changes in `4528dc6` affect candidates and loop-counter
  suppression only, which the pipeline snapshot does not record, so no snapshot
  churn is required. The mutation config example is now documented in
  `docs/engine.md`.

Residual tradeoffs are heuristic, not findings: read detection is prefix-based,
and typed `Queue`/`Table`/`Session` are treated as durable even when a project
might use in-memory instances. Those are configurable defaults covered by the
new tests.

Concurs/dissents:

- Concurs with all round-1 and round-2 fixes listed above. No dissents and no
  remaining findings; the loop can close.
