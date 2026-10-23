# Architecture review — ticket 060 (AI verdict reuse cache)

## Round 1

Verdict: The layering is right. The deterministic short circuit sits in the server,
reuses the cached `AnalysisOutput` for a matching content hash and environment,
allocates a fresh `analysisRevision`, and does not parse, query LSP, run rules,
compose, or call the provider. Environment assembly is split between core
primitives and the server, the in-memory verdict cache lives in `suggest`, and
persistence is server-owned; `suggest` still depends only on `api`/`core` and
`server` on `host`/`suggest`, with no detection logic added to core. Exact cache
hits still produce only `CandidateEmit`s and suggest-layer proposals, and hints
are prompt-only. The reuse identity and its invalidation, however, do not yet
match the documented contract. The text-diff invalidation path is unreachable in
the live server, cached operation offsets are not safe across intra-unit
reformatting, the 059 restriction fingerprint was silently re-keyed without a
migration, the decision fingerprint is coarser than 059's unit identity, prior
restrictions are dropped whenever the verdict cache is disabled, and the
environment/version inputs cannot detect engine, pack, or language-profile code
changes. The persisted cache also carries only a five-field environment and is
never discarded on the other environment changes the docs describe. Not ready to
close.

### Findings

1. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:273-279` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:124-133` and `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/cache/TextChange.kt:16-43` — the text-diff invalidation is never applied in the live server, so units that reference a changed declaration are reused with stale verdicts.**

   `publish` stores the current text in `CachedAnalysis.text` on every publish,
   including an analysis miss. `handleSuggest` only proceeds when
   `cached.version == current.version` and `cached.textHash ==
   contentHash(current.text)`, so by the time `discoveryPass` constructs its
   invalidation, `cached.text == current.text` is always true and
   `CacheInvalidation.NONE` is passed to every session. `TextChange.between` is
   therefore exercised only by `VerdictCacheTest`; the server never diffs the
   old and new document. Reproduction: a suggestion pass caches the verdict for
   a unit `helper(s)` in `use`; change only `helper`'s body or signature in
   another declaration in the same file; after the re-analysis, the `helper(s)`
   unit has the same normalized text, symbol, signature, imports and existing
   intents, so it hits the cache and keeps the old verdict even though the
   referenced declaration changed. The same holds for a unit whose referenced
   local is declared elsewhere and whose declaration/initializer changes while
   the use-site text is unchanged. This is the ticket's “units referencing changed declarations
   ... are graded again” acceptance item and the `engine.md`/snapshot claim that
   a text diff invalidates units referencing changed identifiers
   (`docs/engine.md:526-528`, `docs/ai-annotation-session.md:591-595`). In
   addition, `TextChange` produces no range at all for a pure insertion or
   deletion (`startByte == endByte`, so the range list is empty), so even a
   wired diff would not invalidate units adjacent to the edit point by overlap.

   Suggested fix: retain the text (or analysis version) that the verdict cache
   was last evaluated against — per document, or per cache entry — and diff from
   that, not from the analysis that was just produced. Represent an insertion or
   deletion as a point/adjacent range so the overlap test applies, or invalidate
   units whose boundary contains the insertion point. Add a server test where a
   declaration changes while a referencing unit's own text does not, and assert
   that unit is graded again (and that `analysisRuns` reflects the re-analysis).

2. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:363-414` with `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/cache/UnitCacheKey.kt:38-58` and `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/cache/TextChange.kt:21-24` — cached operation offsets are byte offsets under a normalized-text key, so a formatting or comment edit inside a unit can republish an operation at the wrong span.**

   The unit key is built from `unit.normalizedText` (comments stripped, whitespace
   tokenized), but cached adds/removes are byte offsets relative to
   `unit.startByte`. A whitespace or comment edit inside a unit preserves the key
   — and `TextChange` returns `NONE` for the whole document when the normalized
   text is unchanged — yet shifts the intra-unit byte offsets. `applyCached`
   only adds the new `unit.startByte`; `validateAdd` checks containment, the
   known vocabulary and duplicates, but never that the mapped span still covers
   the same tokens. Reproduction: a unit `val m = Regex("x").find(text)` caches
   `ADD SEARCH` over `find(text)`; reformat to
   `val m = Regex("x").find( text )`; the key is identical and the cached end
   offset now lands inside the argument, so the candidate is published at a
   truncated or shifted range. The existing reformat test only changes leading
   indentation, so it never places whitespace inside a unit. The same identity
   mixes normalized text with raw byte offsets on the input side:
   `existingIntentDigest` hashes `annotation.startByte - unit.startByte`, so a
   reformat inside an annotated unit changes the key and misses the cache,
   contradicting “a reformat reuses every unit” / “reuses units whose normalized
   fingerprints are unchanged” (`docs/ai-annotation-session.md:586-595`,
   `docs/engine.md:526-528`).

   Suggested fix: make operation positions independent of raw layout (for
   example token ordinals within the unit, or a digest of the exact operation
   text verified at the mapped span), and normalize the existing-intent position
   the same way. If direct reuse of positive verdicts must stay byte-based,
   require the unit's raw text digest to match before publishing cached adds and
   treat a normalized-only match as a hint; always verify a mapped span against
   the current text before accepting it. Add a test that reformats whitespace
   inside a unit containing a cached add, and one that reformats an annotated
   unit and expects reuse.

3. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:406` with `:350-380` and `:426-446` — the 059 restriction fingerprint was changed without a migration, so existing confirmed restrictions become dormant after this ticket.**

   The 059 fingerprint normalized expression and sibling text with
   `text.trim().replace(Regex("\\s+"), " ")`; the new `normalize` is
   `SourceNormalizer.normalize(text, stripComments = false)`, which tokenizes and
   spaces every punctuation token. For a typical expression the two hashes
   differ: `log.warn(message)` hashes as `log.warn(message)` under 059 and as
   `log . warn ( message )` now. `anchor()` discards the legacy branch whenever a
   record has a real `unitFingerprint` (`:426-432`), so every record written by
   059 resolves to no unit and `resolveRecord` returns null. Reproduction:
   confirm a restriction under 059, upgrade to this tree, and re-analyze; the
   restriction is silently dormant and the inspector reports it so, although
   the source is unchanged. The new tests build records with the new
   `recordFor`/`unitFingerprint`, so they cannot catch this. This is a
   compatibility break in the 059 identity model, not just a formatting
   behavior change.

   Suggested fix: keep the 059 normalization for the stored `unitFingerprint`,
   or version the fingerprint field (for example `fingerprintVersion`) and
   migrate/recompute old records on read; at minimum fall back to the
   content-hash/symbol resolution for a fingerprint that no longer matches
   instead of going straight to dormant. Add an integration test with a
   hand-built legacy fingerprint (the old normalization) asserting the record
   still applies or is migrated.

4. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:93-151,310-341` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerAnchors.kt`, `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:386-403`, and `analysis-engine/core/core/src/main/kotlin/dev/cint/core/cache/EnvironmentIdentity.kt:48-65` — the decision fingerprint is too coarse: one decision suppresses unrelated identical anchors and different operation shapes.**

   `AnchorFingerprint.of` hashes path, normalized anchor text, sorted intents,
   reason and the enclosing symbol name. It has no occurrence disambiguator and
   no operation shape. Two identical calls in the same function therefore share
   a fingerprint: rejecting one filters both through `rejectedFingerprints`
   (`:115-124`), and confirming one resolves both groups through
   `confirmedFingerprints` (`:93-114`), even though only one was decided. The
   confirmation lane is broader still: it only requires some annotation with an
   intersecting intent anywhere in the path (`:107-112`), not an annotation at
   the matched anchor. For proposals, `proposalFingerprint` hashes the intent set
   and `PROPOSAL_REASON` but not ADD/REMOVE or the operation sub-spans, so two
   different proposals on the same anchor text and intent set collide. This is a
   competing anchor concept next to 059's fingerprint, which deliberately
   includes siblings and an ordinal to make duplicate text dormant
   (`AiRestrictions.kt:350-380`); the docs claim decisions and restriction
   records use the same normalized anchor (`docs/ai-annotation-session.md:349-353`,
   `:600-603`), but restriction records do not use `AnchorFingerprint` at all.

   Suggested fix: give `AnchorFingerprint` a duplicate disambiguator equivalent
   to the 059 unit identity (symbol plus previous/next sibling or ordinal) and
   include a normalized operation signature for proposals (kind, intent,
   normalized sub-span). Alternatively, keep the content-hash/span id as the
   primary key and only use the fingerprint fallback when exactly one current
   anchor matches it. In the confirm lane, require the user annotation to cover
   the matched anchor rather than any intent at the path. Unify or explicitly
   document the two fingerprints, and add duplicate-anchor candidate and
   proposal decision tests.

5. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:69-91` (line 81) — prior restrictions are silently dropped whenever the verdict cache is disabled.**

   `unit.priorHints` are appended to `hintsByUnit` only inside
   `if (cache != null && env != null)`, even though they are derived from
   `.cint/ai-restrictions.json` by the server and are independent of the verdict
   cache. With `cacheEnabled: false` (a supported configuration) the batch prompt
   contains no `prior restriction:` entries, and the same is true for any
   `AnnotationSession` constructed without a cache. The ticket and docs say prior
   restrictions are carried as hints alongside prior verdicts
   (`docs/tasks/060-ai-verdict-reuse-cache.md:31-32`,
   `docs/ai-annotation-session.md:339-347`, `docs/engine.md:526-528`). The
   `cacheDisabledLeavesTheSessionUnchanged` test only exercises the provider
   path and does not inspect the prompt.

   Suggested fix: collect `unit.priorHints` outside the cache condition; keep
   only the cache-similarity hints inside it. Add a test with
   `cacheEnabled = false` and a restriction on the unit's symbol asserting the
   prompt contains a `prior restriction:` entry.

6. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCache.kt:15-16,18-35` with `analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:275-290` — the “engine” and “language profile” versions are literals, and the pack fingerprint does not cover rule code, so rebuilding the engine or changing a pack body does not invalidate the persisted verdict cache.**

   `ENGINE_VERSION = "cint-engine-v1"` and
   `LANGUAGE_PROFILE_VERSION = "cint-language-profile-v1"` are constants in the
   server source. `HostPipeline.fingerprint` hashes rule ids, owned intents,
   dependency sets, pack ids/class names/languages, project config and the
   overlay, but not a pack version or the rule bodies. A new build with changed
   verdict validation, unit enumeration, normalization, or a changed rule body
   therefore produces the same environment hash; on restart the on-disk
   `.cint/cache/ai-verdicts.json` loads and its entries are reused. The docs and
   the ticket claim engine/pack/language-profile changes invalidate the cache
   (`docs/ai-annotation-session.md:604-610`, `docs/engine.md:530-535`, ticket
   acceptance “Changing the prompt, provider, model, vocabulary, packs, config
   ...”). Reproduction: change a rule body or the expression-unit enumeration
   without touching these literals, keep the provider/model/prompt unchanged,
   restart the daemon, and run a suggestion pass; the old verdicts are hits.

   Suggested fix: derive `engineVersion` from the build (the project already
   declares `version = "0.1.0"` in `analysis-engine/build.gradle.kts`, so a
   generated resource or `Implementation-Version` would work), and include a pack
   artifact version/checksum in the pack fingerprint. If a manual bump is the
   intended process, make that explicit in the docs and add a release check that
   the constants changed when engine/prompt semantics do.

7. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/cache/AiVerdictCacheStore.kt:27-48,79-89` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCache.kt:49-61` — the persisted cache is keyed by only five environment fields and is never discarded on the other environment changes the docs promise.**

   `AiVerdictCacheEnvironment` carries engine version, provider, model, prompt
   version and vocabulary. `load` returns entries only when those five match;
   pack, config, user-annotation, restriction, language-profile and oracle
   changes are absent from the header. `ensureVerdictCache` therefore does not
   clear or replace the file for those changes. Entries are keyed by the full
   `aiEnv`, so reuse is safe, but the stale entries are loaded, count against
   `cacheMaxEntries`, and are written back on the next save; the file is not
   “discarded when the engine, pack, config, annotation/restriction, language
   profile, oracle ... changes” as stated in
   `docs/ai-annotation-session.md:604-610` and `docs/engine.md:530-535`. The
   earlier `Persistence` bullet in the same design only requires clearing on
   engine/prompt/provider/vocabulary (`docs/ai-annotation-session.md:355-363`),
   so the two doc sections disagree; the code implements the narrower bullet.
   Reproduction: run a pass, change `.cint/user-annotations.json` or the config
   overlay, restart, and inspect the cache file: the header still matches and
   the old entries are present (inert only because their per-entry `aiEnv` no
   longer matches). The prompt-change test only covers a header field.

   Suggested fix: store the full `aiEnv` (and/or the deterministic hash) in the
   file header and discard or filter entries on mismatch, or filter restored
   entries against the current `aiEnv` before `restore`. Update the docs to say
   “entries are discarded or filtered” precisely. Add a test that changes a
   deterministic environment input and asserts the on-disk cache is replaced or
   that only the current environment's entries survive a reload.

### Concurs / Dissents

- Concurs with the short-circuit layer and semantics: it lives in the server
  (`publish`), not in core, and it skips parse/LSP/rules/composition when
  `textHash` and `deterministicEnv` match. It allocates a fresh
  `analysisRevision`, keeps the config snapshot, updates `CachedAnalysis.version`
  and `text`, and republishes the cached annotations/candidates for the new
  buffer version. `cint/suggest` still requires an analysis whose version and
  text hash match, so an identical `didChange` does not call the provider.
- Concurs with the module direction: `EnvironmentIdentity`/`AnchorFingerprint`
  are core primitives with no detection logic, `UnitCacheKey`/`TextChange`/
  `VerdictCache` are in `suggest`, and `AiVerdictCacheStore` is server-owned.
  `suggest` imports only `api`/`core`, `server` imports `host`/`suggest`, and no
  rulepack or core detection code was added.
- Concurs with the persistence format: one versioned JSON file under
  `.cint/cache/`, key digests and unit-relative operations only, no source text
  or rationale on disk, atomic temp-file replacement, in-process serialization
  on `verdictCache`, `cacheMaxEntries`/`cacheTtlHours` bounds, and
  `cachePersist: false` keeping reuse in memory. The server-only writer makes the
  absence of a cross-process file lock acceptable for a per-project daemon.
- Concurs with the candidate-only boundary: exact hits re-enter through
  `buildCandidates`/`CandidateEmit` and suggest-layer proposals, never through
  `Emit`/`AnnotationStore`/composition, and hints only appear in the batch
  `prior` field, never as published candidates.
- Concurs with the protocol/config shape: `initialize.suggestions.cache` is true
  only for protocol-4 clients when `cacheEnabled` is set, `cint/suggest` reports
  `reused`, `graded`, `total` and `overhead`, and progress reports `reused`.
- Dissents: findings 1-7.

### Residuals

- **Legacy decisions have no fingerprint.** `SuggestionStore` now persists an
  optional `anchorFingerprint`, but `.cint/suggestions.json` records written
  before this ticket only have `anchorId`/candidate ids. After a formatting edit
  those old rejections/confirmations can resurrect or re-resolve through the
  id path. This is a migration limitation of the new fallback, distinct from the
  059 fingerprint migration in finding 3; document it or migrate records when
  the anchor text is still resolvable.
- **The cache key deliberately omits `path`.** Two files with the same language,
  imports, symbol/signature and normalized text share verdicts. That matches the
  design (imports were added to avoid blind sharing) and stays inside the
  “no hard cross-file reuse without matching context” boundary, but it is worth
  stating explicitly in the cache docs because it is observable as cross-file
  candidate publication.
- **Similar-unit hints are memory-only for their text.** `CachedVerdict`
  persists no `normalizedText`/`symbol` (correct for privacy), so after a
  restart `VerdictCache.similar` cannot score near-miss units; only exact-key
  reuse works until those units are re-graded. The restart test covers exact
  reuse only. This is a deliberate privacy trade-off, not currently documented.
- **Reused verdicts lose rationale even in memory.** `cacheVerdict` does not
  store `rationale`, and `applyCached` rebuilds adds with `rationale = null`, so
  a cache hit from the same session also loses the review prose. The docs only
  say rationale is omitted from disk and “after a restart” the old prose is
  gone; either persist/strip it only at the disk boundary or amend that wording.
- **The document analysis cache is per writer.** `analyses` is keyed by
  `DocumentKey(writer, uri)`, so two clients with the same file each analyze it
  once even when the text and environment match. The ticket's short circuit is
  still met per client, but the “stop redoing identical work” goal is not shared
  across clients. Consider a content-keyed cache if that matters.
- **`analysisRuns` is a production test hook.** The `ConcurrentHashMap<DocumentKey,
  AtomicLong>` exists only to let `Harness.analysisRuns` observe the short
  circuit. It is harmless but is test-only state on `LiveServer`; a
  diagnostics counter or a test seam would keep the production surface smaller.
- **Dead/inert cache fields.** `CachedVerdict.anchorFingerprint`, `path`,
  `startByte` and `endByte` are persisted but never used by lookup or
  publication; `NormalizedText.digest`/`normalizedSourceDigest` are unused. Not
  a defect, but the identity model would be easier to reason about if the
  stored fields were exactly the ones reuse consumes.
- **`AiEnvironment` and `AnchorFingerprint` live in core** although only the
  server and suggest consume them. There is no dependency violation and no
  detection logic, but a future cleanup could move the AI-specific identity
  beside the AI cache; the deterministic half can stay core.
- **Declaration-level invalidation remains name-based at best.** Even after
  finding 1 is fixed, `TextChange.identifiers` can only invalidate units whose
  text names a changed identifier; a same-file signature or body change that
  does not put the referenced name in the changed span still reuses dependent
  verdicts. That is broader than the documented “external type change without
  text change” staleness limit. Extend the invalidation or narrow the documented
  limit.
- **Secondary docs are not all updated.** `docs/ai-intents.md` still describes
  the cache flag and protocol without the 060 reuse model or `overhead`, and
  `nvim-renderer/README.md` still lists only a subset of the
  `initialize.suggestions` fields (a 058 residual). Add these when the protocol
  docs are next touched.
- **Unknown cache-file versions are overwritten.** `AiVerdictCacheStore.load`
  returns an empty list for a non-1 file version and the next `save` replaces
  it. For a disposable cache this is acceptable (unlike the 059 restriction
  file, which refuses to write), but it is a silent downgrade path worth a
  comment.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 2

Verdict: All seven Round 1 findings are addressed in substance. The production
suggestion pass now diffs the last completed grading pass, adds declaration-name
invalidation, and represents insert/delete as a point range; cached operations
carry token indices plus a raw-text digest and are remapped through them; 059
restriction fingerprints are versioned and legacy records keep their old
normalization; decision fingerprints add the unit identity and proposal
operation shape, with duplicate-expression tests; prior restrictions are
collected independently of the cache; engine/pack/profile identity uses class
and resource digests; and the cache header carries the full deterministic/AI
environment and is discarded on a mismatch. The round-2 delta does, however,
introduce new defects in the newly added anchored user-annotation path, the
confirmation suppression lane still uses raw user-annotation spans, the
engine/profile fingerprint remains a hand-curated list with silent fallbacks,
and the docs retain a contradictory old identity paragraph. Not ready to close.

### Per-finding status

1. **Concur — production text-diff invalidation is live.** `discoveryPass`
   (`LiveServerSuggestions.kt:275-289`) diffs `suggestionBaselines[key]` against
   the current text, merges `TextChange` identifiers with
   `changedDeclarationNames`, and passes the result as `CacheInvalidation`; the
   baseline is written only after a completed pass (`:336-338`).
   `declarationChangeRegradesUnitsThatReferenceIt` proves a declaration-body
   change re-grades a referencing unit whose text is unchanged. `TextChange`
   now emits a point range for insert/delete. Residual: the baseline starts empty,
   so the first suggestion pass after opening a document (or after a daemon
   restart with a disk cache) has no diff and can reuse a referencing unit from
   an older persisted pass; see Residuals.
2. **Concur — cached operations are no longer bare byte offsets under a
   normalized key.** `CachedVerdictOperation` carries `startToken`/`endToken` and
   `CachedVerdict` carries `rawTextDigest`; `applyCached`
   (`AnnotationSession.kt:381-470`) uses token indices when the raw text changed
   and refuses a byte fallback in that case. `intraUnitFormattingKeepsTheCachedTokenSpan`
   covers the reported `f(a, b)` → `f( a , b )` case. Residual: an operation whose
   original span is not token-aligned has no token indices and is re-graded
   rather than remapped; the existing-intent fingerprint has the same fallback
   for annotations whose boundaries are not token-aligned. Both are safe misses,
   not stale reuse.
3. **Concur — 059 restriction records are migrated.** `UnitIdentity` has a
   versioned fingerprint, `normalize` keeps the old `trim().replace("\\s+", " ")`
   path for version 1, `AiRestrictionsJson` writes/reads `fingerprintVersion`,
   and `legacy059FingerprintStillResolvesWithoutAVersionField` covers a record
   without the field. A v1 fingerprint that no longer matches falls through to
   `legacyAnchor`. The split-region interaction is a new issue; see finding 9.
4. **Concur — decision disambiguation now follows the 059 identity.** Candidate
   and proposal contexts use `UnitIdentity` (symbol, siblings, ordinal) plus a
   token range, and proposal fingerprints include a normalized operation shape.
   `duplicateIdenticalExpressionsKeepIndependentDecisions` and the proposal
   counterpart pass. The confirmation lane's use of raw user annotations is a
   new defect; see finding 10.
5. **Concur — restriction hints no longer depend on the verdict cache.**
   `unit.priorHints` are collected before the cache branch
   (`AnnotationSession.kt:69-74`), and
   `restrictionHintsDoNotDependOnTheVerdictCache` asserts the prompt contains the
   hint with no cache configured.
6. **Concur — engine/pack/profile code is fingerprinted.** `ENGINE_VERSION` and
   `LANGUAGE_PROFILE_VERSION` hash classes and syntax YAML resources,
   `HostPipeline.fingerprint` hashes rule classes and pack classes, and
   `cacheFileHeaderTracksTheFullEnvironment` passes. The remaining scope gap is
   finding 11.
7. **Concur — the disk cache header covers the full environment and discards on
   a mismatch.** `AiVerdictCacheEnvironment` now includes `deterministicHash` and
   `aiEnv`; `environmentMatches` checks both; `ensureVerdictCache` clears and
   reloads, so a changed annotation/config/pack/profile environment both
   invalidates in-memory entries and makes the old file unreadable, and the next
   save replaces it. Residual: the file is per language in practice; see
   Residuals.

### New findings

8. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotations.kt:24-35,67-74` with `UserAnnotationResolver.kt:99-100,169-203` and `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:440-510` — anchored user annotations are unversioned and the resolver has no span/hash fallback when the anchor is incomplete.**

   `LiveServerDecisions.userAnchor` writes `unitFingerprint` whenever an
   enclosing unit exists, even when `UnitCacheKeys.relativeTokenRange(...,
   requireExact = true)` returns null because the confirmed candidate span does
   not start and end on token boundaries. `UserAnnotationResolver.resolve` takes
   the anchored branch solely on `annotation.unitFingerprint != null`, and
   `resolveAnchored` then returns null if `startToken`/`endToken` is missing
   (`:183-184`). A confirmed AI sub-span that is not token-aligned is therefore
   written successfully, then silently disappears from the next analysis; the
   raw span/hash path that would have resolved it is never tried. There is no
   `fingerprintVersion` on `UserAnnotation` (unlike `AiRestrictionRecord`), and
   `resolveAnchored` always calls `UnitIdentity.fingerprint(...,
   CURRENT_FINGERPRINT_VERSION)`; a future fingerprint-version bump drops every
   anchored user annotation. `SuggestionStore.SuggestionDecision.anchorFingerprint`
   has the same missing version, so old decisions lose their formatting
   suppression on a future identity change. The optional `anchorFingerprint`
   check is also all-or-nothing: a record whose intents/text are edited but whose
   unit/token anchors are still valid returns null instead of resolving or
   falling back.

   Suggested fix: only take the anchored path when a stored
   `fingerprintVersion` and both token bounds are present; add that field to
   `UserAnnotation` and `SuggestionDecision` and pass it to
   `UnitIdentity.fingerprint`; fall back to the legacy span/content-hash path
   when the anchor is incomplete or a version is unknown; make a mismatched
   optional `anchorFingerprint` a fallback, not an unconditional drop. Add a
   test that confirms a candidate whose span cuts a token and then re-analyzes,
   and a test with an explicitly versioned anchored record.

9. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:149-199,202-257` with `UserAnnotationResolver.kt:169-203` and `AiRestrictions.kt:408-445` — region clipping copies a host-based v2 anchor into the region source, so new split-document records become dormant.**

   `scopeToRegion` copies `span` and `contentHash` but leaves `unitFingerprint`,
   `anchorFingerprint`, `startToken`, `endToken` unchanged; `scopeRestrictionsToRegion`
   does the same for `unitFingerprint`, `fingerprintVersion`, and operation token
   offsets. `SplitAnalyzer` then resolves those records against the *region*
   source (`SplitAnalyzer.kt:105-106`), where the host unit fingerprint cannot
   match. Version-1 restriction records fall through to `legacyAnchor`; version-2
   records do not (`AiRestrictions.kt:440-445`), and anchored user annotations
   return null. A confirmed add or restriction in a Vue/SFC region is therefore
   written on the host, clipped, and then dormant in every split analysis, even
   though the raw content hash still matches. Reproduction: confirm an AI
   candidate (or a restriction group) inside a `<script>` region of a Vue file,
   then analyze the file; the user annotation/restriction disappears although
   the region text is unchanged.

   Suggested fix: when clipping, clear the anchor fields and fall back to the
   clipped span/content hash, or recompute the unit/token anchors against the
   region text; if host anchors are meant to survive, resolve them against the
   host source rather than the region. Add a split test with an anchored user
   annotation and an anchored v2 restriction, not only legacy span/hash records.

10. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:99-122,350-395` with `LiveServerDecisions.kt:440-510` — confirmation suppression compares raw user-annotation spans, so a confirmed AI candidate can reappear after formatting.**

    After formatting, the user-annotation record's stored `span` is stale (the
    file is not rewritten on re-analysis), and the group's `anchorId` and
    `candidateId` change because `CandidateGrouping` includes the raw anchor hash
    and the new byte offsets. `resolvedAnchors` compares the decision's old span
    to the record's old span but the group id no longer matches. The remaining
    lane is `fingerprint in confirmedFingerprints && userAnnotations.any {
    annotationCovers(...) }`. `annotationCovers` (`:363-395`) selects text with
    the raw `annotation.span`, compares that normalized text to the candidate
    text, and computes the context from `byteRange(annotation.span)`; once
    formatting shifts the span, the selected text and context no longer match,
    so the group is published again. The new `unitFingerprint`/token anchors that
    `UserAnnotationResolver` uses are ignored by this filter. The
    `confirmedCandidateSurvivesIntraSpanFormatting` test only checks that the
    user annotation is republished after formatting; it never runs a second
    suggestion pass to assert the candidate stays suppressed.

    Suggested fix: resolve the user annotations through
    `UserAnnotationResolver` (or map them with their `unitFingerprint`/token
    range) and compare the confirmation against the resolved current span and
    context, or store the decision fingerprint on the user annotation with the
    same recipe and compare it directly. Require `annotation.path == path` while
    there. Add a test that confirms a candidate, reformats inside its span, runs
    `cint/suggest`, and asserts the candidate is absent from
    `cint/publishCandidates`.

11. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCache.kt:23-55` with `analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:275-320` and `analysis-engine/core/core/src/main/kotlin/dev/cint/core/support/hashing/CodeFingerprint.kt:5-27` — the code fingerprint is a hand-curated class list with silent fallbacks, not the engine/profile code the docs claim.**

    `ENGINE_VERSION` hashes a fixed list of ten classes. It omits
    `SessionTools`, `SessionModel`, `SuggestionService`, the provider/prompt
    assembly, `AnnotationStore`/composition, `AiRestrictionsJson`/
    `AiRestrictionApplication`, and the language syntax models and parsers.
    `LANGUAGE_PROFILE_VERSION` likewise hashes five frontend classes and five
    YAML files but not a newly added language, a syntax-model change, a
    tree-sitter grammar, or a parser change. `CodeFingerprint.classDigest` falls
    back to `contentHash(clazz.name)` when the `.class` resource is unavailable,
    and `resourceDigest` to `contentHash(resource)` when the YAML is missing; a
    packaging/classloader change can therefore leave the identity unchanged
    even though the executable code differs. A change in an unlisted class that
    affects verdict validation, existing intents, or unit enumeration leaves the
    persisted cache valid across a restart. This is narrower than the round-1
    defect (rule/pack bodies and the listed classes are now covered) but the
    “engine code” claim is still too broad.

    Suggested fix: derive the engine identity from the build/commit (the project
    declares `version = "0.1.0"`) or hash the engine artifact/module rather than
    a maintainable list; at minimum include the session/tool/provider classes,
    the annotation store/composition, and all supported language
    models/parsers, and fail loudly rather than hashing a name/resource path when
    a digest is unavailable. Add a check that a change outside the list bumps the
    identity.

12. **`docs/engine.md:527-535,363-365` with `docs/architecture.md:327-330`, `docs/summary/annotations-and-ai.md:209-211`, and `docs/ai-annotation-session.md:302-363,221-245` — the docs retain the old identity paragraph and claim versioned decision/user anchors the code does not have.**

    `docs/engine.md` now has two reuse paragraphs: the new one (`:327-358`)
    correctly says engine/pack/profile code and token-relative offsets; the old
    one (`:527-535`) still says “engine version, pack fingerprint” and
    “unit-relative operation offsets”, and `:365` still describes restriction
    records as storing only relative `remove`/`add` offsets. `architecture.md`
    and `summary/annotations-and-ai.md` say decision suppression and
    confirmations use a “versioned unit identity”, but only
    `AiRestrictionRecord` has `fingerprintVersion`; user annotations and
    `SuggestionStore` decisions do not (finding 8). The design's Reuse section
    (`ai-annotation-session.md:302-363`) still describes unit-start offsets, and
    its restriction example (`:221-245`) omits `fingerprintVersion`,
    `startToken`, and `endToken`. This is more than wording: a reader
    implementing a consumer of the restriction file or a migration would follow
    the stale shape.

    Suggested fix: delete the old engine paragraph (or merge it into the new
    one), correct the restriction record description to mention version and
    token offsets, and either add the version field for user/decision anchors or
    narrow the docs to “restriction fingerprints are versioned”. Update the
    example record and the Reuse section to the implemented token/raw-offset
    split.

### Concurs / Dissents

- Concurs with the Round 1 boundary direction: the document short circuit
  remains server-owned and skips parse/LSP/rules/composition; the verdict cache
  remains in `suggest`; persistence remains server-owned; `suggest` still
  depends only on `api`/`core`; the new `UnitIdentity`, `SourceNormalizer`, and
  `CodeFingerprint` are core primitives with no detection logic; candidate-only
  publication is unchanged, and hints remain prompt-only.
- Concurs with the round-2 implementations of findings 1-7 in substance,
  including the token-relative cache operations, the versioned 059 restriction
  migration, duplicate-expression decision identity, cache-independent
  restriction hints, class/resource code fingerprints, and the full-environment
  file header.
- Dissents: new findings 8-12. Finding 4's remaining raw-span confirmation lane
  is filed as finding 10 rather than re-opened; finding 6's remaining scope is
  finding 11; finding 2's non-token-aligned fallback is a residual.

### Residuals

- **First-pass/restart diff gap.** `suggestionBaselines` starts empty and is
  updated only after a completed suggestion pass. An edit before the first pass,
  or after a daemon restart when the disk cache already has entries, is not
  diffed; units referencing a changed declaration can still reuse an older
  persisted verdict. Initialize the baseline at open and/or persist a per-path
  declaration-digest index so a restart retains the diff basis. Add a
  restart-edit-suggest test.
- **Per-language cache thrash.** `AiVerdictCacheEnvironment` is global but its
  `deterministicHash` includes the per-language pack fingerprint and
  `aiEnv`; alternating suggestion passes for two languages will clear/reload the
  shared cache and only the last language’s header matches the file. Correct,
  but a multi-language project loses the other language’s persisted entries.
  Store the environment per language or make the file a map keyed by language if
  that matters.
- **Existing-intent token fallback.** `UnitCacheKeys.of` uses a token range only
  when the annotation boundaries are exact; otherwise it falls back to relative
  byte offsets, so a reformat still misses those units. The snapshot’s
  “including annotations that are part of the existing-intent fingerprint” is
  true only for token-aligned annotations. Normalize a partial-token annotation
  to the enclosing token range or document the miss.
- **Legacy decisions still have no fingerprint.** Pre-060 `SuggestionStore`
  records remain id-only; after a formatting edit they can resurrect or
  re-resolve through the content-hash path. The snapshot notes the migration
  limitation; keep it explicit for the renderer and accept a one-time
  re-decision.
- **`AiVerdictCacheStore.load` still does not filter per-entry `aiEnv`.** The
  full header makes the normal path exact, but a concurrent pass for a second
  environment can write entries under a header from the first environment;
  restoring by `entry.aiEnv` would keep the file contract exact.
- **Unknown cache-file versions are still overwritten.** The disposable-cache
  policy is acceptable (unlike the restrictive 059 file), but there is no
  message; a future file version silently loses its entries on the next save.
- **`parseRuns`/`analysisRuns` are test hooks on `LiveServer`.** They now include
  parses from `changedDeclarationNames` and `anchorContexts`; the production
  surface grows for test observability. A diagnostics counter or a test seam
  would keep it smaller.
- **Performance of the new anchors.** `anchorContexts`/`UnitIdentity.fingerprint`
  recompute expression units and per-unit fingerprints per candidate, and
  `changedDeclarationNames` parses the document twice per changed suggestion
  pass. This is correctness-safe but can be noticeable on large files; cache
  the per-document unit/declaration index for one pass if needed.
- **CodeFingerprint fallback.** The name/resource fallback in
  `CodeFingerprint` (also finding 11) makes a missing class or YAML resource
  look like a stable identity. A missing digest should be reported and treated
  as a changed environment, not silently substituted.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 3

Verdict: The five Round 2 findings are addressed in substance. User and
decision anchors now carry a `UnitIdentity` version, the resolver falls back to
the raw span/content hash when an anchor cannot resolve, split clipping clears
host-based anchor fields and adds versioned user/restriction tests, the
confirmation lane matches against the resolved current user annotations with a
reformat-then-republish test, the engine profile is package-derived with loud
class/resource failures, and the old engine paragraph and stale record example
are gone. Three new defects remain. The new normalized-offset fallback is not the
inverse of the raw-byte fallback, so a non-token-aligned confirmation whose span
starts after whitespace can be remapped into the previous token/whitespace and
then dropped; `restrictionMatchesProposal` compares only the pathless unit
fingerprint across every project restriction and does not check operation shape
or applicability, so a restriction in another file or a dormant/different
restriction suppresses unrelated proposals; and the package fingerprint still
omits `dev/cint/lsp` and shared rulepack code while the guard test only pins the
Gradle module list. Split restrictions that straddle a region boundary also
still keep the host content hash and relative offsets. Not ready to close.

### Per-finding status

1. **Concur — user and decision anchors are versioned and mirrored with
   restrictions.** `UserAnnotation.unitFingerprintVersion`,
   `SuggestionDecision.anchorFingerprintVersion`, `UnitIdentity.versionOf`, and
   the versioned `anchorContexts`/`candidateFingerprints` paths are present.
   `UserAnnotationResolver.resolve` now tries the anchored path and then falls
   back to the legacy span/content-hash path (`:99-102`), and
   `anchoredRange` supports token and normalized offsets. The split test writes
   `unitFingerprintVersion = 2`; the round-2 unversioned records read as version
   2. Residual: the version field versions `UnitIdentity` only; the
   `AnchorFingerprint.of` recipe and the normalized-offset mapping have no
   version of their own, so a future change to either can still lose
   suppression/anchoring with the same version value. See also finding 13.
2. **Concur — split anchoring no longer copies host bases into region
   records.** `scopeToRegion` clears `unitFingerprint`, its version,
   `anchorFingerprint`, tokens, and normalized offsets
   (`HostPipeline.kt:259-271`); `scopeRestrictionsToRegion` clears
   `unitFingerprint` and operation token offsets (`:196-201`); the new
   `splitAnalysisClipsVersionedAnchorsToTheRegionSpan` and
   `splitAnalysisClipsVersionedRestrictionAnchorsToTheRegionSpan` tests cover
   records wholly inside a region. Residual: `scopeRestrictionsToRegion` still
   keeps the host `contentHash` and the host-relative `remove`/`add` byte
   offsets, so a restriction whose anchor straddles the region boundary can
   still fail to resolve or apply after clipping; the tests do not cover a
   clipped anchor. See Residuals.
3. **Concur — confirmation suppression now uses the resolved current identity.**
   `publishCandidates` derives `currentUserAnnotations` from the current
   analysis output by user provenance and `annotationCoversCurrent` compares
   normalized text and versioned context against the current annotation span;
   the old raw-record `annotationCovers` is gone. `confirmedCandidateSurvivesIntraSpanFormatting`
   now runs a second `cint/suggest` after reformatting, and
   `confirmedProposalDoesNotReappearAfterFormatting` covers confirmed
   restrictions. The new `restrictionMatchesProposal` filter is a separate
   over-broad predicate; see finding 14. Residual: the candidate test runs with
   `cacheEnabled = false` and a provider that emits a different span after
   reformatting, so it does not exercise the cached-verdict path end to end;
   `partialTokenConfirmedCandidateSurvivesReformat` only checks that the user
   annotation reappears, not its exact span. Add a cache-enabled
   confirm→reformat→suggest case with an exact anchor assertion.
4. **Dissent — the package fingerprint is broader but still misses required
   code and its guard is not a coverage guard.** `ENGINE_VERSION` hashes the
   `api`/`core`/`suggest`/`host`/`server` packages and
   `LANGUAGE_PROFILE_VERSION` hashes `frontend`/`treesitter` plus the registered
   syntax YAMLs; class/resource digest failures now throw. However:
   `dev/cint/lsp` (the type oracle) is in neither prefix list and has no anchor,
   so a change to LSP type facts can leave both deterministic and AI caches
   valid; `dev/cint/rulepacks` shared helpers are not hashed by
   `HostPipeline.fingerprint` (only the pack and rule classes themselves are),
   so a shared detection helper can change without invalidating; and
   `CodeFingerprint.ofPackages` drops an unreadable anchor root with
   `mapNotNull { runCatching ... }` and only checks that *some* entries exist,
   so a missing module can be silently omitted. `EngineCodeCoverageGuardTest`
   only compares the Gradle module list in `settings.gradle.kts`; it does not
   assert that every module/package has a fingerprint prefix or entry. This is
   finding 15.
5. **Concur — the docs are reconciled for the implemented model.** The
   duplicate old “engine version / unit-relative offsets” paragraph is gone,
   `docs/engine.md` describes versioned fingerprints, token/raw offsets and the
   full environment header, `docs/ai-annotation-session.md` shows
   `fingerprintVersion`/`startToken`/`endToken` and the process-local baseline,
   and `architecture.md`/the summary now correctly say user and decision anchors
   are versioned. Residual documentation overclaims: the design still says the
   short circuit reuses “parsed facts” as well as `AnalysisOutput`
   (`ai-annotation-session.md:296-300`); the split clipping paragraph says both
   user annotations and restrictions “keep the clipped span and content hash”,
   but `scopeRestrictionsToRegion` does not recompute the restriction content
   hash; and “missing code/resources make the fingerprint fail loudly” is true
   for `classDigest`/`resourceDigest` but not for a missing package root in
   `ofPackages`.

### New findings

13. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/support/hashing/NormalizedText.kt:22-56` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:531-532` and `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:225-232` — the normalized-offset fallback is not the inverse of the raw-byte mapping, so a non-token-aligned confirmation starting after whitespace can be remapped into the previous token/whitespace and dropped.**

   `normalizedLengthAtByte(text, byteOffset)` returns
   `normalize(prefix).length`, and `normalize` joins only tokens with spaces and
   has no trailing separator. For `text = "f(a, name)"`, a span start at the
   `name` token (byte 5) has prefix `"f(a, "` and normalized length 7, but the
   normalized full text has `name` at offset 8. `rawByteAtNormalizedOffset(full,
   7)` therefore lands two bytes inside `name` (or on the preceding separator
   for simpler text), not at its start. `userAnchor` stores these values when
   `relativeTokenRange(requireExact = true)` is null (a start or end cuts a
   token); `anchoredRange` maps them back and `resolveAnchored` may either
   resolve an annotation whose span starts in whitespace/a comment or fail the
   `anchorFingerprint` check and fall back to the stale raw span. A non-token-
   aligned confirmation is therefore still not reliably formatting-safe, which
   is exactly the case the normalized fallback was added for. The
   `partialTokenConfirmedCandidateSurvivesReformat` test uses `find(`, which is
   a sequence of whole tokens, so it does not exercise this path.

   Suggested fix: derive `startNormalized`/`endNormalized` and the reverse
   mapping from one token-boundary table that includes separators (or store a
   token index plus character offset, which `anchoredRange` can use directly),
   so the two functions are exact inverses for token starts, token ends, and
   positions inside tokens. Add a test with a span starting after `, ` or a
   newline and ending mid-token, reformat it, and assert both the resolved span
   and its normalized anchor text.

14. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:415-436` — `restrictionMatchesProposal` suppresses proposals using a project-wide, pathless unit fingerprint and ignores operation shape/applicability.**

   `AiRestrictionsFile.loadTolerant(root)` returns every restriction in the
   project, but the predicate never compares `record.path` with the current
   document and `UnitIdentity.unitFingerprint` contains no path. Two files with
   the same language/unit text/symbol/surroundings therefore share a
   fingerprint: confirming a restriction in file A suppresses an AI proposal on
   the matching unit in file B. The predicate also matches any proposal on the
   unit, regardless of whether the proposal's operation shape differs from the
   confirmed restriction or whether the restriction is dormant, so a confirmed
   `− LOGGING` can suppress a later `+ SEARCH` proposal and a dormant record
   can hide a valid new proposal. This is broader than the “prevent the same
   confirmed proposal from reappearing” intent and is not covered by a
   cross-file or dormant-record test.

   Suggested fix: require
   `UserAnnotationResolver.pathMatches(record.path, relativePath(doc.uri), root)`
   and either compare the same normalized operation signature used by
   `proposalFingerprint` or use the resolved/active restriction set so a
   dormant or differently-shaped record does not suppress. Add a two-file
   same-unit test and a dormant-restriction test.

15. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCache.kt:23-64` with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/support/hashing/CodeFingerprint.kt:22-71`, `analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:295-327`, and `analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/EngineCodeCoverageGuardTest.kt` — the package fingerprint still omits `dev/cint/lsp` and shared rulepack code, silently skips unreadable roots, and the guard test does not check prefix/entry coverage.**

   `ENGINE_VERSION` prefixes are `dev/cint/api`, `dev/cint/core`,
   `dev/cint/suggest`, `dev/cint/host`, and `dev/cint/server`; the language
   fingerprint covers `dev/cint/frontend` and `dev/cint/treesitter`. The `lsp`
   module is a Gradle module in the guard test’s own list but appears in neither
   prefix set or anchor list, so a change to the type oracle does not invalidate
   deterministic analysis or the persisted verdict cache. `HostPipeline.fingerprint`
   hashes the pack class and each rule’s own class, but not a shared
   `dev/cint/rulepacks` base/helper class, so shared detection code can change
   without a new pack fingerprint. In `ofPackages`, an anchor whose
   `codeSource.location` cannot be read is skipped by
   `mapNotNull { runCatching ... }`; the function only asserts that the union of
   entries is non-empty, so one missing module/package can vanish silently.
   `EngineCodeCoverageGuardTest` parses `settings.gradle.kts` and asserts a
   hardcoded module-name set; it does not assert that each module has a
   fingerprint prefix, an anchor, or at least one entry, and it would pass with
   the `lsp` omission intact.

   Suggested fix: map the Gradle modules to package prefixes and anchors,
   include `dev/cint/lsp` and `dev/cint/rulepacks` (or hash the whole engine
   artifact/pack JARs), make `ofPackages` fail per missing root/prefix rather
   than only on an empty union, and extend the guard test to assert coverage of
   every module/prefix and that an unlisted package cannot be silently omitted.
   If rulepack shared code is intentionally covered by a pack artifact checksum
   instead, hash that checksum and say so in the docs.

### Concurs / Dissents

- Concurs with the boundary direction: the short circuit remains server-owned,
  the verdict cache remains in `suggest`, persistence remains server-owned,
  `suggest` still depends only on `api`/`core`, and the new anchor plumbing does
  not add detection logic to core or change the candidate-only publication
  contract.
- Concurs with the round-3 implementations of the five Round 2 findings in
  substance, including the version fields and legacy fallback, split anchor
  clearing and tests, current-identity confirmation matching, package-derived
  code fingerprints with loud class/resource failures, and the reconciled docs.
- Dissents: finding 15 (the required package coverage and guard are
  incomplete); new findings 13 and 14. The split restriction hash/offset
  residue is a residual rather than a re-opening of finding 9.

### Residuals

- **Clipped split restrictions.** `scopeRestrictionsToRegion` clears the
  versioned unit/token anchors but keeps the host `contentHash` and host-relative
  `remove`/`add` offsets. A restriction record that starts before the region
  boundary can still be dropped or applied against the wrong span; the new split
  restriction test keeps the anchor wholly inside the region. Recompute the
  clipped hash and remap offsets, or reject clipped records, and add a
  boundary-straddling test.
- **Unversioned anchor algorithm layers.** `unitFingerprintVersion` and
  `anchorFingerprintVersion` describe `UnitIdentity`, not the
  `AnchorFingerprint` recipe or the normalized-offset mapping. A future change
  to either recipe with the same version value can lose decision suppression or
  user-annotation resolution before the legacy fallback can help. Add a separate
  version/fallback or keep those recipes immutable.
- **`restrictionMatchesProposal` short-circuit and legacy records.**
  `publishCandidates` skips the restriction filter when `shortCircuited`, and a
  legacy restriction without `unitFingerprint` is not matched at all; a carried
  proposal could republish a confirmed restriction in those paths. Also filter
  carried proposals against the same path/operation identity.
- **Confirmation test depth.** The candidate confirmation test uses
  `cacheEnabled = false` and a provider whose post-reformat span differs from
  the confirmed span, and the partial-token test only asserts that a user
  annotation is published, not its exact span. Add a cache-enabled
  confirm→reformat→suggest test and assert the resolved span, not only the
  candidate's absence.
- **First-pass/restart baseline.** The docs now state the baseline is
  process-local and a restart falls back to exact keys; the residual is accepted
  but a restart-edit-suggest test would still be useful, as would initializing
  the baseline at open.
- **Per-language cache file.** The file has a single environment header, so
  alternating languages in one project replaces the persisted entries for the
  previous language; the docs now say this. A per-language file/entries map
  would preserve both.
- **`CodeFingerprint.ofPackages` root/prefix silence and docs.**
  `ofPackages` skips unreadable roots and the docs claim missing code/resources
  fail loudly; that is true for the class/resource primitives but not for a
  missing package root. This is part of finding 15.
- **Docs overclaims.** `ai-annotation-session.md:296-300` still says the short
  circuit reuses “parsed facts” as well as `AnalysisOutput`; the implemented
  reuse is the output only (the worklist still parses). The split clipping
  paragraph overstates restriction content-hash recomputation. Correct both
  when the docs are next touched.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 4

Verdict: The three Round 3 items are addressed in substance. Anchoring now
prefers token indices with byte offsets inside the token, the normalized
mapping was rebuilt on a shared token table and has boundary tests, package
identity includes `lsp` and `rulepacks` with loud root failures and a coverage
guard, and split restrictions that straddle a region boundary are dropped with a
dedicated test. One new split-scoping defect remains: `scopeToRegion` and
`scopeRestrictionsToRegion` still gate on the raw host `record.span` content
hash before using the versioned anchors, so a confirmed user annotation or
restriction inside a script region is dropped after a formatting edit in the
host—the exact formatting-survival case the anchors were added for. The tests
cover unformatted split records only. The package guard is also still a static
list rather than a derived module/package check, and the token+byte anchor path
has no end-to-end mid-token test. Not ready to close.

### Per-item status

1. **Concur — token and normalized offsets are exact and invertible.**
   `UnitCacheKeys.tokenAnchorRange` stores a start/end token index plus a byte
   offset inside each token; `UserAnnotations` persists
   `startTokenOffset`/`endTokenOffset`;
   `UserAnnotationResolver.anchoredRange` prefers that precise range and falls
   back to the older whole-token range and then normalized offsets.
   `SourceNormalizer.normalizedLengthAtByte`/`rawByteAtNormalizedOffset` now
   share `normalizedStarts`, and
   `SourceNormalizerTest.rawAndNormalizedOffsetsAreInverseAtTokenBoundaries`
   covers a token after `, `, a mid-token point, leading whitespace, and trailing
   whitespace. Residual: the new token+byte fields are not exercised end to end;
   `partialTokenConfirmedCandidateSurvivesReformat` uses `find(`, which is a
   whole-token range. Add a confirmation whose start or end cuts a token,
   reformat it, and assert the resolved span and anchor text.
2. **Concur — the package fingerprint covers `lsp` and shared rulepacks and
   fails loudly.** `ENGINE_PACKAGE_PREFIXES` now includes `dev/cint/lsp/` and
   `dev/cint/rulepacks/`, `ENGINE_VERSION` has anchors in both roots,
   `HostPipeline.fingerprint` hashes shared rulepack code, `ofPackages` throws
   for an unreadable/missing root, and `EngineCodeCoverageGuardTest` checks the
   prefix set and obtains entries for every prefix. Residuals: the guard’s module
   set and prefix set are still two hardcoded lists rather than a mapping derived
   from the Gradle modules, a new package in an existing module is not
   discovered, and `HostPipeline.fingerprint` uses `allowEmpty = true` for the
   shared-rulepack probe, so a third-party pack root with no
   `dev/cint/rulepacks/` entries contributes nothing. These are coverage
   maintenance risks, not the original lsp/rulepack omission.
3. **Concur — straddling split restrictions are dropped consistently.**
   `scopeRestrictionsToRegion` now rejects a record whose host span starts before
   the region or ends after the last region line (`HostPipeline.kt:161-163`), and
   `splitAnalysisDropsRestrictionsThatStraddleTheRegionBoundary` asserts the
   restriction is not applied. Records wholly inside get a recomputed clipped
   content hash and cleared anchors before region resolution. Residual: the new
   test covers an unformatted straddle only; operation offsets that extend
   outside the region from a wholly-inside anchor are not validated, and the
   host-based inspector status can disagree with the dropped region analysis.
   The formatted split case is finding 16.

### New finding

16. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:161-165,225-228` — the split scope gate checks the raw host span content hash before using the versioned anchors, so a formatted user annotation or restriction inside a region is dropped.**

   Both `scopeRestrictionsToRegion` and `scopeToRegion` first select
   `record.span` from the host and require
   `contentHash(selectionText(host, record.span)) == record.contentHash`. A
   confirmed AI annotation/restriction stores the span and content hash from the
   confirm time. After a formatting edit in the host document, that raw span
   selects different text and the gate returns null before either function
   clears the host anchors or, for restrictions, computes the clipped hash. The
   versioned unit fingerprint, token/token-offset range, and normalized range
   that were added for formatting survival are therefore never consulted in the
   region. Reproduction: confirm an AI candidate (or restriction) inside a
   `<script>` region of a Vue file, reformat inside that anchor, then analyze;
   the user annotation/restriction is dropped, although the same record in a
   non-split document resolves through `UserAnnotationResolver`. The new split
   tests use unformatted records and do not cover this.

   Suggested fix: when a record carries a versioned unit/anchor identity, skip
   the raw host-span hash gate and let the region resolver use the anchors (or
   resolve the anchor against the host and translate the current span before
   clipping). Keep the raw hash gate only for legacy records without anchors.
   Add formatted split tests for a confirmed user annotation and a v2
   restriction, asserting the resolved region span.

### Concurs / Dissents

- Concurs with the boundary direction: the short circuit remains server-owned,
  the verdict cache remains in `suggest`, persistence remains server-owned,
  `suggest` still depends only on `api`/`core`, and the round-4 anchoring and
  fingerprint changes do not add detection logic to core or change
  candidate-only publication.
- Concurs with the three round-3 items in substance: precise token+byte
  anchoring with the normalized inverse and boundary test, lsp/shared-rulepack
  package coverage with loud roots and a coverage guard, and straddling split
  restrictions dropped with a test.
- Concurs that the unlisted Round 3 finding 14 (`restrictionMatchesProposal`)
  is addressed: it now checks `pathMatches`, the normalized operation shape, and
  `AiRestrictionResolver.resolve` applicability before comparing the unit
  fingerprint, with no remaining cross-file or dormant-record suppression found
  by inspection.
- Dissents: new finding 16 (formatted split records are dropped by the raw
  content-hash gate).

### Residuals

- **Formatted split records** — finding 16. The region scope functions still
  cannot use the new anchors for a formatted host record; the unformatted split
  tests pass. Add a format→analyze split test.
- **Token+byte anchor test depth.** The precise `startTokenOffset`/`endTokenOffset`
  path has no end-to-end mid-token confirmation test; the normalized inverse
  test covers the underlying mapping but not `tokenAnchorRange` or
  `anchoredRange`.
- **Package guard derivation.** `EngineCodeCoverageGuardTest` checks two
  hardcoded sets; a new module/package requires manual updates, and
  `ofPackages` called with multiple prefixes in production only checks that the
  union is non-empty. Derive the expected prefixes from the module set or verify
  each prefix in production.
- **Third-party/optional pack shared code.** `HostPipeline.fingerprint` probes
  `dev/cint/rulepacks/` from `effectivePacks.firstOrNull()` with
  `allowEmpty = true`; a third-party pack root without that package contributes
  no shared code. The bundled `ENGINE_VERSION` covers the bundled package, but
  external pack helpers are not covered. Document or hash the pack artifact.
- **Split operation ranges and inspector status.** A restriction whose anchor is
  wholly inside but whose remove/add target extends outside the region is
  dropped by resolution rather than explicitly reported; `cint/restrictions/list`
  resolves against the host and may disagree with the region result. Consider
  validating operations during clipping and surfacing a split-inapplicable
  status.
- **Docs accuracy.** `docs/ai-annotation-session.md:630` says a non-token-aligned
  confirmation falls back to token offsets, normalized offsets, and then a
  raw-range/content-hash check; the raw-range path is only entered when the
  record has no token/normalized anchor at all, while an anchored record whose
  anchors fail remains dormant. The split clipping paragraph also predates the
  formatted-split gate in finding 16. Correct both when the docs are next
  touched.
- **First-pass/restart baseline and per-language cache** remain accepted
  residuals from earlier rounds; the docs now describe them.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 5

Verdict: The residual decisions are now documented and the split functions do
consult a versioned anchor before the legacy raw host-hash gate, but the
pass-through is not host-to-region anchoring. `LiveServerDecisions.userAnchor`
computes the unit/token/normalized identity against the host document with the
host frontend; for a Vue script region the host Vue syntax does not enumerate
the region's Kotlin expression, so the common confirm path has no anchor at all,
and a record that does have a host anchor will not match the fingerprint
computed against the region source. The new formatted split tests build their
fingerprint from `regionSource`, not from the host, so they pass as
region-anchored records while the production host-anchored case remains
untested and unresolved. `scopeToRegion` also returns versioned records before
the clip/straddle handling, so a versioned straddling user annotation is not
clipped. One open finding; not ready to close.

### Per-item status

1. **Dissent — the split pass-through does not actually resolve formatted host
   region records.** `scopeToRegion` and `scopeRestrictionsToRegion` now return
   a record with `unitFingerprint != null` unchanged before the raw host-hash
   gate (`HostPipeline.kt:164-165,228-230`), which removes the formatting
   rejection for a *region-anchored* record. But the anchor is created by
   `LiveServerDecisions.userAnchor` from `Frontends.forLanguage(doc.language)`
   and `doc.text` (`LiveServerDecisions.kt:433-540`); for a Vue host the host
   Vue syntax profile does not expose the script region's Kotlin expression
   units, so in the normal script-region confirm case `unit == null` and no
   `unitFingerprint`/token/normalized anchor is written, leaving the legacy
   path. If a host anchor is written (for example a template-scope unit), its
   fingerprint was computed on the host text and does not match the region
   Kotlin unit computed by `UserAnnotationResolver`/`AiRestrictionResolver`; the
   pass-through then reaches `resolveAnchored` with a non-matching fingerprint,
   and because token/normalized fields are present the raw-range fallback is
   skipped, so the record goes dormant. The new formatted tests
   (`SplitUserAnnotationsTest.kt:412-537`) compute `unitFingerprint` from
   `regionSource` and a Kotlin region frontend; they are not the production
   host-anchored path. `scopeToRegion` also bypasses the straddle/clip handling
   for a versioned user annotation, unlike `scopeRestrictionsToRegion`. See
   finding 17.
2. **Concur — the listed residual decisions are documented.** The
   `anchoredRecordWithoutTokenDataFallsBackToItsRangeNotTheDeclaration` test and
   the snapshot’s “only a record with no token or normalized anchors uses the
   raw-range/content-hash check” cover the unitless fallback; the stability
   contract freezes `AnchorFingerprint.of` and the offset mapping as part of
   version 2 with a required version bump for future changes; the shared external
   rulepack follow-up and third-party artifact note are present; and
   “splitting a restriction whose remove/add target extends outside the region is
   inapplicable/dormant” is documented. The docs are otherwise reconciled, but
   the claim that the host versioned identity is matched against region text is
   the false part covered by finding 17. The package fingerprint and exact
   token+normalized offset work from Round 4 remain in place.

### New finding

17. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:164-165,228-230` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:433-540` and `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/user/SplitUserAnnotationsTest.kt:412-537` — the split pass-through uses host-scoped unit anchors but the region resolver needs region-scoped anchors, so formatted host region records still fail and the tests use region-built fingerprints.**

   `userAnchor` calls `Frontends.forLanguage(doc.language)` on `doc.text`; for a
   Vue document that is the Vue frontend/syntax model. The Vue expression
   profile covers template expressions but not the `raw_text` of a script
   region, so a candidate inside the script region normally has no enclosing
   expression unit (`unit == null`) and the resulting `UserAnnotation` has no
   `unitFingerprint`, tokens, or normalized offsets. `scopeToRegion` then takes
   the legacy raw host-hash branch and a formatting edit in the host drops the
   record (the round-4 failure). If a host unit does exist, its `UnitIdentity`
   fingerprint and token indices are based on the host Vue tree/text, while
   `SplitAnalyzer` resolves against the region Kotlin source; the fingerprint
   does not match, `resolveAnchored` returns null, and because the record carries
   token/normalized fields the raw-range fallback is skipped (`UserAnnotationResolver.kt:99-110`),
   so the record becomes dormant instead of resolving. The same applies to
   restrictions: `scopeRestrictionsToRegion` returns the host record and
   `AiRestrictionResolver` compares the host fingerprint with region units.
   `splitAnalysisKeepsAFormattedAnchoredUserAnnotation` and
   `splitAnalysisKeepsAFormattedAnchoredRestriction` pass because they compute
   `unitFingerprint` directly from `regionSource` with the Kotlin frontend, not
   from the host as production does. A versioned straddling user annotation also
   bypasses the clip path because the early return precedes it.

   Suggested fix: create region-relative anchors at confirm time (or at scope
   time) by locating the script region containing the host span, parsing the
   region with its language frontend, and rewriting the span/token/normalized
   anchors to region coordinates; only then use the region fingerprint. If the
   region cannot be identified, fall back to the clipped legacy path. Add tests
   that construct the record from the host `VueFrontend`/host text (the same
   helper as `LiveServerDecisions.userAnchor`), format the host, and assert the
   exact region span for both a confirmed user annotation and a restriction.
   Handle a versioned straddling user annotation with the same clip/drop policy
   as a straddling restriction.

### Concurs / Dissents

- Concurs with the boundary direction: the short circuit, verdict cache,
  persistence, core/suggest dependency direction, candidate-only publication,
  package fingerprint, and exact token+normalized offset work remain unchanged.
- Concurs with the residual-decision documentation except for the split
  pass-through claim, which finding 17 rejects.
- Dissents: the first item (formatted host region records) is not resolved in
  production; new finding 17.

### Residuals

- **Formatted host-to-region anchoring** — finding 17. The tests currently
  prove region-built fingerprints, not host-built ones; the production Vue
  script path has no host unit anchor.
- **Token+byte anchor test depth.** The precise `startTokenOffset`/`endTokenOffset`
  path still has no end-to-end mid-token confirmation test; the normalized
  inverse test covers the underlying mapping.
- **Package guard derivation and external pack helpers.** The guard remains a
  static module/prefix pair, and third-party pack shared helpers are outside the
  bundled package fingerprint; both are documented follow-ups.
- **Split operation ranges and inspector status.** A versioned restriction whose
  target leaves the region is documented dormant, but the host inspector status
  can still disagree with region analysis; add a status/test for the
  split-inapplicable case.
- **First-pass/restart baseline, per-language cache header, and memory-only
  similar hints** remain accepted residuals from earlier rounds and are now
  described in the docs.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 6

Verdict: Finding 17 is fixed for the decision-time path. `SplitAnchorResolver`
locates the containing script region, parses it with the region language
frontend, and produces region-relative unit/token/normalized anchors;
`LiveServerDecisions` uses it for confirmed user annotations and restriction
records; the scoping step passes region-anchored records through, drops
versioned straddling records, and the new host-shaped tests build from the host
Vue document and assert exact formatted spans. The resolver is correctly placed
in the frontend module and used by the server without a core/language boundary
violation, and the residual decisions are documented. Two integration defects
remain: the host-side restriction inspector/proposal matcher still resolves
region-relative records against the host document, so a confirmed split
restriction is reported dormant and can escape the re-proposal filter; and
region membership is still selected from the stale host `record.span` line
range, so a formatting edit before the `<script>` region can drop a record even
though its region-relative anchor is valid. Not ready to close.

### Per-item status

1. **Concur — finding 17 is fixed for decision-time anchoring and scoping.**
   `SplitAnchorResolver.anchor` finds the containing `SplitHandler` region,
   re-parses the region text with the region language frontend, and returns
   region-relative unit/token/normalized anchors plus the host span offset
   (`SplitAnchorResolver.kt:28-108`). `LiveServerDecisions.userAnchor` and
   `anchorIdentity` use it, and `relativeOperations` maps restriction operation
   offsets through the region context (`LiveServerDecisions.kt:344-398,476-540`).
   `scopeToRegion` and `scopeRestrictionsToRegion` return a versioned record
   unchanged for the region resolver; a versioned user annotation that straddles
   the region boundary is now dropped (`HostPipeline.kt:228-230`), with the new
   `versionedStraddlingUserAnnotationIsDropped`. The host-shaped tests at
   `SplitUserAnnotationsTest.kt:541-712` build the record with
   `SplitAnchorResolver`/`VueFrontend` from a host Vue document, format the host,
   and assert exact region spans for a user annotation and a restriction. Residuals:
   region membership still uses the stale host span line range (finding 19), and
   host-side restriction consumers still resolve on the host (finding 18).
2. **Concur — residual decisions are documented.** The unitless fallback is in
   the `anchoredRecordWithoutTokenDataFallsBackToItsRangeNotTheDeclaration` test
   and the snapshot; the stability contract freezes `AnchorFingerprint.of` and
   the offset mapping inside version 2 and requires a future versioned read
   path; shared external rulepack helpers and the artifact follow-up are
   documented; a restriction operation that leaves the region is documented
   dormant; the split section now describes region-relative anchors and
   consistent straddle dropping. Residual: the host-side status/re-proposal and
   region-membership behavior are not described; see findings 18 and 19.
3. **Concur — layer ownership is right.** `SplitAnchorResolver` lives in
   `languages/frontend`, owns the split-region lookup and region frontend parse,
   and is consumed by `hosts/server`; `core/host` does not depend on frontend and
   continues to own the host/region scope, and no core detection or language
   policy moved into server. The frontend module depends on core as before;
   server already depended on frontend (`Frontends`). No boundary violation
   found.

### New findings

18. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:79-103` and `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:416-455` — host-side restriction consumers do not understand region-relative restriction fingerprints.**

   Restriction records confirmed in a split document now carry the
   region-relative `unitFingerprint`/token data from `SplitAnchorResolver`
   (`LiveServerDecisions.anchorIdentity`/`relativeOperations`), but
   `LiveServerRestrictions.status` still parses the host Vue document and calls
   `AiRestrictionResolver.resolve` against host units, then checks the cached
   output. A v2 region fingerprint has no host match and no legacy fallback, so
   `resolved` is empty and the inspector reports an applied split restriction as
   `dormant`/`unknown` instead of `active`. `LiveServerCandidates.restrictionMatchesProposal`
   likewise computes a host `UnitIdentity.unitFingerprint` and a host-token
   operation shape for the proposal, so a record region fingerprint does not
   match and the “confirmed restriction must not be re-proposed” filter misses a
   proposal in that region. The host-shaped split tests cover analysis only;
   neither the inspector status nor the proposal matcher is exercised with a
   region record.

   Suggested fix: make both consumers use `SplitAnchorResolver` (or a stored
   region identity) to resolve the record against its region and compare
   region-relative fingerprints/shapes; if the host-side status cannot know the
   region, report a split-specific status rather than dormant. Add a server/API
   test for `cint/restrictions/list` on a confirmed split restriction and a
   re-proposal attempt in that region.

19. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:158-163,225-230` with `analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnchorResolver.kt:78-79` — region membership still comes from the stale host span line range, while the region start is never persisted with the record.**

   `scopeToRegion`/`scopeRestrictionsToRegion` still select and reject regions
   using `record.span` host lines before the versioned pass-through. After a
   formatting edit that adds or removes lines before the `<script>` region, the
   record's host span keeps the old line numbers while the region's
   `startLine`/`lastHostLine` shift; the record can fall outside the new range
   and be dropped even though its region-relative unit/token/normalized anchor
   is still valid. The new host tests format inside the script region and keep
   the region start line fixed, so they do not expose this. `SplitAnchor`
   computes `regionStartByte`/`regionStartLine`, but `LiveServerDecisions`
   persists only the candidate host span and the region-relative anchors, not a
   region identity; `regionStartLine` is unused.

   Suggested fix: persist a region identity/ordinal (or a region text/start
   digest) with a split decision, or scope versioned records by trying the
   containing regions and matching the region anchor instead of the stale host
   line range. Add a test that inserts/removes a line above `<script>`, formats
   the host, and asserts a confirmed user annotation and restriction still
   resolve in the correct region; keep a straddling-anchor test.

### Concurs / Dissents

- Concurs with the round-6 resolver/decision/scoping design: region-relative
  anchors at decision time, region pass-through, and versioned straddle dropping
  are the right shape, and the host-shaped tests now cover the production
  anchoring source rather than `regionSource`.
- Concurs with the layering: frontend owns split-region parsing and the new
  resolver; server consumes it; `core/host` retains scoping and no core/language
  dependency was crossed.
- Concurs with the documented residual decisions, including the frozen anchor
  recipe/offset mapping, unitless fallback, shared external pack follow-up, and
  split remove-target dormancy.
- Dissents: new findings 18 (host-side restriction status/re-proposal) and 19
  (stale host span region membership).

### Residuals

- **Host-side restriction status/re-proposal** — finding 18. `cint/restrictions/list`
  and the re-proposal filter still operate on host coordinates; the split
  inspector/test path is missing.
- **Region membership identity** — finding 19. `SplitAnchor.regionStartLine` is
  computed but not persisted; formatting before the script region can drop a
  valid region anchor.
- **Server decision path coverage.** The host-shaped tests call
  `SplitAnchorResolver` and write records directly; they do not drive
  `LiveServerDecisions.userAnnotation`/`relativeOperations` through a
  `cint/candidates/decide` split flow. Add an end-to-end split confirm and
  formatted re-analysis assertion.
- **Token+byte anchor test depth.** The precise `startTokenOffset`/`endTokenOffset`
  path still lacks an end-to-end mid-token confirmation test; the normalized
  inverse test covers the underlying mapping.
- **Package guard and external pack helpers** remain the documented maintenance
  follow-ups: the guard uses static module/prefix sets, and third-party pack
  shared helpers are outside the bundled package fingerprint.
- **First-pass/restart baseline, per-language cache header, memory-only similar
  hints, and split operation-range/inspector status** remain accepted residuals
  from earlier rounds and are documented.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 7

Verdict: Findings 18 and 19 are fixed for the normal one-region path.
`LiveServerRestrictions.resolveForStatus` and
`LiveServerCandidates.restrictionMatchesProposal` now parse the region source
and compare region fingerprints, `SplitRegion.ordinal`/language is persisted on
user annotations and restriction records, scoping uses it so line insertions
above the script region no longer drop a record, and
`splitRestrictionStatusAndSuppressionUseTheRegionIdentity` exercises the
production server path (status after formatting with an inserted host line and
proposal suppression). The region model is coherent and owned at the right
layer. One new defect remains: the host-side status/proposal matcher do not
enforce the persisted region ordinal/language consistently. The proposal matcher
ignores the record's region and can suppress an identical proposal in another
script region; the status resolver falls back to *all* regions (and then the
host) when a recorded ordinal/language does not resolve, so it can report
`active` while region scoping drops the record. Ordinal membership is also
positional: inserting or removing an entire script region shifts it, unlike the
tested line insertions. Not ready to close.

### Per-item status

1. **Concur — status and proposal suppression now use the region resolver.**
   `LiveServerRestrictions.resolveForStatus` selects a region, parses its source
   with its language frontend, resolves the record there, shifts the resolved
   span back to host coordinates, and checks the cached host output
   (`LiveServerRestrictions.kt:79-147`).
   `LiveServerCandidates.restrictionMatchesProposal` uses
   `SplitAnchorResolver` for the proposal context, parses the same region,
   compares region-relative operation shapes and unit fingerprints, and only
   suppresses a matching record (`LiveServerCandidates.kt:416-455`). The server
   test `splitRestrictionStatusAndSuppressionUseTheRegionIdentity` checks
   `cint/restrictions/list` before and after a formatted host with an inserted
   line, then publishes a proposal and asserts it is suppressed. Residual: the
   matcher/status do not enforce the record's persisted region ordinal/language;
   see finding 20.
2. **Concur — region ordinal/language is persisted and used for membership.**
   `SplitRegion` gained `ordinal`; `SplitAnalyzer` assigns it by region index;
   `SplitAnchorResolver` records it with the region language;
   `UserAnnotation`/`AiRestrictionRecord` serialize both;
   `HostPipeline.scopeToRegion`/`scopeRestrictionsToRegion` match
   `region.ordinal`/language before host span logic, so line insertions above the
   region survive. Records without an ordinal keep the legacy host-span path.
   Residual: ordinal is positional; inserting/removing an entire region shifts
   it and can make a record dormant or bind to an identical region. The docs
   claim line insertions only; see Residuals.
3. **Concur — the region model is coherent and owned at the right layer.**
   `SplitRegion.ordinal` lives in the core split fact; `SplitAnalyzer` (frontend)
   assigns it; `SplitAnchorResolver` (frontend) computes region-relative
   anchors; `hosts/server` persists and consumes the identity; `core/host` scopes
   with the ordinal and does not depend on frontend. The persisted
   regionOrdinal/language is one identity alongside the unit/token anchors, not
   a second competing region model. Residual: `SplitAnchorResolver` and the
   status resolver use the region list index while `SplitRegion.ordinal` is the
   model field; they agree because `SplitAnalyzer` assigns the same index, but a
   future custom `SplitHandler` should keep the two in one source.
4. **Concur — tests exercise the production server path.** The new
   `splitRestrictionStatusAndSuppressionUseTheRegionIdentity` uses the harness,
   `cint/restrictions/list`, host formatting, and
   `publishCandidatesForTest`; the earlier
   `productionHostAnchoredUserAnnotationResolvesAfterHostFormatting` and
   restriction tests build records with `SplitAnchorResolver` from a host Vue
   document and assert exact formatted spans. Residual: no two-region test, no
   stale-ordinal/language test, and no split `cint/candidates/decide` confirm
   through `LiveServerDecisions` (the user-annotation confirm path is still
   covered only at the resolver/scoping level).
5. **Concur — docs match the implemented model for the normal path.** The
   snapshot now documents region-relative anchors, persisted region
   ordinal/language, line-insertion survival, straddling-versioned dropping,
   inspector/proposal region resolution, and the residual decisions. Residual:
   the docs do not state the ordinal fallback behavior for a missing/mismatched
   region or the positional region-insertion limitation; finding 20 and the
   ordinal residual below.

### New finding

20. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:416-455` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:95-147` — region ordinal/language is persisted and used for scoping, but the host-side status and proposal matcher do not enforce it consistently.**

   A restriction record now carries `regionOrdinal`/`regionLanguage`, and
   `scopeRestrictionsToRegion` only applies it to the matching ordinal/language
   region. `restrictionMatchesProposal` instead computes the proposal's region
   context but never compares it with the record's persisted region; it resolves
   the record against the proposal's region whenever the path/operation shape and
   unit fingerprint match. With two identical `<script>` regions, a restriction
   confirmed in region A can therefore suppress an AI proposal for the same code
   in region B. `resolveForStatus` selects the recorded region only when the
   ordinal exists and its language matches, but its expression
   `record.regionOrdinal?.let { ... }?.let(::listOf) ?: regions` falls back to
   **all** regions when the ordinal is out of range or the language changed;
   if no region resolves it then falls through to the host parse. A record whose
   persisted ordinal no longer matches can therefore be reported `active` in one
   region while `scopeRestrictionsToRegion` drops it entirely. The new status
   test uses one region and a matching language, so neither path is covered.

   Suggested fix: pass the record's region identity into both consumers and
   require `record.regionOrdinal`/`regionLanguage` to match the region being
   considered; only a null ordinal (legacy) may fall back to all regions, and a
   persisted but stale ordinal should yield `dormant`/`unknown` and no
   suppression. Add a two-identical-region proposal test and a stale
   ordinal/language status test.

### Concurs / Dissents

- Concurs with the round-7 direction: region-relative anchors at confirmation
  time, persisted region ordinal/language, scoping by ordinal, region-aware
  status/proposal matching, and the production server-path test all fit the
  059 restriction identity and the 060 host-to-region anchor model.
- Concurs with the layer ownership: `SplitRegion.ordinal` is a core split fact,
  `SplitAnalyzer`/`SplitAnchorResolver` live in frontend, and `hosts/server`
  persists/consumes the identity without a core/language dependency.
- Concurs with the docs for line-shift behavior and the documented residual
  decisions.
- Dissents: new finding 20 (region ordinal/language is not enforced by the
  status/proposal matcher) and the positional ordinal limitation for region
  insertion/removal.

### Residuals

- **Positional region ordinal.** `SplitAnalyzer` assigns ordinals by list
  index. Inserting or removing a `<script>` region shifts them; a record can go
  dormant or, with identical code, bind to another region. The docs claim
  stability under line insertions only. A stable region key (content/start
  digest) would be needed if region insertion must preserve identity; add a
  two-region insertion/reorder test either way.
- **Single source for region ordinal.** `SplitAnchorResolver`/status use the
  region list index while `SplitRegion.ordinal` is the model field. They agree
  because `SplitAnalyzer` writes the index, but the duplication invites drift if
  a custom `SplitHandler` supplies ordinals.
- **Server decision-path coverage.** The host-shaped tests exercise
  `SplitAnchorResolver`, scoping, status, and proposal publication, but not a
  split `cint/candidates/decide` confirm through
  `LiveServerDecisions.userAnnotation`/`relativeOperations`; add that end-to-end
  assertion.
- **Token+byte anchor test depth.** The precise `startTokenOffset`/`endTokenOffset`
  path still lacks an end-to-end mid-token confirmation test; the normalized
  inverse test covers the underlying mapping.
- **Package guard and external pack helpers** remain documented maintenance
  follow-ups: the guard uses static module/prefix sets, and third-party pack
  shared helpers are outside the bundled package fingerprint.
- **First-pass/restart baseline, per-language cache header, and memory-only
  similar hints** remain accepted residuals from earlier rounds and are
  documented.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 8

Verdict: The stable region identity is implemented at the right layer and is
used consistently by scoping, status, and proposal suppression.
`SplitRegion.identityKey` (language plus normalized region text) is a core split
fact; `SplitAnalyzer`/`SplitAnchorResolver` derive it in frontend; the server
persists `regionKey` and uses it first, with ordinal/language as legacy fallback;
ambiguous or stale keys are dormant in scoping, status, and suppression. The
server-path tests cover a split confirm, status, host formatting, and
suppression, and the region-key insertion/removal/reorder test is good. Three
new defects remain: split-aware discovery only enumerates one level of regions
and drops host-level units; the session still advertises the host language and
uses a host type lookup for region units; and the ambiguity policy is not
tested, while the docs call the ordinal a positional tiebreaker even though the
code deliberately makes duplicate region identities dormant. Not ready to close.

### Per-item status

1. **Concur — `regionKey` is owned at the right layer and used by scoping,
   status, and suppression.** `SplitRegion.identityKey` is a core split fact
   (`language` plus `SourceNormalizer.normalize(text)`);
   `SplitAnalyzer` computes `identityAmbiguous`; `SplitAnchorResolver` exposes
   `regionKey`/`regionAmbiguous`; `UserAnnotation`/`AiRestrictionRecord`
   persist the key; `HostPipeline.scopeToRegion`/`scopeRestrictionsToRegion`,
   `LiveServerRestrictions.resolveForStatus`, and
   `LiveServerCandidates.restrictionMatchesProposal` all prefer the key, with
   ordinal/language only as legacy fallback. The new
   `regionKeySurvivesRegionInsertionRemovalAndReorder` and
   `splitRestrictionConfirmAndSuggestUseTheRegionIdentity` tests exercise the
   identity through analysis and the server. Residual: discovery does not carry
   `regionKey`/ambiguity into `SessionUnit` or the AI session; it uses region
   index prefixes only. This is not currently a misbind because decisions
   recompute the region context from the host span, but the key is not literally
   used by discovery.
2. **Concur — ambiguous and stale keys are dormant and the three consumers
   agree by inspection.** Ambiguous keys are dropped by both scope functions,
   status returns no match unless exactly one region matches, and suppression
   requires a non-ambiguous matching context; a stale key similarly drops in all
   three. Ordinal-only legacy records use ordinal/language in all three; a key
   record never falls back to ordinal. Residual: there is no test for
   `identityAmbiguous`; `identicalUnitsInDifferentScriptRegionsDoNotCrossSuppress`
   uses two identical regions but only asserts the second proposal remains, so
   it would also pass with a different policy and does not assert the confirmed
   record is dormant or that status/scoping agree. See finding 23.
3. **Dissent — split-aware discovery is still one level deep and does not
   maintain the provider/session language/type contract.** `sessionDocument`
   for a `SplitHandler` host enumerates only `frontend.regions(source)` and
   `regionSessionUnits` returns an empty list when the region frontend is itself
   a SplitHandler, so nested split regions have no AI worklist even though
   `SplitAnalyzer` recurses and `engine.md` says split handlers can nest. It also
   drops host-level expression units (for example Vue template expressions),
   while `ai-annotation-session.md` still says every expression node in the
   document is enumerated. `SessionDocument.language` remains the host language
   and `renderBatchPrompt` sends only that language while `SessionUnit.language`
   is the region language; `sessionTypeLookup` parses the host frontend/tree and
   uses the host oracle, so `lookup_type` for a JavaScript/TypeScript region unit
   gets host/Vue facts or null rather than the region type. Host-shifted spans
   and candidate-only publication are correct. See findings 21 and 22.
4. **Concur — the real server-path test is meaningful but incomplete.**
   `splitRestrictionConfirmAndSuggest` confirms a proposal through
   `cint/candidates/decide`, checks `cint/restrictions/list` active before and
   after host formatting with an inserted line, and asserts the later proposal
   set is empty; this is the right production seam. `identicalUnitsInDifferentScriptRegionsDoNotCrossSuppress`
   exercises two identical script regions but does not assert ambiguity
   dormancy or status. No cache-enabled split discover→confirm→reformat→suggest
   test exists; the earlier host-shaped resolver tests cover scoping, not the
   full server decision path for a confirmed **user annotation**. Residual.
5. **Dissent — the docs do not fully match the new region model and split
   discovery.** `ai-annotation-session.md:646-650` calls the region ordinal a
   "positional tiebreaker" while the code marks duplicate region identities
   ambiguous and dormant; the ordinal is only a fallback when `regionKey` is
   absent. The docs also do not mention that split discovery is region-only /
   one-level / host-language for the session, or that host-level units are
   omitted; `engine.md` says split handlers can nest. See findings 21 and 23.

### New findings

21. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDiscovery.kt:37-165` with `analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnalyzer.kt:78-115` and `docs/engine.md:70-80` — split-aware discovery is single-level and silently drops nested regions and host-level units.**

   `sessionDocument` chooses the split branch whenever the host frontend is a
   `SplitHandler`, then enumerates only `frontend.regions(source)`. It never
   includes host-level `syntax.expressionUnits(tree)` units, and
   `regionSessionUnits` explicitly returns `emptyList()` when
   `regionFrontend is SplitHandler`. `SplitAnalyzer` supports nested regions and
   recurses into them, and `docs/engine.md` documents nesting, but the AI
   worklist gives those regions no units. The docs still say the server
   enumerates every expression node in the document, including host template
   expressions; the split branch does not. The `identicalUnits...` test uses
   two top-level regions and cannot expose either gap.

   Suggested fix: recursively enumerate nested `SplitHandler` regions with
   host-shifted spans and a stable unit prefix, and either include host-level
   units alongside region units or explicitly document split discovery as
   region-only. Add a nested-split worklist test and a host-template-unit test.

22. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDiscovery.kt:60-80,170-205` with `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:708-762` — the session advertises the host language and host type oracle for region units.**

   Region units carry `language = region.language` and region-relative text,
   but `SessionDocument.language` is still `doc.language` (for Vue, `"vue"`),
   and `renderBatchPrompt` sends only the document language; the provider is
   told the host language for JavaScript/TypeScript units. `sessionTypeLookup`
   builds the host frontend/tree and `oracleFor(host frontend)`, so
   `lookup_type` for a region unit resolves against the host Vue tree (or the
   null Vue oracle) rather than the region language oracle. Candidate-only
   publication is unaffected, but the provider/session tool contract is
   inconsistent for split documents.

   Suggested fix: include the per-unit language (or a region-language marker) in
   the batch payload and map `lookup_type`/type facts through the containing
   region frontend and oracle; keep host coordinates for ranges. Add a provider
   test that asserts the unit language and a region type lookup for a
   JavaScript/TypeScript script.

23. **`docs/ai-annotation-session.md:646-650` with `analysis-engine/hosts/server/src/test/kotlin/dev/cint/server/suggest/LiveVerdictReuseTest.kt:1091-1130` — the ambiguity policy is untested and the docs call the ordinal a tiebreaker the code does not use.**

   The region key is stable and ambiguity is dormant by design: scope requires
   `!region.identityAmbiguous`, status returns empty unless exactly one region
   matches, and suppression requires a non-ambiguous matching context. The docs
   say the identity is persisted “alongside the region ordinal as a positional
   tiebreaker,” but a record with a duplicate `regionKey` is dropped even when
   it has an ordinal; the ordinal is only used when `regionKey` is absent. The
   only duplicate-region test confirms one of two identical proposals and
   asserts the other remains; it does not assert the confirmed restriction is
   dormant, that `identityAmbiguous` makes scoping/status/suppression agree, or
   that a unique-region proposal is not suppressed.

   Suggested fix: state in the docs that ordinal/language are legacy fallback
   fields and duplicate region identities are dormant (or implement an explicit
   ordinal tiebreaker consistently in scope/status/suppression and update the
   tests). Add a duplicate-region test asserting no annotation is applied,
   `cint/restrictions/list` is dormant, and neither identical proposal
   suppresses the other.

### Concurs / Dissents

- Concurs with the regionKey model and layering: stable identity is a core split
  fact, frontend derives it, server persists/consumes it, and scoping, status,
  and suppression use it first with ordinal/language as legacy fallback.
- Concurs with the host-shifted split discovery spans and candidate-only
  behavior; no provider or established-annotation path was added.
- Concurs with the real server-path confirm/status/suppression test and with
  the region-key insertion/removal/reorder test.
- Dissents: split discovery depth/provider contract (findings 21 and 22) and the
  undocumented/untested ambiguity policy and ordinal wording (finding 23).

### Residuals

- **Ambiguity test depth.** Add a duplicate-region-key test for
  scoping/status/suppression dormancy and a unique-region cross-suppression
  test; `identicalUnitsInDifferentScriptRegionsDoNotCrossSuppress` alone does
  not pin the policy.
- **Host template / nested split units.** Split discovery omits host-level
  expression units and nested `SplitHandler` regions; document region-only
  behavior or implement recursion.
- **Session language/type lookup.** Region units are reported under the host
  language and typed with the host oracle; include per-unit language and a
  region-aware type lookup.
- **Region key content migration.** A unique region key can match a different
  region if the original code disappears and another identical region remains;
  this is a content-addressing tradeoff, but document it and consider whether a
  per-region start/identity supplement is needed.
- **Server decision path coverage.** The host-shaped tests exercise
  `SplitAnchorResolver`, scoping, status, and proposal publication, but not a
  split `cint/candidates/decide` confirm for a **user annotation** through
  `LiveServerDecisions.userAnnotation`/`relativeOperations`; add that end-to-end
  assertion.
- **Token+byte anchor test depth.** The precise `startTokenOffset`/`endTokenOffset`
  path still lacks an end-to-end mid-token confirmation test; the normalized
  inverse test covers the underlying mapping.
- **Package guard and external pack helpers** remain documented maintenance
  follow-ups: the guard uses static module/prefix sets, and third-party pack
  shared helpers are outside the bundled package fingerprint.
- **First-pass/restart baseline, per-language cache header, and memory-only
  similar hints** remain accepted residuals from earlier rounds and are
  documented.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 9

Verdict: The round-8 defects are addressed in substance. Split discovery now
recurses through nested `SplitHandler` regions and includes host-level units
outside regions; unit prefixes, absolute offsets, and spans are coherent; batch
prompts carry per-unit language; `lookup_type` routes spans through the
containing region frontend/oracle; declaration invalidation recurses through
regions; and the ambiguity section now correctly says duplicate region
identities are dormant and ordinal/language are legacy fallback. The new
host+region and nested tests are useful, and the server-path split tests remain
sound. One docs defect remains: the snapshot says batch prompts carry a sorted
`languages` set, but the prompt only carries the document language and a
per-unit `language` when it differs. Residuals cover the documented nested region
selection limitation, ambiguity status/suppression test depth, and host-level
declaration invalidation. Not ready to close.

### Per-item status

1. **Concur — recursive nested region enumeration and host+region worklist.**
   `LiveServerDiscovery` now builds host units plus recursively collected leaf
   regions (`collectLeafRegions`), assigns `host-`/`r…-` prefixes, shifts
   region spans/bytes to host coordinates, and filters host units that overlap a
   region. `SplitDiscoveryNestedTest` verifies nested region absolute start and
   prefix; `splitDiscoveryIncludesHostAndRegionLanguages` verifies both host and
   region unit languages; `splitRestrictionConfirmAndSuggest` covers the server
   path. Residual: nested **region selection** is still one-level for
   `SplitAnchorResolver`/status/suppression (documented limitation); the nested
   test exercises only `collectLeafRegions`, not `sessionDocument` units,
   `sessionTypeLookup`, or a nested confirmation.
2. **Concur — per-unit language and region-routed type lookup.**
   `SessionUnit.language` is attached to each unit, `renderBatchPrompt` emits
   per-unit `language` when it differs from the document, and
   `sessionTypeLookup` builds leaf-region contexts with the region frontend and
   `oracleFor(regionFrontend)`, using host coordinates for the request and
   region-local offsets for the tree/oracle. `splitTypeLookupUsesTheRegionFrontendAndOracle`
   proves a JavaScript region can return a region oracle fact. Residual: docs
   claim a sorted `languages` set that is not sent; see finding 24.
3. **Concur — ambiguity policy and docs.** `Scope` requires a unique
   non-ambiguous region key, status returns no match unless exactly one region
   matches, and proposal suppression requires a non-ambiguous matching context;
   `ambiguousRegionIdentityLeavesAUserAnnotationDormant` covers the user
   annotation. The snapshot now says ordinal/language are legacy fallback and
   duplicate identities are dormant. Residual: no server test asserts
   `cint/restrictions/list` dormant and suppression inert for the same
   duplicate-region record; `identicalUnits...` only checks the second proposal
   remains.
4. **Concur — the real server-path test is adequate for the normal split
   case.** `splitRestrictionConfirmAndSuggest` drives confirm, status before and
   after host formatting with an inserted line, and post-format suppression
   through the harness; `splitDiscoveryIncludesHostAndRegionLanguages` and the
   type-lookup test exercise the new discovery/session helpers. Residual: no
   nested `sessionDocument`/type-lookup test and no split user-annotation
   confirm through `LiveServerDecisions` (only resolver/analysis tests).
5. **Dissent — one docs statement does not match the prompt.** The snapshot
   says the sorted `languages` set is carried; `renderBatchPrompt` has no
   `languages` field, and the test provider reads an empty `languages` node.
   Everything else is reconciled. See finding 24.

### New finding

24. **`docs/ai-annotation-session.md:659-662` with `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:715-750` — the docs claim the batch prompt carries a sorted `languages` set, but only the document language and occasional per-unit language are sent.**

   The snapshot says “Batch prompts carry the document language plus per-unit
   `language` and the sorted `languages` set, so providers see
   JavaScript/TypeScript region languages.” `renderBatchPrompt` emits `language`
   only on a unit whose language differs from `document.language`; there is no
   `languages` field at the payload or document level.
   `splitDiscoveryIncludesHostAndRegionLanguages`/the provider fixtures read
   `root.path("languages")`, but it is empty and the test does not assert it, so
   the gap is not caught.

   Suggested fix: add
   `languages = document.units.map { it.language }.distinct().sorted()` (or a
   document-level languages set) to the batch payload and assert it in the split
   discovery test, or remove the sentence from the snapshot. This is internal
   prompt data, not a protocol change.

### Concurs / Dissents

- Concurs with recursive nested discovery, host+region worklist ownership,
  host-shifted offsets/prefixes, per-unit language reporting, region-routed type
  lookup, and the corrected ambiguity policy.
- Concurs with the production split confirm/status/suppression test and the
  host-shaped resolver tests; no established annotation or provider logic crossed
  into core or frontend.
- Dissents: the docs `languages` claim (finding 24).

### Residuals

- **Nested region selection.** Discovery and `SplitAnalyzer` recurse; the
  one-level `SplitAnchorResolver`/status/selection path remains a documented
  limitation. Nested units can be graded but a nested confirmation/restriction
  cannot rely on the same leaf region identity; either route selection through
  the recursive enumeration or mark nested suggestions review-only.
- **Nested worklist/type tests.** `SplitDiscoveryNestedTest` only calls
  `collectLeafRegions`; add a `sessionDocument` test for nested unit IDs,
  absolute spans, and host+region filtering, plus a nested type-lookup test.
- **Ambiguity server-path test.** Add a duplicate-region test asserting
  `cint/restrictions/list` is dormant and proposal suppression is absent for
  both identical regions; the current ambiguity test covers user analysis only.
- **Host-level declaration invalidation.** Host units outside regions are now in
  the worklist, but `collectDeclarations` for a `SplitHandler` collects only
  regions; a host declaration outside a region is not part of the invalidation
  map. Parse host declarations too or document that host code outside regions
  has no declaration invalidation.
- **Host parent integrity.** Filtering host units that overlap a region can
  leave a child `parentUnitId` pointing at a filtered host parent; clear or
  include the ancestor before the prompt.
- **Region key content migration.** A unique region key can match a different
  identical region after the original code disappears; this is a
  content-addressing tradeoff and should be documented.
- **Server decision-path coverage.** A split user-annotation confirm through
  `LiveServerDecisions`/`relativeOperations` is still not exercised end to end.
- **Token+byte anchor test depth, package guard/external pack helpers,
  first-pass/restart baseline, per-language cache header, and memory-only hints**
  remain documented residuals from earlier rounds.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 10

Verdict: The round-9 items are addressed in substance. Leaf-region enumeration is
now shared in frontend and used by the resolver, status, and suppression; the
prompt conditionally carries a deduplicated `languages` set plus per-unit
language, preserving the single-language shape; host parent IDs are cleared when
a host ancestor is filtered; declaration invalidation now collects direct host
declarations as well as regions; and the docs correctly describe the conditional
language metadata. Two defects remain. `SplitAnalyzer` computes
`identityAmbiguous` only within one recursion level, while the resolver/status/
suppression flatten all leaf regions, so duplicate identities split across
nesting levels can make scoping apply a record while status and suppression treat
it as dormant. The docs still call nested handler selection a limitation even
though nested leaf selection is now recursive; only overlapping regions (and
legacy nested ordinal records) remain. Not ready to close.

### Per-item status

1. **Concur — recursive leaf-region selection is coherent in the main path.**
   `SplitAnchorResolver.leafRegions` recursively collects absolute leaf regions;
   `SplitAnchorResolver.anchor`, `LiveServerRestrictions.resolveForStatus`, and
   `LiveServerCandidates.restrictionMatchesProposal` use it;
   `SplitAnalyzer.analyzeRegions` recurses and `HostPipeline` scopes leaf
   records with the region identity. The host+region worklist, prefixing, and
   absolute spans remain coherent. Residual: ambiguity is still computed locally
   in `SplitAnalyzer` while the other consumers flatten leaves; see finding 25.
   Legacy nested ordinal records also do not share the flattened ordinal.
2. **Concur — document/unit language metadata and `languages` match the
   contract.** `renderBatchPrompt` emits per-unit `language` only when it differs
   from the document and a deduplicated sorted `languages` set only when units
   introduce a non-host language; a single-language document is unchanged.
   `splitTypeLookupUsesTheRegionFrontendAndOracle` verifies region routing.
   Residual: `splitDiscoveryIncludesHostAndRegionLanguages` reads
   `root.path("languages")` but does not assert the set; add that assertion.
3. **Concur — host parent integrity and host declaration invalidation are
   correct by inspection.** `hostIds` filters host units and clears a
   `parentUnitId`/`parentSpan` whose host parent was filtered out;
   `collectDeclarations` now collects direct host declarations before recursing
   through split regions. Residuals: no test covers a filtered host parent or a
   host-level declaration change, and nested/overlapping handlers still need
   coverage.
4. **Dissent — the nested-region/ambiguity contract has one unreconciled
   cross-level case.** `SplitAnalyzer` marks `identityAmbiguous` by counting
   duplicates only in the current `regions` list, while
   `SplitAnchorResolver.leafRegions` and the status/suppression consumers count
   duplicates across all leaves. A nested leaf and a top-level leaf with the
   same language/normalized text therefore make status/suppression dormant while
   scoping can still apply the record. See finding 25.
5. **Dissent — the docs still call nested handler selection a limitation.** The
   snapshot says nested/overlapping handlers remain a limitation of region
   selection, but round 10 routes resolver/status/suppression through recursive
   leaf regions. Only overlapping regions and legacy nested ordinal records
   remain; correct the sentence and add the nested end-to-end tests. See finding
   26.

### New findings

25. **`analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnalyzer.kt:82-90` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt`/`LiveServerRestrictions.kt` and `analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt` — ambiguity is computed per recursion level in `SplitAnalyzer` but globally over leaf regions in the other consumers.**

   `SplitAnalyzer` computes `identityAmbiguous` as
   `regions.count { it.identityKey == key } > 1` for the `regions` list at the
   current `analyzeRegions` call. A nested `SplitHandler` recurses into a
   separate call, so two identical leaf regions at different nesting levels are
   each locally unique. `SplitAnchorResolver.leafRegions` flattens all leaves;
   status and suppression therefore see two matching keys and treat the record
   as ambiguous/dormant, while `HostPipeline.scopeToRegion` receives the local
   `region.identityAmbiguous=false` and applies it. A confirmed split record can
   be applied by analysis while `cint/restrictions/list` reports dormant and
   proposal suppression ignores it, contradicting the docs’ duplicate-identity
   policy. No test builds duplicate identities across nesting levels.

   Suggested fix: compute one global leaf identity/ambiguity set in the shared
   frontend helper and use it in `SplitAnalyzer`, `SplitAnchorResolver`, status,
   and suppression (or make `scope` consult `leafRegions` rather than the local
   flag). Add a cross-level duplicate test asserting analysis, status, and
   suppression all treat the record as dormant.

26. **`docs/ai-annotation-session.md:667-669` — the docs still describe nested handler selection as a limitation after recursive leaf selection was implemented.**

   The paragraph now says nested/overlapping handlers remain a documented
   limitation of region selection, but `SplitAnchorResolver.leafRegions` and
   `resolveForStatus`/suppression use the recursive leaf list and
   `SplitAnalyzer` recurses. Nested region selection therefore works for unique
   keys; the remaining limitations are overlapping regions, cross-level
   ambiguity (finding 25), and legacy nested ordinal records. The stale sentence
   understates what is implemented and conflicts with the preceding sentence
   that restriction/status selection uses the same recursive enumeration.

   Suggested fix: say nested leaf selection is recursive, with overlapping
   regions and legacy ordinal nested records as the remaining limitations; add
   the cross-level ambiguity and nested server-path tests.

### Concurs / Dissents

- Concurs with recursive leaf discovery, resolver/status/suppression use of the
  leaf list, conditional `languages`/per-unit language metadata, host parent
  repair, host declaration collection, and the normal production split tests.
- Concurs with the frontend/server boundary: region parsing/leaf enumeration is
  frontend-owned, the server orchestrates worklists, and no core/language
  dependency was crossed.
- Dissents: finding 25 (cross-level ambiguity can make scoping disagree with
  status/suppression) and finding 26 (stale nested-limitation docs).

### Residuals

- **Cross-level ambiguity test.** Add duplicate leaf identities at different
  nesting levels and assert analysis, status, and suppression all remain
  dormant; the current duplicate-region test uses two top-level regions.
- **Nested worklist/type tests.** `SplitDiscoveryNestedTest` only calls
  `collectLeafRegions`; add `sessionDocument` nested unit IDs/absolute spans and
  a nested `lookup_type` assertion.
- **Host parent and host declaration tests.** The code repairs filtered host
  parents and collects direct host declarations, but no test covers either
  path; add a filtered-parent prompt test and a host-level declaration edit.
- **Overlapping regions.** Overlapping handlers remain a real selection
  limitation; document it separately from nested selection and add a dormant
  test for an overlapping duplicate identity.
- **Legacy nested ordinals.** Flattened leaf ordinals do not match the local
  ordinal assigned inside a nested recursion; only legacy ordinal-only records
  are affected because new records prefer `regionKey`.
- **`languages` assertion.** Add an assertion that the split prompt contains the
  deduplicated `languages` set and that a single-language prompt does not add
  one.
- **Server decision-path coverage.** A split user-annotation confirm through
  `LiveServerDecisions`/`relativeOperations` is still not exercised end to end.
- **Token+byte anchor test depth, package guard/external pack helpers,
  first-pass/restart baseline, per-language cache header, and memory-only hints**
  remain documented residuals from earlier rounds.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 11

Verdict: The global leaf-ambiguity and nested-docs defects are resolved.
`SplitAnalyzer` now computes one leaf identity count and applies it at every
recursion level, `SplitAmbiguityTest` covers a cross-level duplicate, and the
snapshot correctly separates recursive nested selection from overlapping and
legacy-ordinal limits. Host parent repair, host declaration collection, the
conditional per-unit `languages` contract, and normal split tests remain sound.
One new contract defect remains: host-level units are included in discovery and
can be confirmed, but the deterministic split pipeline analyzes only regions, so
a host-level user annotation or restriction is stored and never applied;
`LiveServerCandidates.restrictionMatchesProposal` can still suppress a
host-level proposal even though `LiveServerRestrictions.status` reports the same
record dormant. Not ready to close.

### Per-item status

1. **Concur — global leaf-region ambiguity is computed once and shared.**
   `SplitAnalyzer.analyze` builds `leafIdentityCounts` from
   `SplitAnchorResolver.leafRegions(host, handler)` and passes it through every
   recursive `analyzeRegions` call; `SplitAnchorResolver`/status/suppression also
   flatten leaves. `SplitAmbiguityTest.crossLevelDuplicateLeafIdentitiesAreAmbiguous`
   covers a top-level and nested duplicate. Residual: the test asserts the
   region flags, not that analysis stays dormant and status/suppression agree;
   add that cross-level server assertion.
2. **Dissent — host-level records are not handled consistently between status
   and suppression.** Host units outside regions are in the worklist and can be
   confirmed, but `HostPipeline`/`SplitAnalyzer` analyze only regions, so a
   host-level user annotation or restriction is persisted without ever being
   applied. `resolveForStatus` correctly falls back to the host parse for a
   record without region identity and reports it dormant, while
   `restrictionMatchesProposal` treats the same record as an unconditional
   match (`else true`) and can suppress a host-level proposal because it checks
   only `AiRestrictionResolver.resolve` non-emptiness, not the cached
   applicability that status uses. See finding 27.
3. **Concur — docs now state nested recursion and remaining limits accurately.**
   The snapshot says nested leaf selection is recursive for discovery, anchoring,
   status, and suppression, with overlapping regions and legacy ordinal-only
   nested records as the remaining limitations; it also describes the flattened
   ambiguity set and conditional `languages` set. Residual: the docs still say
   host-level units are included without saying their confirmations are not
   analyzed/applied; finding 27 should update that caveat.
4. **Concur — host parent integrity and host declaration invalidation are
   correct by inspection.** Filtered host parents are cleared
   (`hostIds`/`intactHostUnits`), and `collectDeclarations` now parses direct
   host declarations before recursing into regions. Residual: neither path has
   a dedicated test.

### New finding

27. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDiscovery.kt:68-88` with `LiveServerCandidates.kt:416-455`, `LiveServerRestrictions.kt:99-150`, `HostPipeline`/`SplitAnalyzer`, and `LiveServerDecisions` — host-level units are confirmable but split analysis never applies their records, and status/suppression disagree for them.**

   `sessionDocument` includes host-level expression units outside regions;
   the AI session can grade them and `handleCandidateDecision` can write a
   `UserAnnotation` or `AiRestrictionRecord` with no region identity. The
   deterministic path for a split host is `SplitAnalyzer`, which analyzes only
   `handler.regions`; host code outside regions is never analyzed
   (`engine.md:70-80` explicitly says the host file is not analyzed unless a
   handler returns a region for it). A confirmed host-level user annotation is
   therefore invisible in `publishTags`, and a host-level restriction is never
   applied. `LiveServerRestrictions.resolveForStatus` now correctly takes the
   host branch for a record without `regionKey`/`regionOrdinal` and reports it
   dormant when the cached output has no matching removal, but
   `LiveServerCandidates.restrictionMatchesProposal` lets that same record into
   `relevant` via `else true` and suppresses a host-level proposal whenever
   `AiRestrictionResolver.resolve` is non-empty, without checking
   `restrictionApplies` against the cached output. Scoping has no host-level
   branch at all. The docs say host-level units are included but do not warn
   that their confirmations are not analyzed.

   Suggested fix: choose one policy and enforce it consistently. Either analyze
   host-level code in the deterministic pipeline (host frontend) so host
   annotations/restrictions apply, or exclude host-level units from confirmable
   discovery and document them as suggestions only. In either case make status,
   scoping, and proposal suppression share the same applicability predicate (for
   example require `restrictionApplies`/resolved annotations, not merely a
   non-empty restriction resolution). Add a host-level confirm then
   analyze/suggest/status test.

### Concurs / Dissents

- Concurs with the global leaf ambiguity computation, `SplitAmbiguityTest`,
  nested recursion docs, conditional `languages`/per-unit language contract,
  host parent repair, host declaration collection, and normal split tests.
- Concurs with the frontend/server boundary; the new ambiguity count and leaf
  enumeration stay in frontend and server orchestration uses them.
- Dissents: finding 27 (host-level records are confirmable but not analyzed, and
  status/suppression disagree).

### Residuals

- **Host-level policy and tests.** Add a host-level confirmed user annotation
  and restriction test covering `publishTags`, `cint/restrictions/list`, and
  `publishCandidates`; either analyze host code or exclude it from confirms.
- **Cross-level ambiguity server test.** Extend `SplitAmbiguityTest` through
  `cint/restrictions/list` and proposal suppression, not only the region flags.
- **Nested worklist/type tests.** `SplitDiscoveryNestedTest` only calls
  `collectLeafRegions`; add nested `sessionDocument` unit IDs/absolute spans and
  a nested `lookup_type` assertion.
- **Host parent and host declaration tests.** No test covers a filtered host
  parent or a direct host declaration change.
- **Overlapping regions and legacy nested ordinals.** Overlapping duplicate
  identities remain a selection limitation; flattened leaf ordinals differ from
  local nested ordinals for legacy ordinal-only records.
- **`languages` assertion.** Assert the deduplicated set and the absence of a
  set for a single-language prompt.
- **Server decision-path coverage.** A split user-annotation confirm through
  `LiveServerDecisions`/`relativeOperations` is still not exercised end to end.
- **Token+byte anchor test depth, package guard/external pack helpers,
  first-pass/restart baseline, per-language cache header, and memory-only hints**
  remain documented residuals from earlier rounds.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 12

Verdict: The live/server host-level path now implements the substance of
finding 27. `SplitAnalyzer` can analyze the host frontend, filters host
annotations/candidates that overlap leaf regions, merges them into the region
output, and the server/live hosts wire host user-annotation and restriction
callbacks; the new host-level confirm test covers that path by inspection, and
normal-path status and proposal suppression both use `restrictionApplies`. Three contract defects
remain. Records without region identity are still classified inconsistently:
status treats them as host-level while region scoping still accepts legacy
region records by span, and suppression's `else -> true` lets a host-level
record match a region proposal. Host-level analysis also passes every stored
annotation/restriction into the host frontend, so a region-identified record can
resolve against host-level code. Finally, the CLI split path does not pass the
new host callbacks, while the docs say split analysis always analyzes
host-level source. Not ready to close.

### Per-item status

1. **Concur, with an ownership caveat — host-level analysis is in the right
   frontend layer and merges correctly for the server paths.** `SplitAnalyzer`
   computes leaf regions once, analyzes host-level source when `hostFrontend` is
   supplied, filters host annotations/candidates whose spans overlap a leaf
   region, and sorts the combined output. The server and live hosts supply
   host-frontend user/restriction callbacks; this keeps rule and oracle work in
   the frontend orchestration while persistence stays in `HostPipeline`/server.
   The leaf-overlap filter makes host-level and region spans disjoint by
   construction. Caveats: the host callbacks pass every record (finding 29), and
   the CLI does not opt in (finding 30).
2. **Dissent — status, scoping, and suppression do not share one record scope.**
   `restrictionApplies` is shared, but the surrounding scope selection is not.
   `resolveForStatus` treats every record without `regionKey`/`regionOrdinal`
   as host-level, while `scopeToRegion`/`scopeRestrictionsToRegion` still accept
   a legacy region record by host span. `restrictionMatchesProposal`'s
   `else -> true` also accepts a no-region record when the proposal is inside a
   region, so a host-level record can suppress a region proposal even though
   status reports it against the host. See finding 28. The existing
   short-circuit bypass (`shortCircuited ||`) remains a separate residual.
3. **Dissent — docs overstate the implemented host-level behavior.** The
   snapshot and engine docs say split analysis runs the host frontend over
   host-level source, and the status text says status/suppression share cached
   applicability, but the CLI split path does not pass the host callbacks and
   the no-region legacy/host distinction is not documented or implemented.
   See findings 28 and 30.
4. **Concur — the existing host-level user and status tests cover the main
   server path.** `hostLevelSplitUserAnnotationConfirmIsPublished` exercises a
   confirmed host-level user annotation reaching `publishTags`, and
   `hostLevelSplitRestrictionStatusResolvesAgainstTheHost` asserts a dormant
   host-level record without suppressing its host-level proposal. Gaps remain:
   no active host-level restriction, no legacy region record, no
   host-level/region-proposal collision, and no CLI coverage.
5. **Concur — the region merge and the normal split tests remain sound.**
   Host output is filtered against the same recursive leaf set used by
   discovery, anchoring, and scoping; region output keeps host coordinates; no
   new core/language boundary violation was found in the server path. Nested
   host-level source inside an outer `SplitHandler` is still not analyzed by a
   nested host pass (residual).

### New findings

28. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:104-150` with `LiveServerCandidates.kt:403-455` and `HostPipeline.kt:155-300` — no-region records are not classified consistently, so status and suppression can disagree for both legacy region records and host-level records.**

   `resolveForStatus` uses the region branch only when `regionKey` or
   `regionOrdinal` is present; every other record is resolved against the host
   source. But `HostPipeline.scopeToRegion` and `scopeRestrictionsToRegion` still
   accept a record with no region identity when its host span lies inside the
   region (the legacy span/hash path), and
   `LiveServerCandidates.restrictionMatchesProposal` uses `else -> true` for
   such a record regardless of whether the proposal context is a region or the
   host. Consequences:

   - A legacy region record (no region key/ordinal, span inside a leaf) is still
     applied by region scoping, but status resolves it against the whole host
     and may report `dormant`; a legacy record that also has a unit fingerprint
     can suppress a region proposal through the `else` branch.
   - A host-level record (no region identity, span outside leaves) can suppress
     a region proposal when its fingerprint/content hash collides with a region
     unit, while status resolves and reports it against the host.
   - The reverse ownership direction is not enforced either: there is no
     explicit host context guard for the `else` branch.

   Suggested fix: introduce one record-scope classifier shared by scoping,
   status, and suppression: region identity wins; otherwise a span contained in
   a unique leaf region is legacy-region; otherwise it is host-level. Require
   the proposal/status context to match that scope, and add tests for a legacy
   region record, a host-level record with a region proposal, and a host-level
   record with a host proposal.

29. **`analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnalyzer.kt:49-54,87-98` with `LiveServerDocument.kt:217-223`, `server/Main.kt:116-122`, and `HostPipeline.kt:112-139` — host analysis passes region-identified annotations and restrictions into the host frontend.**

   The host callbacks call `packs.userContributionsFor(host, host, tree, syntax)`
   and `packs.restrictionsFor(host, host, tree, syntax)` with `region = null`,
   so `HostPipeline` returns every record. `UserAnnotationResolver` and
   `AiRestrictionResolver` do not inspect `regionKey`/`regionOrdinal`; a
   region-identified record whose unit fingerprint, content hash, or stored span
   matches a host-level expression can therefore resolve into host output. The
   merge only removes host annotations/candidates whose spans overlap a leaf
   region, so a match on host-level source remains. This applies a record
   outside the region it was scoped to and can also duplicate a region decision
   conceptually at a host-level span. Fix by adding a host-scope filter (records
   with no region identity, and preferably a host-span check) in
   `HostPipeline` or the two server callbacks, and add a collision test where a
   region record's fingerprint equals a host-level unit's fingerprint.

30. **`analysis-engine/hosts/cli/src/main/kotlin/dev/cint/cli/Main.kt:70-78` — the CLI split path omits host-level analysis, while the docs claim it is universal.**

   The CLI calls `SplitAnalyzer.analyze` with the region callbacks but without
   `hostFrontend`, `hostUserContributionsFor`, or `hostRestrictionsFor`.
   Therefore batch tags for a split host do not apply confirmed host-level user
   annotations or restrictions, unlike the HTTP host and live server. This
   contradicts `docs/engine.md:79-80`, the new snapshot paragraph, and the
   summary's "Split analysis also analyzes host-level source" claim. Fix by
   passing the host frontend/callbacks from the CLI as the other hosts do, or
   move the wiring into a shared SplitAnalyzer/HostPipeline entry point; if the
   omission is intentional, document the host-specific limitation and add a CLI
   split test.

### Concurs / Dissents

- Concurs with the host-level analysis placement, the leaf-overlap filter and
  merge, the server/live callback wiring, the host-level confirm test, the
  normal-path shared `restrictionApplies` check, and the existing region tests.
- Concurs that the previous finding 27 is addressed for the server/live path.
- Dissents: finding 28 (record scope classification), finding 29 (region
  records passed into host analysis), and finding 30 (CLI/docs mismatch).
- No new core/language boundary violation was found in the server path; the
  remaining defects are record-ownership and caller-consistency issues.

### Residuals

- **Short-circuit restriction suppression.** `publishCandidates` still bypasses
  `restrictionMatchesProposal` when `shortCircuited`; a carried proposal can
  republish while the same record is active in status. This predates round 12,
  but the docs' unconditional "status and suppression share cached
  applicability" wording should mention the shortcut.
- **Nested host-level source.** Host analysis runs for the top-level host only;
  host-level source inside an outer `SplitHandler` that is not a leaf region is
  not analyzed with that nested host frontend.
- **Tests.** Add legacy-region status/scoping/suppression coverage, a
  host-level-record versus region-proposal case, a region-record/host-unit
  fingerprint collision, an active host-level restriction (or document that
  the current Vue host has no host rule packs), and a CLI split-host test.
- **Host analysis record loading.** `HostPipeline.userContributionsFor` and
  `restrictionsFor` remain unscoped when `region == null`; a host-only scope
  method would prevent future callers from repeating finding 29.
- **Previous residuals.** Cross-level status/suppression server coverage,
  nested worklist/type tests, filtered host-parent and host-declaration tests,
  the `languages` prompt assertion, overlapping regions, legacy nested ordinals,
  and server decision-path coverage remain open.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

## Round 13

Verdict: The round-13 delta closes the main round-12 paths. Host analysis now
receives only records with no region identity whose span is outside every leaf,
via `SplitAnchorResolver.isHostLevelSpan` and `HostPipeline` host-scoped
accessors; the CLI and both server hosts pass the same callbacks; status
classifies a no-region record by whether its span is contained in a leaf region;
suppression has a region-context guard; and the docs describe the host-level
ownership split. The new host-record-versus-region-proposal test covers the
previously missing direction. One ownership gap remains: legacy unanchored
records and the reverse host/region case are not covered by the classifier.
A record without `unitFingerprint` is still resolved by status but can never
suppress because `restrictionMatchesProposal` starts with
`unitFingerprint != null`; the same record inside a leaf is region-scoped by
`HostPipeline` but treated as host by `resolveForStatus`. For records that do
have a fingerprint, the `else` branch still returns true for a host proposal
without checking that a no-region record is itself host-level, so a legacy
region record can suppress a host proposal while status classifies it as a
region record. Not ready to close.

### Per-item status

1. **Concur — host analysis now receives only host-owned records in the current
   hosts.** The CLI, HTTP host, and live server all pass
   `hostFrontend`/`hostUserContributionsFor`/`hostRestrictionsFor`, and each
   include predicate requires `regionKey == null`, `regionOrdinal == null`, and
   `SplitAnchorResolver.isHostLevelSpan`. Region analysis remains span-scoped by
   `scopeToRegion`/`scopeRestrictionsToRegion`. This closes the direct
   region-record leak in finding 29 for current callers. The predicate is still
   copied into three call sites rather than owned by one host-scope helper
   (residual).
2. **Dissent — legacy records still do not share the ownership classifier.**
   `restrictionMatchesProposal` requires `record.unitFingerprint != null` before
   considering ownership, so a legacy unanchored record can be active in status
   but is invisible to suppression. `resolveForStatus` also only enters the
   split-aware span classification when `unitFingerprint != null`; a
   no-fingerprint record whose span is inside a leaf is treated as host while
   region scoping still applies it. See finding 31.
3. **Concur, with one reverse-context caveat — CLI and server now share the
   host-analysis wiring.** The CLI passes the same host callbacks as the HTTP
   and live server paths, and its `usedLanguages` includes the host language.
   The metadata and engine docs now match the implemented host-level behavior
   for the supported hosts. Caveat: the suppression guard handles a host-level
   record against a region proposal, but the reverse legacy-region-record versus
   host-proposal path remains open (finding 31); no CLI split test pins the new
   wiring.
4. **Concur — the new tests cover the intended host/region boundary.** The
   existing host-level confirm and dormant-status tests remain, and
   `hostLevelRestrictionDoesNotSuppressARegionProposal` covers the host-record
   versus region-proposal direction. Missing coverage: legacy no-fingerprint
   records, legacy region record versus host proposal, and CLI split analysis.
5. **Concur — the merge and normal split behavior remain sound.** The
   leaf-overlap filter, host output merge, recursive leaf enumeration, and
   region coordinate shifting are unchanged in substance; no new
   core/language boundary violation was found in the current server path.

### New finding

31. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:420-455` with `LiveServerRestrictions.kt:104-190` and `HostPipeline.kt:112-340` — legacy unanchored records and the reverse host/region context are still outside the ownership classifier.**

   The round-13 classifier handles a record with `unitFingerprint != null`:
   `resolveForStatus` uses the containing leaf when the span is inside one, and
   `restrictionMatchesProposal` uses the region-context span check. It does not
   handle legacy unanchored records, and it does not make the host-context
   branch check that a no-region record is host-level.

   - `restrictionMatchesProposal` requires `record.unitFingerprint != null` in
     its `relevant` filter, so a no-fingerprint restriction never suppresses a
     proposal. The same record can still be resolved and reported active by
     `resolveForStatus`, and `scopeToRegion`/`scopeRestrictionsToRegion` can
     apply it by span/content hash. Status and suppression therefore disagree
     by construction for the legacy path.
   - `resolveForStatus` checks `frontend is SplitHandler && record.unitFingerprint != null`
     before classifying a no-region record by leaf span. A no-fingerprint
     region record inside a leaf is resolved as host, while region scoping
     applies it to the leaf.
   - For a record with a fingerprint but no region identity, the suppression
     `else` branch returns true whenever the proposal context is host
     (`context.regionKey == null && context.regionOrdinal == null`) without
     requiring the record's span to be host-level. A legacy region record whose
     span is inside a leaf can therefore suppress a host-level proposal, while
     status classifies it as a region record. The new test covers the opposite
     direction only.

   Suggested fix: extract one `recordScope` classifier used by host analysis,
   region scoping, status, and suppression. Classify by region identity first,
   then by unique leaf containment for no-region records (including a null
   fingerprint), otherwise host. Require the context to match in suppression,
   and allow legacy no-fingerprint records through the resolver's span/hash
   path. Add tests for a legacy region record versus a host proposal, a
   no-fingerprint region record versus a region proposal, and a no-fingerprint
   host record versus a host proposal.

### Concurs / Dissents

- Concurs with the host-level filtering, the CLI/server callback parity, the
  status span classification for fingerprinted no-region records, the
  region-context suppression guard, and the new host/region test.
- Concurs that findings 28-30 are addressed for the main fingerprinted and
  current-host paths.
- Dissents: finding 31 (legacy unanchored ownership and the reverse
  host-context suppression path).
- No new core/language boundary violation was found; the remaining issue is a
  legacy record-classification gap at the host/server boundary.

### Residuals

- **Short-circuit restriction suppression.** `publishCandidates` still bypasses
  `restrictionMatchesProposal` when `shortCircuited`; a carried proposal can
  republish while the same record is active in status. The docs should state
  this shortcut.
- **Duplicated ownership predicate.** The `regionKey == null && regionOrdinal ==
  null && isHostLevelSpan` predicate is repeated in the CLI, HTTP, and live
  callbacks; a shared host-scope helper would prevent a future host from
  omitting it.
- **Nested host-level source.** Host analysis still runs only for the top-level
  host, not for host-level code inside an outer `SplitHandler` region.
- **Tests.** Add legacy/no-fingerprint status and suppression coverage,
  reverse-context suppression coverage, a CLI split-host test, and an active
  host-level restriction case (or document that the built-in Vue host has no
  host rule packs).
- **Previous residuals.** Cross-level status/suppression server coverage,
  nested worklist/type tests, filtered host-parent and host-declaration tests,
  the `languages` prompt assertion, overlapping regions, legacy nested ordinals,
  and server decision-path coverage remain open.
- Review remains read-only: no source, test, build, lint or formatter command
  was run.

