# Correctness review — ticket 056 (detection and AI sensitivity bundle)

## Round 1

Verdict: The scaffold visual filter, the statement-wide `RETURN_VALUE` span,
the SEARCH/LOOKUP boundary removal, and the AI recall defaults are implemented
as described, but the new span now coincides with the return-category scaffold
and contaminates `ScaffoldComposition`; the derivative exception also promotes
non-`RETURN_VALUE` intents past the all-uses-classified gate. The renderer's
unconditional `UNTAGGED` filtering changes untagged-only spans, and some docs
still describe the old behavior. Not ready to close.

### Findings

1. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/composition/ScaffoldComposition.kt:211-222` (with `analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/structural/returnvalue/ReturnValueRulePack.kt:45-51`) — the statement-wide `RETURN_VALUE` span becomes a scaffold category and suppresses or contaminates parent scaffolds.**

   In semicolon-less languages the new `RETURN_VALUE` emit now has exactly the
   same `startByte`/`endByte` as the return statement node (see the regenerated
   snapshot entry for `kotlin/Composition.kt`: both spans are `645-656`).
   `ReturnComposition` already emits a `core:return-category` `UNTAGGED`
   scaffold on that same node. When `scaffoldContributions` runs,
   `exitIntents(returnNode)` therefore sees
   `ctx.coverage(returnNode) = {RETURN_VALUE, UNTAGGED}`, `hasRealIntent` is
   true, and it returns that mixed set instead of falling through to
   `ctx.intentsAt(expression)`.

   Consequences: for `fun expression(value: String?) { return requireNotNull(value) }`
   the function body resolves to `{RETURN_VALUE, UNTAGGED}` and
   `functionScaffolds` drops the function scaffold (`UNTAGGED` present) instead
   of emitting the previous `ERROR_HANDLING` scaffold; for
   `if (x) { return requireNotNull(y) }` the branch scaffold is now tagged
   `{RETURN_VALUE, UNTAGGED}` instead of `ERROR_HANDLING`. The existing
   `ReturnCategoryCompositionTest.returnScaffoldsTreatBareReturnsAsNeutralAndCarryExpressionIntents`
   does not catch this because it uses only a `ReplayPack` and never installs
   `ReturnValueRulePack`, and the snapshot fixtures only return literals in
   conditional branches, which `return-value` excludes. `return-value` is in the
   bundled `META-INF/services` list, so this affects the production pipeline.

   Suggested fix: make `RETURN_VALUE` non-participating in scaffold category
   resolution. In `exitIntents`, subtract `RETURN_VALUE` from
   `ctx.coverage(node).intents` before the `hasRealIntent` check and return, and
   also subtract it from `ctx.intentsAt(expression)` in the fallback, so the
   domain intent (`ERROR_HANDLING`, `SEARCH`, ...) is recovered. Add a
   full-pipeline test that runs `ReturnValueRulePack` with the domain pack for a
   returned non-literal call and for a conditional return.

2. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/runtime/composition/DerivativeComposition.kt:54-58` — the `RETURN_VALUE` exception relaxes every read intent, not just `RETURN_VALUE`, producing over-propagation.**

   Once `carriesReturnValue` is true the guard is bypassed for the whole local,
   and `useIntents.flatten().filter { it != UNTAGGED }` collects intents from
   reads that the old all-uses-classified policy would have blocked because a
   sibling read was unclassified. The regenerated snapshot shows this:
   `analysis-engine/tests/integration-tests/src/test/resources/snapshots/pipeline-current.json:4`
   (`csharp/Sample.cs` line 23) and line 5 (`javascript/Sample.js` line 10) gain
   `derivative-use LOGGING,RETURN_VALUE` on the `value` declaration. The
   `if (value < 0)` condition read is unclassified, so the old policy suppressed
   the derivative entirely; the new exception now also promotes `LOGGING` from
   the `Console.WriteLine`/`console.log` read. The ticket says to propagate
   `RETURN_VALUE` while keeping *other* derivative-use intents on the existing
   all-uses-classified policy; these snapshot additions are beyond that
   acceptance and are not covered by the new tests.

   Suggested fix: in the relaxed branch (`!allClassified &&
   carriesReturnValue`), keep the promoted set to `RETURN_VALUE` (or only add
   intents from fully classified reads plus `RETURN_VALUE`), and use the full
   union only when `allClassified`. Add a test with a classified `LOGGING` read
   plus an unclassified sibling read and assert the declaration does not gain
   `LOGGING`; regenerate the two snapshot entries.

3. **`nvim-renderer/lua/cint/render/init.lua:11-19,41,65-75` and `nvim-renderer/lua/cint/render/labels.lua:31-37` — unconditional `UNTAGGED` filtering changes untagged-only spans instead of only fixing mixed scaffolds.**

   `real_intents` is applied even when `UNTAGGED` is the only intent. Such a
   span then has no labels, so `resolve_meaning` returns `resolve.default`
   (`SHOW` in the default profile) and `chosen_tag` returns `"default"` rather
   than the configured `UNTAGGED` meaning/tag. Before this change an
   untagged-only span honored a profile such as `UNTAGGED = { meaning =
   "IGNORE" }` or `UNTAGGED = { meaning = "CONCEAL" }`; now it falls through to
   the default meaning/tag. Normal `core:return-category` returns are
   `UNTAGGED`-only, so this is a live shape, not only a contrived one. The
   ticket's goal is to stop `UNTAGGED` from suppressing a scaffold that has a
   real intent, not to change untagged-only rendering.

   Suggested fix: filter `UNTAGGED` only when at least one non-`UNTAGGED` intent
   remains; otherwise preserve the original intent list (or return an explicit
   neutral no-op) so untagged-only spans keep their existing meaning/tag. Add a
   renderer test with a single `UNTAGGED` scaffold and `UNTAGGED` meaning
   `IGNORE`/`CONCEAL`.

4. **`docs/architecture.md:157` — the architecture doc still says `return-value` emits on the returned expression.**

   This contradicts the new whole-statement contract in `docs/engine.md`,
   `docs/intent-model.md`, and the code. Suggested fix: update it to the
   statement-wide span (from the `return` keyword through the expression).

5. **`docs/notes.md:425-429`, `nvim-renderer/README.md:238-239`, and `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/CandidateValidator.kt:11,31` — some AI default surfaces still advertise/use the old recall values.**

   Both docs still say `maxTokens` defaults to `512` (and the notes/README still
   give `timeoutMs` 4000), while the code default is now `1024`/`10000`. The
   `CandidateValidator` fallback default also remains `5` even though
   `SuggestionOptions` and `SuggestionSettings` moved to `10`; production
   callers pass an explicit budget, so it is currently dormant, but it is an
   easy future inconsistency. Suggested fix: update both docs to `maxTokens:
   1024` and `timeoutMs: 10000`, and either raise the validator fallback to `10`
   or document that its `5` is only a last-resort default.

### Verified

- `ReturnValueRulePack` now starts at `site.node` (the `return` keyword) and ends
  at the expression end; normal, labeled (`return@map ...`), literal-only, and
  coexistence tests are updated and non-vacuous, and the Kotlin/C#/JS/TS
  snapshot spans reflect the full statement.
- The motivating derivative shapes (`val result`, `val updated`, and writes of
  `val merged`) are covered with real pipeline packs and assert `RETURN_VALUE`;
  known writes/mutation still block promotion.
- `boundaryPrefixes` defaults to empty, `repository.findById` remains `SEARCH`
  with no default `LOOKUP` candidate, an explicit `boundaryPrefixes` override
  still emits it, and known-map/enum retrieval is unchanged.
- AI defaults moved to 10/12/1024 with temperature `0.3`, the prompt asks for
  alternatives/lower-confidence guesses, and vocabulary, byte containment,
  candidate-only output, and the confidence cap are unchanged; provider and
  server tests exercise the new values.
- The `{ERROR_HANDLING, UNTAGGED}` scaffold test is non-vacuous (the old
  mixed-scaffold suppression would skip the highlight), and labels/stripes
  filter `UNTAGGED` consistently with hover.
- No source edit, build, test, lint, or formatter command was run by this role;
  this was a read-only review.

### Summary

The bundle is largely correct: the mixed-scaffold `UNTAGGED` fix, the full
`return` span, the motivating derivative propagation, the SEARCH/LOOKUP
boundary removal, and the AI recall changes all work and are tested, but the
statement-wide `RETURN_VALUE` span exactly equals the return node in Kotlin and
so `ScaffoldComposition.exitIntents` now reads `{UNTAGGED, RETURN_VALUE}` and
either drops the domain scaffold (functions) or tags it with `RETURN_VALUE`
(conditionals) instead of the domain intent; that needs `RETURN_VALUE` excluded
from scaffold category resolution plus a full-pipeline test, the derivative
exception should be narrowed to `RETURN_VALUE` when the all-classified gate
fails, the renderer should keep untagged-only spans on their configured
behavior, and the stale architecture/AI-default docs should be updated. No
other contract or test defect was found.

Verdict: dissents.

## Round 2

Verdict: Findings 1-5 are resolved. One new defect was introduced by the
round-2 `getBy*`/`loadBy*` search-prefix addition: prefix matching is a plain
case-insensitive `startsWith`, so unrelated calls such as `getBytes()` become
established `SEARCH`, and the explicit `lookupCalls` entry `getByKey` now also
picks up a `SEARCH` candidate. Not ready to close until that is fixed or
explicitly deferred.

### Finding status

1. Resolved — `ScaffoldComposition.exitIntents` now subtracts `RETURN_VALUE`
   from both `ctx.coverage(node).intents` and the `ctx.intentsAt(expression)`
   fallback. The new `ReturnCategoryCompositionTest` cases install the real
   `ReturnValueRulePack` and `ErrorHandlingRulePack` for the function and
   conditional shapes, so they would fail if the returned domain intent were
   suppressed; the snapshot still contains the expected function/branch
   scaffolds and only widens the return spans.
2. Resolved — the relaxed derivative branch now emits only
   `setOf(RETURN_VALUE)` when `!allClassified`; the full union is back behind
   `allClassified`. The new `returnedLocalKeepsSiblingIntentsBehindTheAllClassifiedGate`
   test pins a classified `LOGGING` read plus an unclassified sibling read and
   asserts only `RETURN_VALUE`. The regenerated C#/JS snapshot entries are now
   `derivative-use RETURN_VALUE` (no `LOGGING`), matching the ticket.
3. Resolved — `real_intents` in `render/init.lua`, `labels.lua`, and
   `stripes.lua` now falls back to the original intent list when filtering
   leaves nothing, so untagged-only spans keep their configured meaning/tag.
   The new untagged-only `IGNORE` renderer case is non-vacuous (the round-1
   filter would resolve `SHOW`/`default` instead), and the mixed case still
   renders `ERROR_HANDLING`.
4. Resolved — `docs/architecture.md:156-158` now describes the statement-wide
   span.
5. Resolved — `docs/notes.md` and `nvim-renderer/README.md` now state
   `maxCandidates` 10, `maxEnrichmentCandidates` 12, `maxTokens` 1024, and
   `timeoutMs` 10000; `CandidateValidator` defaults are 10. The prompt version
   was also bumped to `deepseek-intent-v2` and its docs/tests updated, which is
   consistent with the round-1 prompt change.

### New finding

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/config/DataAccessConfigDefaults.kt:33` with `.../rules/DataAccessSearchMatcher.kt:41-55` and `.../rules/DataAccessMatching.kt:6-15` — the new `getBy`/`loadBy` search prefixes over-match and can establish `SEARCH` on unrelated calls.**

   Search prefixes are matched with `callee.startsWith(prefix, ignoreCase =
   true)` and no token boundary. Adding `getBy` therefore also matches
   `getBytes`: on a receiver whose name is a search qualifier (for example
   `service.getBytes()`, `api.getBytes()`, or `client.loadBytes()`) the call is
   now an established `SEARCH`; before this round it was only an ambiguous
   `get*`/`load*` candidate. Separately, `getByKey` is an explicit
   `lookupCalls` entry, but it also starts with `getBy`, so
   `config.getByKey()` (known lookup receiver) now gets a spurious `SEARCH`
   candidate alongside its established `LOOKUP`, and a search-qualified
   receiver can make it established `SEARCH`. The added test only exercises
   `getById`/`loadById`, so neither collision is pinned.

   Suggested fix: match search prefixes at a camel/snake token boundary
   (prefix followed by end, an uppercase letter, or a separator) at least for
   the new `getBy`/`loadBy` entries, and do not treat an explicit `lookupCalls`
   callee as a configured search call unless it is also explicitly configured
   as a search call. Add tests for `service.getBytes()`/`client.loadBytes()`
   (no established `SEARCH`) and `config.getByKey()` (established `LOOKUP`,
   no `SEARCH` candidate), then re-run the data-access suites.

### Verified

- Snapshot comparison against `HEAD` shows only the intended full-statement
  `RETURN_VALUE` widening plus the now-`RETURN_VALUE`-only derivative entries in
  `csharp/Sample.cs` and `javascript/Sample.js`; no scaffold was dropped.
- The new scaffold, derivative, renderer, and getBy/loadBy tests are
  non-vacuous against their corresponding round-1 implementations.
- Prompt version v2 is used by the provider, live DeepSeek test, `docs/engine.md`,
  `docs/notes.md`, and `docs/ai-intents.md`; no `deepseek-intent-v1` references
  remain outside historical review reports.
- No source edit, build, test, lint, or formatter command was run by this role.

### Summary

Findings 1-5 are resolved with targeted code and non-vacuous regression tests:
`RETURN_VALUE` no longer participates in scaffold category resolution, the
derivative exception is limited to `RETURN_VALUE` unless every read is
classified, untagged-only renderer spans retain their configured behavior, and
the architecture/AI-default docs plus the validator fallback and prompt version
are current. The only remaining correctness issue is new in this round: the
`getBy`/`loadBy` search prefixes are matched without a token boundary and
without lookup-call precedence, so common calls such as `getBytes()` can become
established `SEARCH` and `config.getByKey()` gains a spurious `SEARCH`
candidate. If that is fixed or deferred to a named ticket, this dimension can
close.

Verdict: dissents.

## Round 3

Verdict: The round-2 search-prefix defect is resolved, no new correctness
finding was identified, and findings 1-5 remain resolved. Ready to close.

### Finding status

1. Round-2 finding — resolved. `matchesTokenBoundaryPrefix` requires either an
   exact/prefix match whose suffix starts an uppercase letter, digit, or
   separator, or a whole camel/snake token-prefix match, and it replaces the
   plain `startsWith` checks in `isAmbiguous`, `isEstablishedSearch`, and
   `isConfiguredSearchCall`. `getBytes`/`loadBytes` no longer match `getBy`/
   `loadBy`; the new `searchPrefixDoesNotMatchLowercaseContinuation` test pins
   that they remain ambiguous `SEARCH` candidates, and the existing
   `getById`/`loadById` test still establishes `SEARCH` with no `LOOKUP`
   alternative.
2. Round-2 finding — resolved. `isLookupOnly` (`lookupCalls` minus explicit
   `searchCalls`) is applied before search classification, so `config.getByKey`
   stays an established `LOOKUP` with no `SEARCH` source or candidate; the new
   `explicitLookupCallTakesPrecedenceOverSearchPrefix` test is non-vacuous
   against the round-2 implementation, which emitted a `SEARCH` candidate.
   Explicit user opt-in through `searchCalls` still works because `isLookupOnly`
   excludes entries that are also search calls.
3. Findings 1-5 from round 1 remain resolved: the scaffold `RETURN_VALUE`
   subtraction, restricted derivative branch, untagged-only renderer fallback,
   and the architecture/AI-default/validator/prompt-version updates are all
   unchanged and still covered by their non-vacuous tests.
4. `docs/data-access-intents.md` now documents the token-boundary rule, the
   lookup-call precedence, and the explicit `searchCalls` opt-in consistently
   with the implementation; `docs/engine.md` remains aligned.

### Verified

- `matchesTokenBoundaryPrefix` rejects lowercase continuations (`getBytes`,
  `loadBytes`, `searching`) while accepting exact prefixes, camel continuations
  (`getById`, `searchUsers`), digit continuations, separators, and snake-case
  token prefixes (`get_by_id`).
- `isLookupOnly` does not suppress `SEARCH` for calls explicitly present in
  both `lookupCalls` and `searchCalls`, matching the documented opt-in.
- The new tests fail against the round-2 code and pass against the round-3
  code; no snapshot change was needed for this fix.
- No source edit, build, test, lint, or formatter command was run by this role.

### Summary

Round 3 replaces the plain `startsWith` search-prefix checks with a
token-boundary helper and gives explicit `lookupCalls` precedence over search
prefixes. The earlier over-match is gone: `service.getBytes()` and
`client.loadBytes()` stay ambiguous candidates rather than becoming established
`SEARCH`, while `repo.getById`/`repo.loadById` still establish `SEARCH` with no
`LOOKUP` alternative, and `config.getByKey()` remains an established `LOOKUP`
with no `SEARCH` candidate. The added tests are non-vacuous against round 2,
the docs describe the same rule, and all earlier scaffold, derivative,
renderer, and AI-default findings remain resolved. I found no new correctness
issue; this dimension can close.

Verdict: concurs.
