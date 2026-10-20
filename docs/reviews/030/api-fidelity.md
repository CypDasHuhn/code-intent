# API-fidelity review — ticket 030 (client-authored annotations)

## Round 1

Verdict: The vertical slice is present and the port is substantially cleaner than
the first experiment: user records are a separate `SOURCE_INTENT` contributor
with `source = "user"`, the `user:` rule namespace is reserved, the split and
HTTP request-root paths are wired, and the published provenance shape remains
evidence-free. Contract gaps remain at the user boundary: the scheduler adds new
reserved names to the v4 open-intent/contributor space without declaring them,
user records can emit reserved intent names unchecked, `nodeAnchor` is still
never populated, the artifact vocabulary omits user intents, and the renderer's
user marker does not appear under the shipped default policy.

### Findings

1. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/scheduler/RuleScheduler.kt:102-125` with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotations.kt:6-9` and `analysis-engine/core/api/src/main/kotlin/dev/cint/api/Model.kt:5-12` — new reserved names are enforced outside the frozen v4 contract.**

   The scheduler now rejects any rule id starting with `user:` and any rule
   owning `PURE`. Neither reservation is declared in the `api` module: `Model`
   defines only `UNTAGGED_INTENT` as reserved, and the open-intent contract still
   says packs may introduce intent names. A pack that was valid under v4 before
   this branch (pack id `user`, or a rule owning `PURE`) now fails to load while
   `CINT_API_VERSION` remains 4. That is a semantic breaking change hidden from
   the versioned contract.

   Fix: make the reservations part of the declared API surface (constants and
   KDoc in `api`, plus a version/documentation decision), or avoid reserving the
   bare names by namespacing the user directive (for example a user-scoped
   directive name) so existing v4 packs remain valid. Add a loader test that a
   previously valid v4 pack is not silently broken, alongside the new rejection
   tests.

2. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotations.kt:59-83` with `UserAnnotationResolver.kt:71-78` and `analysis-engine/core/core/src/main/kotlin/dev/cint/core/artifact/AnnotationJson.kt:11-15` — user records can publish reserved names as ordinary source intents.**

   `parseEntry` only requires a non-empty intent set, and the resolver copies
   those names directly into a `SOURCE_INTENT` contribution. A user record can
   therefore assert `UNTAGGED` (the contract's reserved "unclassified" marker)
   or any directive name without passing through the scheduler's reserved-name
   validation. `PURE` is also published in
   `intents` and `provenance[].intents` like a detection intent, so clients
   cannot distinguish the purity directive from a renderable open intent; it can
   affect tag/priority selection when merged with a pack annotation.

   Fix: validate user intent names against reserved names before creating the
   contribution, and represent `PURE` as a directive (reserved contributor flag
   or dedicated field) rather than an open intent, or at minimum document and
   test its published rendering semantics. Add rejection tests for
   `UNTAGGED`/other reserved names and PURE-only/PURE-merged render cases.

3. **`analysis-engine/core/api/src/main/kotlin/dev/cint/api/Model.kt:26-33,56-58` with `UserAnnotationResolver.kt:71-79` and `analysis-engine/core/core/src/main/kotlin/dev/cint/core/artifact/AnnotationJson.kt:22` — the durable anchor never bridges to `nodeAnchor`.**

   The persisted record has path, span, symbol and `contentHash`, but the
   resolved `Contribution` carries none of that identity and every user
   annotation is published with `"nodeAnchor": null`. `Contribution` has no
   anchor field even though `Annotation` has one, so there is no path from a
   persisted user record to the reserved payload anchor. The ticket asks the
   stable-anchor design to stay compatible with `NodeAnchor`; this
   implementation neither populates it nor records that user annotations remain
   outside occurrence persistence. The symbol fallback at
   `UserAnnotationResolver.kt:49-50` also accepts any descendant whose text
   equals the symbol, so the parallel anchor is not structurally stable.

   Fix: derive a stable `NodeAnchor` id from the persisted record and carry it
   through the contribution/annotation path, or explicitly record and test the
   decision that user annotations do not participate in occurrence persistence.
   Restrict symbol rebinding to declaration/function candidates when computing
   the fallback.

4. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:114-121` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/Main.kt:153-155` and `analysis-engine/hosts/cli/src/main/kotlin/dev/cint/cli/Main.kt:100` — the artifact intent vocabulary omits user-authored intents.**

   Artifact `intents` is built from `plan(...).terminal.sourceIntents` plus
   `UNTAGGED`, so a user annotation carrying `PURE` or an open name such as
   `TEAM_CUSTOM_INTENT` appears in `files[].spans[].intents` but not in the
   top-level `intents` list. Consumers that initialize their known-intent table
   or validate spans against that list see an internally inconsistent artifact.
   `HttpUserAnnotationsTest` checks the span and provenance but not this list,
   so the mismatch is unpinned.

   Fix: include resolved user intents in the artifact vocabulary for the
   analyzed files, or document that `intents` is pack vocabulary only and that
   clients must tolerate span-only names. Add an HTTP/CLI artifact test with a
   user-only intent and a merged intent.

5. **`nvim-renderer/lua/cint/render/init.lua:137-142` with `nvim-renderer/lua/cint/core/config.lua:27-40` and `nvim-renderer/tests/annotations_spec.lua:79-104` — the user marker is not part of the default renderer contract.**

   The `U` sign is only attached when `next(opts) ~= nil`. Under the shipped
   default policy a user intent with no `tags` entry resolves to `SHOW`, and
   `SHOW = false`, so the annotation produces no extmark and no visual
   distinction at all. The only render assertion configures
   `PURE -> HIGHLIGHT` in the test. When the annotation is visible, `sign_text`
   also unconditionally overwrites any handler-configured icon.

   Fix: emit the user marker independently of the meaning rule (or give
   user-authored source a documented visible default), preserve or explicitly
   document the sign-icon precedence, and test user rendering under the shipped
   default config as well as a merged pack+user annotation.

### Concurs / Dissents

- Concurs: the frozen `api` files themselves are untouched, `CINT_API_VERSION`
  is still 4, `Emit` remains source-only, and user records enter as
  `Role.SOURCE_INTENT` only. The role boundary is intact.
- Concurs: the `user:` contributor namespace is now rejected for packs and
  `PURE` is rejected as an owned intent by the scheduler; the new named errors
  and tests are coherent.
- Concurs: user contributions merge by role/byte span with pack contributions,
  deletion removes only the user provenance, and the payload keeps the
  per-contributor `provenance` shape with `source = "user"`.
- Concurs: no evidence field is reintroduced anywhere in the user path; user
  records and payloads use `note`/`createdAt` only as persistence metadata.
- Concurs: purity reaches `isAnnotatedPure` and derivative composition on both
  the normal and split paths, and explicit reload is a valid refresh strategy.
- Concurs: unknown user intent names round-trip and survive to the client, and
  the renderer keys user authorship off the published `source` tier rather than
  a rule-id prefix.
- Dissents: findings 1-5.

### Residuals (not filed as findings)

- The Lua writer (`vim.json`) and Kotlin reader (`SnakeYAML` plus hand-written
  encoding) have no shared schema version check or cross-language fixture; the
  nvim spec never feeds its file to `UserAnnotationsJson`.
- Lua span columns are byte-based while the Kotlin resolver uses `String`
  indices, so non-ASCII ranges can drift between the two layers.
- `HostPipeline.userContributionsFor` reports malformed annotation files only to
  stderr; the payload has no diagnostic for a corrupt user file.
- `annotations_spec.lua` injects decoded Lua tables through `_live_tags` rather
  than exercising the `cint/publishTags` JSON boundary, so the wire-level
  `source`/`provenance` contract for user annotations is still unpinned.

### Verified

- `AnnotationJson` remains the single artifact/live annotation serializer; the
  user path adds no fields, no evidence, and no alternate provenance shape.
- Reviewer performed read/reason only; no build, test, formatter, or source
  edit was run, per `docs/workflow.md`.

## Round 2

Verdict: The five Round 1 findings are addressed without a new v4 namespace
break. `USER_CONTRIBUTOR_ID` is `core:user-annotations`, derived from the
pre-existing `CoreContributors.PREFIX` (`UserAnnotations.kt:12`); the scheduler
now only rejects that already-reserved `core:` rule prefix, and
`RuleSchedulerTest.userPrefixedPackRuleRemainsValid` and
`RuleSchedulerTest.packCanOwnPureIntent` pin that `user:` ids and pack-owned
`PURE` remain valid. `UNTAGGED` is removed before a user contribution is built
(`UserAnnotationResolver.kt:36`), with `untaggedIsNotAPublishableUserIntent`
covering a mixed `UNTAGGED` + custom record. User annotations explicitly do not
participate in occurrence persistence: `UserAnnotations.kt:16-19` and
`Model.kt:26-29` document the decision, `HostUserAnnotationsTest` asserts
`nodeAnchor == null`, and `AnnotationSerializationTest` pins the serializer's
null key. The artifact vocabulary now unions user intents through
`HostPipeline.userIntents()` (`HostPipeline.kt:140-163`), pinned by
`HttpUserAnnotationsTest` and by `HostUserAnnotationsTest` expecting `PURE` and
`TEAM_CUSTOM_INTENT` alongside pack and `UNTAGGED` names. The renderer emits the
`U` sign when the resolved meaning is `SHOW`/false as well as when handlers
produce an extmark, and preserves a handler-provided `sign_text`
(`render/init.lua:122-149`); `annotations_spec.lua` now renders a user
annotation under the shipped default config and asserts the marker. No
source-level regression was found in the reviewed paths.

Residual observations (not filed as findings): `userIntents()` publishes the raw
file vocabulary rather than only intents resolved for the analyzed files, so a
stale or path-unmatched record can over-declare a top-level name (it cannot
leave a published span name absent); `UNTAGGED` filtering happens at the
resolver/artifact boundary, so the parsed record and the client list still carry
it; `PURE` remains indistinguishable from a detection intent inside
`intents`/provenance (now documented and render-tested, and purity checks are
provenance-based so a pack-owned `PURE` cannot be mistaken for user purity);
and nodeAnchor non-participation is recorded in KDoc and tests rather than in
the architecture docs. Round 1 residuals about the Lua/Kotlin fixture gap,
client-side byte columns, stderr-only malformed-file reporting, and the
`_live_tags` injection bypass remain unchanged. This round was read/reason only;
no build, test, formatter, or source edit was run.
