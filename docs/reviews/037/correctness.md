# Correctness review — ticket 037 (normalization intent)

## Round 1

Verdict: The pack is structurally sound: it uses the shared config schema and
defaults, owns only `NORMALIZATION`, keeps established emits and candidates on
the correct channels, and its direct pack tests cover the four languages and the
main established/candidate split. It is not ready for closure: the chain
candidate rule fires on fully established chains and on non-transform tails
(including validation predicates), and one default "deterministic" call
(`Json.decodeFromString`) can only ever become a candidate because its receiver
is not in the Kotlin qualifier/type defaults. The direct tests do not pin those
negatives or the additive config path.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/normalization/NormalizationRulePack.kt:58-91`
   (pinned by `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/normalization/NormalizationRulePackTest.kt:230-249,280-290`) — chain candidates are emitted for any receiver chain that contains any matched step, including fully established chains and arbitrary outer calls.**

   `chainMatches.none { it != null }` treats both `ESTABLISHED` and `CANDIDATE`
   steps as a reason to emit a candidate for the outermost call. Therefore
   `value.trim().uppercase()` produces a `PACK_DOMAIN` candidate on the exact
   span that is already established as `NORMALIZATION`; `value.trim().isBlank()`
   produces a candidate over a validation predicate; and
   `value.trim().substring(1)` produces a candidate over a call the
   `unrelatedCallsRemainUntagged` test says should stay untagged (the test only
   covers the non-chained form). This contradicts the candidate channel's
   review-only semantics and the composition goal that normalization does not
   drift into validation. The all-established chain test currently codifies the
   overlap instead of rejecting it, and the coexistence test never inspects
   candidates.

   Fix: only emit a chain candidate when the chain actually needs review — at
   minimum skip chains whose every step is already `ESTABLISHED`, and do not
   treat the presence of an inner configured call as sufficient for an arbitrary
   outer callee. Gate the unresolved tail on a configured candidate/transform
   pattern (or add an explicit chain-call config) so predicates, accessors, and
   other non-transform tails do not produce `NORMALIZATION` candidates. Update
   the all-established test to expect no candidate and add negative cases for
   `trim().isBlank()`, `trim().length`, and `trim().substring(...)`.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/normalization/NormalizationConfig.kt:69-142`
   with `NormalizationRulePack.kt:116-133` — the default established call
   `decodeFromString` cannot establish on its canonical receiver.**

   `decodeFromString` is in the Kotlin `calls` set, but the Kotlin `qualifiers`
   and `types` lists do not contain `Json` or
   `kotlinx.serialization.json.Json`. Since `Json` is a type-like receiver,
   `matchKind` takes the `unqualifiedStatic` branch and returns `CANDIDATE` for
   `Json.decodeFromString(...)` (with or without a type oracle). A configured
   deterministic call therefore never produces an established span in its main
   form, and the same trap applies to any exact configured name whose type-like
   receiver is omitted from `qualifiers`/`types`.

   Fix: add `Json` (or the fully-qualified type) to the Kotlin
   `qualifiers`/`types`, or move `decodeFromString` to `candidateCalls` if
   serialization should require review. Add a test that pins
   `Json.decodeFromString(...)` as established if that is the intended default.

3. **`analysis-engine/core/rulepacks/src/test/kotlin/dev/cint/rulepacks/NormalizationConfigTest.kt`
   and `NormalizationRulePackTest.kt` — the direct pack tests omit the negative
   and additive cases that protect the new contract.**

   The tests cover defaults, replace-only overrides, unknown keys/languages, and
   the per-language happy paths, but they do not cover: `configureAdditive`
   preserving defaults while adding values (the overlay path new packs are
   expected to support); a default exact call on a type-like receiver
   (`Json.decodeFromString`); no candidate for a fully established chain; and no
   candidate for chained validation predicates or unrelated tails. In addition,
   `configuredPackUsesOnlyTheConfiguredCalls` overrides only `calls`, leaving
   the default `prefixes` and `candidateCalls` in place, so it does not actually
   prove isolation from all defaults.

   Fix: add an additive-config test that asserts existing Kotlin defaults survive
   and the new value is unioned; add the `Json.decodeFromString` case and the
   chain negative cases from findings 1-2; and clear the remaining default
   fields in the isolation test or rename it to state what it actually pins.

### Concurs / Dissents

- Concurs: config decoding uses the shared schema, keeps per-language defaults,
  replaces only named values, rejects unknown languages/keys/value types, keeps
  TypeScript aliased to the JavaScript defaults, and leaves the pack's language
  set intact after `configure`. The inherited additive path is the correct one,
  but it is unverified for this pack.
- Concurs: ownership is clean. One rule owns `NORMALIZATION`, `reads` is empty,
  established emits and candidates both carry only the owned intent, and the
  engine's ownership check is not bypassed.
- Concurs: established spans use `CallFact.site`, direct candidates use the same
  call site, per-language spans are produced, and type-based promotion of
  `format` is tested with a fixed oracle. The chain candidate span covering the
  whole chain rather than only the unresolved step is part of finding 1.
- Concurs: the direct pack tests correctly instantiate `NormalizationRulePack`
  and do not assume shared service registration. This report therefore does not
  require a service-file change; if host/live availability is expected from this
  ticket, that remains a separate named follow-up.
- Concurs: the type-like-receiver guard keeps `Other.normalize` a candidate
  rather than an established call, and ambiguous formatting helpers stay
  candidate-only without a configured type.
- Dissents: findings 1-3.

### Residual observations (not filed)

- The diff adds no `testing-grounds` fixture and no normalization-specific doc,
  although the ticket's acceptance says fixtures and docs are added. The inline
  snippets exercise the rule directly but not through `HostPipeline`.
- The pack is absent from `META-INF/services/dev.cint.api.RulePack`, so the host,
  CLI, and live daemon do not load it. The sibling 038-040 work takes the same
  direct-pack approach, so this may be intentional sequencing rather than a 037
  defect, but it should be owned by a named follow-up if `NORMALIZATION` is meant
  to be user-visible at the end of this batch.
- Prefix matching is case-sensitive and established prefixes on lowercase
  receivers are broad (`obj.parseDomainEvent()` is established). This is
  config-driven by design, but it is the main remaining false-positive surface.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`.

## Round 2

Verdict: The Round-1 findings are substantively addressed. Chain candidates now
require an unmatched, configured chain tail over an established inner step, so
fully established chains and validation/non-transform tails stay candidate-free;
lowercase-receiver qualifier gating works; `Json` is a Kotlin qualifier/type and
`Json.decodeFromString` is pinned as established; broad prefixes no longer
establish without a qualifier/type; formatting helpers are candidate-only by
default; and additive config preserves defaults. One cross-language false
negative remains in the new instance-method gating, so the loop cannot close yet.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/normalization/NormalizationConfig.kt:112-131,210-219,255-264`
   with `NormalizationRulePack.kt:120-147` and
   `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/normalization/NormalizationRulePackTest.kt` — the exact configured `normalize`/`Normalize` calls are missing from `instanceCalls`, so standard instance normalizers become candidates instead of established spans.**

   JavaScript `value.normalize()`, C# `value.Normalize()`, and Kotlin
   `path.normalize()` are standard instance normalizers and are exact entries in
   the `calls` sets, but none is in the corresponding `instanceCalls` set. With a
   variable receiver, `isQualified` is true and `acceptsReceiver` requires a
   qualifier or type match. JavaScript's `types` do not contain `String` and
   C#'s do not contain `string`, so even an oracle-backed string receiver stays a
   candidate; Kotlin's `Path.normalize()` is established only when the oracle
   reports `java.nio.file.Path`. The default test matrix uses `trim`/`uppercase`
   and `parse`, so this regression is not pinned. This still breaks the
   acceptance criterion that configured normalizers produce established spans
   per language for a core normalization operation.

   Fix: add `normalize`/`Normalize` to the Kotlin, JavaScript, and C#
   `instanceCalls` defaults (or, alternatively, add the string types to `types`
   and test the oracle path), then add cross-language tests asserting that
   `value.normalize()` (JS), `value.Normalize()` (C#), and `path.normalize()`
   (Kotlin, with and without an oracle) are established and not candidates.

### Concurs / Dissents

- Concurs with the chain fix: only an unmatched `chainCalls` tail over an
  inner `ESTABLISHED` step yields a candidate, so `trim().uppercase()` and
  `trim().isBlank()`/`trim().substring(...)` are clean while `trim().replace(...)`
  still yields the review candidate.
- Concurs with qualifier gating: configured names on lower-case receivers are
  now held to `qualifiers`/`types`, and the lowercase `other.normalize`
  counterexample is pinned. The `instanceCalls` bypass itself is sound; finding 1
  is about its default membership.
- Concurs with the `Json` fix: `Json` is present as a qualifier and
  `kotlinx.serialization.json.Json` as a type, and `Json.decodeFromString` is
  asserted established in the Kotlin matrix.
- Concurs with the broad-prefix and formatting fixes: unqualified prefix matches
  are candidates, type-confirmed prefix matches can establish, and
  `String.format`/`JSON.stringify` remain candidates by default with opt-in
  possible through `calls` plus `instanceCalls`/qualifier or type.
- Concurs with additive config: `configureAdditive` unions the new call into the
  defaults while retaining `trim`; `instanceCalls`/`chainCalls` are string-set
  fields with the shared additive setter, although their individual additive
  behavior is not directly tested.
- Concurs with cross-language coverage for the main matrix (Kotlin, JavaScript,
  TypeScript, C#), formatting (Kotlin/JS), and broad prefixes (Kotlin/C#).
  Chain and additive cases remain Kotlin-only; TypeScript inherits the JavaScript
  config, but the new instance-call default needs the per-language tests in
  finding 1.
- Dissents: finding 1. The Round-1 dissent on findings 1-3 is withdrawn for
  findings 1-3 as implemented; Round-1 residuals about registration, fixtures,
  and normalization docs are unchanged.

### Residual observations (not filed)

- `instanceCalls` is a gating modifier, not an independent detection list:
  adding a custom name there alone does nothing unless it is also in `calls`.
  That two-list contract is undocumented and untested for custom configs.
- The pack still is not service-registered and still has no normalization
  fixture or dedicated doc; the direct-pack/no-shared-registration approach is
  unchanged, so this remains a follow-up outside this correctness thread.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`.

The correctness loop can close once finding 1 is fixed and its cross-language
tests are present; no other correctness dissent remains.

## Round 3

Verdict: The Round-2 finding is addressed. Gating now follows the explicit
`qualifiedCalls` list, and exact configured calls outside it establish as
instance/global calls; `normalize`/`Normalize` are in the Kotlin, JavaScript, and
C# `instanceCalls` defaults, and the new test matrix covers standard instance
normalizers in all four languages, Kotlin `path.normalize()` with and without an
oracle, and a custom configured instance call without any extra list. The
previous chain, qualifier, `Json`, broad-prefix, formatting, and additive-config
findings remain fixed. No new correctness finding remains, so the correctness
loop can close.

### Findings

- None.

### Concurs / Dissents

- Concurs with the instance-normalizer fix: Kotlin, JavaScript, TypeScript, and
  C# `normalize` calls now establish, and the Kotlin path case no longer depends
  on the oracle because exact non-qualified calls are treated as instance/global.
- Concurs with the `calls`/`instanceCalls`/`qualifiedCalls` split: static names
  (`parse*`, `fromString`, `decodeFromString`, C# `Parse*`/`Get*`/`To*`) are
  gated by `qualifiedCalls`, while instance and custom configured calls establish
  without a second list. The defaults have no name in both lists, so the instance
  override is unambiguous, and the lowercase-receiver qualifier counterexample
  now uses a qualified `fromString` call.
- Concurs that all prior findings are addressed: chain candidates are limited to
  configured chain tails over established inner steps; fully established and
  non-transform tails stay candidate-free; `Json.decodeFromString` is
  established; formatting remains candidate-only by default; broad prefixes
  require a qualifier/type to establish; and `configureAdditive` preserves
  defaults.
- Concurs with cross-language test coverage for the fixed paths, including
  TypeScript inheriting the JavaScript config and C# `Normalize`/`TryParse`
  behavior. `instanceCalls` and `chainCalls` remain string-set config fields with
  the shared additive setter.
- Dissents: none.

### Residual observations (not filed)

- `instanceCalls` is now effectively an override for names also present in
  `qualifiedCalls`; for the defaults it does not change behavior because all
  instance names are absent from `qualifiedCalls`. Kotlin `canonicalize` and C#
  `Canonicalize` are exact `calls` entries in neither list and rely on the
  fallback that any non-qualified call establishes. This is behaviorally
  consistent today but worth documenting or simplifying in a cleanup follow-up.
- The pack still has no service registration, normalization fixture, or dedicated
  doc; the direct-pack/no-shared-registration approach is unchanged, so these
  remain follow-ups outside this correctness thread.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`.
