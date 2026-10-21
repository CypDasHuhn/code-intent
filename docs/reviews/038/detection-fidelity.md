# Detection-fidelity review — ticket 038 (SEARCH and LOOKUP)

## Round 1

Verdict: the directional split is right — criteria-based repository/query calls
become `SEARCH`, known-key map/table resolution becomes `LOOKUP`, and ambiguous
`get`/`find`/`fetch*`/`load*` calls stay candidate-only — but the pack is not
ready to close. Established `LOOKUP` is emitted for map writes, enum resolution
falls through to a candidate, the name/type defaults are simultaneously too
broad and too narrow, and there is no fixture or bundled-discovery proof.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:169-184`**
   — every index node whose receiver looks like a known map is emitted as
   established `LOOKUP` without checking whether it is a read or a write.
   `knownMapIndexes` scans all index expressions and calls `Emit.of(...)`
   directly; it never consults `view.writes()` or an assignment ancestor. Thus
   `statusById[id] = status` and `config["mode"] = "x"` become established
   `LOOKUP`, even though they are mutations. This is a false positive and
   contradicts the overlap policy in `docs/data-access-intents.md:60-63` that
   the persistence boundary wins and this pack only classifies read-shaped data
   access. It also places `LOOKUP` on the same span that ticket 040 will need to
   classify as `DATA_MODIFY`.
   Fix: exclude index nodes contained in `view.writes()` or in assignment,
   compound-assignment, increment/decrement, and similar write contexts (or
   require a read operand checker), and add negative tests for assignment,
   compound assignment, and `++` on a known map.

2. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessConfig.kt:83-120` with `DataAccessRulePack.kt:136-151`**
   — enum-like single-value resolution is not established, while `valueOf` is
   simultaneously treated as a strong lookup name. `Status.valueOf("ACTIVE")`
   and `Status.fromCode("X")` have a non-null receiver that is neither a known
   map nor a lookup qualifier; `lookupTypes` only matches an actual type named
   `Enum`, not a concrete enum type, and with the shared `NullOracle` it matches
   nothing. Both calls therefore fall through to `containsName(lookupCalls, ...)`
   and become `LOOKUP` candidates only, not the established `LOOKUP` the
   acceptance criteria require for enum/constant resolution. In the other
   direction, `String.valueOf(x)` is a `LOOKUP` candidate because `valueOf` is
   in `lookupCalls`, although that is a normalization/coercion operation and
   belongs to ticket 037's `NORMALIZATION` boundary.
   Fix: add an enum-specific path (enum value/constant call names plus enum
   receiver types/suffixes, or configured enum types) that can establish
   `LOOKUP` for `Status.valueOf`/`Status.fromCode`; remove `valueOf` from the
   unconditional strong lookup set or gate it on an enum/known receiver, and add
   positive enum tests plus a `String.valueOf` negative or `NORMALIZATION`
   boundary test.

3. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:196-221` with `DataAccessConfig.kt:54-107,136`**
   — receiver matching is an unbounded case-insensitive `endsWith` on each
   dot-split segment, so ordinary identifiers collide with the default
   qualifier/suffix sets and become established output. Examples: `mutable[key]`
   matches the `table` qualifier and `Map` suffix, `bitmap[i]` matches `Map`,
   `predict.getValue(x)` matches `dict`, and `restore.filter { ... }` matches
   the `store` search qualifier. Separately, bare configured names are
   established without receiver evidence (`receiver == null` returns true in
   both `isEstablishedSearch` and `isEstablishedLookup`), so a top-level
   `lookup(id)`, `getValue()`, `valueOf()`, `from(...)`, or `select(...)` is
   established without a known repository, table, map, or query source. The
   camel-case convenience for `userRepository`/`statusById` is useful, but it
   needs a token boundary rather than arbitrary substring overlap, and bare
   strong calls need an explicit contract.
   Fix: match whole normalized tokens or suffixes that start on a camel/snake
   boundary, add negative tests for `mutable`, `bitmap`, `predict`, and
   `restore`, and either require receiver evidence for established calls or
   split bare calls into an explicitly documented strong-call key and align the
   tests/classification policy with it.

4. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessConfig.kt:141-164` with `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/intents/dataaccess/`**
   — deterministic coverage is not meaningfully per-language: every language
   receives the same Kotlin-oriented call/qualifier/type set and only
   `indexKinds` differs. Common retrieval shapes in the ticket's scope are
   missing, for example JS/TS Prisma `findMany`/`findUnique`, Kotlin JPA
   `getResultList`/`getSingleResult`, and C# EF `FirstOrDefault`/
   `SingleOrDefault`/`ToListAsync`; the tests contain one JavaScript search case,
   one C# search/lookup case, no TypeScript case, and no positive enum case.
   The type-based paths are also effectively untested because the shared
   `dataAccess` helper always uses `NullOracle`
   (`DataAccessTestSupport.kt:11-16`). That hides narrow type matches such as
   Spring `JpaRepository`/`CrudRepository` not matching the configured
   `Repository` type and Kotlin `MutableMap` not matching `Map`, both of which
   leave common repository/map calls as candidates only.
   Fix: add per-language default entries for the common retrieval idioms (or
   document the shared baseline as intentional and require configuration for
   them), add TypeScript and enum tests, and add oracle-backed tests for
   `searchTypes`/`lookupTypes` including Spring Data, Kotlin `MutableMap`, and
   same-simple-name types in different packages.

5. **`analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack:1-4` with `docs/data-access-intents.md:67-75` and the absent testing-grounds fixture**
   — the pack is intentionally not registered, and the diff adds no
   `testing-grounds` fixture; every test constructs `DataAccessRulePack`
   directly. Consequently no host, CLI, server, live-analysis, or bundled
   composition path can emit `SEARCH` or `LOOKUP`, and the ticket's "Fixtures,
   config, docs, and tests are added" acceptance item is only partly met. The
   document says to add the service line "when wiring it into bundled
   discovery" but names no follow-up ticket.
   Fix: either register the pack and add a bundled/host composition test plus a
   testing-grounds fixture covering both intents and the ambiguous/write
   negatives, or record the integration work under a named ticket and manual
   check so it is an explicit deferral rather than an unwired implementation.

### Concurs / Dissents

- Concurs: ownership is correct — two rules, one owned intent each, empty
  `reads`, distinct ids/sources, and no accidental claim on
  `SERVICE_DELEGATION`, `DATA_MODIFY`, `NORMALIZATION`, or other packs'
  intents.
- Concurs: the `findById` policy is explicit and matches the ticket: established
  `SEARCH` with a same-span `LOOKUP` `PACK_DOMAIN` candidate; known-map `get`
  correctly establishes `LOOKUP` with a same-span `SEARCH` candidate.
- Concurs: ambiguous `get`/`find`/`fetch*`/`load*`/`read*`/`resolve*` calls
  produce candidate-only output on both sides, and unknown index access stays
  unestablished. This is the right conservative use of the 036 candidate
  channel.
- Concurs: the config schema, language-keyed overrides, replacement/additive
  decoding, case-insensitive matching, and four-language `indexKinds` are
  structurally sound for the tested paths.
- Concurs with the correctness reviewer's findings on suffix matching, bare
  calls, type specificity, and missing registration; findings 1-4 above add the
  detection-specific write/asymmetry and per-language/type-coverage angles.
- Dissents: findings 1-5. The current pack is not ready to close while map
  writes receive established `LOOKUP`, enum resolution is candidate-only,
  common language idioms fall through, and there is no fixture or discovery
  path.

### Residuals (not filed)

- `DataAccessConfig.forLanguage` falls back to Kotlin defaults for an
  unsupported language when the pack is constructed directly; production
  language selection masks this today.
- `knownMapIndexes` scans the whole file root and can emit nested `LOOKUP` spans
  for chained indexing such as `cellMap[row][col]`; this is redundant noise
  rather than a boundary error.
- Static review only; no build, test, formatter, or source edit was run by this
  role, per `docs/workflow.md`. The handover is assumed green.

## Round 2

Verdict: the round-1 enum/NORMALIZATION split, bare-call tightening, documented
token-collision handling, JS/TS/JPA additions, and type-oracle coverage are
present and directly tested. Registration is now explicitly deferred in
`docs/data-access-intents.md` and `docs/manual-test.md` (m013), which is an
acceptable manual-gate disposition. One index-write defect remains: the new
write filter is statement-wide rather than target-specific, so a known-map
read on the right-hand side of an assignment is suppressed as though it were a
write. The loop cannot close until that is fixed.

### Findings

1. **`analysis-engine/core/rulepacks/src/main/kotlin/dev/cint/rulepacks/intents/dataaccess/DataAccessRulePack.kt:197-217`**
   — `knownMapIndexes` excludes every index node contained in any
   `view.writes()` node. `view.writes()` comes from
   `SemanticFacts.allWrites`, which records the whole assignment/update
   expression, not only its target. Consequently a read on the right-hand side
   is lost: in `copy = statusById[id]`, `copy += statusById[id]`, or
   `statusById[id].field = value`, the map index is inside the write node and
   never emits established `LOOKUP`. The same applies to an index used as a
   nested key on the left-hand side. This is a false negative and is broader
   than the documented rule in `docs/data-access-intents.md:61-63`, which only
   excludes an index that is itself the target. The added
   `knownMapWritesAreNotLookup` test covers the direct left-hand-side cases
   (`DataAccessLookupTest.kt:230-244`) but not these reads.
   Fix: determine the write target span (for example from `WriteFact.reference`
   when available, or by comparing against the assignment's left operand) and
   exclude only index nodes that are the assigned target. Add tests for
   `copy = statusById[id]`, `copy += statusById[id]`,
   `statusById[id].field = value`, and a nested index read used as a key; keep
   the existing left-hand-side write cases unestablished.

### Concurs / Dissents

- Concurs: round-1 finding 2 is addressed. `valueOf`/`fromCode` moved out of
  `lookupCalls` into `enumCalls`, enum-like receivers establish `LOOKUP`, and
  `String.valueOf` produces neither an established annotation nor a candidate
  (`DataAccessLookupTest.kt:149-178`). The SEARCH/LOOKUP boundary remains
  conservative for ambiguous `get`/`find` wrappers.
- Concurs: round-1 finding 3 is addressed for the reported collisions. Token
  matching stops `mutable`, `bitmap`, `predict`, and `restore` from becoming
  established, and bare configured calls now produce candidates only
  (`DataAccessNegativeTest.kt`).
- Concurs: round-1 finding 4 is substantially addressed. Prisma `findMany`/
  `findUnique`, JPA result calls, and C# EF calls are covered; JavaScript,
  TypeScript, and C# index paths have tests; and `DataAccessTypeTest` exercises
  the oracle-backed `JpaRepository`/`MutableMap` and fully-qualified-type
  cases.
- Concurs: round-1 finding 5 is addressed as a deferral. The pack remains
  unregistered, but the limitation is explicit in the data-access document and
  in manual check m013, so it is no longer a hidden integration gap.
- Dissents: the new finding 1. The direct-write fix is correct, but extending
  it to every index contained in a write expression creates a read false
  negative and does not match the documented target-only policy.

### Residuals (not filed)

- Enum recognition by uppercase receiver-name suffix (`Status`, `Mode`, `Type`,
  `Code`, ...) can still establish a non-enum class with a static `valueOf`
  method; it is a default heuristic, not type-certified enum evidence.
  `fromString` also remains a normalization-shaped name, so its co-occurrence
  with ticket 037's defaults should be confirmed when both packs are wired.
- Single-token qualifier matching still matches any token in a receiver, so a
  name such as `mapData` or `configData` can match `map`/`config` without being
  a suffix; fully-qualified type matching also still uses a package suffix
  (`other.com.acme.Dao` would match `com.acme.Dao`). These are narrower
  overmatching remnants, not the reported collisions.
- No testing-grounds fixture was added; the manual m013 entry now carries the
  registration/composition deferral and the direct pack tests cover the
  classification shapes.
- Static review only; no build, test, formatter, or source edit was run in this
  round. The handover is assumed green.

## Round 3

Verdict: the round-2 index-write defect is fixed. `writeTarget` now limits the
write boundary to the assignment left operand and increment/decrement target,
so `copy = statusById[id]`, `copy += statusByCode[active]`,
`codeMap[id].field = value`, and nested
`translationTable[statusById[id]] = value` retain the read `LOOKUP`, while
direct target indexes remain excluded; `mixedWriteTargetExcludesOnlyAssignedIndex`
pins the left/right split. The enum/NORMALIZATION boundary also holds:
`valueOf`/`fromCode` are separated from `lookupCalls`, `String` is excluded from
the enum-candidate fallback, non-suffix enum-style receivers such as `Color` and
`Role` become candidates, and configured enum types establish. The reported
false-positive collisions and bare-call behavior remain fixed, the JS/TS/JPA and
type-oracle coverage is in place, and m013 remains an explicit manual-gate
deferral for registration/composition. No blocking findings remain; the
detection loop can close, with integration still unverified by design under
m013.

### Findings

None.

### Concurs / Dissents

- Concurs: the RHS-read handling is now target-specific and matches the
  documented policy; the new write tests cover direct writes, RHS reads,
  nested reads, and mixed target/source assignments.
- Concurs: `valueOf`/`fromCode` no longer establish `String.valueOf`, known
  enum-like receivers still establish, and unknown uppercase enum-style
  receivers are candidate-only until configured.
- Concurs: tokenized receiver matching and bare-call candidate handling close
  the reported `mutable`/`bitmap`/`predict`/`restore` false positives without
  losing the configured repository/search and known-map paths.
- Concurs: the JS/TS index and Prisma tests, JPA/C# retrieval tests, and
  oracle-backed `JpaRepository`/`MutableMap`/fully-qualified-type tests cover
  the requested language boundaries.
- Concurs: m013 is still the correct disposition for unregistered host
  integration; the pack is explicitly documented as deferred rather than
  silently missing.
- Dissents: none.

### Residuals (not filed)

- `enumExclusions` is applied to the candidate fallback but not to the
  established suffix path, so an unusual suffix-named non-enum such as
  `StringStatus` could still establish from its name. Date/path-style static
  factories such as `Path.valueOf` also remain candidate-only enum-style
  fallbacks because they are not in the exclusion list. These are heuristic
  edges, not the reported `String.valueOf` boundary.
- `fromString` remains both an enum-call default here and a normalization-shaped
  name; if both packs are wired, that span may legitimately carry both intents.
- Single-token qualifier matching still accepts any receiver token, and
  fully-qualified type matching still accepts package suffixes such as
  `com.acme.Dao` inside `other.com.acme.Dao`; these are narrower overmatching
  remnants.
- No testing-grounds fixture was added; m013 carries the explicit
  registration/composition deferral and the direct tests cover the
  classification shapes.
- Static review only; no build, test, formatter, or source edit was run in this
  round. The handover is assumed green.
