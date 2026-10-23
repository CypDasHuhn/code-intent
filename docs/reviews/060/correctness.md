# Correctness review — ticket 060 (AI verdict reuse cache)

## Round 1

Verdict: The document short circuit, the per-unit lookup/store path, the disk
format, and the reuse reporting are in the right places, and several of the
state transitions are handled correctly: negative verdicts are stored, unit
operations are stored relative to the unit start, hints are prompt-only, the
disk file omits source text and rationale, and provider/prompt/vocabulary
changes miss the unit cache through `aiEnv`. The implementation is not ready to
close, however. Existing 059 restriction records stop applying because the
fingerprint normalization changed without a version or migration; restrictions
that do re-anchor after formatting keep stale raw byte offsets and therefore
drop or misplace their operations; the production `cint/suggest` path never
actually computes a text diff, so the declared invalidation contract is not
live; cached positive verdicts remap raw offsets across normalized-identical
formatting and can publish spans on whitespace; hint lookup is blocked by the
same invalidation that is meant to force regrading; and decision fingerprints
conflate identical expressions in one symbol. Confirmed add-only annotations
also still drop after formatting even though 060 claims confirmations are
re-keyed. The tests that are present pass despite these defects because they do
not exercise the production diff path or intra-unit formatting.

### Findings

1. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:406` and `:426-447` — changing the restriction fingerprint normalization orphans every pre-060 record whose unit contains punctuation, whitespace, or operators.**

   `AiRestrictionResolver.normalize()` changed from
   `trim().replace(Regex("\\s+"), " ")` to
   `SourceNormalizer.normalize(text, stripComments = false)`, which tokenizes
   punctuation into separate space-separated tokens. For example the old value
   of `log.warn(message)` is `log.warn(message)` while the new value is
   `log . warn ( message )`. `fingerprint()` hashes that value together with the
   node kind, symbol, siblings, and ordinal, so every record written by 059
   (`.cint/ai-restrictions.json`, file version 1) now has a `unitFingerprint`
   that can no longer be recomputed. In `anchor()` the fingerprint branch
   returns null when `matches` is empty (`matches.singleOrNull() ?: return
   null`), so the record is treated as dormant. No migration or fingerprint
   version exists, and `AiRestrictionsJson` still rejects anything but version
   1.

   Reproduction: on `HEAD` (059) create a record for the
   `log.warn(message)` expression using `AiRestrictionResolver.unitFingerprint`,
   copy it to `.cint/ai-restrictions.json`, then run the working-tree
   resolution on the unchanged document. `AiRestrictionResolver.resolve(...)`
   returns an empty list (or `cint/restrictions/list` reports the record
   dormant), and the `LOGGING` annotation remains. Almost every call,
   binary expression, or indexed access has this shape; only anchor units that
   are a bare name survive.

   Suggested fix: version the fingerprint. Either store a fingerprint version
   per record (and compute the 059 algorithm for version-1 records while
   writing the new algorithm for new records), or bump the restriction file
   version and migrate/dual-read. As a safety net, when a stored fingerprint
   matches nothing and the stored span still has the stored content hash, use
   the stored span rather than returning dormant; add a test that reads a
   literal 059 record and asserts it still applies.

2. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/restriction/AiRestrictions.kt:322-323` and `:488-495` — formatting a restriction anchor keeps the record active but leaves its relative operation offsets pointing into the old byte layout, so the removal no longer matches and the restriction is silently not applied.**

   The new fingerprint path deliberately accepts `log.warn(message)` after it
   becomes `log.warn( message )`. `anchor()` returns the new unit range, but
   `resolveRecord` then computes each operation as `anchorStart + startOffset`
   and `anchorStart + endOffset` using the offsets stored at confirm time. The
   stored remove is `0..18`; the formatted anchor is 20 bytes, so the resolved
   remove is `0..18`, not the whole call. `AiRestrictionApplication.removable`
   requires exact `startByte`/`endByte` equality, so `applyRemovals` returns 0,
   the application `continue`s, and the confirmed removal and replacement adds
   are both lost. If the offsets happen to land on another adjacent annotation,
   the wrong intent is removed instead.

   Reproduction: extend `formattedFingerprintKeepsARestrictionApplied`
   (`analysis-engine/tests/integration-tests/src/test/kotlin/dev/cint/integration/restriction/AiRestrictionResolutionTest.kt:38-57`)
   to assert the resolved remove's `endByte` and to run the resolved record
   through `AiRestrictionApplication.apply` (or a full analysis). The end is
   `start + 18`, not `start + 20`, and the `LOGGING` contribution is still
   present. The current test asserts only `startByte`, which hides the defect.

   A related formatting gap: `normalize()` is called with
   `stripComments = false`, while the unit verdict cache strips comments. A
   comment-only edit inside the restriction anchor therefore changes the
   fingerprint and makes the record dormant (`anchor()` returns null), even
   though it is formatting and the same unit cache would reuse the unit. The
   two layers disagree about what counts as normalized formatting.

   Suggested fix: persist operations as normalized token positions (or a
   normalized sub-span identity) and translate them to the current raw anchor
   at resolution time; alternatively, on a fingerprint-only re-anchor, align
   the old and new anchor text and shift each operation through the alignment.
   Do not return a resolved operation whose normalized sub-text no longer
   matches the operation's original sub-text. Use one comment/whitespace
   normalization policy for restriction fingerprints and verdict keys.

3. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:273-279` — `CacheInvalidation` is always `NONE` in the production path, so changed ranges and changed names never invalidate anything.**

   `handleSuggest` only proceeds when
   `cached.textHash == contentHash(current.text)` (lines 117-125), and
   `publish` always stores the new text in `CachedAnalysis.text`
   (`LiveServerDocument.kt:124-133`). Therefore `cached.text == current.text`
   is a tautology at line 274, and `TextChange.between` is never called with
   two different documents. `changedNames` and `changedRanges` are dead in
   production. A unit whose own normalized text is unchanged but which
   references a declaration changed elsewhere in the file therefore reuses its
   cached verdict, even though the ticket explicitly requires invalidating
   units that reference changed declarations. The direct
   `CacheInvalidation` test (`VerdictCacheTest.changedRangeAndReferencedNamesBlockReuse`)
   injects an invalidation that production cannot produce.

   A second issue in the same path is that `TextChange.between` emits no range
   for a pure deletion: `oldText = "x + y"`, `newText = "x  y"` gives
   `startByte == endByte == 2` and `changedSource` contains only `"+"`, so
   `ChangeSet(emptyList(), emptySet()).empty` is true even though the normalized
   document changed. Once the baseline is corrected, a deletion-only edit can
   still bypass range invalidation.

   Reproduction: source

   ```kotlin
   fun f(text: String) {
       val pattern = Regex("cint")
       pattern.find(text)
   }
   ```

   with a cached `SEARCH` verdict for `pattern.find(text)`. Edit the declaration
   to `val pattern = "cint"` (same imports and same unit text), call
   `cint/suggest`, and observe `reused` includes the `pattern.find(text)` unit
   and the provider is not called for it. Even if the baseline were fixed, the
   changed text is the initializer (`Regex("cint")` / `"cint"`), so
   `TextChange.identifiers` would not contain `pattern`; the declaration name
   is never derived from the removed/inserted syntax.

   Suggested fix: keep the text used by the last completed grading pass per
   `DocumentKey` (or store a small per-document history) and diff that against
   the new text before the cache loop; update it only after a successful pass.
   Invalidate units that reference declarations changed in the old/new
   document, derived from syntax (`val`/`var`/`fun`/parameters), not just
   identifiers appearing in the changed byte range. Fix the zero-length range
   case in `TextChange.between`. Add a server-level test that edits a
   declaration and asserts the referencing unit is regraded; keep the synthetic
   `CacheInvalidation` test only as a unit test of the predicate.

4. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/cache/VerdictCache.kt:123-149` — `similar()` refuses hints for exactly the units that `CacheInvalidation` invalidated, so the units that changed cannot receive prior verdicts.**

   `similar()` starts with `if (threshold <= 0.0 || !invalidation.allows(unit))
   return null`, and `AnnotationSession` passes the same `cacheInvalidation`
   used for `lookup()` (`AnnotationSession.kt:73-89`). A changed unit that
   overlaps a changed range or references a changed name fails
   `allows(unit)`, so it never gets a similar-unit hint. In the current tree
   this is masked by finding 3 (production passes `NONE`), but it is the
   opposite of the ticket's "carry prior verdicts as hints for similar but
   changed units" requirement and will break as soon as the diff is wired.
   The test `changedUnitIsGradedAndSeededWithAPriorHint` passes only because it
   passes `CacheInvalidation.NONE`; the range test sets
   `similarHintThreshold = 0.0` and never checks the prompt.

   Reproduction: cache a verdict, construct a current unit that overlaps a
   `CacheInvalidation.ranges` entry (or references a changed name), run
   `AnnotationSession` with the default hint threshold, and inspect the batch
   prompt. It contains no `prior:` entry even though the cached unit has the
   same symbol and a high token similarity. Hints are not published, so the
   invalidation predicate should not apply to them.

   Suggested fix: drop the `invalidation` parameter from `similar()` (or use a
   separate predicate that only rejects unrelated documents/symbols), since a
   hint cannot become a cached reuse. Add a test that asserts the invalidated
   unit's prompt contains `prior:` while the unit is still regraded.

5. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:363-414` with `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/cache/UnitCacheKey.kt:50-60` — cached positive verdicts reuse normalized-text keys but remap raw byte offsets, so an intra-unit formatting change publishes an add or remove on the wrong text.**

   `UnitCacheKey.textDigest` is the digest of `unit.normalizedText`, which
   collapses whitespace and strips comments. A unit `f(a, b)` and its
   reformatted form `f( a , b )` hash the same. The cached add on `a` stores
   offsets `2..3` relative to the unit. `applyCached()` remaps them to
   `unit.startByte + 2 .. unit.startByte + 3` in the new document without any
   text check; `validateAdd` only checks confidence, vocabulary, unit bounds,
   and duplicate span/intent, so the span on the space before `a` is accepted.
   The result is a published `SEARCH`/`LOGGING` candidate over whitespace, or
   over the wrong token if the whitespace shift is larger. Cached removes are
   safer only because `validateRemove` requires an exact annotation span, but a
   shifted offset can still match a different nested annotation. The same
   problem applies to cached parent-unit adds after a formatting change inside
   the parent, because parent and child normalized text both stay unchanged.

   Reproduction: run `AnnotationSession` with `VerdictCache` on a unit whose
   text is `f(a, b)` and whose provider adds a verdict for `a`; reformat the
   unit to `f( a , b )` and run the same unit again. The second result is
   reused (`reused == 1`) and the candidate's `startByte/endByte` cover the
   whitespace before `a` (or a truncated word), not `a`. The existing
   reformat test only changes indentation outside every unit, so it cannot
   catch this.

   Suggested fix: store a raw unit-text digest (or the unit byte length plus a
   token anchor) alongside the normalized digest. Reuse negative verdicts
   across normalized-identical text, but only reuse a positive verdict when the
   raw text is identical; for formatting changes, align the old and new token
   streams and translate each operation, then verify the normalized sub-text
   at the mapped span before accepting it. Add a reformat test that changes
   whitespace inside the unit and asserts the published span covers the same
   token.

6. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:313-341` with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/cache/EnvironmentIdentity.kt:48-64` — the normalized decision fingerprint conflates different occurrences of an identical expression in the same symbol, so rejecting or confirming one occurrence suppresses the others.**

   `candidateFingerprint` hashes the path, the normalized candidate text, the
   intent set, the reason, and `anchorContextAt(startByte)`. `symbolAt` returns
   the nearest enclosing function/declaration/property *name*, so two identical
   `println(x)` expressions in the same function produce the same fingerprint.
   `publishCandidates` filters every candidate whose fingerprint is in
   `rejectedFingerprints` (lines 115-123) and suppresses every group whose
   candidate fingerprint is in `confirmedFingerprints` (lines 93-113). The
   pre-existing candidate id also included the start/end bytes; the new
   fingerprint fallback therefore broadens suppression from one occurrence to
   all identical occurrences. The same is true for `proposalFingerprint`.

   Reproduction: a function containing two identical `println(x)` expressions
   (or two identical `Regex(...).find(text)` calls in one function), with two
   published AI candidates. Reject the first; the republished
   `cint/publishCandidates` contains neither. Confirm one; the other is hidden
   as resolved and the `userAnnotations` fallback only checks path and intent,
   not span. This is exactly the "rebind to a different identical expression"
   class of defect 059 guarded against with ordinal/sibling context.

   Suggested fix: include a stable ordinal and/or sibling context in the
   decision fingerprint (the same ingredients as
   `AiRestrictionResolver.unitFingerprint`), or only use the fingerprint
   fallback when exactly one candidate/proposal in the document has that
   fingerprint and otherwise fall back to the span-based id. Add a two
   identical-occurrence decision test for both reject and confirm.

7. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:130-139` with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:115-127` — a confirmed add-only suggestion still drops after a formatting edit because the user annotation is stored with a stale span and raw content hash, and the fingerprint is only in `.cint/suggestions.json`.**

   Candidate confirm writes `UserAnnotation(path, span, intents, contentHash,
   createdAt)` without `symbol`. `UserAnnotationResolver.resolve` for a
   symbol-less annotation requires the stored span to resolve and
   `contentHash(rangeText)` to equal the stored hash. Any edit that moves the
   line, changes indentation, or changes whitespace inside the selected span
   makes that comparison fail, so the established annotation disappears. The
   confirm's `anchorFingerprint` is never consulted by the user-annotation
   reader; it only affects candidate suppression. After the annotation drops,
   the candidate is either republished (if no other user annotation with an
   overlapping intent exists in the path) or remains hidden while the
   annotation is gone. The ticket scope and the 060 docs say confirmations must
   not drop on formatting.

   Reproduction: confirm an AI candidate whose span is `find(text)`, then
   reformat the line to `find( text )` (or insert a line above) and run
   `textDocument/didChange`. The analysis output no longer contains the
   confirmed `SEARCH` user annotation, even though `.cint/suggestions.json`
   still records the confirmation.

   Suggested fix: on confirm, persist enough anchor identity to re-resolve the
   annotation (at minimum the `symbol` from `anchorContext`, plus a normalized
   content hash or anchor fingerprint) and teach `UserAnnotationResolver` to
   use that identity when the stored span/content-hash check fails; or store
   add-only confirmations in a re-keyed store with the same semantics as
   restrictions. Add a test that confirms, reformats inside the span, and
   asserts the annotation is still established.

8. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:153-157` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:86-87` — the document short circuit still parses the source on every republish through `anchorContexts(doc)`.**

   `publish()` correctly skips `analyze()` when text hash and
   `deterministicEnv` match, but it always calls `publishCandidates`, whose
   first work after grouping is `val contextAt = anchorContexts(doc)`.
   `anchorContexts` calls `frontend.parse(source)` and builds a syntax model
   even when there are no decisions, candidates, or proposals. The ticket and
   `docs/engine.md`/`architecture.md` state that the short circuit does no
   parse. The identical-text test counts `analysisRuns` only; it does not
   observe parses, so it passes while the contract is violated.

   Reproduction: instrument `Frontends.forLanguage(...).parse` (or count
   `anchorContexts` calls) and send an identical `didChange`. `analysisRuns`
   stays at one, but the parse count increases by one per publish. A source
   with no candidates/proposals still pays the parse.

   Suggested fix: compute anchor contexts lazily only when a decision,
   candidate, or proposal actually needs a fingerprint, or cache the
   symbol/context per document version alongside `CachedAnalysis`. Add a
   short-circuit test that asserts no parse call, not just no analysis call.

9. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:97-110` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerConfig.kt:21-32` — the deterministic environment is computed before the `synchronized(this)` analysis lock, so a config mutation from another client can be missed and an old cached analysis can be republished under the new config revision.**

   `publish` computes `textHash` and `deterministicEnvironment` outside the
   lock (lines 97-98), then enters `synchronized(this)` and compares the
   previously stored `CachedAnalysis` (lines 101-135). A concurrent
   `configAppend`/`configRemove` takes the same lock, rewrites the overlay,
   reloads packs, and increments `configRevision`. If the mutation lands
   between the environment read and the lock acquisition, the new `publish`
   compares an old environment with an old `CachedAnalysis`, sees equality,
   short-circuits, and publishes the old annotations while `configRevision`
   and `revision` already describe the new config. The `deterministicEnv` is
   supposed to prevent exactly this.

   Reproduction: two writers (or a test hook after the environment read);
   writer A sends `didChange` and is paused after computing
   `deterministicEnv`; writer B appends a pack config value and reloads packs;
   writer A resumes, acquires the lock, and short-circuits. The republished
   annotations are the pre-change output. A test can add an
   `environmentBarrierForTest` hook or use a custom slow `packs.fingerprint`.

   Suggested fix: compute `textHash`, `deterministicEnvironment`, and the
   config snapshot inside the same `synchronized(this)` block as the cache
   lookup and analysis, or capture `configRevision` under the lock and
   recompute the environment if it changed. Add a concurrency test for a
   config mutation racing a same-text publish.

10. **Acceptance tests are weaker than the acceptance criteria and stay green with findings 1-9.**

    `LiveVerdictReuseTest.reformatReusesEveryUnitWithoutCallingTheProvider`
    only changes leading indentation, which is outside every expression unit;
    it never changes whitespace or a comment inside a unit, so it cannot catch
    finding 5. `lintEditReusesTheUnaffectedFunctionAndGradesOnlyChangedUnits`
    edits a string literal inside the changed unit and does not edit a
    declaration referenced by an unchanged unit, so it cannot catch finding 3.
    `VerdictCacheTest.changedRangeAndReferencedNamesBlockReuse` constructs
    `CacheInvalidation` directly instead of driving `discoveryPass`, so it
    passes while production always passes `NONE`. The restriction formatting
    test asserts only `startByte`, not the operation end or application
    (finding 2), and no test loads a literal 059 fingerprint (finding 1).
    `rejectedCandidateDoesNotReappearAfterAFormattingEdit` has one candidate
    per normalized text (finding 6) and never tests a confirmed annotation
    (finding 7). `identicalTextDoesNotReanalyzeButRepublishesTheNewVersion`
    counts `analysisRuns` but not parses (finding 8), and there is no
    concurrent config/publish test (finding 9). `diskCacheSurvivesRestart...`
    covers exact-key restart only; there is no corrupt/partial file, TTL/LRU
    across restart, `cachePersist=false`, or concurrent-writer test. The only
    environment test is `EnvironmentIdentityTest`, which hashes constructed
    fields; there is no integration test for provider/model/vocabulary or
    pack/config changes, and `userAnnotationsAndRestrictionsInvalidate...`
    exercises only the annotation file and a restriction record, not a pack or
    config change.

    Suggested fix: add the reproduction tests named in findings 1-9 and a
    server-level path for each acceptance scenario; in particular, drive
    `cint/suggest` after a real declaration edit, format inside a unit with a
    positive cached verdict, and load a 059-format restriction record.

### Concurs / Dissents

- Concurs: the split between `deterministicEnv` and `aiEnv` follows the design;
  each declared component changes the hash; provider/model/prompt/vocabulary
  effectively invalidate unit reuse through `aiEnv`.
- Concurs: the document short-circuit condition itself is the right exact
  condition (content hash plus deterministic environment) and it allocates a
  fresh analysis revision and republishes annotations; no wrong-environment
  hash hit is possible from the stored environment alone (subject to finding 9's
  race).
- Concurs: negative verdicts are stored and reused, operation offsets are
  stored relative to the unit start so pure moves work, cached adds/removes are
  revalidated against the current document, and a failed validation evicts the
  entry and regrades.
- Concurs: hints (cached prior verdicts and same-symbol restrictions) are
  inserted only into the batch prompt and are never published as candidates or
  proposals.
- Concurs: the disk format is versioned, contains no unit source text,
  rationale, or normalized text, stores environment digests, and is bounded by
  `cacheMaxEntries`/`cacheTtlHours`; corrupt JSON is tolerated as an empty
  cache.
- Concurs: `reused`/`graded`/`total` arithmetic is consistent on full and
  partial reuse and on budget exhaustion (`graded` is the number of units the
  provider graded, `uncovered` is the complement of all graded-or-reused
  units); `overhead` is reported.
- Dissents: findings 1-9 above.

### Residuals

- The cache file environment (`AiVerdictCacheStore.environmentMatches`) omits
  the deterministic environment. Entries written under old annotations,
  restrictions, packs, config, or oracle mode survive a restart and occupy
  LRU/TTL slots; they cannot be hit because the unit key contains `aiEnv`, but
  the docs claim the file is discarded on those changes. Including the
  deterministic hash in `AiVerdictCacheEnvironment` would make the file
  semantics match the documentation and reclaim the slots.
- `CachedVerdict.normalizedText` and `CachedVerdict.symbol` are intentionally
  not persisted, so after a restart the exact-key cache works but
  `VerdictCache.similar` cannot produce hints. If cross-restart hints are
  desired, persist non-source identity (symbol/name and normalized-text digest
  or token signature) rather than the text.
- Rejections and confirmations written before 060 have no
  `anchorFingerprint`; a formatting edit can still resurrect those old
  rejections. This is a migration gap rather than a regression in the new path,
  but it should be named if old `.cint/suggestions.json` state is expected to
  survive the upgrade.
- A transient analysis exception falls back to `analyzeHeuristic`, and that
  output is cached under the same `deterministicEnv`; an identical `didChange`
  will short-circuit and never retry the real analysis. A successful-analysis
  marker or a retry policy would avoid making a one-off failure sticky.
- The `TextChange` prefix/suffix diff collapses multi-region edits into one
  broad range. That is safe but over-invalidates; a pure deletion also produces
  a zero-length range as described in finding 3. Both are worth fixing before
  relying on `reused` counts as a performance measure.
- The cache file has no cross-process lock; `persistVerdictCache` serializes
  writers inside one daemon only. A fixed `.tmp` name also means two daemons on
  the same project can race. This is acceptable only if the socket ownership
  guarantees one daemon per project; otherwise the annotation-lock protocol
  should be reused.
- `aiEnv` follows the documented components, so changing `similarHintThreshold`,
  `redactPatterns`, `allowGlobs`, token/budget limits, or the system prompt
  without bumping `promptVersion` does not invalidate cached verdicts. These
  are not in the ticket's listed identity, but they change the prompt or the
  text the provider sees; if "exact AI identity" is the goal, they belong in
  `aiEnv` or in the prompt version discipline.
- `ENGINE_VERSION` and `LANGUAGE_PROFILE_VERSION` are hard-coded constants.
  They must be bumped with every behavior-changing engine or language-profile
  change; a build-derived value would make the document short circuit safer.
- `CachedAnalysis` does not store the language, and `deterministicEnv` derives
  language only through `packFingerprint(language)`. If a document changes
  languageId without changing text and both languages resolve to the same pack
  plan (for example a multi-language pack with identical rules), the short
  circuit can reuse output from the previous frontend. Storing the language or
  the frontend id in `CachedAnalysis` would make this impossible.
- Same-symbol restriction hints are also emitted for restrictions that are
  already active, inviting the agent to propose a removal that has already been
  applied. It is only a hint and validation drops the duplicate, but the hint
  wording ("confirm, adjust, or discard") is misleading for an active record.
- Review was read-only: no source, tests, docs other than this report, build,
  formatter, lint, or test command was run.

## Round 2

Verdict: The main Round 1 defects are fixed. Restriction fingerprints are
versioned with a working 059 read path; restriction operations and unit-cache
operations now carry token-relative offsets and positive cached values survive
normalized intra-unit formatting; the production suggestion path keeps a
last-completed-pass baseline, computes changed ranges and changed declaration
names, and represents deletions as point ranges; similarity hints are offered
for invalidated units; duplicate expressions stay independent across decisions;
the document environment and language are captured under the analysis lock; and
the added tests exercise the declaration-edit, intra-unit-reformat, duplicate,
disk-header, corrupt-file, cachePersist, and config-race paths. Two Round 1
areas are only partially covered: a confirmed add-only annotation is lost when
its span is not exactly token-aligned, and a short circuit still parses when any
stored decision forces anchor contexts. The restriction token mapping itself
does require exact token boundaries, so the restriction operation path is not
broadened; the tests do not exercise the two remaining cases.

### Per-finding status

- **1. Addressed — concur.** `UnitIdentity` now has
  `CURRENT_FINGERPRINT_VERSION = 2`; `AiRestrictionRecord.fingerprintVersion` is
  persisted/read, missing versions default to 1 when a fingerprint exists, and
  version 1 reproduces the 059 `trim`/whitespace normalization. `anchor()`
  falls back to `legacyAnchor` for version-1 records. The literal 059 record test
  (`legacy059FingerprintStillResolvesWithoutAVersionField`) asserts the read path.
  Residual: version-1 records still cannot survive an intra-anchor formatting
  edit because they carry no token offsets; only new records do.
- **2. Addressed for token-aligned operations — concur.** `RestrictionOperation`
  carries `startToken`/`endToken`; `tokenOffsetsFor` requires exact token
  boundaries, `resolveRecord` computes `exactAnchor`, and `mappedRange` uses the
  token stream when present. The formatting test now asserts the resolved
  remove's end and runs the application.
- **3. Addressed — concur.** `suggestionBaselines` stores the last completed
  pass text; `changedDeclarationNames` compares normalized declaration bodies;
  `TextChange` emits a point range for a pure deletion. The server-level
  `declarationChangeRegradesUnitsThatReferenceIt` test pins the intended path.
  Residual: the baseline is process-local and is not set by a pass that exhausts
  a budget, so the first suggestion after a restart or a budget-exhausted pass
  has no document diff (see residuals).
- **4. Addressed — concur.** `VerdictCache.similar` no longer takes
  `CacheInvalidation`; `AnnotationSession` calls it for every unit and suppresses
  hints only for units that were actually reused. `changedRangeAndReferencedNamesBlockReuse`
  now asserts a `prior:` prompt for both invalidated cases.
- **5. Addressed — concur.** Entries carry `rawTextDigest`; positive operations
  carry exact token offsets; `applyCached` rejects raw-offset use after a raw
  text change and remaps token offsets. The `f(a, b)` → `f( a , b )` test asserts
  the published `a` span. Residual: a cached operation that could not be
  token-aligned falls back to raw offsets only while the unit raw text is
  unchanged; formatting then regrades (safe, conservative).
- **6. Addressed — concur.** Decision fingerprints now include the containing
  unit identity (symbol, siblings, ordinal) and proposal fingerprints include the
  normalized operation shape. The duplicate candidate and duplicate proposal
  tests reject one occurrence and leave the other. The unit context also makes
  `anchorContext` stable across formatting.
- **7. Partially addressed — dissent on non-token-aligned confirmations.**
  A confirmed add whose span is exactly a token range now survives an intra-span
  formatting edit: `userAnnotation` stores `unitFingerprint` and token positions,
  and `UserAnnotationResolver.resolveAnchored` remaps them. But `userAnchor`
  sets `unitFingerprint` even when `relativeTokenRange(..., requireExact = true)`
  returns null, and `resolveAnchored` then returns null because
  `startToken`/`endToken` are null. See finding 11.
- **8. Partially addressed — dissent on decision-bearing short circuits.**
  A no-decision identical `didChange` now performs no parse and the test asserts
  `parseRuns` is unchanged. `publishCandidates` still builds anchor contexts
  whenever any reject/confirm fingerprint exists, so a document with a single
  stored decision parses on every identical republish. See finding 12.
- **9. Addressed — concur.** `deterministicEnvironment`, `configRevision`, the
  cache lookup, and the stored `CachedAnalysis` are now inside the same
  `synchronized(this)` block; `CachedAnalysis` stores `language`; the race test
  uses `analysisEnvironmentBarrierForTest`. The lock order remains
  `this` → `reloadLock`, matching the config mutation path.
- **10. Improved but not complete — concur in scope, dissent on coverage.**
  The new tests cover most Round 1 scenarios. They do not cover a confirmed
  non-token-aligned add (finding 11) or a decided document with an identical
  `didChange` (finding 12). `confirmedCandidateSurvivesIntraSpanFormatting`
  checks the annotation but not that the candidate stays unpublished, and
  `reformatReusesEveryUnitWithoutCallingTheProvider` still only changes leading
  indentation.

### Findings

11. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:433-461` and `:470-510` with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:97-101` and `:162-188` — a confirmed add-only annotation is dropped on the next analysis when its span is not exactly token-aligned.**

   `userAnchor()` always returns `unitFingerprint` when it finds the containing
   expression unit, but `startToken`/`endToken` are populated only when
   `UnitCacheKeys.relativeTokenRange(..., requireExact = true)` succeeds. If an
   AI add targets a partial token or includes whitespace at an edge (both are
   accepted by `AnnotationSession.validateAdd`), the stored `UserAnnotation`
   has a non-null `unitFingerprint` and null token fields. On the next analysis
   `UserAnnotationResolver.resolve` takes the anchored branch solely because
   `unitFingerprint != null`, and `resolveAnchored` returns null at
   `val startToken = annotation.startToken ?: return null`. The confirmed
   annotation disappears immediately, without any formatting edit; the old
   span/content-hash path that Round 1 used would have kept it. Once the
   annotation is gone, the AI session can re-propose the same add.

   Reproduction: a scripted `AnnotationSessionProvider` returns an add with
   `startByte` at the start of `find` and `endByte` two bytes later (inside the
   token), or with a trailing space. Confirm the published candidate, then run
   `textDocument/didChange` with unchanged text. The `cint/publishTags`
   annotations no longer contain the confirmed intent, and
   `.cint/user-annotations.json` still has `unitFingerprint` with null
   `startToken`/`endToken`. The existing confirm-formatting test uses the whole
   `find(text)` token, so it does not reach this path.

   Suggested fix: if the token range is not exact, leave `unitFingerprint` null
   (so the legacy span/content-hash resolver is used), or make
   `resolveAnchored` fall back to the stored span/content hash when either token
   is absent. Add a test with a partial-token add that confirms and analyzes
   without any edit.

12. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:88-93` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerAnchors.kt:10-13` — an identical document still parses when any stored decision exists.**

   `needsFingerprints` is true whenever the global decision store contains any
   reject or confirm fingerprint, regardless of path. Every candidate then calls
   `contextAt`, which calls `anchorContexts(doc)`, which calls
   `frontend.parse(source)` and increments `parseRuns`. A project with one
   rejected candidate in `A.kt` therefore parses `B.kt` on an identical
   `didChange`, and the current test only covers a project with no decisions.
   This leaves Round 1 finding 8 open for the normal case where a user has
   reviewed anything.

   Reproduction: reject one published candidate, then send an identical
   `didChange` for the same document (or any open document). `analysisRuns`
   stays unchanged, but `harness.parseRuns()` increases. The current
   `identicalTextDoesNotReanalyzeButRepublishesTheNewVersion` test performs no
   decision first.

   Suggested fix: for a short-circuited analysis, filter by the stored
   candidate/proposal ids without building anchor contexts (the spans and text
   are unchanged), or cache the per-version anchor context alongside
   `CachedAnalysis` and reuse it. Add a decided-document no-parse test.

### Concurs / Dissents

- Concurs: versioned restriction fingerprints and the 059 legacy read path;
  token-relative restriction and unit-cache operations for token-aligned spans;
  last-completed-pass document diff and changed-declaration invalidation;
  similarity hints for invalidated units; raw-text and token handling for cached
  positive verdicts; duplicate decision identity; environment capture under the
  analysis lock and language in `CachedAnalysis`.
- Dissents: finding 7 is not complete for non-token-aligned confirms (finding
  11); finding 8 is not complete when decisions exist (finding 12); the Round 1
  test gap is therefore only partly closed.
- No new locking, cache-load/discard, or environment-version defect was found in
  the Round 2 delta. The full deterministic/AI environment is now stored in the
  cache file and a mismatched file is ignored, so old cache entries are not
  loaded after an annotation, restriction, pack, config, or oracle change.
  The Round 1 residuals about `CachedAnalysis` not storing the language,
  hard-coded engine/language-profile constants, and the cache file omitting the
  deterministic environment are addressed by the language field, `CodeFingerprint`,
  and the full environment header respectively.

### Residuals

- `annotationCovers` (`LiveServerCandidates.kt:380-394`) checks
  `annotation.path.isEmpty()` but not `annotation.path == relativePath(doc.uri)`,
  and it uses the stored annotation span rather than the annotation's
  `unitFingerprint`/token range. A cross-file annotation with the same normalized
  text and context, or a stale span after formatting, can therefore fail to
  resolve or falsely resolve a confirmation. The simple path check is worth
  adding; the current tests do not exercise cross-file identical content.
- The suggestion baseline is process-local. After a daemon restart (or after a
  pass that exhausts its budget and therefore does not update the baseline), the
  first suggestion pass has no previous text to diff. Exact cache keys still
  protect changed units, but a unit that references a declaration changed before
  the restart is reused. This is the same class as the accepted external-type
  staleness limit but applies to text changes across restarts.
- Confirmed restriction proposals are suppressed only by the stored
  `proposalId`. `proposalId` is derived from the raw unit content hash and span,
  so a formatting edit changes it; if the provider proposes the same group again,
  the `restrictionIds` filter does not match and the group can reappear. The
  restriction itself still applies through the record's unit fingerprint.
  A proposal-fingerprint check against `AiRestrictionRecord` would close this.
- `UnitIdentity` version 2 strips comments from the target but preserves them in
  previous/next sibling context (`normalize(..., comments = true)`). A
  comment-only edit in a sibling changes a restriction or confirmed-annotation
  fingerprint even though it is formatting; the documentation says this is
  intentional, but it is inconsistent with the unit cache's comment stripping.
- Version-1 restriction records cannot survive an intra-anchor formatting edit;
  the migration only preserves them when the stored content hash or span still
  matches. Reconfirming an old record upgrades it to token-aware version 2.
- `UserAnnotation.unitFingerprint` has no fingerprint version field. A future
  fingerprint change would silently orphan confirmed annotations rather than
  selecting a legacy resolver.
- `CachedVerdict.normalizedText`/`symbol` remain memory-only, so hints are
  unavailable after a restart; exact-key reuse still works. The baseline
  limitation above is the more consequential restart gap.
- The cache file still has no cross-process lock and uses a fixed `.tmp` name;
  `persistVerdictCache` only serializes writers inside one daemon.
- `similarHintThreshold`, `redactPatterns`, batch/token limits, and the system
  prompt still are not in `aiEnv`; they can change provider output without
  invalidating exact cached verdicts. This is unchanged from Round 1.
- The heuristic fallback output is still stored under the same
  `deterministicEnv`; a transient analysis exception is not retried on an
  identical `didChange`.
- Restriction hints still include active records, and `priorHint` still prints
  raw offsets rather than token offsets for a formatted prior unit; both are
  hint-only and harmless to publication.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 3

Verdict: Round 2 findings 11 and 12 are addressed in their tested paths.
Versioned user anchoring now records `unitFingerprintVersion`, token bounds, and
normalized offsets, and `partialTokenConfirmedCandidateSurvivesReformat` plus
the new split-region tests cover the anchored path. A short-circuited
`didChange` with a stored decision sets `shortCircuited`, skips
`needsFingerprints`, and `identicalTextWithAStoredDecisionDoesNotParse` pins the
parse count. The residual fixes are also present: the process-local baseline is
documented, proposals are matched against a stored restriction unit fingerprint
instead of only `proposalId`, current analysis annotations replace the stale
`annotationCovers` span path, legacy formatting is documented, and
`AiVerdictCacheStore.load` filters entries by the current `aiEnv`. Two new
correctness defects remain in the fallback and path scope of the new
anchoring: a failed anchored lookup falls through to the old symbol-based
resolver and can expand a confirmed expression annotation to its enclosing
function, and `restrictionMatchesProposal` compares fingerprints from every
restriction in the project without checking the record's path, so an identical
unit in another file can suppress a proposal here.

### Per-item status

- **11. Addressed for the tested partial-token and formatting path — concur,
  with new finding 13.** `UserAnnotation` now carries
  `unitFingerprintVersion`, `startToken`/`endToken`, and normalized offsets;
  `UserAnnotationResolver.resolveAnchored` selects the versioned unit
  fingerprint, maps token bounds first, and uses
  `normalizedLengthAtByte`/`rawByteAtNormalizedOffset` for non-token spans. The
  partial-token test confirms and reformats a span inside `find`, and the split
  tests exercise clipped host anchors. The remaining defect is the
  fallback after `resolveAnchored` returns null, not the anchored success path.
- **12. Addressed — concur.** `publish` sets `shortCircuited` while holding the
  analysis lock, passes it to `publishCandidates`, and
  `needsFingerprints = !shortCircuited && fingerprintVersions.isNotEmpty()`;
  `restrictionMatchesProposal` is also bypassed under `shortCircuited`.
  `identicalTextWithAStoredDecisionDoesNotParse` rejects a candidate, sends an
  identical `didChange`, and asserts both `analysisRuns` and `parseRuns` are
  unchanged. The no-decision short-circuit test remains in place.
- **Process-local baseline — addressed as documentation, residual unchanged.**
  The design docs now state that the baseline is process-local and that the
  first pass after restart or a budget-exhausted pass has no diff. The code still
  has that limitation; exact cache keys continue to protect changed units, but a
  declaration changed before restart can still reuse a referencing unit.
- **Proposal re-keying independent of `proposalId` — implemented, with new
  finding 14.** `restrictionMatchesProposal` recomputes
  `UnitIdentity.unitFingerprint` from the current proposal and compares it to
  the stored restriction record, and `confirmedProposalDoesNotReappearAfterFormatting`
  passes. The helper does not filter by `record.path` and therefore over-reaches
  across files; it also collapses all proposals on one unit to the same result
  (operation shape is still not compared).
- **Stale path removed from confirmation lookup — concur for the candidate
  lane.** `annotationCoversCurrent` now reads `Annotation` values from the
  current `analyses[key].output`, so it compares current spans and current user
  provenance rather than the stale raw user-annotation span. The old
  `annotationCovers` helper is gone. Finding 14's path leak is in the separate
  restriction/proposal lane.
- **Legacy formatting documented — concur.** The docs explicitly state that
  version-1 records cannot survive an intra-anchor formatting edit without token
  offsets and that reconfirming upgrades them.
- **Per-entry `aiEnv` filtering — concur.** `AiVerdictCacheStore.load` filters
  parsed entries with `it.aiEnv == environment.aiEnv` after the full header
  check; the current cache header includes `deterministicHash` and `aiEnv`.
- **Split-region scoping — partly verified, with a test gap.** `scopeToRegion`
  clears host unit/token/normalized fields for user annotations and
  `scopeRestrictionsToRegion` clears unit fingerprints and operation tokens.
  The new split tests assert the user annotation's start line and the
  restriction's effect, but `splitAnalysisClipsVersionedAnchorsToTheRegionSpan`
  does not assert the annotation's end line/columns or exact span, and the
  versioned record deliberately has a non-matching fingerprint, so it exercises
  the fallback rather than the anchored path.
- **Code fingerprint expansion — concur.** `ENGINE_VERSION` and
  `LANGUAGE_PROFILE_VERSION` now use `CodeFingerprint.ofPackages` over the
  server/core/suggest/host and frontend/treesitter package roots plus syntax YAML
  resources; `HostPipeline.fingerprint` digests rule and pack classes.
  `EngineCodeCoverageGuardTest` pins the module list. No new cache-identity
  defect was found in this delta.

### Findings

13. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:99-127` with `:163-207` and `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDecisions.kt:480-532` — when an anchored lookup fails, the old symbol resolver can expand a confirmed expression annotation to its entire enclosing declaration, or rebind it to a different location.**

   `resolve()` tries `resolveAnchored` only when `unitFingerprint != null`, then
   falls through to the legacy path on **any** null result. `resolveAnchored`
   returns null for a fingerprint mismatch, an ambiguous/missing unit, an
   invalid or collapsed normalized range, or an `anchorFingerprint` mismatch.
   The fallback uses `annotation.symbol`, and `userAnchor` sets that symbol to
   the nearest enclosing function/property for every confirmed candidate.
   The legacy branch selects a named function/property node and verifies only
   that the stored span lies inside it and that the text at the stored span has
   the stored content hash; it then returns the whole named node. A sibling
   edit, a duplicate insertion, or a non-token span whose normalized range
   collapses therefore turns a confirmed `foo()` annotation into a contribution
   spanning the whole `fun f` (or property), rather than dropping it or keeping
   the expression span. It can also rebind a stale span to a newly inserted
   identical expression when that span now selects the duplicate.

   Reproduction: confirm an AI add on `foo()` inside
   `fun f() { foo(); bar() }`, then change the unrelated sibling `bar()` to
   `baz()`. The unit fingerprint for `foo()` changes through sibling/ordinal
   context, `resolveAnchored` returns null, and the next analysis publishes the
   USER contribution over the whole `f` function. Alternatively, use a
   non-token add whose start/end normalized offsets collapse (for example a
   whitespace-only edge); `anchoredRange` returns null and the same symbol
   fallback runs. `splitAnalysisClipsVersionedAnchorsToTheRegionSpan` also takes
   this fallback because its `unitFingerprint` is `"host-only-fingerprint"`, and
   it asserts only `startLine`, so the expanded span is not detected.

   Suggested fix: distinguish "no anchor data" from "anchor data present but
   failed to match". Only take the legacy fallback when the annotation has no
   `unitFingerprint` (or no token/normalized fields at all), and when
   `unitFingerprint` was present use a raw `RangeSelection`/content-hash check
   without the symbol-to-declaration step; if a fingerprint or anchor check
   fails, leave the annotation unresolved (dormant) rather than expanding it.
   Add a test that edits an unrelated sibling after a confirm and asserts the
   annotation either keeps the expression span or disappears, never becomes the
   function span; also assert exact end/columns in the split versioned test.

14. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:415-435` — `restrictionMatchesProposal` compares a proposal to every restriction in the project without checking the record's path.**

   `restrictions` is loaded with `AiRestrictionsFile.loadTolerant(root)` and
   passed across all documents; `UnitIdentity.unitFingerprint` contains node
   kind, normalized text, symbol, siblings, and ordinal, but no path. If two
   files contain the same unit identity, a confirmed restriction in `A.kt`
   makes `restrictionMatchesProposal` return true for an unrelated proposal in
   `B.kt`, so `publishCandidates` filters the B proposal and the user loses a
   legitimate review group. The helper also matches any proposal on the same
   unit, regardless of its ADD/REMOVE operation shape, so a different proposal
   on a restricted unit is suppressed too.

   Reproduction: two files with identical `fun f() { log.warn(message) }`,
   confirm a restriction in `A.kt`, then run `cint/suggest` for `B.kt` with a
   provider that proposes the same group. `publishCandidates` returns no
   proposal for `B.kt`; the record's `path` is never consulted. The
   `confirmedProposalDoesNotReappearAfterFormatting` test uses one path only and
   does not catch it.

   Suggested fix: filter restrictions by
   `UserAnnotationResolver.pathMatches(record.path, relativePath(doc.uri), root)`
   before comparing fingerprints. If distinct operation shapes on the same unit
   must remain proposable, compare a normalized operation signature against the
   record's `remove`/`add` as well. Add a two-file identical-unit test and a
   same-unit different-operation-shape test.

### Concurs / Dissents

- Concurs: versioned user anchoring for partial-token spans; `shortCircuited`
  parse avoidance and its `parseRuns` test; process-local baseline
  documentation; proposal fingerprint re-keying for the same path/unit;
  current-analysis confirmation lookup instead of stale raw spans; legacy
  formatting notes; per-entry `aiEnv` filtering; split-region field clearing;
  and the expanded code fingerprints.
- Dissents: finding 11's fallback is unsafe (finding 13); the proposal
  re-keying helper is path-blind and unit-shape-blind (finding 14); the
  split-region versioned test does not pin the span it claims to test.
- No new locking or cache-load/discard defect was found. `CodeFingerprint` now
  fails loudly on missing class resources instead of silently hashing a class
  name, which is preferable for cache identity, but it means a packaging change
  that omits a resource can fail `ENGINE_VERSION` initialization rather than
  silently reusing stale entries.

### Residuals

- Normalized offsets are approximate boundaries. `normalizedLengthAtByte`
  normalizes a prefix that may end inside a token, whitespace, or comment, and
  `rawByteAtNormalizedOffset` maps the resulting length back to a token
  boundary. A span beginning at a token start after whitespace can gain leading
  whitespace; a span whose normalized start and end collapse falls through to
  the fallback in finding 13. The partial-token test covers an inside-token
  span but not these boundary cases; add direct round-trip tests for
  whitespace/punctuation boundaries, not only formatted source.
- `restrictionMatchesProposal` still matches any proposal on a unit with a
  confirmed restriction, regardless of operation shape; the operation-shape
  comparison is only in the decision fingerprint path. If a restricted unit can
  legitimately receive a different follow-up proposal, this is a false
  suppression; if not, document the unit-level rule.
- Version-1 restrictions remain unable to survive intra-anchor formatting and
  are not matched by `restrictionMatchesProposal` after formatting; only
  reconfirmed version-2 records have the token path.
- The fallback can also apply to split user annotations after
  `scopeToRegion` clears their host anchors. `SplitUserAnnotationsTest` asserts
  only the start line in the versioned case; a whole-declaration span would pass.
- `UserAnnotation.anchorFingerprint` itself has no version; the new
  `unitFingerprintVersion` protects the unit lookup, but a future change to
  `AnchorFingerprint` would not be versioned per annotation.
- `restrictionMatchesProposal` parses the document on every publication that
  has proposals and any fingerprinted restriction, even when all relevant
  restrictions are dormant or from another path. The path check in finding 14
  also reduces this work.
- `CodeFingerprint.ofPackages` uses class-output roots and `check(entries.isNotEmpty())`.
  That is correct for the normal build/test layout, but a native image or a
  packaging change that exposes no class directory/jar can fail initialization
  instead of falling back to a stable version marker.
- The earlier residuals still apply: hints are memory-only after restart,
  `aiEnv` omits suggestion-layer parameters, heuristic fallback output is cached,
  the cache file has no cross-process lock, active restrictions are hinted, and
  `priorHint` uses raw offsets.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 4

Verdict: Round 3 findings 13 and 14 are addressed in substance. An anchored
user annotation no longer falls through to the symbol resolver: if the anchor
fields exist and the anchored lookup fails, `UserAnnotationResolver` returns
null, and only a record with no token/normalized anchors uses the raw
range/content-hash path. The unrelated-sibling and duplicate-insertion tests
pin that behavior, and the split versioned annotation test now asserts the exact
span. `restrictionMatchesProposal` is path-scoped, has a shape filter, and calls
`AiRestrictionResolver.resolve` so dormant/stale records do not suppress
proposals; the cross-file, differently-shaped, and dormant tests pass. The
architecture fixes are present: token and normalized offsets are now invertible
at the tested boundaries, the engine fingerprint includes lsp/rulepacks with
loud class/resource failures, split restrictions that straddle a region are
dropped, and the stale docs are corrected. One new correctness defect remains:
the restriction/proposal shape filter compares only operation kinds and intents,
not the operation sub-spans, so a confirmed restriction can still suppress a
different proposal on the same unit. A few residuals remain around unitless
user anchors and shared rulepack fingerprint coverage.

### Per-item status

- **13. Addressed — concur.** `UserAnnotationsJson` now persists
  `startTokenOffset`/`endTokenOffset`, `UserAnnotationResolver.anchoredRange`
  maps a token index plus byte offset, and `resolve` no longer takes the legacy
  symbol path when any anchor field is present. `resolveRangeOnly` is limited to
  records with no token/normalized fields and verifies the stored content hash.
  `confirmedExpressionDoesNotExpandToTheEnclosingFunctionAfterSiblingEdit` and
  `confirmedExpressionDoesNotRebindToADuplicateAfterInsertion` cover the two
  Round 3 reproductions. Residual: a candidate with no containing expression
  unit still gets only a `symbol` anchor and no `unitFingerprint`, so the legacy
  symbol path can expand it to the enclosing declaration; see Residuals.
- **14. Addressed for path and dormancy — concur, with new finding 15.**
  `restrictionMatchesProposal` filters by `UserAnnotationResolver.pathMatches`,
  requires `recordShape == proposalShape`, and calls
  `AiRestrictionResolver.resolve` before comparing the unit fingerprint.
  `restrictionFromAnotherFileDoesNotSuppressAProposal`,
  `dormantOrDifferentlyShapedRestrictionDoesNotSuppressAProposal`, and
  `dormantRestrictionDoesNotSuppressAProposal` cover the reported cases. The
  shape comparison is still only `kind + intent`, so it does not distinguish
  different operation sub-spans within the same unit.
- **Invertible token/normalized offsets — concur.** `normalizedStarts` includes
  separators, `normalizedLengthAtByte` maps raw boundaries to normalized offsets,
  and `rawByteAtNormalizedOffset` maps back, with tests for a token after `", "`
  and leading/trailing whitespace. `tokenAnchorRange` stores token index plus
  byte offsets, and `anchoredRange` uses them before the normalized fallback.
  Residual: the tests do not cover a comment or a multibyte token boundary, and
  the normalized mapping is only guaranteed at the tested token/whitespace
  boundaries.
- **Fingerprint covers lsp/rulepacks and fails loudly — mostly concur.**
  `ENGINE_PACKAGE_PREFIXES` includes `dev/cint/lsp/` and
  `dev/cint/rulepacks/`; the anchors include `KotlinLspOracle` and
  `BundledRulePackConfigResolver`; `CodeFingerprint.ofPackages` throws when a
  code source is unreadable and `classDigest`/`resourceDigest` throw when a
  class or YAML is missing. Residual: `HostPipeline.fingerprint` still calls
  `ofPackages(..., allowEmpty = true)` for shared rulepack code and only anchors
  the first effective pack class, so a missing/shared rulepack root can
  contribute an empty hash; `EngineCodeCoverageGuardTest` still checks only the
  Gradle module list, not that every module/prefix is actually fingerprinted.
- **Split restrictions straddling a boundary — concur.** `scopeRestrictionsToRegion`
  now drops records whose start line is before the region or whose end line is
  after it, and recomputes the clipped content hash before returning the record;
  `splitAnalysisDropsRestrictionsThatStraddleTheRegionBoundary` asserts the
  straddling restriction does not apply.
- **Docs corrected — concur.** The old engine-version/unit-relative paragraph is
  gone, the restriction example includes `fingerprintVersion` and token fields,
  and the reuse model describes versioned anchors, the process-local baseline,
  split clipping, and the full cache environment. Residual documentation
  overclaims remain where they describe shared rulepack coverage or
  normalized-offset exactness more broadly than the implementation/tests.

### New findings

15. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:415-436` with `:454-458` — the restriction/proposal shape filter ignores operation sub-spans, so a confirmed restriction can suppress a different proposal on the same unit.**

   `recordShape` and `proposalShape` reduce each operation to only
   `REMOVE:<intent>` / `ADD:<intent>` (or the equivalent kind and intent). Two
   proposals on the same unit with the same operation kinds and intents but
   different target spans are therefore treated as the same shape.
   `restrictionMatchesProposal` then compares only the unit fingerprint, so the
   confirmed record suppresses the new proposal even though the user would be
   reviewing a different removal or replacement span. This is the remaining
   part of the Round 3 operation-shape concern: the decision fingerprints
   already include normalized operation text, but the restriction/proposal lane
   does not.

   Reproduction: confirm a restriction that removes `LOGGING` from the whole
   `log.warn(message)` call and adds `SEARCH` on the whole call, then run a
   provider that proposes the same `REMOVE LOGGING` + `ADD SEARCH` shape on a
   narrower argument span. `recordShape == proposalShape` and the unit
   fingerprint matches, so `publishCandidates` filters the proposal; the
   `dormantOrDifferentlyShaped...` test only changes the operation kinds
   (remove-only versus remove+add), so it does not catch this case.

   Suggested fix: include the normalized operation sub-span or relative offset
   in `recordShape`/`proposalShape` (the same normalized operation text used by
   `proposalFingerprint`), or compare a full operation signature before matching.
   Add a same-kind/same-intent, different-sub-span test.

### Concurs / Dissents

- Concurs: the anchored user-annotation fix and its sibling/duplicate/split
  tests; path-scoped and dormancy-checked restriction/proposal matching;
  invertible token/normalized boundary tests; lsp/rulepacks inclusion in the
  engine fingerprint; split boundary dropping; and docs reconciliation.
- Dissents: the restriction/proposal shape filter is still too coarse (finding
  15); the unitless user-anchor path can still expand to a declaration
  (residual); shared rulepack fingerprinting and the coverage guard are only
  partly verified (residual).
- No new locking, cache-load/discard, or environment-version defect was found
  in the Round 4 delta.

### Residuals

- A candidate with no containing expression unit is still returned by
  `userAnchor` as `UserAnchor(symbol, null, ...)`, so `userAnnotation` stores a
  symbol but no `unitFingerprint`/tokens. `UserAnnotationResolver.resolve` takes
  the legacy symbol branch and can establish the contribution over the whole
  enclosing declaration. This is the same expansion class as the fixed finding
  13, but for a unitless candidate (for example a deterministic candidate on a
  declaration/name rather than an expression). Return a null symbol or a
  range-only record for that case and add a test.
- `HostPipeline.fingerprint` computes `sharedRuleCode` from
  `effectivePacks.firstOrNull()` with `allowEmpty = true`; shared rulepack
  helpers in another code source or a missing rulepack root can therefore be
  omitted from the pack fingerprint without a failure. The engine prefix list
  now includes `dev/cint/rulepacks/`, but the anchor/root coverage is still
  single-pack.
- `EngineCodeCoverageGuardTest` only compares `settings.gradle.kts` module names.
  It does not assert that each module has a fingerprint prefix/anchor or that a
  code change outside the current lists changes `ENGINE_VERSION`; the lsp and
  rulepacks additions are present but not guarded against a future omission.
- The normalized-offset tests cover ASCII token boundaries with a comma,
  leading whitespace, and trailing whitespace. Comments, escaped characters, and
  multibyte token boundaries are not covered; `charOffsetForByte` converts a
  possibly partial UTF-8 prefix, so these are worth direct round-trip tests.
- The split versioned restriction test covers a wholly-inside anchor and the
  straddle test covers a record that crosses the region boundary, but no test
  covers a restriction whose anchor is inside the region while a remove target
  is outside it; the current `scopeRestrictionsToRegion` raw offsets could
  resolve differently after clipping.
- `UserAnnotation`/`SuggestionDecision` now store version fields, but
  `AnchorFingerprint` itself has no version and `userAnchor` still stores an
  `anchorFingerprint` derived from the current recipe; a future recipe change
  would not be selected by `unitFingerprintVersion`.
- The earlier residuals still apply: hints are memory-only after restart,
  `aiEnv` omits suggestion-layer parameters, heuristic fallback output is cached,
  the cache file has no cross-process lock, active restrictions are hinted,
  `priorHint` uses raw offsets, and the baseline is process-local.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 5

Verdict: Round 4 finding 15 and the listed residuals are addressed. Proposal
identity now uses normalized operation sub-spans on both publication
(`proposalFingerprint`) and confirmation suppression
(`restrictionMatchesProposal`/`ProposalIdentity`), and
`differentlyShapedSubSpanProposalIsNotSuppressed` pins the same-unit,
different-sub-span case. Unitless confirmations now get a null symbol and use
their stored range instead of the enclosing declaration, the raw-range fallback
is documented as limited to records without token/normalized anchors, comment
and multibyte boundary tests were added, and the version-2 identity recipe is
documented as frozen. There are zero open correctness findings in the Round 5
delta. Only residuals remain.

### Per-item status

- **15. Addressed — concur.** `ProposalIdentity.proposalOperationShape` includes
  `kind`, `intent`, and normalized operation text;
  `restrictionOperationShape` maps a record's token/raw operation spans against
  the current proposal anchor and normalizes the selected text.
  `publishCandidates` uses the former for decision fingerprints and the latter
  for `restrictionMatchesProposal`; `LiveServerDecisions` uses the same proposal
  shape when recording rejections. `differentlyShapedSubSpanProposalIsNotSuppressed`
  confirms that a whole-unit restriction does not suppress a proposal whose ADD
  targets only `message`.
- **Unitless candidate fallback — addressed.** `userAnchor` now returns
  `UserAnchor(null, ...)` when no containing expression unit exists, so no
  symbol-only record reaches the legacy declaration resolver.
  `UserAnnotationResolver.resolveRangeOnly` is used only when every token and
  normalized anchor is absent and verifies the stored content hash; the new
  `anchoredRecordWithoutTokenDataFallsBackToItsRangeNotTheDeclaration` test
  pins the range.
- **Comment/multibyte boundaries — addressed.** `SourceNormalizerTest` now
  covers a token after a comment and a multibyte token start/end, in addition
  to the token-after-`", "` and leading/trailing whitespace cases.
- **Raw-range documentation — addressed.** The documentation now says the
  normalized fallback is attempted before the raw range, and the raw range is
  used only when no token or normalized anchor exists.
- **Identity recipe version — addressed.** The stability contract documents that
  `AnchorFingerprint.of` and the raw/normalized offset mapping are part of the
  version-2 unit identity and must be versioned together with `UnitIdentity`;
  `UserAnnotation` and `SuggestionDecision` carry the version fields.
- **Architecture Round 4 items — concur.** Token+offset anchoring and the
  normalized inverse have boundary tests; `ENGINE_PACKAGE_PREFIXES` includes
  `lsp` and `rulepacks`, `ENGINE_VERSION` anchors both, `CodeFingerprint`
  raises on missing roots/classes/resources, and the coverage guard checks the
  prefix set and per-prefix entries. Split restrictions that straddle a region
  boundary are dropped, and formatted anchored user/restriction split tests now
  exist. The stale documentation paragraphs were removed.
- **No new locking, cache-load/discard, environment-version, or decision-reuse
  defect was found in this delta.** The new `ProposalIdentity` helper is
  server-local and uses the same normalized operation text as the decision
  fingerprints.

### Concurs / Dissents

- Concurs: normalized operation sub-spans for publication and confirmation
  suppression; the same-unit/different-sub-span regression test; unitless
  candidate range fallback; the raw-range documentation correction; comment and
  multibyte boundary tests; the identity-recipe version contract; lsp/rulepack
  fingerprint coverage and loud failures; formatted split anchoring; split
  straddle dropping; and the corrected docs.
- Dissents: none in this round. There are no open correctness findings.

### Residuals

- `HostPipeline.scopeRestrictionsToRegion` still uses the unmodified
  `record.span` for its region-boundary gate before passing an anchored record
  through. A formatted anchor whose stale host span crosses a line boundary can
  therefore be dropped even though its versioned unit anchor is inside the
  region; formatted tests keep the anchor on the same line. Resolve/translate
  the current anchor before the boundary check when anchors are present.
- `ProposalIdentity` compares normalized operation text, not the operation's
  normalized relative offset. Two operations with identical normalized text at
  different positions inside the same unit are indistinguishable; include the
  relative token or byte offset if position is meant to be part of the shape.
- `HostPipeline.fingerprint` still probes shared rulepack code from only
  `effectivePacks.firstOrNull()` with `allowEmpty = true`; third-party pack
  roots without `dev/cint/rulepacks/` entries are not covered even though the
  bundled `ENGINE_VERSION` is. The docs now disclose this limitation.
- `EngineCodeCoverageGuardTest` checks a hardcoded module/prefix set and
  per-prefix entries. A new package in an existing module, or a future module
  added to Gradle without a prefix, is not derived automatically.
- The normalized-offset tests cover token boundaries, a comment, and a
  multibyte token, but not a byte offset that splits a multibyte character or a
  position inside a comment; `charOffsetForByte` still uses a possibly partial
  UTF-8 prefix for those inputs.
- A token+offset anchor whose `startToken`/`endToken` are present but whose
  offsets are missing (old round-4 records) falls back to the whole-token range;
  that is exact only for token-aligned records.
- `UserAnnotation.anchorFingerprint` still has no independent recipe version;
  the stability contract says a change requires a `UnitIdentity` version bump,
  which is a process invariant rather than enforced metadata.
- The earlier residuals remain: hints are memory-only after restart, `aiEnv`
  omits suggestion-layer parameters, heuristic fallback output is cached, the
  cache file has no cross-process lock, active restrictions are hinted,
  `priorHint` uses raw offsets, and the suggestion baseline is process-local.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 6

Verdict: The new `SplitAnchorResolver` is wired into the confirmation path for
user annotations and restrictions, and the production-shaped Vue tests now
build the host→region anchor through the real host frontend and assert the
resolved region span. Boundaries, non-overlapping Vue script regions, script
language selection, straddling spans, and the host/legacy clip policy are
handled sensibly. Two new defects remain. `LiveServerCandidates.restrictionMatchesProposal`
still computes shape and unit fingerprints against the host document with the
host Vue frontend, while confirmed split records now carry region-relative
anchors, so confirmed restriction proposals in a script region are not
suppressed. Separately, `SplitAnchorResolver` writes context-relative
`startNormalized`/`endNormalized` values while `UserAnnotationResolver` applies
them to the containing unit text; a confirmation that has no token range (for
example a whitespace/comment-only or otherwise non-token span) will be remapped
at the wrong offset or go dormant. Not clean.

### Per-item status

- **Host→region anchor mapping — concur for the normal path.** `SplitAnchorResolver`
  selects a containing region by host byte range, parses the region with
  `region.language`, and returns region-relative `anchorStart`/`anchorEnd`,
  `regionStartByte`, unit fingerprint, symbol, token+offset range, and
  normalized offsets. Vue regions are non-overlapping and the production tests
  use `VueFrontend` with real `raw.startByte` offsets. Straddling spans select
  no region and fall back to the host/legacy path rather than binding a wrong
  region. Residual: nested/overlapping split handlers and a wrong-region choice
  are not tested; only Vue's non-nested script regions are exercised.
- **Recording user annotations and restrictions — concur for the token path.**
  `LiveServerDecisions.userAnchor` and `anchorIdentity` use the resolver's
  region unit fingerprint, and `relativeOperations` maps operation spans through
  `regionStartByte` before computing token offsets. `productionHostAnchoredUserAnnotationResolvesAfterHostFormatting`
  and `productionHostAnchoredRestrictionResolvesAfterHostFormatting` assert the
  exact region span/effect. Residual: `restrictionMatchesProposal` was not
  migrated to the resolver; see finding 18. The normalized fallback also has a
  context/unit mismatch; see finding 19.
- **Scoping drop/clip policy — concur in substance.** `scopeToRegion` drops a
  versioned straddling user annotation while still clipping legacy records;
  `scopeRestrictionsToRegion` drops straddling restrictions and returns
  region-anchored within-region records. `versionedStraddlingUserAnnotationIsDropped`
  and the formatted host user/restriction tests pin the new behavior. Residual:
  the boundary gate still uses the stale host `record.span` for versioned
  records, so a host formatting edit that shifts the anchor across a line
  boundary can drop a record whose current anchor is inside the region.
- **Production-shaped tests — partly concur.** The new Vue tests exercise the
  real host frontend, region discovery, `SplitAnchorResolver`, host formatting,
  and exact spans for user annotations; the restriction test asserts application.
  They do not go through `LiveServerDecisions`/`handleCandidateDecision` or
  `restrictionMatchesProposal`, and they do not cover the no-token normalized
  path, multiple scripts, or a wrong-region span. The test helper still builds
  the record directly rather than calling `LiveServerDecisions.userAnchor`.

### Findings

18. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:405-438` — `restrictionMatchesProposal` still uses host-scoped shapes and unit fingerprints, so confirmed restrictions in a split script region do not suppress later proposals.**

   Restriction records confirmed through `LiveServerDecisions` now store a
   region-relative `unitFingerprint`, `fingerprintVersion`, and token offsets
   from `SplitAnchorResolver`. `restrictionMatchesProposal` nonetheless calls
   `proposalOperationShape(doc.text, proposal)` and
   `restrictionOperationShape(doc.text, proposal, record)` on the full host
   text, then resolves `AiRestrictionResolver` and calls
   `UnitIdentity.unitFingerprint` with the host frontend
   (`Frontends.forLanguage(doc.language)`, Vue) and host source. Vue's host
   syntax does not enumerate the script region's Kotlin/TypeScript expression
   units, so the fingerprint comparison is null/false and the confirmed
   restriction proposal can reappear. This is the same host-vs-region identity
   mismatch that round 6 fixed for recording, not for suppression.

   Reproduction: confirm a restriction proposal inside a Vue `<script>` region
   (or construct the same region-anchored `AiRestrictionRecord` with
   `SplitAnchorResolver`), then run `cint/suggest` after a host formatting
   change. `publishCandidates` does not reuse `restrictionMatchesProposal`'s
   `SplitAnchorResolver` context, so the proposal group is published instead of
   being suppressed. The existing `confirmedProposalDoesNotReappearAfterFormatting`
   test is Kotlin-only; the new Vue tests call `SplitAnchorResolver` directly
   and never run publication suppression.

   Suggested fix: resolve the proposal anchor with
   `SplitAnchorResolver.anchor(SourceFile(doc.uri, doc.text), frontend,
   proposal.startByte, proposal.endByte)` and use `context.text`,
   `context.anchorStart`/`anchorEnd`, and `context.regionStartByte` for both the
   proposal and record operation shapes; run `AiRestrictionResolver` and
   `UnitIdentity.unitFingerprint` against the region source/tree/syntax. Add a
   Vue `confirm proposal → format → suggest` test that asserts no proposal
   group is published.

19. **`analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnchorResolver.kt:88-89` with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:225-232` — normalized offsets are computed relative to the split context text but consumed relative to the containing unit text.**

   `SplitAnchorResolver.anchor` sets
   `startNormalized = normalizedLengthAtByte(contextText, contextStart)` and
   the matching end value, where `contextStart`/`contextEnd` are offsets from
   the start of the region or host document. `UserAnnotationResolver.anchoredRange`
   later calls `rawByteAtNormalizedOffset(unit.text, startNormalized)` and
   applies the result to `unit.text`. Those normalized values include all text
   before the containing unit, so for a confirmation with no token anchor
   (`tokenAnchorRange` null: a whitespace/comment-only span or another
   non-token span) the fallback maps to an offset beyond the unit end or to the
   wrong location; the anchor fingerprint then fails and the annotation goes
   dormant. Before round 6, `userAnchor` computed these values relative to
   `unit.text`; this is a regression in the new resolver.

   Reproduction: construct a host region span whose start/end produce no token
   range but are inside an expression unit that does not start at byte 0, run
   `LiveServerDecisions.userAnchor`/`SplitAnchorResolver.anchor`, then resolve
   the record through `UserAnnotationResolver`. The stored `startNormalized`
   is the region/document offset while `anchoredRange` applies it to
   `unit.text`; the resolved span is wrong or missing.

   Suggested fix: compute `startNormalized`/`endNormalized` from
   `contextStart - unit.startByte` and `contextEnd - unit.startByte` (using the
   selected unit), or pass the unit-relative offsets through the resolver. Add
   a test with a no-token confirmation inside a non-zero-offset unit and assert
   the exact span after formatting.

### Concurs / Dissents

- Concurs: `SplitAnchorResolver` region selection by host byte range, region
  language parsing, straddling fallback, region-relative unit/token anchors,
  the `relativeOperations` region offset translation, the production Vue
  formatting tests, versioned straddling-user dropping, and the existing
  legacy clip path.
- Dissents: finding 18 (restriction suppression is still host-scoped) and
  finding 19 (normalized offsets are context-relative under a unit-relative
  consumer).

### Residuals

- `scopeToRegion`/`scopeRestrictionsToRegion` still use the stale host
  `record.span` for the boundary gate. A formatted anchor whose line moved
  outside the old region line range is dropped even when its region unit
  fingerprint is present and current.
- `SplitAnchorResolver`'s region picker uses the first containing region. Vue
  script regions are non-overlapping, but nested/overlapping SplitHandlers and a
  wrong-region fallback are untested.
- The new production tests construct records with `SplitAnchorResolver` directly;
  they do not exercise `LiveServerDecisions.userAnchor`, `relativeOperations`,
  or the server confirm path end to end.
- `restrictionMatchesProposal` remains host-scoped for its shape computation as
  well as its fingerprint; fixing finding 18 should cover both.
- The token+offset anchor path is exercised for whole-token confirmations; the
  precise inside-token/no-token normalized path still lacks an end-to-end split
  test.
- The earlier residuals remain: shared external rulepack fingerprinting,
  process-local suggestion baseline, per-language cache header thrash,
  memory-only hints, no cross-process cache lock, and `aiEnv` not covering all
  suggestion-layer parameters.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 7

Verdict: Round 6 findings 18 and 19 are addressed in the single-region path.
`LiveServerRestrictions.status` now resolves versioned split records through the
region language/frontend and shifts the result back to host coordinates;
`restrictionMatchesProposal` uses `SplitAnchorResolver` and the region
operation shape; `SplitAnchorResolver` writes unit-relative normalized offsets,
and `splitAnchoredUserAnnotationUsesUnitRelativeNormalizedFallback` covers the
no-token fallback after formatting. Persisting `regionOrdinal`/`regionLanguage`
also keeps a record active when lines are inserted above `<script>`, and
`splitRestrictionStatusAndSuppressionUseTheRegionIdentity` exercises status plus
suppression. Two region-identity defects remain: suppression does not compare the
record's region ordinal/language with the proposal's region, so identical units
in two script regions can cross-suppress; and the ordinal is only the current
region-list index, so inserting/removing/reordering a script region can point a
record at the wrong region or drop it. The new end-to-end test also bypasses the
actual confirm/suggest path.

### Per-item status

- **18. Addressed for the single-region case — concur, with finding 20.**
  `LiveServerRestrictions.resolveForStatus` selects the region by
  `record.regionOrdinal`/`regionLanguage`, parses the region frontend, resolves
  the record against region source, and shifts `ResolvedRestriction` spans back
  to host coordinates before `restrictionApplies`. `restrictionMatchesProposal`
  now uses `SplitAnchorResolver.anchor` for `context.text`,
  `regionStartByte`, and region `UnitIdentity`. The split status/suppression
  test asserts `active` before and after formatting plus an inserted comment.
  The proposal matcher still does not require the record's region ordinal and
  language to equal the proposal's region; see finding 20.
- **19. Addressed — concur.** `SplitAnchorResolver` now computes
  `startNormalized`/`endNormalized` from `unit.text` and unit-relative offsets,
  so `UserAnnotationResolver.anchoredRange` applies them correctly.
  `splitAnchoredUserAnnotationUsesUnitRelativeNormalizedFallback` writes a
  record with no token range, formats inside the region, and asserts the exact
  region span.
- **Region membership — partly addressed.** Records now persist
  `regionOrdinal` and `regionLanguage`, and `HostPipeline.scopeToRegion`/
  `scopeRestrictionsToRegion` pass a versioned record only to the matching
  region without consulting stale host lines. Inserting lines above `<script>`
  works because the ordinal is unchanged. The ordinal is still a list index, so
  inserting, removing, or reordering script regions is not stable; see finding
  21.
- **End-to-end test — partly concur.** The new test opens a Vue document, checks
  `cint/restrictions/list` status before and after host formatting/insertion,
  constructs the current proposal, and calls
  `publishCandidatesForTest`; it asserts suppression. It does not confirm the
  restriction through `cint/candidates/decide`, run `cint/suggest` with a
  provider, or assert the complete decision/session path. See Residuals.

### Findings

20. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:405-455` — split restriction suppression does not compare the record's region ordinal/language with the proposal's region.**

   `restrictionMatchesProposal` builds a region context for the proposal and
   compares `record.unitFingerprint` and operation shape, but `relevant` only
   checks `record.unitFingerprint`, path, and shape. `regionOrdinal` and
   `regionLanguage` are ignored. `UnitIdentity.unitFingerprint` is
   region-relative and does not include the region ordinal, so two Vue script
   regions with the same unit content have identical fingerprints. A confirmed
   restriction in region A therefore suppresses a legitimate proposal in
   region B on the same path. `LiveServerRestrictions.status` already uses the
   ordinal/language pair; the suppression lane must do the same.

   Reproduction: a Vue host with two `<script>` regions, each containing
   `console.log("x")`; confirm a restriction in the first, then publish a
   proposal for the second. `SplitAnchorResolver` selects the second region for
   the proposal, the unit fingerprint and shape match the first region's record,
   and `publishCandidates` filters the second proposal even though it belongs to
   a different region. The current split status/suppression test has one script
   region.

   Suggested fix: when `context.regionOrdinal != null`, require
   `record.regionOrdinal == context.regionOrdinal` and
   `record.regionLanguage == context.language` before the shape/fingerprint
   comparison; legacy records without region identity can keep the current
   path. Add a two-script, identical-unit cross-region test.

21. **`analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnchorResolver.kt:35-39,78-83` with `analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:160-165,230-236` and `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:108-132` — the persisted region ordinal is a list index, not a stable region identity.**

   `SplitAnchorResolver` stores `regionIndex` as `regionOrdinal`, and
   `SplitAnalyzer` assigns the same index to `SplitRegion.ordinal`.
   `HostPipeline.scopeToRegion`/`scopeRestrictionsToRegion` pass a record only
   when `record.regionOrdinal == region.ordinal && record.regionLanguage ==
   region.language`; `LiveServerRestrictions.resolveForStatus` selects
   `regions.getOrNull(record.regionOrdinal)`. Inserting a new `<script>` region
   before the target, deleting one, or reordering script elements shifts every
   later index. A record for the old second region then points at the old first
   region: scoping may bind it to the wrong region (or drop it when the
   fingerprint does not match), and status may report `dormant` or resolve the
   wrong region. `regionLanguage` only distinguishes languages, not two scripts
   in the same language. `SplitAnchor.regionStartLine` is computed but not
   persisted, and no region content digest is stored.

   Reproduction: Vue host with two same-language script regions, each containing
   an identical `console.log("x")`; confirm a restriction in the second; insert
   a new `<script>` region before it; re-analyze/list. The record's ordinal now
   selects the new/old first region; scoping and status can bind the wrong
   region or go dormant. The current test inserts only a comment above the
   single script, which leaves the index unchanged.

   Suggested fix: persist a stable region identity (region text/start digest,
   or ordinal plus a region content hash/start marker) and use it for both
   scoping and status; if an ordinal lookup fails, try all regions and match the
   unit/anchor before falling back. Add tests inserting and removing a script
   region before the target, including two same-language regions.

### Concurs / Dissents

- Concurs: region-frontend status resolution and host-coordinate shifting;
  region-based proposal matching for the single-region case; unit-relative
  normalized offsets and the no-token fallback test; persisted
  `regionOrdinal`/`regionLanguage` for line insertion above `<script>`; and the
  single-region status/suppression test.
- Dissents: finding 20 (suppression ignores region ordinal/language) and finding
  21 (ordinal is not stable when script regions are inserted/removed/reordered).

### Residuals

- The new split status/suppression test constructs the restriction and proposal
  directly and calls `publishCandidatesForTest`; it does not exercise
  `cint/candidates/decide`, `cint/suggest`, or a provider-driven session, so the
  full confirm/format/re-suggest path is still untested.
- `resolveForStatus` selects the ordinal region first and does not fall back to
  all regions when that region's resolution is empty; a stale ordinal therefore
  reports dormant even if another region contains the anchor.
- Records without `regionOrdinal` (round-6 split records) still use the stale
  host span line gate and are not protected from insertions before the region.
- `SplitRegion.ordinal` defaults to 0 in `VueFrontend.regions` and is only
  assigned by `SplitAnalyzer`; any consumer that reads `frontend.regions`
  directly and uses `region.ordinal` rather than the list index would see 0.
- `SplitAnchorResolver` still picks the first containing region; nested or
  overlapping SplitHandlers are untested.
- The earlier residuals remain: shared external rulepack fingerprinting,
  process-local baseline, per-language cache header, memory-only hints, no
  cross-process cache lock, and `aiEnv` not covering all suggestion-layer
  parameters.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 8

Verdict: Round 7 findings 20 and 21 are addressed. The persisted `regionKey`
is content/language-derived rather than positional, `identityAmbiguous` marks
duplicate region identities, and scoping, status, and suppression all reject
ambiguous or absent region identities instead of falling back to all regions or
the host. `identicalUnitsInDifferentScriptRegionsDoNotCrossSuppress` covers the
two-region suppression case, and `splitRestrictionConfirmAndSuggestUseTheRegionIdentity`
now exercises the real `cint/suggest` → `cint/candidates/decide` →
format/insert → status → re-suggest path. Split discovery now enumerates
region-local expression units with host-shifted spans, unit prefixes, and
host-overlap annotations, so it does not double-grade host and region units.
One new correctness defect remains: split declaration invalidation is still
computed by `changedDeclarationNames` against the host Vue document, so a
script declaration change can leave a referencing unit's cached verdict stale;
`sessionTypeLookup` has the same host-only parsing and loses type facts for
region units. Not clean.

### Per-item status

- **20. Addressed — concur.** `restrictionMatchesProposal` now reads the
  proposal's region context from `SplitAnchorResolver` and requires a
  non-ambiguous `regionKey` plus `regionLanguage` match before comparing shape
  and unit fingerprint. `LiveServerRestrictions.resolveForStatus` uses the same
  `regionKey` selector and returns empty (dormant) on zero or multiple matches;
  `HostPipeline.scopeToRegion`/`scopeRestrictionsToRegion` reject a record when
  the region is ambiguous or the key/language differs. The two identical
  `<script>` regions test confirms a restriction from one region does not
  suppress the other region's proposal.
- **21. Addressed — concur.** `SplitRegion.identityKey` hashes language plus
  normalized region text; `SplitAnalyzer` marks duplicate keys
  `identityAmbiguous`; `SplitAnchorResolver` persists `regionKey` and the
  ambiguity flag. Inserting, removing, or reordering another script region no
  longer changes the target's key, and `regionKeySurvivesRegionInsertionRemovalAndReorder`
  covers insertion, reorder, and removal of other regions. Status, scoping, and
  suppression agree on zero/multiple-match dormancy.
- **End-to-end server path — concur.** `splitRestrictionConfirmAndSuggestUseTheRegionIdentity`
  uses the harness/provider path, confirms a proposal through
  `cint/candidates/decide`, checks active status before and after a formatted
  host with an inserted comment, then re-runs `cint/suggest` and asserts no
  proposal group. The separate two-region test also uses the real suggest and
  decide path.
- **Split-aware discovery units — concur for unit construction.** `sessionDocument`
  enumerates each split region's own expression units, shifts `startByte`,
  `endByte`, and spans by `region.startByte`/line/column, prefixes unit and
  parent ids by region, and uses region-language syntax for symbol/signature/
  imports/references. `annotations` remain host-shifted for overlap matching,
  and host units are not also emitted for a SplitHandler. No double-grading or
  unit span double-shift was found.
- **Residuals — not fully closed.** `LiveServerDiscovery.sessionTypeLookup`
  still parses the host document with the host frontend, so `lookup_type` for a
  region unit uses a non-region tree/oracle and returns no useful fact. Split
  declaration invalidation is the finding below.

### Finding

22. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:343-358` with `LiveServerDiscovery.kt:161-186` — split declaration changes are not seen by the host-only declaration diff, and split type lookup still uses the host frontend.**

   `changedDeclarationNames` calls `declarations(frontend, text)` with
   `frontend = Frontends.forLanguage(current.language)` (`vue` for a Vue host)
   and the full host text. Vue's host syntax does not expose declarations inside
   `<script>` regions, so a script function/property body change contributes no
   changed declaration name. A unit in another part of the script that
   references that declaration can keep the same normalized text, symbol,
   signature, imports, and existing-intent fingerprint; its cached verdict is
   reused even though the declaration changed. `sessionTypeLookup` has the same
   host-only `frontend.parse(source)`/`oracleFor(frontend)` path and therefore
   cannot return type facts for a region unit's host-shifted span.

   Reproduction: a Vue script with `function helper() { return 1 }` and a use
   `const x = helper()`; run `cint/suggest`, change only the helper body to
   `return 2`, run it again. `TextChange` sees no non-keyword identifiers in
   the changed numbers, host `declarations` sees no script function, and the
   `helper()` unit is reused instead of graded. The type lookup for that unit
   similarly returns null.

   Suggested fix: make `changedDeclarationNames` iterate
   `frontend.regions(host)` with each region's language/frontend/text (the same
   split source used by `sessionDocument`) and merge declaration names; add the
   corresponding region offset when comparing spans. Make `sessionTypeLookup`
   select the region containing the requested host span, parse it with its
   language frontend, and translate the span into region coordinates. Add a
   split helper-body edit test that asserts the referencing unit is graded and a
   region `lookup_type` test.

### Concurs / Dissents

- Concurs: content-stable `regionKey`, ambiguity detection and dormancy,
  scoping/status/suppression agreement, the two-region no-cross-suppression
  test, the real confirm/re-suggest split test, and split region unit
  enumeration with host-shifted spans and ids.
- Dissents: finding 22 (split declaration invalidation and split type lookup
  remain host-only).

### Residuals

- `SessionDocument.language` remains the host language (`vue`) while
  `SessionUnit.language` is the region language; prompt metadata can describe
  the host rather than the unit language.
- `regionKey` tests cover scoping/analysis but not status/suppression after
  inserting or removing a whole script region; only the two-region suppression
  and single-region status paths are exercised.
- `SplitAnalyzer`/`SplitAnchorResolver` still choose the first containing region;
  nested or overlapping SplitHandlers are not covered.
- Records without `regionKey` (round-7 and older split records) still use the
  positional ordinal and legacy host-span path.
- `sessionTypeLookup` region support is included in finding 22; until fixed,
  split discovery has region units but host-only type lookup.
- Earlier residuals remain: shared external rulepack fingerprinting,
  process-local baseline, per-language cache header, memory-only hints, no
  cross-process cache lock, and `aiEnv` not covering all suggestion-layer
  parameters.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 9

Verdict: Round 8 finding 22 is addressed for the normal and nested discovery
paths. Split declaration invalidation now recurses through region frontends and
sources (`collectDeclarations`), and the helper-body test proves the referencing
unit is graded again. `sessionTypeLookup` builds host plus leaf-region contexts,
uses the region frontend/oracle, and the JavaScript region type test passes.
`sessionDocument` now includes host-level units outside regions and recursively
collects nested leaf regions; per-unit language is carried into the batch prompt,
the ambiguity policy has a focused dormant-annotation test, and the docs match
the key-first/ordinal-fallback model. One new defect remains: the decision and
status anchor paths still enumerate only top-level `frontend.regions(host)`, so
candidates and records in nested leaf regions cannot be anchored, resolved, or
suppressed even though discovery now enumerates them.

### Per-item status

- **22. Addressed — concur.** `changedDeclarationNames` now calls
  `collectDeclarations`, which recurses through a `SplitHandler`'s regions using
  each region language and text, and `splitDeclarationEditInvalidatesAReferencingUnit`
  changes a JavaScript helper body and asserts the unchanged `helper()` unit is
  graded. `sessionTypeLookup` creates a host context plus a context for every
  leaf region, selects the containing/smallest context, translates the host
  span to region coordinates, and uses the region oracle;
  `splitTypeLookupUsesTheRegionFrontendAndOracle` returns the injected
  JavaScript type for the region unit.
- **Nested enumeration and host-level units — concur for discovery.**
  `collectLeafRegions` recursively visits nested split regions with accumulated
  byte/line/column offsets and prefixes, `sessionDocument` includes host units
  that do not overlap a leaf region, and `splitDiscoveryIncludesHostAndRegionLanguages`
  sees both `vue` and `javascript` units. `SplitDiscoveryNestedTest` pins the
  recursive leaf collection. The gap is in the consumers that still select only
  top-level regions; see finding 23.
- **Per-unit language and region type lookup — concur.** `SessionUnit.language`
  is the region language, the batch prompt adds a per-unit `language` when it
  differs from the host language, and the type lookup test verifies the
  JavaScript region oracle. The document-level `language` remains the host
  language; the docs describe the per-unit supplement.
- **Ambiguity policy and docs — concur.** `ambiguousRegionIdentityLeavesAUserAnnotationDormant`
  covers the duplicate-region case, and the docs now state that region
  language plus normalized text is the identity, ordinal/language are legacy
  fallbacks, and duplicate identities are dormant rather than resolved by
  ordinal.
- **End-to-end path — concur for the tested restriction flow.**
  `splitRestrictionConfirmAndSuggest...` uses the real `cint/suggest` and
  `cint/candidates/decide` server path with a formatted/inserted host and
  re-suggest; the two-region test covers cross-region suppression. Residual:
  no split user-annotation confirm through `LiveServerDecisions` end-to-end.

### Finding

23. **`analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnchorResolver.kt:35-39` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:108-132` and `LiveServerCandidates.kt:405-455` — nested leaf regions are enumerated by discovery but not by the decision/status anchor resolver.**

   `sessionDocument` uses `collectLeafRegions` to enumerate every nested leaf
   region, so a nested JavaScript/TypeScript expression is now a worklist unit.
   `SplitAnchorResolver.anchor`, however, still calls
   `frontend.regions(host)` once and selects only a top-level region. For a
   candidate inside a nested leaf, it treats the enclosing top-level region as
   the context, which may itself be a SplitHandler or a different language; the
   unit fingerprint/token/normalized anchor is then absent or wrong. The same
   top-level-only enumeration is used by `LiveServerRestrictions.resolveForStatus`
   (`frontend.regions(source)`), so a record confirmed in a nested leaf cannot
   be resolved for status. `LiveServerCandidates.restrictionMatchesProposal`
   inherits the resolver error, so nested restriction suppression is incorrect
   too. The new `collectLeafRegions` test only checks collection; no nested
   confirmation, status, or suppression path is exercised.

   Reproduction: use the nested SplitHandler from `SplitDiscoveryNestedTest`
   (an outer region containing a Vue region containing a script expression),
   run `cint/suggest`, and confirm the nested expression's proposal. The
   decision path calls `SplitAnchorResolver.anchor` on the host; it selects the
   outer region and cannot compute the leaf unit anchor, so the user annotation
   or restriction is written without the nested region key, or with the wrong
   context, and later status/suppression does not agree with discovery.

   Suggested fix: make the anchor resolver recurse through leaf regions with
   the same accumulated absolute offsets and prefixes as `collectLeafRegions`
   (or share one leaf-region enumeration helper), and make
   `LiveServerRestrictions.resolveForStatus` enumerate leaf regions rather than
   only `frontend.regions(source)`. Add a nested session test that confirms a
   nested unit and asserts its region-relative anchor, status, and suppression.

### Concurs / Dissents

- Concurs: split declaration invalidation through region declarations; region
  type lookup and per-unit language; nested leaf enumeration for discovery and
  type context; host-level unit inclusion; strict ambiguous/stale region
  dormancy; the focused ambiguity test and corrected docs; and the real
  restriction confirm/format/re-suggest path.
- Dissents: finding 23 (nested decision/status/suppression anchors still use
  top-level regions only).

### Residuals

- `collectDeclarations` does not include host-level declarations when the host
  frontend is a SplitHandler; host-level units are now in the worklist, so their
  declaration invalidation relies only on `TextChange` identifiers.
- `SessionDocument.language` remains the host language; the prompt adds per-unit
  language only when it differs, and the documented sorted `languages` set is
  not present in `renderBatchPrompt`.
- The ambiguity test covers a user annotation, but not `cint/restrictions/list`
  status or proposal suppression under duplicate region keys.
- `splitDiscoveryIncludesHostAndRegionLanguages` checks languages but not exact
  host/region unit spans, ids, or the absence of duplicate units.
- Nested split discovery is covered only by `collectLeafRegions`; nested
  `sessionDocument`, type lookup, decision, status, and suppression are not
  end-to-end tested.
- Region selection still chooses the first containing outermost region; the
  leaf-recursion change in finding 23 should also settle overlapping/nested
  ambiguity.
- Earlier residuals remain: shared external rulepack fingerprinting,
  process-local baseline, per-language cache header, memory-only hints, no
  cross-process cache lock, and `aiEnv` not covering all suggestion-layer
  parameters.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 10

Verdict: Round 9 finding 23 is addressed in the production anchor and status
paths. `SplitAnchorResolver.leafRegions` now recursively collects nested leaf
regions with absolute offsets, `LiveServerRestrictions.resolveForStatus` uses
that leaf list, and `LiveServerCandidates.restrictionMatchesProposal` inherits
the same resolver. `sessionDocument` includes host-level units plus recursively
collected leaf regions, filters host units overlapping regions, and clears
missing host parents; `changedDeclarationNames` now includes direct host and
region declarations; `sessionTypeLookup` routes each region through its own
frontend/oracle; and the batch prompt now sends a sorted `languages` set under
the documented conditions. One new correctness defect remains: the split
status resolver treats every record with a `unitFingerprint` as region-backed,
including host-level split records with no region identity, so such a
restriction is reported dormant even though scoping and suppression apply it.

### Per-item status

- **23. Addressed — concur.** `SplitAnchorResolver.leafRegions` recursively
  visits nested regions with accumulated byte/line/column offsets, and both
  `LiveServerRestrictions.resolveForStatus` and
  `LiveServerCandidates.restrictionMatchesProposal` now use that leaf list
  instead of top-level `frontend.regions`. Region keys, ambiguity, status, and
  suppression therefore agree for nested leaf units. `SplitDiscoveryNestedTest`
  still only checks collection; nested decision/status is not exercised.
- **Host-level declaration invalidation — addressed.** `collectDeclarations`
  now collects direct declarations from the host frontend before recursing into
  regions, so host-level units outside regions are included in the invalidation
  map alongside region declarations.
- **Host parent integrity — addressed.** `sessionDocument` filters host units
  that overlap regions, then clears `parentUnitId`/`parentSpan` on host units
  whose parent was filtered out. Region unit parents remain region-prefixed.
- **Per-unit/document language and `languages` set — concur.** `SessionUnit`
  carries the region language, the prompt emits a per-unit language when it
  differs, and `renderBatchPrompt` adds a sorted `languages` set when there is
  more than one language or the only language differs from the document
  language. The split provider fixtures read `languages`; the split provider
  and type-lookup tests exercise per-unit language.
- **Ambiguity policy and docs — concur.** Duplicate region identities remain
  dormant in scoping, status, and suppression; the ambiguity test covers user
  analysis, and the docs describe content identity plus legacy ordinal
  fallback. Residual: no server status/suppression assertion for the same
  duplicate-region record.
- **Discovery/traversal — concur.** Host-level and region units are
  host-shifted without duplicate spans, nested leaf offsets/prefixes are
  accumulated, and host parent links are repaired. Nested `sessionDocument`,
  nested type lookup, and nested confirm/status remain test gaps.

### Finding

24. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:108-147` — `resolveForStatus` sends host-level split restrictions with no region identity through the region-only branch, so they are reported dormant while scoping and suppression apply them.**

   The guard is `frontend is SplitHandler && record.unitFingerprint != null`.
   A restriction anchored on a **host-level** unit (for example a Vue template
   expression outside every `<script>` region) is emitted by
   `SplitAnchorResolver.anchor` with `regionKey == null` and
   `regionOrdinal == null` but a valid host `unitFingerprint`. `resolveForStatus`
   still enters the split branch, builds `regions = leafRegions(...)`, and when
   the record has no region identity selects **all leaf regions** and resolves
   the host fingerprint against region sources. That returns empty, and the
   function returns empty without trying the host source. The host-scoped
   `HostPipeline` path applies the same record, so `cint/restrictions/list`
   disagrees with analysis, and `LiveServerCandidates.restrictionMatchesProposal`
   (which does use the host context for a host-level proposal) can suppress
   proposals while status says dormant.

   Reproduction: a Vue document with a template expression carrying a confirmed
   restriction and a `<script>` region; confirm the template restriction, then
   request `cint/restrictions/list`. Scoping/analysis applies it, but status
   resolves only leaf script regions and reports `dormant`. A split host with
   no leaf regions returns empty as well.

   Suggested fix: use the region branch only when the record has a region
   identity (`regionKey != null || regionOrdinal != null`); for a
   `unitFingerprint` without region identity, resolve against the host source
   (or try host first). If a region record's ordinal/key does not match, keep it
   dormant rather than falling through unpredictably. Add a host-template split
   restriction status test and a check that it agrees with scoping/suppression.

### Concurs / Dissents

- Concurs: recursive leaf anchor/status resolution, host+region discovery units,
  host parent repair, host/region declaration invalidation, region-routed type
  lookup, per-unit language, conditional sorted `languages`, and the corrected
  ambiguity docs.
- Dissents: finding 24 (host-level split restrictions are misclassified as
  region records by status).

### Residuals

- `SplitDiscoveryNestedTest` validates `collectLeafRegions` only; add a nested
  `sessionDocument`/type-lookup/confirm/status test to cover the full recursive
  path.
- `languages` is sent conditionally (more than one language or the single
  language differs from the document); the docs/tests should state and assert
  that condition.
- The ambiguity test covers a user annotation but not
  `cint/restrictions/list` status or proposal suppression for the same
  duplicate-region record.
- A region record whose region key is unique at confirm but later matches a
  different identical region is still a content-addressing tradeoff; document
  the migration behavior.
- Host-level `collectDeclarations` now exists, but its normalized declaration
  aggregation is by name across host and regions and is conservative.
- Earlier residuals remain: shared external rulepack fingerprinting,
  process-local baseline, per-language cache header, memory-only hints, no
  cross-process cache lock, and `aiEnv` not covering all suggestion-layer
  parameters.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 11

Verdict: The Round 10 finding 24 branch-selection defect is fixed: a record
without `regionKey`/`regionOrdinal` now resolves against the host, while a
region-identified record is resolved through the flattened leaf list. Global
leaf identity counting is now used by `SplitAnalyzer`, so scoping sees the same
duplicate-identity decision as status and suppression, and the docs now describe
recursive nested selection with only overlapping regions and legacy nested
ordinals as limitations. The `hostLevelSplitRestrictionStatusResolvesAgainstTheHost`
test proves the host resolver branch returns a restriction. One correctness
contract defect remains: host-level units are discoverable and confirmable, but
the deterministic split pipeline analyzes only regions, so host-level confirmed
annotations/restrictions are never applied; status reports such a restriction
dormant, while proposal suppression can still treat it as applicable. The
host-level status test checks only resolver non-emptiness, not status/scoping/
suppression agreement. Not ready to close.

### Per-item status

- **24. Addressed in branch selection — concur.** `resolveForStatus` now takes
  the region branch only when the record has a region identity
  (`regionKey != null || regionOrdinal != null`) and a fingerprint; the
  host-level record therefore goes through the host parse. Region records use
  `SplitAnchorResolver.leafRegions`, select exactly one key match or the legacy
  ordinal leaf, and shift results into host coordinates. The host-level test
  exercises the host branch. End-to-end agreement is still blocked by finding
  27, because host-level records are not analyzed.
- **Global ambiguity — concur.** `SplitAnalyzer.analyze` computes one
  `leafIdentityCounts` map from `SplitAnchorResolver.leafRegions(host, handler)`
  and threads it through recursive `analyzeRegions`; each leaf region gets the
  flattened-count flag. `SplitAnchorResolver.anchor`, status matching, and
  suppression use the same flattened key space. `SplitAmbiguityTest` covers a
  top-level and nested duplicate. Residual: the test asserts region flags, not
  dormant analysis/status/suppression.
- **Docs — concur.** `docs/ai-annotation-session.md` now states that ambiguity
  is computed over the flattened leaf set and that nested leaf selection is
  recursive for discovery, anchoring, status, and suppression; overlapping
  regions and legacy ordinal-only nested records are the remaining selection
  limits. Missing caveat for host-level confirmations, see finding 27.
- **Nested/host traversal and language metadata — concur by inspection.** Host
  parent repair, host-level declaration collection, per-unit language, the
  conditional sorted `languages` set, and region-routed type lookup remain in
  place. Residuals: no filtered-host-parent test, no host-level declaration
  test, and no assertion on the prompt's `languages` field.
- **No new regressions found** in recursive leaf enumeration, absolute
  byte/line/column shifts, or region-key selection. The one open correctness
  issue is the host-level analysis/confirmation policy below.

### Finding

27. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerDiscovery.kt:68-88`, `LiveServerDecisions.kt:128-168`, `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:197-214`, `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:104-170`, and `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:405-464` — host-level units are confirmable but the split pipeline analyzes only regions, and status/scoping/suppression disagree for their records.**

   `sessionDocument` adds host-level expression units outside regions (for
   example a Vue template `foo()`), so the AI session can grade them and the
   user can confirm a candidate. Candidate confirmation writes a
   `UserAnnotation` with no region identity; host-level restriction records can
   also be persisted (the status test constructs one), and nothing in the split
   analysis path scopes or applies them. The deterministic split path is `SplitAnalyzer`,
   which only loops over `handler.regions`; host code outside regions is never
   analyzed (confirmed by `docs/engine.md`: the host file is not analyzed unless
   a handler returns a region for it). `HostPipeline.userContributionsFor` and
   `restrictionsFor` are likewise called only for regions. A confirmed
   host-level user annotation therefore never reaches `publishTags`, and a
   host-level restriction is never scoped or applied.

   `LiveServerRestrictions.resolveForStatus` now correctly parses the host for
   a record with no region identity, but `status` then calls
   `restrictionApplies` against the cached output. Because the cached split
   output contains no host-level annotation, the record is reported `dormant`.
   `LiveServerCandidates.restrictionMatchesProposal`, by contrast, accepts a
   host-level record through the `else -> true` branch, resolves it against the
   host source and checks the host fingerprint, and can suppress a host-level
   proposal. Scoping has no host-level branch at all. The new test
   `hostLevelSplitRestrictionStatusResolvesAgainstTheHost` only asserts that
   `resolveForStatus` is non-empty; it does not assert active status,
   confirmation, publishTags, or suppression agreement.

   Reproduction: a Vue host with a template expression and a `<script>`
   region; confirm an AI candidate on the template expression as a user
   annotation; call `cint/publishTags` and observe that no host-level annotation
   appears. For a host-level restriction record, `resolveForStatus` returns a
   resolution but `cint/restrictions/list` reports `dormant`, while
   `publishCandidates` can suppress the same host-level proposal.

   Suggested fix: choose one host-level policy and enforce it everywhere. Either
   analyze host-level units with the host frontend (while excluding units inside
   regions to avoid duplicates), or exclude host-level units from confirmable
   discovery and clearly mark them suggestions-only. Make status, scoping, and
   suppression use the same applicability predicate, for example resolved
   records must match cached annotations or a shared applicability check rather
   than suppression accepting merely a non-empty resolution. Add an end-to-end
   host-level confirm then analyze/status/suppress test.

### Concurs / Dissents

- Concurs: global flattened ambiguity, recursive leaf anchoring/status, the
  host fallback branch, the host-level resolver test, nested docs correction,
  host parent repair, host declaration collection, per-unit language, and the
  conditional `languages` contract.
- Dissents: finding 27 (host-level units are confirmable but not analyzed;
  status, scoping, and suppression do not agree).

### Residuals

- Cross-level ambiguity is tested only at the frontend region-flag level; add a
  server assertion that analysis, `cint/restrictions/list`, and proposal
  suppression all stay dormant for the same record.
- Add a host-level candidate confirm test covering `publishTags`, and a
  host-level restriction test covering status and suppression; the current test
  only checks resolver non-emptiness.
- `SplitDiscoveryNestedTest` still covers only `collectLeafRegions`; add nested
  `sessionDocument` unit IDs/absolute spans and a nested `lookup_type` test.
- No test covers a filtered host parent or a direct host declaration edit.
- Assert the prompt's deduplicated `languages` set and that a single-language
  prompt omits it; the current fixture reads the field but does not assert it.
- Overlapping regions and legacy ordinal-only nested records remain selection
  limitations; flattened leaf ordinals differ from local nested ordinals.
- A region-identified record without a `unitFingerprint` falls back to the host
  branch; normal confirmations carry a fingerprint, but hand-edited/legacy
  records should be covered explicitly.
- Earlier residuals remain: shared external rulepack fingerprinting,
  process-local baseline, per-language cache header, memory-only hints, no
  cross-process cache lock, and `aiEnv` not covering all suggestion-layer
  parameters.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 12

Verdict: Finding 27 is addressed for the live/server split path. `SplitAnalyzer`
now parses the host with `hostFrontend`, runs the normal rule engine over the
host source outside leaf regions, merges those annotations/candidates, and
filters results that overlap a leaf region. The server callbacks pass host user
annotations and restrictions, so a confirmed host-level user annotation is
published, and status/suppression now both call `restrictionApplies` against the
cached analysis. The strengthened tests cover a real host-level candidate
confirmation and a host-level restriction that is dormant and does not suppress
a proposal. Three correctness issues remain: unscoped records are passed into
the host analysis, host-level-suppression context is not constrained to
host-level proposals, and the CLI split path still uses the old no-host
analysis call. Not ready to close.

### Per-item status

- **27. Addressed for the server/live path — concur.** `SplitAnalyzer` accepts
  `hostFrontend`/host callbacks, analyzes the host source, filters annotations
  and candidates overlapping `leafRegions`, and concatenates/sorts the merge.
  `LiveServerDocument` and server `Main` pass the host callbacks.
  `hostLevelSplitUserAnnotationConfirmIsPublished` confirms a real discovered
  host unit and asserts a user tag; the restriction test asserts status and
  proposal suppression agree for a non-applicable record. Residual: no test
  exercises an applicable host-level restriction (one whose removal/addition
  matches a host annotation) or checks that host analysis does not leak region
  records.
- **Status/suppression predicate — concur in the shared-predicate sense.**
  `LiveServerRestrictions.status` and `LiveServerCandidates.restrictionMatchesProposal`
  both use `restrictionApplies` / `resolved.any` against cached annotations.
  They can still differ in *context* for host-level records; see finding 29.
- **Merged output — concur for span de-duplication, dissent on record scoping.**
  The host result is filtered by `overlapsLeaf` before merging, so a host
  annotation/candidate inside a leaf is dropped. However the host callbacks are
  unscoped and can resolve region records against a host-level unit before that
  filter; see finding 28.
- **Host-level tests — partial concur.** The new tests are real server-path
  tests for confirmation and status/suppression non-applicability. They do not
  test an active host restriction, a region proposal with a host restriction,
  or identical template/script content.
- **Docs — concur for the server behavior.** `docs/engine.md` and
  `docs/ai-annotation-session.md` now describe host-source analysis and the
  shared applicability predicate. The CLI caveat/behavior remains inconsistent
  (finding 30 if the CLI host is in scope).

### Findings

28. **`analysis-engine/languages/frontend/src/main/kotlin/dev/cint/frontend/SplitAnalyzer.kt:87-112` with `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerDocument.kt:217-223` (and server `Main.kt`) — host analysis receives all user annotations/restrictions, including region-identified records, and only filters the resulting spans by leaf overlap.**

   The server callbacks call
   `packs.userContributionsFor(host, host, tree, syntax)` and
   `packs.restrictionsFor(host, host, tree, syntax)` with no region scope.
   `HostPipeline` therefore passes every record to `UserAnnotationResolver` and
   `AiRestrictionResolver` against the host source. Region records carry a
   `regionKey`/`regionLanguage` and are intended to resolve only inside their
   leaf; the host frontend does not inspect those fields, and the host parse may
   contain an identical host-level unit (for example template `f(a, b)` and
   script `f(a, b)` have the same normalized unit identity/context). If the
   fingerprint/content matches, the region user annotation or restriction is
   applied to the host-level unit, producing an annotation outside the leaf.
   `overlapsLeaf` removes only results that land inside a region, so the leaked
   host-level result survives the merge. This can both add an unauthorized
   host tag and apply a region restriction to host source.

   Suggested fix: give the host pass a host-only record set (records with no
   `regionKey`/`regionOrdinal`, or records whose host span is outside every
   leaf) before calling the user/restriction resolvers; keep the final span
   filter as defense in depth. Add a same-text template/script test asserting
   that a region confirmation does not appear on the template and a host
   confirmation does not appear in the region.

29. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:420-448` — a host-level restriction is accepted for a region proposal (`else -> true`), then resolved against the region source, so suppression can use a different context than status.**

   The `when` matches a record with no region identity to every proposal and
   resolves it with `context` taken from the proposal. For a region proposal,
   `context.text` is the leaf source; a host-level restriction with the same
   content/fingerprint can resolve and satisfy `restrictionApplies` against a
   region annotation. `resolveForStatus` resolves the same host-level record
   against the host source, so the two consumers can disagree (especially when
   the host has no matching annotation but the region does). The inverse
   direction is guarded because a region record requires `context.regionKey`.
   The current host-level suppression test only uses a host-level proposal.

   Suggested fix: for a record with no region identity, require the proposal
   context to be host-level (`context.regionKey == null && context.regionOrdinal
   == null`) and resolve against the host source; keep the existing region-key
   branch for region records. Add a host-level restriction + region proposal
   test asserting it is not suppressed unless the host context itself applies,
   and compare the result with `cint/restrictions/list`.

30. **`analysis-engine/hosts/cli/src/main/kotlin/dev/cint/cli/Main.kt:66-80` — the CLI split path still calls `SplitAnalyzer.analyze` without `hostFrontend` or host callbacks, so host-level source is analyzed only by the server/live paths.**

   The engine documentation now states that host source outside leaf regions is
   analyzed with the host frontend, and both server hosts pass the new
   arguments. The CLI still relies on the old default (`hostFrontend = null`),
   so running the CLI over a Vue file produces region annotations only and
   omits host-level template expressions, confirmed host annotations, and host
   restrictions. If the CLI is intended to share the split-analysis contract,
   add the same host arguments there (and include the host language in
   `usedLanguages`). Add a CLI/integration test if the CLI is in scope.

### Concurs / Dissents

- Concurs: host-source analysis and leaf-overlap filtering in the server path,
  host-level user-annotation confirmation, shared `restrictionApplies`
  predicate, candidate/status/suppression test wiring, and the docs update.
- Dissents: finding 28 (unscoped records can leak across host/region contexts),
  finding 29 (host-level restriction suppression can use a region context), and
  finding 30 (CLI split analysis does not run the host pass).

### Residuals

- Add an applicable host-level restriction test: confirm a host-level user
  annotation, add a restriction whose remove/add matches it, republish/analyze,
  and assert `cint/restrictions/list` is active and the matching proposal is
  suppressed.
- Add a host/region identical-content test covering both user annotations and
  restrictions; the current tests use different template/script text.
- Cross-level ambiguity is still tested only at the frontend region-flag level;
  add server status/suppression assertions for the same duplicate record.
- `SplitDiscoveryNestedTest` still covers only `collectLeafRegions`; add nested
  `sessionDocument` unit IDs/absolute spans and a nested `lookup_type` test.
- No test covers a filtered host parent or a direct host declaration edit.
- Assert the prompt's deduplicated `languages` set and that a single-language
  prompt omits it; the fixture reads the field but does not assert it.
- Overlapping regions and legacy ordinal-only nested records remain selection
  limitations; flattened leaf ordinals differ from local nested ordinals.
- A region-identified record without a `unitFingerprint` falls back to the host
  branch; normal confirmations carry a fingerprint, but hand-edited/legacy
  records should be covered explicitly.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.

## Round 13

Verdict: Round 12 findings 28-30 are addressed for records with a unit
fingerprint and for the normal CLI/server paths. Host analysis now receives
only no-region, host-span records through `hostUserContributionsFor`/
`hostRestrictionsFor`; region analysis scopes by region identity or legacy span;
the identical template/script user-annotation test pins ownership; suppression
checks the proposal context for a host-level record; the CLI passes the host
callbacks; and the split prompt test asserts a root `languages` entry. Two
ownership/legacy defects remain. Records without a `unitFingerprint` still bypass
the split classifier in status and suppression, so scoping can apply a legacy
region record while status resolves it against the host and suppression ignores
it; and the straddling-span/host-context suppression path still lacks a shared
record-ownership check. Not ready to close.

### Per-item status

- **Host ownership — concur for normal records.** The server/CLI host callbacks
  now require `regionKey == null && regionOrdinal == null` and
  `SplitAnchorResolver.isHostLevelSpan`, so region-identified records and
  records whose spans overlap a leaf are excluded from host analysis.
  `HostPipeline.scopeToRegion`/`scopeRestrictionsToRegion` retain legacy
  span/hash scoping for region analysis, and
  `identicalTemplateAndScriptContentKeepRecordOwnership` checks the inverse
  direction for user annotations. Residual: the test covers user annotations,
  not restrictions, and the no-fingerprint legacy path is not covered.
- **Suppression ownership — concur for fingerprinted host/region records.**
  `restrictionMatchesProposal` now requires a region-owned record for a region
  context (the no-region `else` branch checks the record span is contained in the
  region) and accepts a no-region record for a host context. The new
  `hostLevelRestrictionDoesNotSuppressARegionProposal` test covers the normal
  case. Residual: the host-context branch does not prove the record is
  host-owned, and records without a fingerprint are excluded before the branch.
- **CLI — concur.** `analysis-engine/hosts/cli` now passes `hostFrontend`,
  `hostUserContributionsFor`, and `hostRestrictionsFor` with the same ownership
  predicate, and includes the host configuration language in `usedLanguages`.
  Residual: no CLI split-host end-to-end test.
- **Prompt `languages` — concur for the added assertion.** The split prompt
  provider records root `languages` in `seenPromptLanguages`, and
  `splitDeclaration...` asserts `javascript` is present both as a unit language
  and in the root set. Residual: the test does not assert the exact sorted set
  (`javascript`, `vue`) or that a single-language prompt omits the set.
- **Docs — concur for host analysis and CLI.** The engine/snapshot text describes
  host-level analysis, leaf filtering, and shared applicability; the CLI now
  implements that contract. The legacy/no-fingerprint and straddling-span
  ownership behavior is not documented.
- **No new merge/span regression in the normal path.** Host and region spans are
  filtered against the same leaf set; the new same-text test catches the
  user-annotation collision. The remaining issues are classifier/legacy
  completeness, not duplicate spans for normal records.

### Findings

31. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerRestrictions.kt:109-170`, `LiveServerCandidates.kt:420-466`, and `analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:183-351` — legacy records without a `unitFingerprint` still bypass the split ownership classifier.**

   `resolveForStatus` enters the split branch only when
   `record.unitFingerprint != null`; `restrictionMatchesProposal` also filters
   on that field. But region scoping in `scopeRestrictionsToRegion` and
   `scopeToRegion` accepts a no-region record by span and content hash even when
   it has no fingerprint, and the host callbacks accept a no-region, non-
   overlapping host record by span. Consequently:

   - A legacy region record (no region key/ordinal, `unitFingerprint == null`,
     span inside a leaf) is applied by region analysis, while status takes the
     host fallback and suppression ignores the record entirely.
   - A legacy host-level record without a fingerprint is applied by host analysis
     and status can report it active against host annotations, while suppression
     ignores it because of the `unitFingerprint != null` filter.
   - A region-key/ordinal record with no fingerprint is likewise treated as a
     host record by status and is never considered for suppression, while region
     scoping still applies it by span.

   This preserves the old disagreement finding 28 was meant to remove for the
   legacy subset. Suggested fix: introduce a shared ownership classifier that
   does not depend on `unitFingerprint`: region identity wins; otherwise a span
   contained in exactly one leaf is legacy-region; a span with no overlap is
   host; an overlapping/straddling span is dormant. Use it in status, scoping,
   host callbacks, and suppression, and add no-fingerprint legacy region and
   host tests for each path.

32. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/live/LiveServerCandidates.kt:420-448` with `SplitAnchorResolver.isHostLevelSpan` and `resolveForStatus` — host-context suppression and straddling spans use different ownership rules.**

   For a record with no region identity, the `else` branch of
   `restrictionMatchesProposal` returns true whenever the proposal context is
   host-level, without checking that the record's span is actually outside every
   leaf. A legacy region record whose span is contained in a leaf can therefore
   suppress a host-level proposal (status resolves that record through the
   region branch), while a record whose span straddles a leaf is excluded from
   host analysis by `isHostLevelSpan` (it overlaps) but can still be treated as
   host by status/suppression because it is not fully contained in a region and
   no span-ownership check rejects it. `scopeRestrictionsToRegion` rejects the
   straddling restriction, while `scopeToRegion` may clip a no-fingerprint user
   annotation; the result is another scope disagreement. The engine docs say a
   restriction that straddles a region boundary is inapplicable, which should be
   a dormant result, not a host fallback.

   Suggested fix: use the same classifier for the no-region case in
   `restrictionMatchesProposal` and `resolveForStatus`: a host-context proposal
   accepts only a record whose span has no leaf overlap; a region-context
   proposal accepts only a unique contained legacy record; straddling/ambiguous
   records are dormant. Make the user-annotation and restriction scoping paths
   use the same rule. Add tests for a legacy region record with a host proposal
   and for a boundary-straddling record.

### Concurs / Dissents

- Concurs: host callback ownership filters, the identical template/script
  user-annotation test, normal host/region suppression context checks, CLI host
  wiring, the prompt root-`languages` assertion, host-source analysis, and the
  host/region merge for fingerprinted records.
- Dissents: finding 31 (no-fingerprint legacy records still bypass split
  status/suppression ownership) and finding 32 (host-context suppression and
  straddling span ownership remain inconsistent).

### Residuals

- Add legacy no-fingerprint tests for `cint/restrictions/list`, region scoping,
  host-level analysis, and proposal suppression; the current tests all use
  fingerprinted records.
- Add a restriction version of the identical template/script ownership test and
  a host-level restriction that actually applies to a host annotation (the
  current host-level restriction test exercises a dormant record).
- Add exact prompt `languages` assertions: the sorted set for host+region and no
  set for a single-language prompt.
- Add a CLI split-host test covering host-level tags and restrictions.
- Cross-level ambiguity still needs server status/suppression coverage; nested
  `sessionDocument`/`lookup_type`, filtered host-parent, host declaration, and
  nested host-level analysis coverage remain open.
- Overlapping regions, legacy nested ordinals, the short-circuit suppression
  bypass (added in the prior architecture residual), and earlier cache/rulepack
  residuals remain.
- Review remains read-only: no source, tests, documentation other than this
  report, build, formatter, lint, or test command was run.
