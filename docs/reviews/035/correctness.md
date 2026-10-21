# Correctness review — ticket 035 (protocolized config overlay)

## Round 1

Verdict: The happy path is present — descriptors are exposed additively, the
overlay is loaded into the host pipeline, config mutations persist and force a
pack reload, and the protocol/picker filter by language and field kind. It is not
ready for closure: the overlay is not fully additive against pack defaults or
scalar shorthand, open-buffer refresh is suppressed by the unchanged content
hash, malformed overlay content can be silently destroyed, and republishes are
routed to one client while documents are global. The tests exercise the helpers
and one successful round trip but do not pin those behaviors.

### Findings

1. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:69-73`
   with `analysis-engine/core/api/src/main/kotlin/dev/cint/api/Config.kt:253-283`
   and `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/logging/LoggingConfig.kt:25-68`
   — the overlay is merged only into the project-provided config, so appending a
   value replaces the pack's default set for that field instead of adding to it.**

   `effectiveProject()` merges the overlay into `project.packs`; `configure`
   then starts from the pack's defaults but `ConfigField.apply` calls the field
   setter with exactly the decoded overlay value. On a fresh project with no
   `logging` section, appending `audit` to `logging.kotlin.methods` makes
   `methods = {audit}`, dropping the default `info`, `debug`, `warn`, `error`,
   `trace`, `log`, `verbose`, `fatal`. Existing logging calls stop being tagged
   even though the user only asked to append one symbol. This contradicts the
   acceptance wording that base/default/project values are preserved (and the
   user-facing "append" semantics); if replacement is intended it needs to be
   stated explicitly because the effect is destructive.

   Fix: merge the overlay additively against the pack's effective/default value
   for the targeted field (for example by passing the overlay separately into
   the configure step and unioning string set/list fields there, while keeping
   the overlay file user-only for removal), then add an end-to-end test that
   appends `audit` with no project `logging.methods` and still detects
   `log.info`.

2. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/PackConfigOverlay.kt:163-182`
   — a scalar string for a string-list/set field is not unioned with an overlay
   list, so base values are lost.**

   `ConfigValues.strings` and `stringList` accept a bare string (and
   `parseProjectConfigTolerant` preserves it), but `mergeValue` only unions when
   both sides are `List`. With base `methods: audit` and an overlay
   `methods: [trace]`, the `else -> overlay` branch returns `[trace]` and
   `audit` disappears. The reverse (base list, overlay scalar) also discards the
   base.

   Fix: for string collection fields normalize a string to a one-element list
   before unioning, or perform the merge field-aware using the descriptors.
   Add a test covering the scalar-to-list case in the real pipeline.

3. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/PackConfigOverlay.kt:15-27,45-72,75-97,140-147`
   — a malformed overlay is treated as empty and the next mutation overwrites
   the user's file; writes are also not atomic.**

   `load` logs and returns `emptyMap()` on any read/parse failure, and `packs`
   silently drops non-map roots, non-map pack entries, and non-string keys.
   `config/read` therefore reports an empty overlay and `append`/`remove` start
   from empty and write a fresh file, destroying whatever the user had. A crash
   or concurrent writer during `Files.writeString` can also leave a truncated
   YAML file, which then enters the same silent-empty path.

   Fix: distinguish "no file" from "invalid file", surface the parse error
   through the protocol instead of returning success, refuse mutations until the
   file is valid, and write via a temporary file plus atomic move. Cover a
   malformed file and a non-map root with tests.

4. **`nvim-renderer/lua/cint/ops/live_ops.lua:223-232` with
   `nvim-renderer/lua/cint/ops/config_ops.lua:87-92` and
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:151-152,165-166`
   — config changes do not refresh already-rendered open buffers.**

   The server republishes with the same document text and version, hence the same
   content hash. `live_on_tags` stores the new annotations but returns before
   rendering when `previous.hash == params.hash and state.rendered[buf] ~= nil`.
   The explicit refresh in `config_ops` sends `didChange` with the same text and
   `changedtick`, producing the same hash and the same early return. Server-side
   analysis changes, but marks remain stale until the buffer text changes, so
   add/remove appears not to work in the editor.

   Fix: force a re-render on config mutation (clear `state.rendered[buf]` before
   the request or render the returned tags directly), or make `live_on_tags`
   detect changed annotation payloads/config revision rather than using only the
   source hash. Add a headless test that publishes different annotations with an
   unchanged hash and asserts extmarks update.

5. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:144-171,278-282`
   — republished documents are written to the mutating client's writer even
   though `docs` is global.**

   `republishOpenDocs(writer)` iterates every open document and publishes it on
   the socket that sent the mutation. With two clients, the client that did not
   mutate receives no update for its open document, and the mutating client may
   receive tags for documents it does not own. The global `docs` map also means
   one client's `didOpen`/`didClose` can overwrite another's state for the same
   URI.

   Fix: keep the owning connection/writer with each `DocState` and republish each
   document to its owner (or broadcast to all connected writers); add a
   two-client test that mutates config on one connection and asserts the other
   receives refreshed tags.

6. **`nvim-renderer/lua/cint/transport/live.lua:260-276` with
   `nvim-renderer/lua/cint/ops/live_ops.lua:36-60`
   — `on_ready` still fires independently of the initialize response, so the
   config cache can be empty when the user first runs a config command.**

   The request now correlates the initialize result and caches descriptors, but
   `on_ready` is scheduled immediately after sending the request. `live_ready`
   becomes true and documents are opened before `config_on_initialize` has run.
   A `:CintConfigAdd` immediately after connect sees no cached packs and reports
   "no string config fields", and a reconnect can briefly expose the previous
   root's cached descriptors.

   Fix: run `on_initialize` before `on_ready` (or gate on the initialize
   response), and clear the config cache when connecting to a different root.
   The live transport spec should assert that descriptors are populated when
   `on_ready` fires, not merely that both callbacks eventually run.

7. **`analysis-engine/core/host/src/test/kotlin/dev/cint/host/PackConfigOverlayTest.kt`,
   `analysis-engine/core/host/src/test/kotlin/dev/cint/host/HostPipelineTest.kt`,
   `analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/LiveServerConfigTest.kt`,
   `nvim-renderer/tests/config_spec.lua` — the tests pin helper behavior and one
   happy path, not the stated contract's hard cases.**

   There is no test that pack defaults survive an append, that a scalar project
   value is unioned, that a malformed overlay is rejected without being
   overwritten, that a fresh `HostPipeline` (daemon restart) sees the persisted
   overlay, that an open document receives changed tags after a mutation, that
   same-hash tags are re-rendered, or that a second client is notified. The nvim
   spec stubs `live.request` and sets the overlay cache by hand, so it cannot
   catch the stale-render path. Add these cases, at minimum an end-to-end open
   document + append + republish assertion and a restart test using a second
   pipeline on the same root.

### Concurs / Dissents

No prior round exists for this ticket. I concur that the descriptor additions are
additive at `CINT_API_VERSION = 4` (default `configSchema()` plus the
`SingleIntentPack` override), that `LoadedRulePack` preserves the schema captured
before wrapping, that pack/rule metadata and language scoping are serialized in
the shape the client consumes, and that the server validates pack, language,
field existence, field kind, and language scope before writing. I also concur
that the protocol request correlation and the picker's language/kind filtering
and rule/intent labelling work on the happy path. I dissent from the additive
semantics being complete (findings 1-2), the refresh path being functional
(finding 4), and the multi-client republish (finding 5).

### Residual observations (not filed)

- `configDescriptor` advertises language-scoped fields for packs whose
  `languages` is empty (meaning "all" elsewhere in the host), but
  `resolveConfigField` requires `descriptor.languages` to contain the requested
  language; such a pack would be advertised and then rejected. No bundled pack
  currently has empty languages.
- Descriptor fields are keyed only by field key, so a pack with the same key in
  both PACK and LANGUAGE scope produces indistinguishable picker rows and
  `resolveConfigField` picks the first. No bundled pack does this today.
- The ticket's Docs scope (overlay file and live protocol additions) is not
  reflected in `docs/` or `nvim-renderer/README.md` yet; only the task index was
  updated.
- The worktree was reviewed as an unstaged/untracked handover; no build, test,
  lint, or formatter command was run by this role, per `docs/workflow.md`.

## Round 2

Verdict: Round-1 findings 1-6 are addressed in the implementation, and finding 7
is mostly addressed. The additive path now applies the overlay after project
config using field-aware `configureAdditive`, so defaults survive; malformed
overlays are reported, refused, and preserved with atomic writes; publishes carry
a config revision that forces same-hash re-renders; documents are tracked per
connection and republished to each owner; and `on_ready` is gated on the
initialize result. Two new correctness issues remain: the config revision can be
sampled after an analysis was produced from the previous config, and the
scalar/list union test only asserts that *some* logging hit exists, so it does
not actually pin the union in either direction.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:341-357`
   — `configRevision` is read after the analysis lock is released, so a publish
   can pair annotations from the old config with the new revision and cause the
   client to skip the correct republish.**

   `publish` computes `analysis` inside `synchronized(this)` but reads
   `configRevision` afterwards. Consider a `didChange` from client A that
   analyzes the old config, then a config mutation from client B that reloads
   packs, increments the revision, and republishes new annotations, then A writes
   its old annotations with the new revision. On the client both publishes have
   the same document hash and revision; whichever write lands second wins, and if
   the stale one lands second `live_on_tags` skips it only when the correct one
   was already rendered, or renders it when B's republish came first. The
   revision is intended to make config changes observable, but it is neither tied
   to the analysis snapshot it describes nor ordered against concurrent writes.

   Fix: serialize the whole publish (analysis, revision snapshot, and write) with
   config mutations so a later revision can never be written by an earlier
   analysis; alternatively carry a per-analysis revision and have the client
   ignore same-hash publishes whose revision is not strictly newer (combined with
   a revision captured under the analysis lock). Add a concurrent
   didChange/config-mutation test that asserts the last rendered payload is the
   post-mutation analysis.

2. **`analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/LiveServerConfigTest.kt:110-149`
   — the scalar/list union test does not pin that both values survive.**

   Both directions assert `analyzeForTest(...).any { "LOGGING" in it.intents }`
   over a source containing both `log.audit` and `log.trace`. A regression back
   to replace semantics (only the overlay value, or only the project value)
   still leaves at least one LOGGING hit and the test passes. This is the exact
   behavior round-1 finding 2 asked to pin.

   Fix: assert that both `audit` and `trace` are tagged (for example by checking
   the two call spans or distinct annotation texts) for scalar-project/list-overlay
   and list-project/scalar-overlay. Add a direct additive `STRING_LIST` schema
   assertion alongside the existing `STRINGS`/`STRING_SETS` one so the list field
   kind is covered by unit tests too.

### Concurs / Dissents

- Concurs with finding 1 being addressed: the overlay is now passed separately
  to `configureAdditive`, additive field decoders union into the current
  (post-project/default) value, and `LiveServerConfigTest` asserts that adding
  `audit` keeps `log.info` detectable before and after a fresh pipeline restart.
- Concurs with finding 2's implementation: project scalar/list values and overlay
  list/scalar values both flow through typed decoding plus the additive setter.
  Dissents only on the test pinning, as finding 2 above.
- Concurs with finding 3: `PackConfigOverlay.read` distinguishes absent from
  invalid, invalid YAML and non-map roots are reported and preserved, mutations
  refuse via `validState`, and writes use a temp file plus atomic move. The host
  test and the protocol-level invalid-overlay test cover the refusal path.
- Concurs with finding 4: `publishTags` carries `revision`, `live_on_tags`
  re-renders when it changes even with the same hash, and the nvim spec exercises
  a same-hash revision change. The new race in finding 1 is the remaining gap.
- Concurs with finding 5: `docs` is now per-writer, disconnect cleanup removes
  the writer's documents, republish iterates every connection, and the server
  test asserts both connections receive the publish. The write path is
  serialized per writer.
- Concurs with finding 6: `on_ready` runs only after the initialize response and
  `on_initialize`, the live spec asserts descriptors are cached when ready
  fires, and `live_connect` clears config state when the root changes.
- Concurs that finding 7's other hard cases are now present and meaningful:
  default survival, daemon restart (fresh pipeline on the same root), malformed
  overlay, cross-client republish, revision re-render, protocol-1 gating, and
  descriptor capture across a plain configured pack. Dissents on the scalar/list
  assertion only.

### Residual observations (not filed)

- The empty-`languages` inconsistency from round 1 remains: descriptor language
  fields for a pack with no declared languages advertise `languages = []` (which
  the client treats as all languages), but `resolveConfigField` still requires a
  concrete match in `descriptor.languages` and would reject every mutation. No
  bundled pack has empty languages.
- The duplicate-field-key ambiguity remains: descriptors and
  `resolveConfigField` still key fields only by `key`, so a pack with the same
  key at PACK and LANGUAGE scope would produce indistinguishable choices and a
  first-match target.
- The overlay now bypasses `BundledRulePackConfigResolver` (it is applied after
  project config is resolved). Overlay keys that use the legacy
  `error-handling-<language>` ids are therefore reported as unknown rather than
  migrated; current client writes use the canonical pack id, so this only affects
  hand-written overlays.
- Overlay writes remain coordinated only within one server process. Two daemons
  racing on the same root (for example two editors starting before the socket
  exists) would still be last-writer-wins even though each individual write is
  atomic.
- The overlay remains config-only: it reaches packs only through
  `configureAdditive` and does not feed annotations, provenance, confidence,
  evidence, or magic comments.
- No build, test, lint, or formatter command was run by this role; the handover
  is assumed green per `docs/workflow.md`.

## Round 3

Verdict: Round-2 findings are addressed. The publish path now holds the config
lock across analysis, revision sampling, and the write, and the new concurrent
test pins the final post-mutation payload; both scalar/list directions assert
both surviving lines and `STRING_LIST` has a dedicated additive test. The
opt-in additive path is explicit and covered, and the server attempts rollback on
reload failure. Two new issues remain in the rollback and lock-scope work: a
pre-existing overlay failure can make a later mutation report success without
being applied, and a slow client can now block the whole server because the
global config lock is held through the socket write/flush.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:258-288`
   — rollback compares failure messages with the pre-mutation set, so a target
   pack that already had the same overlay failure is not rolled back and the
   mutation is reported as applied when the overlay was ignored.**

   `previousFailures` collects every failure message for the pack, and the
   post-reload failure is ignored when its message is already in that set. For a
   non-opt-in pack with a pre-existing hand-written overlay, the loader reports
   `does not support additive config` on every reload, returns the
   project-configured pack (so `targetMissing` is false), and the same message is
   filtered out. The new value is written, `configRevision` is bumped, and the
   client is told the append succeeded even though analysis still uses only the
   project config. The current rollback test starts from no overlay, so it never
   exercises this case.

   Fix: decide rollback from whether the candidate overlay was actually applied
   to the target pack, not from message diffs. For example, reject/rollback when
   the reloaded target still has any `UNSUPPORTED_PACK_OVERLAY` or
   `INVALID_PACK_CONFIG` failure (including one present before the mutation), or
   compare the target's effective config/descriptor before and after. Add a test
   that pre-seeds an unsupported overlay, mutates it, and asserts an error plus
   an unchanged file and project-configured behavior.

2. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:371-396,489-498`
   — the global server lock is now held through JSON serialization and a
   blocking socket write/flush, so one slow or non-reading client stalls every
   publish and config mutation.**

   `publish` keeps `synchronized(this)` while calling `write`, and `write` flushes
   the socket under `synchronized(writer)`. If a client stops draining its socket,
   the flush blocks while holding the global lock; analysis, republish, and
   `configAppend`/`configRemove` for all other clients then wait indefinitely.
   The lock ordering itself is safe (`this` before `writer`; no path takes
   `writer` then `this`, and `republishOpenDocs` does not hold `this` across its
   loop), so this is head-of-line blocking rather than a deadlock, but it is a
   new availability regression from moving the write inside the lock.

   Fix: compute the analysis and its revision under `synchronized(this)`, then
   release the lock before serializing/writing, and preserve ordering per writer
   with a queue or a monotonically increasing publish sequence that the client
   uses to discard stale payloads. Do not flush an unbounded/blocking socket
   while holding the global config lock.

### Concurs / Dissents

- Concurs with Round-2 finding 1 being addressed: `publish` holds
  `synchronized(this)` across analysis, revision sampling, and the write, while
  `applyConfigMutation` holds the same lock across reload and revision bump, so
  the revision always matches the analyzed config. The concurrent
  didChange/append test asserts the last publish has revision 1 and both the
  default `info` and appended `audit` lines.
- Concurs with Round-2 finding 2 being addressed: both scalar/list directions
  now assert the two distinct logging lines, and
  `additiveStringListUnionsAndDeduplicates` covers list and scalar overlay input
  for `STRING_LIST` including deduplication.
- Concurs with the opt-in additive path: the default `configureAdditive` rejects
  instead of replacing, the loader keeps the project-configured pack and reports
  `UNSUPPORTED_PACK_OVERLAY`, plain-`RulePack` configure results are handled
  explicitly, and the host tests pin both the legacy-configurable and plain-pack
  cases. Dissents on the pre-existing-failure case in finding 1.
- Concurs with the rollback mechanics for the fresh-overlay case: the file is
  restored/deleted, the pipeline is reloaded again, descriptors and project
  behavior return, and no revision is bumped for the failed mutation.
- Concurs that no deadlock was introduced by the nested `synchronized` scopes;
  the only lock-scope problem found is the blocking socket flush in finding 2.

### Residual observations (not filed)

- Config mutation is atomic for the final state but not for observers:
  `config/read`, `initialize`, and `configDescriptors()` do not take the server
  lock, so another client can observe the candidate overlay or the interim
  post-reload descriptor set during the window before a rollback. Final state is
  correct and tested.
- Non-opt-in packs still advertise their schema fields through
  `configDescriptor`, so the picker can offer a field whose append will always
  fail; the failure is now reported and rolled back, but the descriptor has no
  "additive supported" flag.
- The round-1 residuals remain otherwise unchanged: empty-`languages` descriptor
  mismatch, duplicate field-key ambiguity, overlay bypassing the legacy config
  resolver, multi-daemon write coordination, and the overlay staying config-only.
- No build, test, lint, or formatter command was run by this role; the handover
  is assumed green per `docs/workflow.md`.

## Round 4

Verdict: Both Round-3 findings are addressed in the implementation. Any failure
for the target pack now forces a rollback, including a pre-existing unsupported
overlay, and the mutation is rejected every time with the seeded overlay and
project config preserved. Analysis and revision sampling remain atomic under the
server lock, writes now happen outside it with per-writer synchronization, and
the client discards lower-revision publishes; the lock-narrowing does not
introduce a deadlock. One test problem remains: the server-side concurrent test
assumes the last publish written to the StringWriter is the post-mutation one,
but with writes outside the lock an older-revision publish may legally be written
after the newer one and be ignored only by the client guard.

### Findings

1. **`analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/LiveServerConfigTest.kt:430-488`
   — the concurrent test asserts the *last* server output is revision 1, which is
   no longer guaranteed after the lock narrowing.**

   The test starts a `didChange` and a config append together and then takes
   `.last { publishTags }` from the first client's output, expecting revision 1
   with both `info` and `audit` lines. If the `didChange` thread captures the
   pre-mutation revision 0, releases the lock, and is then delayed, the append
   republish can acquire the writer first and write revision 1; the delayed
   `didChange` write then lands last with revision 0. That is a valid server
   order because the client monotonic guard is responsible for dropping the
   stale revision, but the server-side test would fail nondeterministically.
   The test therefore does not reliably pin the final post-mutation payload it
   was added for.

   Fix: collect all publish lines and assert on the highest-revision publish (or
   feed them through the same monotonic rule as `live_on_tags`), rather than
   assuming the last line is the newest. Keep the existing client-side lower-
   revision test, and optionally add a deterministic server test that forces an
   older revision to be written after a newer one and verifies the client guard
   (not the raw order) selects the newer payload.

### Concurs / Dissents

- Concurs with Round-3 finding 1 being addressed: `applyConfigMutation` now
  rolls back on any target-pack failure regardless of prior failures, and the
  new pre-seeded-overlay test shows repeated mutations fail, the seeded file is
  preserved byte-for-byte, the project-configured pack remains, and descriptors
  still load. Rollback does not bump the revision or republish, so tags stay
  consistent with the restored config.
- Concurs with Round-3 finding 2 being addressed: analysis and revision are
  captured together inside `synchronized(this)` and all serialization/socket
  writes happen after release, with `write` synchronized per writer. Lock order
  is now `this` (analysis only) then later `writer`, with no path taking them in
  reverse, so I found no deadlock or write-under-global-lock regression.
- Concurs that the client guard is sufficient for the overlay contract: a
  lower-revision publish is dropped before it can update `live_tags`, while a
  same-hash republish with a newer revision re-renders. Equal or higher revisions
  cannot carry an older config because revision and analysis are sampled
  atomically; equal-revision differences would reflect analysis nondeterminism,
  not overlay staleness, and the version/hash checks separately reject stale
  document versions.
- Concurs that republish still reaches every connected client through the
  per-writer document maps, and a failed/rolled-back mutation performs no
  republish, leaving the previously valid tags in place.
- Concurs that the docs now state the opt-in `configureAdditive` contract,
  `UNSUPPORTED_PACK_OVERLAY`, rollback/reload behavior, protocol revision
  handling, and the config-only scope accurately.
- Dissents only on the concurrency test's final-line assumption in finding 1.

### Residual observations (not filed)

- Config mutation is still not atomic for observers outside the server lock:
  `config/read`, `initialize`, and `configDescriptors()` can see the candidate
  overlay or the interim post-reload descriptor set during the window before a
  rollback. Final state is correct.
- Non-opt-in packs still advertise schema fields through `configDescriptor`, so
  the picker can offer a field whose append always fails and rolls back; there is
  no "additive supported" flag in the descriptor.
- The earlier residuals remain otherwise unchanged: empty-`languages` descriptor
  mismatch, duplicate field-key ambiguity, overlay bypassing the legacy config
  resolver, and multi-daemon write coordination. The overlay remains config-only.
- No build, test, lint, or formatter command was run by this role; the handover
  is assumed green per `docs/workflow.md`.

## Round 5

Verdict: The Round-4 finding is addressed. The concurrent server test now
collects every published payload, asserts that the highest revision is 1, and
checks the `info` and `audit` logging lines on a publish at that revision instead
of assuming the last raw write is the newest. This still pins the post-mutation
outcome (a failed mutation would leave the highest revision at 0, and a
post-mutation publish missing either line would fail) without relying on write
order. I reviewed the surrounding server, host, API, and renderer changes again
and found no new finding; the remaining residuals from the earlier rounds are
unchanged and are not blockers for this ticket.

### Findings

- None.

### Concurs / Dissents

- Concurs with Round-4 finding 1 being addressed: the test uses
  `maxOf { revision }` and selects a publish at that revision, so a legal
  older-revision write arriving last can no longer make the test flaky. Both the
  revision and the two expected logging lines are still asserted.
- Concurs that coverage is not weakened: the client-side lower-revision test
  still exercises the monotonic guard, and the server test still verifies the
  concurrent mutation produces revision 1 with the appended symbol while
  preserving the default symbol.
- Concurs with all prior fix validations: rollback fails consistently for
  already-failing/non-opt-in packs and preserves the overlay and project
  behavior; the lock narrowing keeps analysis and revision sampling atomic,
  writes outside the lock, and does not deadlock; republish reaches each client
  and failed mutations do not republish; docs match the opt-in additive contract
  and error/rollback behavior.

### Residual observations (not filed)

- Config mutation remains non-atomic for lock-free observers (`config/read`,
  `initialize`, and descriptor reads can see the interim state before a
  rollback), as noted in Round 4.
- Non-opt-in packs still advertise schema fields without an additive-supported
  flag, and the earlier empty-`languages`, duplicate-field-key, legacy-resolver,
  and multi-daemon residuals remain.
- No build, test, lint, or formatter command was run by this role; the handover
  is assumed green per `docs/workflow.md`.

The loop can close.
