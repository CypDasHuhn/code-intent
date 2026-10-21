# API-fidelity review — ticket 035 (protocolized config overlay)

## Round 1

Verdict: The slice is directionally right. The new `api` surface is additive at
v4: `ConfigSchemaProvider` has a real JVM default method, `ConfigurableRulePack`
only gains that default supertype, the existing `ConfigField` factories are
unchanged, and the descriptor types project `ConfigSchema` without giving `core`
a host or wire dependency. Overlay writes stay config-only, `initialize` adds a
`config` field and bumps the protocol to 2 without changing the existing
`cint/publishTags` envelope, and the new config methods share one response shape
and the existing JSON-RPC error code. The remaining gaps are in propagation and
contract recording: the client's content-hash render guard defeats the new
republish path, descriptor schema can be lost across configuration, config
changes are not broadcast to other clients, a new client cannot quietly coexist
with a reusable protocol-1 daemon, and the docs/wire shape were not updated.

### Findings

1. **`nvim-renderer/lua/cint/ops/live_ops.lua:230` with
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:278-282,319`
   — config republishes are discarded by the content-hash render guard.**

   `publishTags.params.hash` is `contentHash(doc.text)`, but a config
   append/remove does not change buffer text. `live_on_tags` stores the new
   annotations and then returns when `previous.hash == params.hash` and the
   buffer was already rendered. The server's `republishOpenDocs` therefore
   updates `state.live_tags` but leaves the old extmarks on screen; the client's
   follow-up `live_request_analysis` in `config_ops.lua:87-92` republishes the
   same content hash and is skipped for the same reason. The user-visible result
   is that `:CintConfigAdd`/`:CintConfigRemove` do not change the painting until
   the text changes or the buffer is re-opened.

   Suggested fix: key the re-render decision on the analysis result (for example
   compare annotations, or add a server-owned analysis/config revision to the
   publish payload and cache it) rather than on the source hash alone, and add a
   headless test with identical text but changed annotations.

2. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/rulepack/RulePackValidation.kt:113-118`
   with `RulePackDiscovery.kt:44-45` — descriptor metadata is captured only
   from the configured pack.**

   `validateRulePack` reads `configSchema()` from the object returned by
   `configure`. `ConfigurableRulePack.configure` is declared to return a plain
   `RulePack`, so a valid pack whose configured instance does not also re-expose
   `ConfigSchemaProvider` loses its fields even though the original provider
   described them. The bundled `SingleIntentPack` path happens to preserve the
   schema, so the gap is unpinned. `runCatching` also silently treats a throwing
   schema as "no descriptor" while other pack metadata failures are reported.

   Suggested fix: capture the schema from `provider.pack` before configuration
   (schema is not changed by config values) and pass it into the loaded wrapper,
   or require/normalize `configure` to return a `ConfigurableRulePack` and test
   that contract. Add a fixture whose `configure` returns a plain `RulePack` and
   assert the descriptor still contains its fields.

3. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:278-282`
   — config mutation republishes only to the requesting connection.**

   `docs` is server-wide, but `republishOpenDocs(writer)` writes
   `cint/publishTags` only to the writer of the `cint/config/append|remove`
   request. The overlay and reload are global, so any other connected client
   with open documents keeps rendering tags produced under the old pack config
   until its next edit. The daemon is explicitly reusable and per-project, so
   this is not only a theoretical second-client case.

   Suggested fix: track connected writers/clients and broadcast the republish (or
   at least all open documents for the changed project), or record and test an
   explicit single-client contract.

4. **`nvim-renderer/lua/cint/transport/live.lua:260` with
   `nvim-renderer/lua/cint/ops/config_ops.lua:125-133` and
   `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:127-137`
   — there is no protocol negotiation, so a new client against an already
   running protocol-1 daemon takes an avoidable error path.**

   The client sends `protocol = 2` unconditionally. The server ignores the
   requested value and always answers with its own version. If the reusable
   daemon predates this ticket, `initialize` has no `config`; the client then
   calls `cint/config/read`, which the old daemon rejects as an unknown method
   and the client surfaces as a warning. Live tagging still works, but the
   fallback is noisy and there is no explicit compatibility gate or test.

   Suggested fix: gate config consumption on `result.protocol >= 2`, do not
   issue `config/read` when the initialize result proves protocol 1, and decide
   whether the server should reject a client protocol it cannot serve. Add a
   fake-daemon test with a protocol-1 initialize result.

5. **`docs/engine.md:173-187`, `nvim-renderer/README.md:250-273`,
   `docs/architecture.md:239-242`, and `docs/notes.md:399-402` — the protocol
   and overlay docs now disagree with the implementation.**

   The ticket requires documenting the overlay file and live protocol
   additions, but only `docs/tasks/README.md` changed. The docs still list only
   `didOpen`/`didChange`/`didClose`, `cint/publishTags`, and `cint/analyze`; they
   do not mention protocol 2, the `initialize.config` object, `cint/config/read`,
   `cint/config/append`, `cint/config/remove`, the `{packs, overlay}` response,
   the overlay file, or `:CintConfigAdd`/`:CintConfigRemove`. Architecture §9
   still says reload is daemon restart and "No hot-swap machinery is built,"
   while `HostPipeline.reload()` and server-owned reload now exist. Notes §20
   still says detection config is not auto-discovered without mentioning the
   auto-discovered overlay. The descriptor wire tokens (`kind`/`scope` as
   uppercase Kotlin enum names, `languages` empty for pack scope) are also
   implicit Jackson output; the server test only pins `scope`, not the full
   field shape.

   Suggested fix: update the live-server/socket-protocol sections with the exact
   request params, response shape, overlay layout, error behavior, and command
   names; reconcile the architecture reload statement; and add an explicit
   serializer or exact-JSON assertion plus docs for the descriptor `kind`/`scope`
   tokens. Record any non-automatable live restart check in `manual-test.md`.

6. **`analysis-engine/core/api/src/main/kotlin/dev/cint/api/Config.kt:3-71`
   with `Contract.kt:250-253`, `Model.kt:5-12`, and
   `analysis-engine/core/api/src/test/kotlin/dev/cint/api/ConfigSupportTest.kt:302-338`
   — the new frozen v4 surface is additive but has no contract note or
   compatibility pin.**

   The additions are optional and do not require a `CINT_API_VERSION` bump, but
   nothing in `api` records that decision (the v4 KDoc still only mentions the
   evidence removal), and no test pins the important additive case: a
   `ConfigurableRulePack` that does not override `configSchema()` must still
   load and yield an empty descriptor. The existing contract tests exercise only
   the new descriptor shape and the v4 constant, so a future change that makes
   `ConfigSchemaProvider` mandatory would not fail a test at this boundary.

   Suggested fix: add a v4 contract note naming the additive descriptor surface
   and its no-bump rationale, and add a contract test for an old-style
   `ConfigurableRulePack` (default `configSchema()` is null, descriptor still
   carries `packId`/`rules`/`languages`). Keep the `CINT_API_VERSION` value at 4.

### Concurs / Dissents

- Concurs: adding `ConfigSchemaProvider`, `ConfigSchema.describe()`,
  `RulePack.configDescriptor()`, and the descriptor data classes does not
  require an API-version bump. `ConfigSchemaProvider.configSchema()` is a JVM
  default method in the built class, so existing `ConfigurableRulePack`
  implementations remain source- and binary-compatible; `ConfigField`'s
  constructor is `internal` and the public factory signatures are unchanged.
- Concurs: the descriptor API belongs in `api` for this design. It is a
  projection of api-owned `ConfigSchema`/`ConfigField`, and placing it in
  `core` would either invert the dependency or duplicate the schema types. The
  loader wrapper forwarding the schema is the right boundary for standard
  packs.
- Concurs: the overlay remains config-only. No path touches source annotations,
  contributions, provenance, evidence, confidence, `user-annotations.json`, or
  introduces reserved intent/contributor names.
- Concurs: `initialize` keeps the existing `protocol`/`root`/`languages`
  fields and adds `config`; existing clients that ignore the result are
  unaffected. `cint/config/read|append|remove` return the same nested
  `{packs, overlay}` object, use camelCase `packId`/`language`/`field`/`value`,
  and report failures through the existing `{code: -32000, message}` JSON-RPC
  error shape.
- Concurs: the HTTP artifact is unchanged, matching the ticket's "live protocol
  first" scope; the artifact does not need descriptor fields.
- Dissents: findings 1-6.

### Residuals (not filed)

- A corrupt overlay YAML is logged to stderr and treated as empty; the next
  append then overwrites it. `cint/config/read` cannot distinguish "no overlay"
  from "unreadable overlay".
- `configAppend` writes the overlay before reloading; if the merged config is
  rejected by a pack, the invalid overlay remains on disk and the response still
  reports success with the pack missing from the new descriptors.
- `ConfigFieldKind.STRING_SETS` is not editable by the picker even though the
  ticket says string-set/list fields; the bundled packs currently use only
  `STRINGS`/`STRING_LIST`, so the gap is latent.
- Descriptor flattening handles one nested `LanguageConfig` level; a future
  nested schema would be dropped without an error.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.

## Round 2

Verdict: Round-1 findings 1-5 are closed, and finding 6 is closed for the
descriptor surface: `publishTags` now carries a config revision that the client
uses with the hash; the loader captures the schema before configure, including
for a plain-`RulePack` configured result; config mutations broadcast to every
client's open documents; the client gates config features on the returned
protocol; the docs and m011 match the protocol; and the v4 note plus the
legacy-provider test record the additive descriptor decision. The code fixes
also added a public `configureAdditive`/`decodeAdditive` path. That path is
binary/source-additive (the method is a JVM default), but its default behavior
does not provide the additive overlay semantics the docs and acceptance clause
promise for existing packs, and a configured result that is only a plain
`RulePack` cannot accept an overlay at all. One new finding remains, so the loop
cannot close yet.

### Findings

7. **`analysis-engine/core/api/src/main/kotlin/dev/cint/api/Config.kt:14-21`
   with `RulePackDiscovery.kt:44-48`, `RulePackSupport.kt:222-245`, and
   `Model.kt:8-15` — the new default additive path is not additive for
   non-opt-in packs, and can drop a pack whose `configure` returns a plain
   `RulePack`.**

   `configureAdditive(values)` defaults to `configure(values)` and is called on
   the already project-configured pack with only the overlay values. A valid
   `ConfigurableRulePack` that uses the public schema/language helpers without
   overriding `configureAdditive` therefore re-applies the overlay with
   replace-only semantics; project values are lost instead of unioned. The test
   fixture in `HostPipelineTest` that deliberately returns
   `PlainConfiguredRulePack` loses the whole pack if an overlay section targets
   it, because `configureRulePackAdditive` sees a non-`ConfigurableRulePack` and
   reports `INVALID_PACK_CONFIG`. Both contradict `docs/engine.md:171-174` and
   the ticket's unconditional "overlay merge is additive" acceptance clause. The
   v4 contract note names the descriptor surface but not the new public
   `configureAdditive`/`ConfigSchema.decodeAdditive` surface or its default
   semantics.

   Suggested fix: preserve additive behavior for every configurable pack, for
   example by applying the schema-aware overlay to the effective project values
   before the final configure, or by making the default path merge rather than
   replace. If opt-in is intentional, reject or explicitly report an overlay for
   a pack that has not opted in instead of silently replacing values, document
   the opt-in requirement in `Model`/`docs/engine.md`/the ticket outcome, and add
   a test with a legacy `ConfigurableRulePack` plus project config and overlay
   (including a plain-`RulePack` configure result). Extend the v4 contract note
   to name `configureAdditive`/`decodeAdditive`.

### Concurs / Dissents

- Concurs finding 1: server publishes `revision` (incremented on config
  append/remove) and `live_on_tags` re-renders when the hash is unchanged but the
  revision changed; `config_spec.lua` pins the same-hash/new-revision case.
- Concurs finding 2: `configSchemaOf(provider.pack)` captures the schema before
  configuration and `validateRulePack` forwards it to `LoadedRulePack`, so a
  plain configured result keeps its descriptor fields. The
  `descriptorSchemaIsCapturedBeforeConfigureReturnsAPlainPack` test covers it.
- Concurs finding 3: documents are tracked per writer, removed on client close,
  and `republishOpenDocs` publishes to every writer; the two-client test asserts
  both connections receive `cint/publishTags` with the new revision.
- Concurs finding 4: `config_on_initialize` records the returned protocol,
  clears caches below 2, and `config_read`/append/remove/add are gated on
  protocol 2. The protocol-1 test asserts no request and no cache; docs now state
  that the server returns its own version and does not negotiate.
- Concurs finding 5: `docs/engine.md`, `nvim-renderer/README.md`,
  `docs/architecture.md`, `docs/notes.md`, and `docs/manual-test.md` (m011) now
  document `initialize.config`, descriptors, overlay layout and additive rules,
  the config methods and errors, the revision, both commands, and the in-place
  pack reload.
- Concurs finding 6 for its original scope: the v4 KDoc records the additive
  descriptor surface, `LegacyConfigurablePack` pins the default empty descriptor,
  and `CINT_API_VERSION` remains 4. The note does not cover the newly added
  `configureAdditive`/`decodeAdditive` surface; that omission is folded into
  finding 7.
- Concurs: the revision token is additive in `publishTags`; old clients ignore
  it and the new client treats a missing value as revision 0. The
  `configureAdditive` default is a real JVM default method, so precompiled
  `ConfigurableRulePack` implementations remain binary-compatible; only its
  semantics are at issue.
- Dissents: finding 7.

### Residuals (not filed)

- A syntactically valid overlay that a pack rejects after reload is still left
  on disk while the mutation response reports success; the pack then disappears
  from the next descriptor payload.
- `ConfigField.custom` falls back to replace semantics in the additive path, and
  `STRING_SETS` remains picker-ineligible; both are latent for the bundled packs.
- `configRevision` resets per daemon process; this is safe today because a
  disconnect/reconnect clears cached tags, but the lifetime is not documented.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`.

## Round 3

Verdict: Round-2 finding 7 is closed. `configureAdditive` is now explicitly
opt-in: the default throws instead of replacing values, `Model`'s v4 note names
the additive surface, the loader reports a distinct
`UNSUPPORTED_PACK_OVERLAY` and keeps the project-configured pack, and plain
`RulePack` configured results are handled the same way. The server mutation path
now rolls back and errors for an overlay the target pack rejects, including the
legacy/default-pack case. One narrow rollback-detection gap remains: if the
target pack was already failing with the same overlay-unsupported message before
the mutation, the message-difference heuristic treats the failure as pre-existing
and returns success even though the new overlay value was not applied. One
finding remains; the docs opt-in note is a separate scribe follow-up.

### Findings

8. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:258-289`
   — a mutation can return success when a pre-existing unsupported overlay makes
   the new value inapplicable.**

   `applyConfigMutation` snapshots `previousFailures` by message, then only
   rolls back when the reload produces a target-pack failure whose message is not
   in that set. If the target pack already loaded with `UNSUPPORTED_PACK_OVERLAY`
   (for example a hand-written overlay for a non-opt-in pack, which the loader
   reports but still keeps), appending another value produces the same stable
   message. `failure` is therefore null, `targetMissing` is false, the revision is
   incremented, and the command responds with `{packs, overlay}` success even
   though `configureAdditive` rejected the overlay and the values were not
   applied. This violates the round-3 requirement that no success response be
   returned when the overlay cannot be applied.

   Suggested fix: do not diff target failures by message text. After reload,
   require the target pack to be present and free of target-scoped failures (or
   refuse the mutation up front when the target already has any failure). Add a
   test that seeds an unsupported overlay for a non-opt-in pack, then appends to
   it and asserts a `{code: -32000}` error, an unchanged overlay, and an unchanged
   revision.

### Concurs / Dissents

- Concurs finding 7: `ConfigurableRulePack.configureAdditive` is a JVM default
  that throws `UnsupportedOperationException`; `SingleIntentPack` and the
  language-schema path override it with `decodeAdditive`. The loader catches the
  default/unsupported case, reports `UNSUPPORTED_PACK_OVERLAY`, and returns the
  project-configured pack rather than dropping it. A configured result that is a
  plain `RulePack` is handled the same way with its own message.
  `overlayOnLegacyPackIsReportedWithoutLosingThePackOrProjectConfig`,
  `overlayOnPlainConfiguredResultIsReportedWithoutDroppingThePack`, and
  `clientConfigMutationRollsBackWhenTargetPackRejectsOverlay` cover the cases.
- Concurs: `UNSUPPORTED_PACK_OVERLAY` is a coherent, pack-scoped, non-fatal
  loader kind. Unlike `INVALID_PACK_CONFIG`, it means "pack kept, overlay
  ignored", which matches the loader returning the configured pack and the
  server refusing the mutation.
- Concurs: the normal rollback path is atomic for the overlay file (`restore`
  uses the same temp-file/atomic-move write), leaves `configRevision` unchanged,
  reloads to the restored state, and reports through the existing
  `{code: -32000, message}` error shape. The host and server tests assert the
  overlay disappears and descriptors/project values remain.
- Concurs: `CINT_API_VERSION` stays at 4 soundly. `configureAdditive` and
  `ConfigSchema.decodeAdditive`/`decodeAdditiveResult` are additive defaults,
  `Model` records the opt-in contract, and `UNSUPPORTED_PACK_OVERLAY` lives in
  `core`, not the frozen `api` surface.
- Concurs: no new protocol compatibility issue. The revision token and config
  method shapes are unchanged from round 2; the failure kind is internal loader
  state, not a new wire field.
- Dissents: finding 8.

### Docs follow-up (not a source finding)

- `docs/engine.md:158-175`, `docs/architecture.md:207-210`,
  `nvim-renderer/README.md:290-301`, and the ticket's acceptance narrative still
  describe the overlay as unconditionally additive. Additive overlay support is
  now explicitly opt-in through `configureAdditive`; non-opt-in packs keep their
  project values and the server rejects the mutation. A scribe should state that
  caveat (and the `UNSUPPORTED_PACK_OVERLAY` behavior) in those sections and the
  ticket outcome. I am not filing the prose mismatch as a source finding.

### Residuals (not filed)

- The rollback comparison also ignores a new target failure whose message is
  identical to an older one for a different reason; using failure kind/identity
  rather than message text would close both variants.
- `configRevision` still resets per daemon process; this remains safe because
  reconnect clears cached tags but is not documented.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`.

## Round 4

Verdict: Round-3 finding 8 is closed. `applyConfigMutation` now treats any
target-scoped failure after the candidate reload as a failed mutation, including
one that existed before the mutation, so a re-append to an already-unsupported
pack restores the overlay, reloads the previous state, leaves the revision
unchanged, and returns the `{code: -32000, message}` error. The narrowed lock
correctly pairs analysis with the revision it observed and moves socket writes
and republish orchestration outside the global lock; the client's per-document
monotonic revision guard preserves the same-hash config repaint while discarding
stale out-of-order publishes. The opt-in, rollback, and stale-revision behavior
is now documented, and the API remains additive at v4. No new findings; the loop
can close.

### Findings

None.

### Concurs / Dissents

- Concurs finding 8: the message-difference heuristic is gone. After the
  candidate reload, `packs.failures.firstOrNull { packId in it.packIds }` plus
  `targetMissing` triggers `PackConfigOverlay.restore` and a second reload before
  throwing, so a pre-existing `UNSUPPORTED_PACK_OVERLAY` cannot mask a new
  failure. `preExistingUnsupportedOverlayStillRejectsFurtherMutations` seeds the
  unsupported overlay, appends again, and asserts the error, the restored
  overlay, the surviving descriptor, and the unchanged project-configured pack.
- Concurs: the rollback path keeps the contract coherent. `configRevision` is
  incremented only after a successful reload with no target failure, descriptors
  are read from the restored pipeline, and the failure reaches the client through
  the existing `-32000` error object with no success payload.
- Concurs: the narrowed lock preserves the publish contract. `publish` computes
  the analysis and samples `configRevision` in the same `synchronized(this)`
  block, then serializes and writes outside it; a mutation that lands first makes
  the older publish carry a lower revision, which the client discards. The
  same-hash/config-revision repaint path, the lower-revision discard, and the
  concurrent didChange/config-mutation ordering are pinned in
  `config_spec.lua` and `concurrentDidChangeAndConfigMutationEndsWithPostMutationPublish`.
- Concurs: docs now match the code. `docs/engine.md`,
  `docs/architecture.md`, `docs/notes.md`, `nvim-renderer/README.md`, and the
  ticket notes state that additive overlay is opt-in through
  `configureAdditive`/`decodeAdditive`, that non-opt-in packs keep project/default
  values with `UNSUPPORTED_PACK_OVERLAY`, that failed mutations roll back without
  bumping the revision, and that clients discard stale lower-revision publishes.
  The round-3 docs follow-up is resolved.
- Concurs: `CINT_API_VERSION` remains 4 correctly. The new API surface is
  additive: `configureAdditive` is a JVM default (rejecting rather than
  replacing), `ConfigSchema.decodeAdditive`/`decodeAdditiveResult` are new
  methods, and `UNSUPPORTED_PACK_OVERLAY` is a `core` loader-failure kind rather
  than a wire or `api` versioned contract. `Model` records the opt-in decision.
- Concurs: no new wire or API compatibility issue. The revision token is
  additive, config method shapes are unchanged, protocol-1 clients remain gated
  out, and the loader failure kind does not appear in `publishTags` or the
  initialize/config payloads.
- Dissents: none.

### Residuals (not filed)

- Concurrent `config/read` or a second overlapping mutation can observe an
  intermediate overlay snapshot while the first mutation is between write and
  rollback; descriptors carry no values and the next read/initialize reconciles
  it, so this is not a durable wire inconsistency.
- `configRevision` still resets per daemon process; the client clears cached
  tags on disconnect/restart, and the guard also keys on root, so this remains
  safe but is not stated explicitly in the docs.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`.
