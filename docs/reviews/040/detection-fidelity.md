# Detection-fidelity review — ticket 040 (mutation intents)

## Round 1

Verdict: The pack has the requested shape: two rules owning distinct intents,
established emits plus `CandidateEmit`s, per-language configuration with
replace/additive semantics, and overlap coverage with bookkeeping and synthetic
search/delegation packs. The durable/in-memory boundary is not yet faithful
enough to accept. Defaults promote clearly in-memory receivers (`stream`,
`channel`, `writer`, `OutputStream`, `Collection`) and generic contexts
(`context`, `session`, `table`) to established `DATA_MODIFY`, bare configured
calls are treated as durable without a receiver or type, several candidate
thresholds point in the wrong direction, `LOCAL_VALUE_MODIFY` still paints every
assignment and every default `add`/`put`/`remove` as established, and
`BOOKKEEPING` precedence is re-derived from hardcoded marker names rather than
the configured intent. The pack is also absent from bundled registration, shared
test packs, fixtures, docs, and renderer defaults, so none of this behavior
reaches the production pipeline.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationConfig.kt:103-177`
   with `MutationRulePack.kt:210-212,262-273` — in-memory types and generic
   receiver names are established durable persistence.**

   The default qualifiers include `stream`, `channel`, `writer`, `bus`,
   `context`, `session`, `table`, and `queue`; the default types include
   `Stream`, `Channel`, `OutputStream`, `Writer`, `Collection`, and `Table`.
   Once a callee is in `persistenceCalls`, a matching receiver name or resolved
   type is sufficient. A Kotlin coroutine `channel.send(value)` is therefore
   established `DATA_MODIFY` even though it is process-local, as are
   `ByteArrayOutputStream.write(...)`, `StringWriter.write(...)`, and a
   `MutableCollection.remove(...)` whose receiver type the oracle resolves
   (the type suffix `Collection` matches). `context.update(...)` and
   `session.update(...)` are likewise durable by name alone. This reverses the
   ticket's core distinction: `DATA_MODIFY` must persist beyond the process or
   cause an external side effect, while these are in-memory mutations.

   Suggested fix: remove in-memory stream/channel/writer/collection/table types
   and generic context/session/table/stream/channel names from the defaults, or
   require a durable subtype/path (file, socket, database, broker) before a
   receiver name or type can establish persistence. Add negative cases with a
   fixed oracle for coroutine channels, byte/string streams, mutable collections,
   and request/UI contexts.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:203-213`
   — a bare call matching a configured persistence name is established without
   any receiver or type evidence.**

   `val receiver = call.receiver ?: return true` makes `send(message)`,
   `write(buffer)`, `update(row)`, `remove(item)`, `delete(id)`, and `save(x)`
   established whenever the callee appears in the default set, even when it is a
   local helper or an in-memory transport/channel. The ticket asks for
   deterministic persistence, not a callee-name certificate.

   Suggested fix: treat a receiver-less configured call as a candidate unless its
   path/import resolves to a known durable API or its resolved declaration is
   known to persist. Add tests for local helpers named `save`/`update`/`send`
   and for bare channel/queue sends.

3. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:184-192,215-223`
   with `MutationDataModifyRuleTest.kt:69-82` — candidate thresholds are broad
   on the data side, asymmetric on the local side, and not pinned by the
   in-memory test.**

   `DataModifyRule.candidate` accepts every exact configured persistence name, so
   the test's in-memory `cache.save(user)` still emits a `DATA_MODIFY` candidate;
   the test only calls `assertNoSource`, so the acceptance statement "the same
   call shape used only in memory does not emit `DATA_MODIFY`" is not actually
   asserted. Prefix matching makes `updateProfile`, `syncState`, `storeValue`,
   and similar in-memory wrapper calls data candidates too. Conversely,
   `LocalValueModifyRule.localCandidate` returns false for any callee with a
   persistence prefix, so an ambiguous in-memory `handler.updateProfile()` or
   `handler.mergeState()` can never be a `LOCAL_VALUE_MODIFY` candidate and is
   routed only to `DATA_MODIFY`.

   Suggested fix: do not emit a data candidate for a configured call whose
   receiver type resolves to a non-persistent type; use wrapper/hidden-persistence
   signals other than a plain configured callee for the candidate tier. On the
   local side, let persistence-prefixed names be local candidates when the
   receiver is in-memory instead of discarding them. Add a test that asserts the
   candidate set as well as the established set for `cache.save`, and cover
   `update*`/`merge*` on an in-memory receiver.

4. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:143-157`
   with `MutationConfig.kt:202-258` — `LOCAL_VALUE_MODIFY` is established for
   every assignment and every default `add`/`put`/`remove` operation.**

   The write loop has no target or receiver filter: any `WriteFact` accepted by
   the default patterns is established, including assignments to properties,
   globals, and external/API objects, not just local values. The mutable-operation
   loop likewise establishes any call whose callee is in the default set,
   regardless of whether the receiver is a collection or map. This contradicts
   the ticket notes that `LOCAL_VALUE_MODIFY` should be candidate-heavy and the
   out-of-scope item "treating every `add`/`put`/`remove` as meaningful state
   change".

   Suggested fix: establish only declaration-local writes and writes whose
   receiver/type proves a mutable collection or map; make member/external/global
   assignments and untyped `add`/`put`/`remove` candidates. Narrow the default
   established operations (or keep them candidate until a receiver/type match),
   and add negative tests for domain methods named `add`, setter calls, and
   external-object field assignment.

5. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:277-285`
   — `BOOKKEEPING` precedence is hardcoded to the default marker names and does
   not read the bookkeeping intent.**

   The code checks `hasAnnotation("Bookkeeping")` and
   `hasMagicComment("bookkeeping")` directly. A project that configures
   `BookkeepingConfig.annotation` or `magicComment` (the bookkeeping pack exposes
   both) still gets the default markers here, so the bookkeeping write is painted
   as `LOCAL_VALUE_MODIFY` while the bookkeeping pack tags it as `BOOKKEEPING`.
   Re-deriving the declaration ranges also duplicates the bookkeeping pack's
   source-of-truth logic instead of consuming its spans.

   Suggested fix: declare `BOOKKEEPING` as a read and suppress writes covered by
   `view.annotations("BOOKKEEPING")`, or inject the shared bookkeeping marker
   configuration into the mutation pack. Add a test with a custom annotation and
   a custom magic comment. The unused `language` parameter in `localCandidate`
   is also a sign that candidate names were intended to be per-language
   configurable; wire it or remove it.

6. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:174-182`
   with `MutationConfig.kt:258,352-447` — assignment patterns cannot distinguish
   increments from decrements, and per-language defaults miss common durable
   APIs.**

   `WriteKind.INCREMENT` covers both `++` and `--`, and `accepts` treats either
   configured token as accepting both. Configuring `assignmentPatterns` to only
   `decrement` still paints `count++`, and only `increment` still paints
   `count--`. On the language side, C# `File.WriteAllTextAsync`,
   `File.AppendAllTextAsync`, `File.WriteAllLinesAsync`, and
   `File.WriteAllBytesAsync` are absent from `persistenceCalls`/`persistencePaths`,
   so they only reach the candidate tier through the `write`/`append` prefixes;
   `kafkaTemplate.send(...)` is likewise only a candidate because the `kafka`
   qualifier does not match `kafkaTemplate`. The JavaScript and TypeScript
   defaults are copies of the shared base plus the same additions.

   Suggested fix: distinguish increment/decrement by operator text (or split the
   write kind) and test each pattern independently. Add the async file APIs and
   suffix rules for `*Template`/`*Client`/`*Producer` (or require configured
   types) per language, with one established case per language for the common
   async write and messaging shapes.

7. **`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/mutation/MutationOverlapTest.kt:43-77`
   — overlap tests do not pin the real SEARCH/DATA boundary or same-call
   persistence precedence under a resolved type.**

   The search case only checks two independent calls and uses a synthetic pack;
   that is reasonable while ticket 038 is unimplemented, but it does not cover
   the likely collision (`deleteBy*`, `findOneAndUpdate`, derived deletes), which
   currently falls to `DATA_MODIFY` candidates through the `delete`/`update`
   prefixes rather than an explicit SEARCH/DATA policy. The local precedence case
   uses different receivers (`repository.delete` vs `local.delete`) rather than
   one call whose receiver type resolves to persistence, so the
   `Collection`-type misclassification above passes the suite.

   Suggested fix: add a derived-delete/modify case and state the expected
   SEARCH/DATA co-occurrence; add a fixed-oracle case where a mutable-operation
   callee is called on a persistence receiver and assert only `DATA_MODIFY` is
   established.

8. **`analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack:1-4`,
   `analysis-engine/tests/test-fixtures/src/testFixtures/kotlin/dev/cint/test/fixtures/TestPacks.kt:9-15`,
   and the absence of mutation fixtures/docs in the diff — the pack is not
   integrated or documented.**

   `MutationRulePack` is not registered in the bundled service file and is not in
   `testPacks()`, so the production host and the shared fixture suite never load
   it; its config schema is not discoverable and the pack cannot be configured
   through the normal overlay path. The ticket acceptance requires fixtures,
   config, and docs, but the diff adds no fixture file, no docs, and no renderer
   tag defaults for `DATA_MODIFY`/`LOCAL_VALUE_MODIFY`.

   Suggested fix: add the provider entry, include the pack in the shared test
   packs (or document why not), add mutation fixtures covering persistence,
   in-memory collections, bookkeeping overlap, and candidates, and update the
   intent/architecture/engine docs plus the client's tag defaults. Record any
   non-automated live check in `manual-test.md`.

### Concurs / Dissents

- Concurs: the two rules own `DATA_MODIFY` and `LOCAL_VALUE_MODIFY`
  respectively, have empty `reads`, and return established and candidate emits
  through the v5 `RuleEmit` channel.
- Concurs: established spans are the call site and write node, candidates carry
  `CandidateReason.PACK_DOMAIN` with `rule:<rule-id>` sources and are
  `aiEligible`; candidates stay out of the established annotation set.
- Concurs: persistence wins over local mutation when the data rule establishes a
  call: `LocalValueModifyRule` skips established persistence calls, so
  `repository.delete` and `local.delete` separate as intended in the default
  case.
- Concurs: bookkeeping suppression works for the default `@Bookkeeping` marker,
  and loop-counter suppression works for the hardcoded names inside a loop.
  Config replacement and additive union behave as tested.
- Concurs: established intents remain a set, so `DATA_MODIFY` can coexist with a
  `SERVICE_DELEGATION` contribution on the same call and with `SEARCH` on a
  different call.
- Dissents: findings 1-8.

### Residuals (not findings)

- `PersistenceMatch.candidate` excludes a callee merely because it is in the
  mutable-operation set; this is the mechanism behind the local/data candidate
  asymmetry in finding 3 and will need revisiting when the SEARCH and service
  packs land.
- `matchesQualifier` trims a receiver at `(`, so a receiver call such as
  `getFile().delete(...)` can match the `file` qualifier even though its type is
  unknown. This is another instance of the name-over-type issue in finding 1.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `docs/workflow.md`.

## Round 2

Verdict: Most Round-1 findings are addressed. The durable/in-memory defaults are
much tighter (`session`, `stream`, `channel`, `writer`, `OutputStream`,
`Collection`, and `Table` are gone), receiver-less calls are candidates unless a
path is configured, member/external assignments are candidates, local collection
operations require receiver/type evidence, bookkeeping precedence now reads the
`BOOKKEEPING` intent and honours custom markers, increment/decrement are
distinct, C# async file writes and `kafkaTemplate.send` are established, and the
pack is registered with matching tests, docs, renderer tags, fixture, and
pipeline snapshot. The loop cannot close yet: the candidate tier currently
turns every call on a persistent receiver into a `DATA_MODIFY` candidate,
including searches, the exposed `persistencePrefixes` config is not used at all,
and messaging/local-wrapper coverage still has language and method-name gaps.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:323-340`
   with `MutationOverlapTest.kt:159-174` — every unknown call on a persistent
   receiver becomes a `DATA_MODIFY` candidate, including SEARCH reads.**

   After `established` returns false, `candidate` checks only that the receiver
   is not a mutable-name receiver and then returns true when
   `persistentReceiver(...)` matches. The callee is never consulted. Thus
   `repository.findByStatus(status)`, `repository.count()`, `file.exists()`,
   `Files.exists(...)`, and `bucket.get(...)` all emit `DATA_MODIFY` candidates
   even though they retrieve data or test existence. The search overlap test
   only asserts established `texts`, so it does not notice the extra candidate
   on `repository.findByStatus`. This contradicts the SEARCH/DATA boundary in
   `docs/intent-model.md` and makes the review channel noisy on ordinary reads.

   Suggested fix: make `candidate` require a persistence/wrapper signal in the
   callee (configured call, candidate prefix, or a receiver type that proves a
   write API), or exclude known read verbs (`find`, `get`, `query`, `search`,
   `load`, `read`, `exists`, `count`, `has`, `fetch`, `list`). Keep the
   persistent-receiver evidence as a qualifier, not the sole candidate trigger.
   Add a candidate-level assertion to the search overlap case.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationConfig.kt:180-204,323,359-363`
   with `MutationRulePack.kt:39-55,338-340` — `persistencePrefixes` is exposed
   as config but is dead; candidates use a different hardcoded set.**

   `DEFAULT_PERSISTENCE_PREFIXES` still includes `send`, `write`, `delete`,
   `remove`, `update`, and `insert`, and `MutationLanguageConfig` exposes the
   field through the schema, but no rule reads it. `candidate` uses the private
   `DATA_CANDIDATE_PREFIXES`, which omits those verbs. A project overriding
   `persistencePrefixes` sees no behavior change, and hidden-persistence
   wrappers such as `handler.sendAll(...)`, `handler.writeRecord(...)`, or
   `handler.removeRecord(...)` on an unknown receiver produce no candidate at
   all even though the configured default claims those prefixes. This is both a
   config-contract defect and a candidate-coverage gap.

   Suggested fix: drive candidate matching from
   `config.persistencePrefixes` (optionally unioned with the durable
   object-store-specific names), or remove the field from the schema/defaults
   and document the hardcoded list. Add a test that a configured prefix changes
   the candidate result.

3. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationConfig.kt:126-149,284-314`
   with `MutationRulePack.kt:308-340` — queue/messaging coverage is only
   partial, and removing `queue` from the qualifiers leaves a durable
   `queue.send(...)` completely unclassified.**

   `kafkaTemplate.send(...)` and `*Client`/`*Producer` receivers now reach the
   candidate or established tiers, and the C# async file cases are covered.
   However `queue` was removed from `persistenceQualifiers` but is still in
   `mutableReceiverNames`, so `queue.send(...)` is neither established nor a
   candidate (the mutable receiver veto runs before the prefix check, and
   `send` is not in `DATA_CANDIDATE_PREFIXES`). `sqsClient.sendMessage(...)` and
   `rabbitTemplate.convertAndSend(...)` likewise stay candidates because their
   method names are not in `persistenceCalls`, even though they are ordinary
   durable API shapes rather than ambiguous wrappers. The removal of `db` as a
   qualifier similarly demotes `db.save(...)` to a candidate.

   Suggested fix: keep durable queue/broker names (or provider-specific
   qualifiers/types) in the established defaults, add the common send/publish
   method names (`sendMessage`, `convertAndSend`, ...) to the configured calls,
   and distinguish a mutable-name receiver from a durable queue by type or
   explicit config. Add one established test each for a queue handle and a
   non-Kafka broker.

4. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:57-82,295-301`
   — local wrapper candidate matching is case-sensitive, so C# wrappers never
   become candidates.**

   `localCandidate` compares against lower-case exact names and prefixes with
   `callee.startsWith(prefix)` and `callee[prefix.length].isUpperCase()`. A C#
   `handler.UpdateProfile()`, `handler.Mutate()`, or `handler.MergeState()`
   therefore matches nothing, while the same Kotlin names do. The new wrapper
   tests are Kotlin-only, and `DATA_CANDIDATE_PREFIXES` is case-insensitive, so
   the asymmetry is easy to miss. This leaves an ambiguous local mutation
   unclassified on a supported language.

   Suggested fix: compare candidate names case-insensitively (or normalise the
   first character/verb separately from the camel-case boundary), and add C#
   wrapper-candidate tests.

### Concurs / Dissents

- Concurs: the durable/in-memory boundary is substantially improved. In-memory
  stream/channel/writer/collection/table defaults are gone, `cache.save` no
  longer yields a data candidate, `items.add` stays local, and member/external
  assignments are candidates rather than established local mutations.
- Concurs: `BOOKKEEPING` precedence now consumes the established intent and the
  custom annotation/comment tests pin it; `ignoreBookkeeping = false` still
  exposes the write as configured.
- Concurs: receiver-less calls are candidates unless a persistence path is
  configured, and `increment`/`decrement` assignment patterns are independently
  honoured.
- Concurs: registration is complete enough for the production path: service
  provider, bundled registration/loading tests, shared test packs, docs,
  renderer tag defaults, `testing-grounds/kotlin/Mutation.kt`, and the pipeline
  snapshot all include the mutation pack.
- Concurs: local wrappers such as `mutate`/`updateProfile` are candidates and
  low-signal prefixes (`setup`, `setVisible`, `addEventListener`,
  `applyPlugin`) no longer flood the channel on Kotlin.
- Concurs: established persistence still wins over local mutation, and
  `SERVICE_DELEGATION` can co-occur on the same persistence call.
- Dissents: findings 1-4. Finding 1 is the blocker for closing the loop; finding
  2 is a silent config-contract failure; findings 3-4 are coverage gaps on
  supported languages/APIs.

### Residuals (not findings)

- `MutationDataModifyRuleTest.inMemoryStreamsAndCollectionsAreNotDataModify`
  returns one `SendChannel` type for every receiver from its `FixedOracle`, so
  it does not actually exercise `ByteArrayOutputStream`, `StringWriter`, or a
  collection type. The config test pins the removed defaults, but the
  behavioral negative is weaker than its name suggests.
- `candidate` compares `receiverName` to `mutableReceiverNames` case-sensitively
  while `isMutableReceiver` uses `ignoreCase`; a capitalised receiver name can
  bypass the mutable veto.
- `DEFAULT_MUTABLE_OPERATIONS` contains a duplicate `reverse` literal; this is
  hygiene only.
- Static review only; the green handover is treated per `docs/workflow.md`.

## Round 3

Verdict: Round-2 findings 1-4 are addressed in form: persistent-receiver reads
are filtered, `persistencePrefixes` now drives candidates and has a test,
`sendMessage`/`convertAndSend` establish, C# wrapper candidates are
case-insensitive, and the registration/docs/fixture/snapshot integration is
unchanged and still present. The durable/in-memory boundary regressed on the
queue fix, however, and the new read filter is too broad. The loop cannot close
until a `java.util.concurrent.BlockingQueue` is no longer treated as durable
persistence and explicit/compound mutation calls are not swallowed by the read
prefix list.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationConfig.kt:161-185`
   with `MutationRulePack.kt:306-311,334-350` and
   `MutationDataModifyRuleTest.kt:305-326` — generic `Queue`, `Table`, and
   `Session` types re-introduce in-memory false positives, and the test pins a
   `BlockingQueue` as durable.**

   `Queue` and `Table` (and `Session`) are back in `persistenceTypes`, and
   `candidate` checks `persistentReceiver` before `isInMemoryType`. The new test
   asserts that `queue.send(item)` on a
   `java.util.concurrent.BlockingQueue` is established `DATA_MODIFY`. A
   `BlockingQueue` is process-local memory, exactly the `LOCAL_VALUE_MODIFY`
   side of the ticket's boundary. The same ordering makes `queue.add(item)` on
   that type a `DATA_MODIFY` candidate because `mutationShaped` accepts any
   mutable operation once the receiver is considered persistent, while the local
   rule skips it. Generic `Table` (in-memory table/UI model) and `Session`
   (`jakarta.servlet.http.HttpSession`) have the same ambiguity.

   Suggested fix: remove generic `Queue`/`Table`/`Session` from the persistence
   types and use broker/database-specific types or qualifiers (`KafkaTemplate`,
   `SqsClient`, `Hibernate Session`, etc.); check in-memory/package types before
   `persistentReceiver`; change the `BlockingQueue` case to expect
   `LOCAL_VALUE_MODIFY`/no `DATA_MODIFY`, and add a positive broker-queue case
   with a broker-specific type.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/mutation/MutationRulePack.kt:306,320-321`
   with `MutationConfig.kt:34-49` — the read-prefix filter runs before
   configured-call matching, so explicit and compound mutations are suppressed.**

   `candidate` returns false for any callee starting with `find`, `get`,
   `query`, `read`, `list`, etc. before checking `configuredCandidate`. This
   hides `findOneAndUpdate` and `findOneAndDelete`, which are already in the
   JavaScript/TypeScript persistence defaults, when the receiver is not yet
   proven persistent; it also hides a configured wrapper such as `getPersist` or
   `getAndIncrement`, and ordinary `getOrCreate`-style mutations. The result is
   a false negative on exactly the compound read/write methods that need the
   candidate channel.

   Suggested fix: let exact/configured persistence calls win before applying the
   read filter, and only treat a call as a read when it is not a compound
   mutation (`AndUpdate`, `AndDelete`, `OrCreate`, `AndIncrement`, ...). Add
   tests for `findOneAndUpdate` with an unknown receiver and for a configured
   read-prefixed persistence call.

### Concurs / Dissents

- Concurs: persistent-receiver reads such as `repository.findByStatus`,
  `repository.count`, `file.exists`, and `bucket.get` no longer produce
  `DATA_MODIFY` candidates; the search overlap test now asserts this.
- Concurs: `persistencePrefixes` is now consumed by candidate matching, the
  hardcoded `DATA_CANDIDATE_PREFIXES` duplication is gone, and the configured
  prefix override is pinned.
- Concurs: queue/messaging detection improved for typed queues and client/
  template methods (`sendMessage`, `convertAndSend` are established), and C#
  wrapper candidates now work through case-insensitive local matching.
- Concurs: local noise remains controlled: member/external assignments and
  unproven mutable calls are candidates, loop counters (including prefix
  `++i`/`--i`) are suppressed, and bookkeeping precedence still holds.
- Concurs: registration integration remains complete: service provider, bundled
  loading/registration tests, shared test packs, docs, renderer tags, fixture,
  and pipeline snapshot.
- Dissents: findings 1-2. Finding 1 is a boundary regression and blocks closing
  the loop; finding 2 is a narrower false-negative that should be fixed in the
  same pass.

### Residuals (not findings)

- `isInMemoryType` is consulted only on the non-persistent-receiver path, so any
  future type that appears in both persistence and in-memory suffix lists will
  prefer persistence.
- `isReadCall` is a prefix heuristic with no word boundary, so names such as
  `listen` and `listing` are treated as reads; this only affects candidates.
- The `inMemoryStreamsAndCollectionsAreNotDataModify` test still returns a
  single `SendChannel` type for every receiver and does not exercise the actual
  stream/collection types it names.
- Static review only; no build, test, edit, or formatter command was run by this
  role.
