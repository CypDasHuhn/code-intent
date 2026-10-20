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

## Round 3

Verdict: No finding. The changes made since Round 2 are contract-consistent and
close the remaining hash/identity gap between the client and the engine. The
frozen `api` module is still untouched (`git diff main -- analysis-engine/core/api`
is empty), `CINT_API_VERSION` is 4, `Emit` and `Annotation.nodeAnchor` are
unchanged, and the engine still publishes `nodeAnchor = null` for user
annotations. The persisted record shape, the `source = "user"` provenance tier,
the `core:user-annotations` contributor id, and the artifact/user-intent
vocabulary carry over from Round 2 unchanged. Split clipping now verifies the
stored hash against the original host span before remapping and then re-hashes
the clipped local selection; that is internal-only (records are never written
back or published), so a client record keeps one identity even though the same
record is clipped per region. The Lua writer's canonicalization now matches
Kotlin `contentHash` exactly (`\r\n` -> `\n`, strip one trailing `\n`), and the
new Kotlin and nvim tests pin the same canonical form from both sides. I concur
with correctness Rounds 3-4 closing; this dimension can close.

### Findings

None.

### Concurs / Dissents

- Concurs: the host-span verification restores the client contract instead of
  changing it. A record's hash has always meant "canonical text of the persisted
  host span"; before this change a hashed selection crossing a region boundary
  was hashed correctly at persist time and then compared against the clipped
  region text, so it could never resolve. Recomputing the hash only after the
  host-span check (and only for the in-memory scoped copy) makes split and
  non-split paths agree on identity. Hashless records keep `null` and the
  coordinate fallback.
- Concurs: the clipped copy never escapes the analysis path. `scopeToRegion`
  returns `UserAnnotation` values to `UserAnnotationResolver.resolve`; no code
  writes records back to `.cint/user-annotations.json`, so the persisted shape
  and its hash are not silently redefined by the engine.
- Concurs: cross-language hashing now agrees for every writer-produced shape.
  `content_hash` (`annotations.lua:165-169`) equals `contentHash`
  (`Hashing.kt:5-8`): both replace `\r\n` globally and remove exactly one
  trailing `\n`. Symbol records hash `anchor.text == symbol`, which contains
  neither sequence, so they are unaffected. A linewise range ending on an empty
  line now stores `sha256("code")`, matching the resolver's slice that ends in
  `\n` and is canonicalized the same way; the same check holds by construction
  for CRLF, where Neovim joins lines with `\n` and the engine normalizes the
  host slice's `\r\n`.
- Concurs: the agreement is pinned on both sides without sharing an
  implementation. `annotations_spec.lua` asserts the canonical hash and rejects
  the old raw joined-text hash; `rangeEndingOnAnEmptyLineUsesTheWriterCanonicalHash`
  uses an independent `MessageDigest` helper that re-implements the writer's
  normalization rather than calling `contentHash`, so a one-sided change fails a
  test.
- Concurs: the `SplitAnalyzer` callback widened from four to five parameters
  without touching the `api` surface. `analyze` passes the current file as
  `host`, recursion re-enters `analyze` with the region file as its own host,
  the default lambda has matching arity, and every in-repo call site (CLI,
  `Main`, both `LiveServer` paths, `SplitUserAnnotationsTest`, and the
  callback-less integration tests) is consistent.
- Concurs: `HostPipeline.userContributionsFor(host, source, ...)` remains the
  single resolver entry point; the non-split `analyze` passes
  `userContributionsFor(source, source, tree, syntax)`, so host and source are
  the same file there.
- Concurs: `UserAnnotationResolver.selectionText` is additive and reuses the
  existing `lineStarts`/`offset`/`slice` helpers, so the precheck, the clipped
  re-hash, and `resolve`'s own range slice compute identical bytes in the
  current code. The overloads are in `core`, not `api`; no rulepack contract
  changes.
- Concurs: provenance, roles, and vocabulary are unchanged from Round 2. User
  contributions are still `Role.SOURCE_INTENT`, `rule = USER_CONTRIBUTOR_ID`,
  `source = "user"`, merged by role/byte span, and `intents()` still unions
  `PURE` and open user names with pack vocabulary and `UNTAGGED`.
- Concurs: the renderer change since Round 2 is limited to the writer's hash;
  marker, cache key, and `sign_text` precedence are untouched. The three removed
  imports have no remaining references and are pure lint cleanup.
- Dissents: none. The five Round 1 findings remain addressed, and I do not
  re-open the Round 2 residuals.

### Residual observations (not filed)

- `scopeToRegion` hashes the clipped selection from `region.text` while
  `UserAnnotationResolver.resolve` verifies against `source.text`. These are the
  same string in the only production path because `SplitAnalyzer.analyzeRegions`
  constructs `SourceFile(host.path, region.text)`, but nothing in the signature
  or KDoc states the requirement; a future caller passing a different `source`
  would silently drop every hashed record.
- The two verifiers accept slightly different hash definitions in principle:
  `scopeToRegion` requires the hash to equal the *persisted span* text, while
  `UserAnnotationResolver.verified` also accepts the *node* text hash for symbol
  records. The writer always stores the symbol text over the symbol span, so
  they agree for every client-produced record; a hand-authored record with a
  declaration-text hash and a symbol-only span would resolve non-split and be
  dropped in a split file.
- `selectionText` introduces a public identity primitive while `resolve` keeps
  a parallel inline slice over the same private helpers. They cannot drift
  today, but there is no contract note that they must stay byte-identical.
- The record contract (`path`, span, `symbol`, `contentHash` and its
  canonicalization, `intents`, `note`, `createdAt`) and the `source = "user"`
  tier are specified in code, KDoc, and tests only; `docs/architecture.md` §6
  still lists the removed `evidence` field and no user tier, and no schema
  version is checked by either reader. This predates the ticket's recent work
  and remains a documentation gap rather than a wire inconsistency.
- The nvim and Kotlin tests now pin the same canonical form independently, but
  still not by a single artifact written in Lua and fed through
  `UserAnnotationsJson` to the resolver; the CRLF branch is covered by
  inspection only, as correctness Round 4 also noted.
- Round 2's residuals remain as recorded: `intents()` over-declares user names
  from unmatched or stale records (it cannot omit a published span name),
  `UNTAGGED` survives in the parsed record and the client list but is filtered
  before publication, `PURE` is indistinguishable from a detection intent in
  `intents`/provenance, nodeAnchor non-participation is documented only in KDoc
  and tests, and the `_live_tags` injection path still bypasses the wire-level
  `source`/`provenance` contract.
- The HTTP `analyze` path now builds a `HostPipeline` per request (the shared
  `defaultPacks` was removed so the request root can scope annotations). That is
  the intended root behavior and does not change the artifact shape, but it
  re-runs pack discovery per request rather than at startup.
- Static review only; no source edit, build, test, lint, or formatter command
  was run by this role, per `docs/workflow.md`. The implementor reports
  `just build`, `just test-engine`, `just lint`, and `just test-nvim` green, and
  this review assumes that handover.
