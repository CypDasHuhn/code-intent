# Correctness review — ticket 038 (SEARCH and LOOKUP)

## Round 1

Verdict: the two-rule ownership, per-language config, additive overlay path, and
the explicit `findById` policy are sound. The pack is not ready to close because
name matching is deliberately loose enough to establish false positives, bare
lookup calls are established without any known-key evidence, fully-qualified
type configuration is not actually specific, and the pack is only exercised in
isolation rather than through bundled discovery/composition.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:196-214` with `DataAccessConfig.kt:94-107,136`**
   — qualifier and known-map suffix matching is an unbounded `endsWith` on each
   dot-split segment, so ordinary identifiers collide with the defaults:
   `mutable[key]` matches the `table` lookup qualifier and the `Map` suffix,
   `bitmap[i]` matches `Map`, and `restore.filter { }` matches the `store`
   search qualifier. Those become established `LOOKUP`/`SEARCH` annotations
   even though neither the receiver nor its type identifies a table or query
   source. The camel-case convenience for `userRepository`/`statusById` is
   useful, but it needs a token boundary rather than arbitrary suffix overlap.
   Fix: tokenize identifiers on camel/snake boundaries and match whole
   normalized tokens, or restrict suffix matching to explicitly configured
   suffixes that begin at a token boundary. Add negative tests for `mutable`,
   `bitmap`, and `restore`.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:115-127,136-150` with `docs/data-access-intents.md` classification policy**
   — an unqualified configured search or lookup call is established without any
   receiver evidence (`receiver == null` returns true in both paths). That makes
   `lookup(id)`, `getValue()`, `valueOf()`, and bare `from`/`select` calls
   established `LOOKUP`/`SEARCH` regardless of whether a known table, map, enum,
   repository, or query source is involved. This also contradicts the document's
   statement that configured lookup calls are established only on a known
   receiver/type, and the test `knownMapAndConfiguredLookupCallsEmitLookup`
   currently codifies the broad behavior with `lookup(id)`. Fix: require a known
   receiver/qualifier/type for established calls, emitting candidates when the
   receiver is absent or unresolved; if bare calls are intentional, split them
   into an explicitly documented strong-call config key and align the docs and
   tests with that contract.

3. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:229-240`**
   — `typeMatches` always falls back to comparing the actual simple name with the
   configured expected value's simple name. A configured fully-qualified type
   such as `com.acme.Dao` therefore also matches `other.Dao`; the configuration
   cannot express the specificity it appears to support. Fix: only use the
   simple-name fallback when the configured expected value contains no dot;
   otherwise require `actual == expected` or `actual.endsWith(".$expected")`.
   Add an oracle-backed test with two same-named types in different packages.

4. **`docs/data-access-intents.md:63-75` and `analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`**
   — the pack is intentionally not registered, and the tests instantiate it
   directly, so no host/CLI/server path can emit `SEARCH`/`LOOKUP` and the
   acceptance criteria are not exercised through bundled discovery or
   composition. The document names no follow-up ticket that owns wiring. Fix:
   either register the pack and add a bundled/host composition test, or record
   the integration work under a named ticket (and in the manual gate) so this
   is an explicit deferral rather than an unwired implementation.

5. **`analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/dataaccess/` and `analysis-engine/core/rulepacks/src/test/kotlin/dev/cint/rulepacks/DataAccessConfigTest.kt`**
   — coverage does not exercise several contract points: there is no
   JavaScript/TypeScript index or lookup case even though both default to
   `subscript_expression`; additive behavior is checked at the config-data level
   but never through `configureAdditive` on a rule; there are no negative cases
   for bare lookup calls or suffix collisions; and type matching is only tested
   with a null oracle. Fix: add direct rule cases for JS/TS index access, a
   `configureAdditive` rule case that proves an overlay retains `findAll` while
   adding a custom table, the false-positive negatives above, and an
   oracle-backed `searchTypes`/`lookupTypes` test.

### Concurs

- The pack owns exactly `SEARCH` and `LOOKUP` through two rules, with empty
  `reads`, distinct rule ids/sources, and no accidental ownership of other
  intents.
- Config is keyed by pack id and language, replacement and additive decoding are
  wired correctly, and string sets union under `configureAdditive`.
- `findById` is explicitly established `SEARCH` and receives a same-span
  `LOOKUP` `PACK_DOMAIN` boundary candidate; known-map `get` is the inverse with
  a same-span `SEARCH` candidate. The tested spans for those boundaries align.
- Ambiguous `get`/`find`/`fetch*`/`load*` calls produce candidates only, and
  unknown index access remains unestablished.
- Case-insensitive matching makes the shared defaults usable for Kotlin,
  JavaScript, TypeScript, and C#; no span defect was found in the tested call
  and index paths.

### Dissents

- Dissents from establishing bare configured lookup calls without known-key
  evidence, and from the docs/code disagreement about that behavior.
- Dissents from arbitrary `endsWith` qualifier/suffix matching as the default
  source of established output.
- Dissents from considering the ticket complete while the pack has no
  registration or named integration owner and its tests bypass the production
  discovery path.

## Round 2

Verdict: findings 1, 2, 3, and 5 are addressed. Tokenized matching now respects
camel/snake boundaries (`mutable`, `bitmap`, `predict`, and `restore` no longer
collide); established calls require receiver/qualifier or type evidence; bare
configured calls are candidates only; fully-qualified types no longer match
same-named types in other packages; and the new cross-language, additive,
negative, enum, and oracle-backed tests cover the reported gaps. Finding 4 is
addressed as an explicit deferral: the pack remains intentionally unregistered,
and manual test m013 names ticket 038 and the exact registration line. The loop
cannot close yet because the new index-write exclusion also suppresses reads on
the right-hand side of a write, and default enum recognition misses common
enum names that are not in the suffix set.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:198-214`**
   — `knownMapIndexes` excludes an index expression whenever any write node
   contains it. `view.writes()` returns the whole assignment/increment node, so
   `x = statusById[id]`, `x += statusById[id]`, and
   `statusById[id] = otherMap[id]` lose the `LOOKUP` for the RHS read (and the
   mixed case loses both reads) even though only the left-hand index is the
   write target. This contradicts the new documentation, which excludes only
   index access that is the target of a write. Fix: exclude an index only when
   it lies within the write target (the assignment's left operand or the
   increment/decrement target), not anywhere inside the write node; add RHS-read
   and mixed LHS/RHS tests.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:158-181` with `DataAccessConfig.kt:134-136`**
   — enum recognition is limited to configured `enumTypes`, an oracle type whose
   simple name ends in the fixed suffix set, or an uppercase receiver name ending
   in that set. Common enum types such as `Color`, `Role`, `Currency`, or
   `Priority` therefore produce neither established `LOOKUP` nor a candidate for
   `Color.valueOf`/`Role.fromCode`; this is a regression from the previous
   candidate fallback for `valueOf`. Fix: broaden the default enum-name/type
   vocabulary or use oracle type information to identify enum receivers, and
   retain a candidate fallback for unclassified enum-style calls while keeping
   the `String.valueOf` negative. Add tests for a non-suffix enum such as
   `Color.valueOf`, plus a configured `enumTypes` case.

### Concurs

- Tokenized qualifier/suffix matching and the new negative cases are correct.
- Receiver/qualifier evidence now gates established search and lookup output,
  and bare configured calls are candidate-only as documented.
- Fully-qualified type configuration is specific; simple names still use token
  suffix matching.
- Known-map writes are excluded on the tested LHS/increment paths, and
  `String.valueOf` stays out of `LOOKUP`.
- JavaScript/TypeScript index and lookup coverage, the rule-level additive
  overlay, and the `MapOracle` type tests adequately exercise the prior gaps.
- The registration deferral is now explicit in `docs/data-access-intents.md`
  and manual test m013; it must be completed before the ticket is marked done.

### Dissents

- Dissents from suppressing RHS index reads while trying to exclude index
  writes.
- Dissents from the suffix-only default enum vocabulary and the loss of the
  previous candidate fallback for common enum-like receivers.

## Round 3

Verdict: the previous findings remain addressed. Target-specific index-write
exclusion now preserves RHS and nested reads (`copy = statusById[id]`,
`statusById[id] = otherMap[id]`, `translationTable[statusById[id]] = value`),
and the enum fallback now makes non-suffix enum-like receivers
(`Color.valueOf`, `Role.fromCode`) candidates while keeping `String.valueOf` out
and allowing configured `enumTypes` to establish lookup. One narrow write-target
bug remains, so the loop cannot close yet.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:234-238`**
   — increment/decrement targets are selected by rejecting any child whose text
   contains `+` or `-`. If the indexed target itself contains either character,
   the write target is missed and the index is emitted as a read `LOOKUP`. For
   example `counts[i - 1]++`, `counts[i + 1]--`, or
   `statusById["x-y"]++` still emits `LOOKUP` on the assigned index, contrary to
   the documented rule that an index write target emits no lookup. Fix: identify
   the increment/decrement operand by node/operator structure rather than
   scanning operand text for `+`/`-` (for example, skip only the exact `++`/`--`
   operator child), and add tests with arithmetic or hyphenated keys in the
   increment target.

### Concurs

- Assignment and compound-assignment target exclusion is span-specific; RHS
  reads, nested reads inside a target, and mixed LHS/RHS writes behave as
  documented and are covered by tests.
- Enum `valueOf`/`fromCode` fallback works for uppercase non-excluded receivers,
  configured `enumTypes` establish, and `String`/numeric wrapper exclusions keep
  `String.valueOf` out.
- Tokenized matching, receiver/qualifier evidence, fully-qualified type
  specificity, cross-language/additive/negative tests, and the registration
  deferral under m013 all remain intact.
- Existing data-access test results show zero failures, including the new
  write, nested-read, and enum cases.

### Dissents

- Dissents from using operand text containing `+`/`-` as the signal for an
  increment target; it conflates the operator with characters inside a valid
  index expression.
