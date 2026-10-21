# Detection-fidelity review — ticket 037 (normalization intent)

## Round 1

Verdict: The basic shape is right: `NORMALIZATION` is a config-driven call rule
with per-language defaults, established emits are separate from
`aiEligible` candidates, and the pack owns only its own intent with no reads or
grammar traversal. The detection semantics are not yet faithful enough to close
the ticket. The pack is not service-registered, so it never runs in the host/CLI
or live daemon; chain candidates are produced for any call whose receiver chain
contains a normalizer, including validation predicates and non-transform tails;
and the qualifier/prefix/format defaults promote several ambiguous or
display-oriented helpers to established `NORMALIZATION`. The composition test
does not exercise these overlaps.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`
   omits `NormalizationRulePack`; `analysis-engine/core/rulepacks/src/test/kotlin/dev/cint/rulepacks/BundledRulePackRegistrationTest.kt:7`,
   `BundledRulePackConfigMigrationTest.kt:7`, and
   `analysis-engine/tests/integration-tests/src/test/kotlin/dev/cint/integration/host/BundledRulePackRegistrationHostTest.kt:6`
   still enumerate only the four pre-existing packs.**

   `RulePackLoader` discovers service providers from `META-INF/services`; nothing
   references `dev.cint.rulepacks.intents.normalization.NormalizationRulePack`
   outside the new tests, which construct it directly. In a real host run the pack
   is absent, `NORMALIZATION` never enters the intent vocabulary, no
   normalization span is produced, and a project section named `normalization`
   is reported as an unknown rulepack. The acceptance's deterministic coverage is
   therefore test-only, and there is no `testing-grounds` fixture or golden that
   exercises the pack through `HostPipeline`.

   Suggested fix: register the provider in the service file, add `normalization`
   to the bundled-id/intent assertions and the host registration test, and add a
   fixture that proves the pack is selected per language and emits through the
   normal host path.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/normalization/NormalizationRulePack.kt:58-92`
   (pinned by `NormalizationRulePackTest.kt:230-249,280-290`) — any call on a
   receiver chain that contains one matched step becomes a candidate, regardless
   of what the outer call does.**

   `chainMatches.none { it != null }` only requires one step in the receiver
   chain to be established or candidate. Consequently `value.trim().isBlank()`
   produces a `NORMALIZATION` candidate over the whole validation condition,
   `value.trim().length()` and `value.trim().substring(1)` produce candidates over
   non-transform tails, and `value.trim().uppercase()` produces a candidate on a
   span that is already established as normalization. This makes candidates
   overlap `VALIDATION` and ordinary value use, and it contradicts the ticket's
   "ambiguous blocks yield candidates" rule: a chain of known normalizers is not
   ambiguous, yet it still gets a review candidate.

   Suggested fix: emit a chain candidate only when the chain actually needs
   review — the outermost step is not already `ESTABLISHED`, and the tail is a
   configured transform/candidate (not an arbitrary call such as a predicate,
   logging call, or accessor). Do not emit a candidate over a span that is
   already established unless the established step spans are replaced by a
   single block-level candidate. Add cases for `trim().isBlank()`,
   `trim().length`, `trim().substring(...)`, and a fully established chain.

3. **`NormalizationRulePack.kt:121-142` — configured `qualifiers` do not gate
   calls with an instance/lowercase receiver.**

   For a configured name, the qualifier check only runs inside the
   `unqualifiedStatic` branch, which requires `isTypeLike(receiver)`. Any
   receiver whose last segment starts lowercase is accepted as established
   without consulting `qualifiers` or `types`. A project that configures
   `calls: [normalize]` and `qualifiers: [Normalizers]` still tags
   `other.normalize(value)` as established; the existing separation test only
   covers the uppercase `Other.normalize` case
   (`NormalizationRulePackTest.kt:143-167`). The same bypass applies to C#
   `someObject.Parse(...)` and to any configured instance-style helper.

   Suggested fix: distinguish instance method names from qualified/static names
   (or add an explicit `requireQualifier`/`qualifiedCalls` config), so a project
   can actually narrow a name to the configured namespace. Keep unqualified
   instance calls such as `value.trim()` working through an explicit instance
   list rather than the fallback. Add a lowercase-receiver counterexample.

4. **`NormalizationConfig.kt:103,140,167,169` with `NormalizationRulePack.kt:130-132`
   — candidate formatting names are promoted to established by broad
   qualifiers.**

   `format` and `stringify` are candidate calls, but they become `ESTABLISHED`
   whenever `qualifierMatches` or `typeMatches` succeeds. Because `String` is a
   Kotlin qualifier and `JSON` is a JavaScript/TypeScript qualifier,
   `String.format(...)` and `JSON.stringify(...)` are established
   `NORMALIZATION` by default. Those are formatting/serialization helpers, and
   the ticket explicitly keeps display-only formatting out of the initial
   scope. The same rule turns `date.format(...)` on any configured date type
   into established output even when the call is only for presentation; the
   ticket allows date/time formatting, but the type alone is not a downstream
   use signal.

   Suggested fix: keep `format`/`stringify` candidate-only unless an explicit
   established field (for example `formatCalls` or a specifically configured
   qualifier/type) opts in. Do not let generic qualifiers such as `String` and
   `JSON` promote the candidate list. Add assertions that `String.format` and
   `JSON.stringify` remain candidates by default.

5. **`NormalizationConfig.kt:100,166,202` with `NormalizationRulePack.kt:121-127`
   — default prefixes are treated as established for any receiver.**

   The Kotlin/JavaScript/TypeScript defaults include `parse`, `normalize`, and
   `canonicalize` as prefixes, and C# includes `Parse`, `TryParse`, and similar
   spellings. A matching prefix is immediately `ESTABLISHED` for a receiverless
   or lowercase-receiver call, so unrelated names such as
   `obj.parseDomainEvent()`, `widget.normalizeLayout()`, or
   `command.canonicalizeForDisplay()` are tagged without qualifier/type
   confirmation. C# `TryParse` is also a common validation guard, so a broad
   prefix produces established normalization on code whose purpose may be
   rejection. The ticket notes say broad/weak cases should be `aiEligible`
   candidates rather than widening the default established set.

   Suggested fix: make prefix matches candidate-only by default and reserve
   established status for exact configured calls or prefix matches that also
   match a qualifier/type. Add false-positive cases for arbitrary `parse*` and
   `normalize*` names and for a `TryParse` guard.

6. **`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/normalization/NormalizationRulePackTest.kt:170-195`
   — the composition test checks nesting, not intent coexistence or candidate
   isolation.**

   The test only verifies that both `trim` spans are inside the validation
   condition and that two normalizations exist. It would pass even though the
   current chain logic also emits a `NORMALIZATION` candidate over
   `value.trim().isBlank()`. It does not assert the enclosing guard/scaffold
   intents, that `isBlank` itself is not normalized, or that no normalization
   candidate covers the validation predicate. The logging/bookkeeping test also
   checks only established texts, not candidates.

   Suggested fix: assert the candidate set for the guarded expression (empty
   after fixing the chain rule), assert the validation condition keeps its
   `VALIDATION` intent without a normalization candidate over it, and assert the
   enclosing `if` composes the expected intents. Add the analogous candidate
   check to the logging/bookkeeping composition case.

### Concurs / Dissents

- Concurs: the pack is config-driven and uses semantic `View` call facts; it
  contains no grammar node kinds or AST traversal, consistent with the rulepack
  support plan.
- Concurs: the per-language defaults are present for Kotlin, JavaScript,
  TypeScript, and C#, `forLanguage` falls back consistently with the other
  packs, and the configuration schema rejects unknown languages and keys.
- Concurs: established emits use `CallFact.site` spans, confidence `0.9`, and
  the tree-sitter source, while candidates use `rule:normalization:normalization`,
  `PACK_DOMAIN`, confidence `0.4`, and `aiEligible = true`. Candidates stay on
  the candidate channel and cannot certify returns or enter composition.
- Concurs: the pack owns only `NORMALIZATION` and declares no reads, so it does
  not create a scheduler dependency on validation, logging, or bookkeeping.
- Concurs: validation and normalization can coexist in principle.
  Normalization emits the nested `trim` call while validation owns the guard
  condition; the separation from logging and bookkeeping on distinct spans is
  also correct.
- Dissents: findings 1-6. The core membership/span behavior is sound, but the
  established/candidate boundary, production registration, and overlap handling
  do not yet match the intent definition.

### Residuals (not findings)

- The defaults have no unit-conversion entries despite the ticket's examples,
  and common date/time forms such as `DateTimeFormatter.ISO_DATE.format(...)`
  are not matched because the qualifier check does not handle a chained
  formatter constant. Both are config-extensible gaps rather than runtime
  failures.
- No `testing-grounds` fixture or golden covers normalization; the only tests
  use inline snippets and direct pack construction.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `docs/workflow.md`.

## Round 2

Verdict: Round-1 findings 2-6 are addressed. Chain candidates now require an
explicit chain tail and an established inner step while skipping any matched
outer call, so `trim().isBlank()`, `trim().length`, `trim().substring(...)`, and
fully established chains no longer produce candidates. Exact configured names
now require qualifier/type confirmation unless declared in `instanceCalls`,
candidate formatting names no longer promote on a broad qualifier or type, and
prefix matches are candidate unless qualifier/type-confirmed. The composition
test now checks the guard scaffold and candidate isolation, and Kotlin/JS/C#
coverage is added for the changed paths. The loop cannot close yet: the pack is
still not service-registered, and a newly configured instance-style call in
`calls` is only a candidate unless the user also knows to add it to the separate
`instanceCalls` set.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`
   still omits `NormalizationRulePack`; the bundled registration tests and the
   integration host test still enumerate only the four pre-existing packs, and
   no `testing-grounds` fixture was added.**

   This is the unaddressed Round-1 finding 1. `NormalizationRulePack` is only
   constructed directly by its tests, so host, CLI, and live-daemon runs do not
   discover it, `NORMALIZATION` does not enter the intent vocabulary, and
   project config for `normalization` is reported as an unknown pack. The
   deterministic acceptance therefore remains test-local.

   Suggested fix: register the provider, add `normalization` to the bundled-id
   and intent assertions in `BundledRulePackRegistrationTest`,
   `BundledRulePackConfigMigrationTest`, and
   `BundledRulePackRegistrationHostTest`, and add a fixture that exercises the
   pack through `HostPipeline`.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/normalization/NormalizationRulePack.kt:120-145`
   with `NormalizationConfig.kt:7-15,108-145` and
   `NormalizationRulePackTest.kt:133-156` — a normalizer added through the
   documented `calls` config is not established when called on a receiver.**

   `calls` is the ticket's per-language normalizer-call config, but an exact
   name is only receiver-independent when it is also in `instanceCalls`. For
   `calls: [slugify]`, `value.slugify()` goes through `isQualified` and
   `acceptsReceiver`; `slugify` is not in the default `instanceCalls`, so the
   call becomes a candidate. The current configuration test only uses
   `audit(value)`, a receiverless call, so it does not expose this. This fails
   the acceptance that configured normalizers produce `NORMALIZATION` spans,
   and it makes the new `instanceCalls` key an undocumented requirement for
   ordinary instance helpers.

   Suggested fix: either treat explicitly configured `calls` as
   instance-eligible by default and keep a separate qualified/static-only list
   (the inverse of the current split), or document `instanceCalls` as part of
   the public config and add a test for `value.slugify()`. Keep the existing
   `other.normalize()` candidate behavior for default qualified-style names.

### Concurs / Dissents

- Round-1 finding 2: concurs. `chainCandidates` now requires the outer callee
  to be in `chainCalls`, skips calls that are themselves matched or used as
  receivers, and requires an `ESTABLISHED` inner step. The new
  `nonTransformChainTailsStayCandidateFree` and
  `fullyEstablishedChainsStayCandidateFree` cases pin the validation and
  established-chain overlaps.
- Round-1 finding 3: concurs. Exact names outside `instanceCalls` now go
  through `isQualified`/`acceptsReceiver`, and the test now uses a lowercase
  `other.normalize(value)` to prove qualifier gating. The `instanceCalls` split
  is a reasonable semantic distinction, subject to finding 2's config
  ergonomics.
- Round-1 finding 4: concurs. `candidateCalls`/`candidatePrefixes` now return
  `CANDIDATE` before any qualifier/type promotion, so `String.format` and
  `JSON.stringify` stay candidates, and `format` on a configured date type is a
  candidate. The added Kotlin/JavaScript cases pin this.
- Round-1 finding 5: concurs. Prefix matches now establish only with a
  qualifier/type match; `parseDomainEvent`, `normalizeLayout`, and
  `canonicalizeForDisplay` stay candidates, and C# `TryParse` is a candidate.
  The added Kotlin and C# cases cover the broad-prefix direction.
- Round-1 finding 6: concurs. The composition test now asserts no normalization
  candidate over the guarded expression, no `isBlank` normalization span, and
  the guard scaffold's `VALIDATION` intent; the logging/bookkeeping test also
  asserts candidate isolation.
- Concurs: chain, qualifier, display-formatting, and prefix behavior use the
  same code path for Kotlin, JavaScript, TypeScript, and C#; the per-language
  `instanceCalls`/`chainCalls` defaults are present for all four. The new
  chain-specific tests are Kotlin-only and the new C#/JavaScript tests cover
  prefix/display cases, so cross-language chain behavior is reasoned from the
  shared matcher rather than independently pinned.
- Concurs: candidate isolation is restored. A chain candidate is only emitted
  for an unmatched outer call, direct and chain candidates are deduplicated, and
  candidates remain outside established emits, composition, and the `reads`
  graph.
- Dissents: findings 1 and 2. The detection-semantics fixes for the reviewed
  overlaps are correct; the remaining registration gap and configured-instance
  call behavior prevent the loop from closing.

### Residuals (not findings)

- `chainCandidates` requires an `ESTABLISHED` inner step, so a multi-step chain
  whose first step is itself only a candidate (for example
  `value.format(...).replace(...)`) gets no chain candidate; the inner candidate
  is still published.
- Unit-conversion defaults and chained date-formatter constants such as
  `DateTimeFormatter.ISO_DATE.format(...)` remain uncovered, as in Round 1.
- No `testing-grounds` fixture or golden exercises normalization through the
  host pipeline.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `docs/workflow.md`.

## Round 3

Verdict: Round-2 finding 2 is fixed, and the detection semantics now match the
intended boundaries. Exact configured calls are instance-eligible by default,
`qualifiedCalls` is the explicit exception that requires qualifier/type
confirmation, `normalize` is an instance normalizer in Kotlin, JavaScript,
TypeScript, and C#, and the display/prefix behavior remains conservative.
Registration is still absent from the service file, but that is the only
deferred production-wiring item and is consistent with an integration step;
there are no new detection findings, and the detection loop can close with
registration owned by integration.

### Findings

- None.

### Concurs / Dissents

- Round-2 finding 2: concurs. `isQualified` now returns true only for names in
  `qualifiedCalls` (`NormalizationRulePack.kt:133-136`), so a name added through
  `calls` is established by default. The new
  `configuredInstanceCallsEstablishWithoutAnExtraList` test covers
  `value.slugify()`, and the qualifier test now uses an explicitly qualified
  `fromString` for the `Normalizers`/`other` split.
- Concurs: standard instance normalizers are established across languages.
  `normalize` is in `instanceCalls` for Kotlin, JavaScript, and C# (TypeScript
  shares the JavaScript defaults), and
  `standardInstanceNormalizersEstablishAcrossLanguages` covers all four plus
  Kotlin `path.normalize()` with and without an oracle.
- Concurs: qualifier gating still behaves as intended.
  `Normalizers.fromString(value)` is established while `other.fromString(value)`
  remains a candidate, and `qualifiedCalls` gives projects an explicit way to
  require a qualifier/type for static-style names without making every configured
  instance call a candidate.
- Concurs: display formatting and candidate isolation are unchanged from Round
  2. `candidateCalls`/`candidatePrefixes` return `CANDIDATE` before any
  qualifier/type promotion, so `String.format`, `JSON.stringify`, and
  configured-type `format` stay candidates; chain candidates still require a
  `chainCalls` tail and an established inner step and skip matched outers.
- Concurs: broad prefixes remain candidate unless qualifier/type-confirmed, with
  the Kotlin `parseDomainEvent`/`normalizeLayout`/`canonicalizeForDisplay` and C#
  `TryParse` cases still pinned.
- Round-2 finding 1 / registration: the service file still contains only the
  four pre-existing packs, so the pack is not discoverable in host/CLI/daemon
  runs. That is accepted here as an integration-only deferral; no partial
  registration or contradictory wiring was added. Unlike the `data-access`
  scaffold, this branch has no manual-test/integration row naming
  `dev.cint.rulepacks.intents.normalization.NormalizationRulePack`, so the
  integration step should record and own that line before the overall bundle is
  considered shipped.
- Dissents: none. The detection loop can close; registration is the only
  follow-up and belongs to integration rather than another detection round.

### Residuals (not findings)

- The integration step must add the provider line, update the bundled-id/intent
  assertions, and add a host/fixture exercise; none of that is present on this
  branch by design.
- `chainCandidates` still requires an `ESTABLISHED` inner step, so a chain whose
  first step is only a candidate gets no outer chain candidate; the inner
  candidate is still published.
- Unit-conversion defaults, chained date-formatter constants, and a
  `testing-grounds` normalization fixture remain uncovered.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `docs/workflow.md`.
