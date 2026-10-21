# Data-access intents — SEARCH and LOOKUP

The `data-access` pack owns two rules:

| rule | intent | meaning |
|---|---|---|
| `data-access:search` | `SEARCH` | retrieve data by criteria from a source that may contain many records |
| `data-access:lookup` | `LOOKUP` | resolve one known value by key/constant from a static or effectively static table/map/enum/config |

Both rules are source-only (`reads` is empty). They do not own
`SERVICE_DELEGATION`, `DATA_MODIFY`, or any other pack's intent.

## Configuration

Configuration is per language under the `data-access` pack id. Every value is a
set of strings and additive overlays union with the defaults.

- `searchCalls`, `searchPrefixes`: repository/query methods and method prefixes.
- `searchQualifiers`, `searchTypes`: receiver names and oracle-resolved types
  that make a configured call a search call.
- `lookupCalls`: strong key/value calls (`getValue`, `getOrDefault`, `lookup`, ...).
- `lookupQualifiers`, `lookupTypes`: receiver names/types treated as dictionaries,
  maps, configs, tables, or lookup registries.
- `enumCalls`, `enumTypes`, `enumTypeSuffixes`, `enumExclusions`: enum/constant
  single-value resolution (`Status.valueOf`, `Mode.fromCode`), explicit enum
  types, and non-enum type names such as `String` that must not fall back to an
  enum candidate.
- `knownMaps`, `knownMapSuffixes`: names/suffixes of static tables used for
  known-key resolution.
- `ambiguousCalls`, `ambiguousPrefixes`: calls such as `get`, `find`, and
  `fetch*` wrappers that produce candidates instead of established output.
- `boundaryPrefixes`: `findBy*`-style prefixes that also receive a LOOKUP
  boundary candidate.
- `indexKinds`: language node kinds for index/subscript/element access.

Method and qualifier matching is case-insensitive, so the same defaults serve
Kotlin/JavaScript/TypeScript camelCase and C# PascalCase. Identifier matching is
token-based: camel/snake boundaries form tokens, so `userRepository` matches the
`repository` qualifier and `statusById` matches the `ById` suffix, while
`mutable`, `bitmap`, `predict`, and `restore` do not collide with `table`, `Map`,
`dict`, or `store`.

## Classification policy

- A configured search call or `findBy*`/`search*`/`query*` prefix emits
  established `SEARCH` only on a configured search receiver/type. Bare or
  unresolved calls become candidates.
- Index access on a known map/table or lookup qualifier, and configured lookup
  calls on a known receiver/type, emit established `LOOKUP`. Bare lookup calls
  are candidates, not established.
- Enum/constant calls (`valueOf`, `fromCode`, `fromValue`, ...) establish
  `LOOKUP` for configured enum types and enum-like receivers/types recognized by
  the suffix vocabulary. Other uppercase, non-excluded enum-style receivers
  (`Color.valueOf`, `Role.fromCode`) become `LOOKUP` candidates until configured.
  `String.valueOf` stays out of this pack and belongs to the `NORMALIZATION`
  boundary.
- `findById`-style calls are explicitly `SEARCH`: they retrieve by criteria from
  a repository or other multi-record source that may return empty. The lookup
  rule emits a `LOOKUP` candidate on the same span as a reviewable boundary
  alternative.
- Generic `get`, `find`, `fetch*`, `load*`, and similar wrappers are not
  established. Both rules emit `PACK_DOMAIN` candidates so review/AI can choose,
  and the established output stays conservative.
- A known-map `get` is established `LOOKUP` because the key identifies one known
  value; the search rule still adds a `SEARCH` candidate for the boundary.
- Index access that is the assigned target of an assignment, compound
  assignment, or increment/decrement emits no `LOOKUP`; that write boundary
  belongs to the mutation pack. Reads on the right-hand side
  (`copy = statusById[id]`) and nested reads inside a target
  (`codeMap[id].field = value`, `translationTable[statusById[id]] = value`) still
  emit `LOOKUP`.
- Unknown index access is not LOOKUP.

Known fidelity gap: structural increment/decrement targets such as
`counts[i - 1]++` and `statusById["x-y"]++` are still missed by the text-based
target filter. Ticket 042 tracks the syntax-based target fix.

## Overlap policy

Intents are a set, not a mutually exclusive enum, so SEARCH and LOOKUP may
legitimately co-occur on different spans in the same function or call chain.

- `SERVICE_DELEGATION` describes responsibility transfer, not the delegated
  call's business meaning. The service pack may tag a thin wrapper while the
  inner repository/search call remains `SEARCH` or `LOOKUP`.
- `DATA_MODIFY` describes persistence. A persistence write is not silently
  re-labelled as search/lookup; the persistence boundary wins for writes. This
  pack only classifies read-shaped data access and does not model calls that both
  query and mutate.
- The data-access rules are independent and composable; neither suppresses the
  other or the service/mutation packs.

## Registration and host coverage

The pack is registered in
`analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`
as `dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack`. Bundled discovery
and host composition are covered by `BundledRulePackRegistrationTest`,
`BundledRulePackLoadingTest`, and `BundledRulePackRegistrationHostTest`, which
assert both `SEARCH` and `LOOKUP` per bundled language. Classification itself is
covered by the direct pack tests in `rulepacks-tests`. The current pipeline
snapshot contains no data-access spans because its fixtures do not include
search/lookup examples; the manual gate records this coverage in m013.
