# Data-access intents — SEARCH and LOOKUP

The `data-access` pack owns two rules:

| rule | intent | meaning |
|---|---|---|
| `data-access:search` | `SEARCH` | retrieve data by criteria where the result may be absent or span many records |
| `data-access:lookup` | `LOOKUP` | resolve one known value by key/constant from a static or effectively static table/map/enum/config/registry |

Both rules are source-only (`reads` is empty). They do not own
`SERVICE_DELEGATION`, `DATA_MODIFY`, or any other pack's intent.

## SEARCH vs LOOKUP contract

`LOOKUP` retrieves a value known to exist from a static or effectively static
source: a map/table key, enum/constant, config entry, or registry. `SEARCH`
queries a collection or repository by criteria where the result may be absent
(empty, null, or many records); a prior validation/guard does not change the
data kind or turn a search into a lookup.

## Configuration

Configuration is per language under the `data-access` pack id. Every value is a
set of strings and additive overlays union with the defaults.

- `searchCalls`, `searchPrefixes`: repository/query methods and method prefixes.
  Defaults include `findBy`, `getBy`, `loadBy`, `findAllBy`, `search`, and
  `query`, so repository criteria calls establish SEARCH rather than ambiguity.
  A prefix matches only at a token boundary: the callee must end at the prefix
  or the remaining suffix must start an uppercase letter, a digit, or a
  separator. The prefix must also cover whole camel/snake tokens. Thus `getBy`
  and `loadBy` match `getById`/`loadById` but not `getBytes`/`loadBytes`, and
  `search` does not match `searching`.
- `searchQualifiers`, `searchTypes`: receiver names and oracle-resolved types
  that make a configured call a search call.
- `lookupCalls`: strong key/value calls (`getValue`, `getOrDefault`, `lookup`, ...).
  An explicit lookup call takes precedence over search-prefix matching, so
  `getByKey` never establishes or candidates SEARCH. To give such a call search
  meaning, add it explicitly to `searchCalls` as well.
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
- `compoundMutationCalls`: known read-shaped mutation calls that must not produce
  SEARCH/LOOKUP ambiguity. Durable defaults (`findOneAndUpdate`,
  `findOneAndDelete`, `findOneAndReplace`, `findOneAndIncrement`,
  `findOneAndDecrement`, `findOneAndModify`, `findAndModify`) are mutation
  persistence defaults and classify as `DATA_MODIFY`. Local atomic defaults
  (`getAndUpdate`, `getAndIncrement`, `getAndDecrement`) are local-candidate
  defaults and classify as `LOCAL_VALUE_MODIFY`. Additive overlays can add
  project wrappers such as `getPersist`; add the same wrapper to the mutation
  vocabulary to give it a data or local classification.
- `boundaryPrefixes`: optional `findBy*`-style prefixes that also receive a
  LOOKUP boundary candidate. The default is empty, so repository searches stay
  SEARCH unless a project explicitly opts into a boundary alternative.
- `indexKinds`: language node kinds for index/subscript/element access.

Method and qualifier matching is case-insensitive, so the same defaults serve
Kotlin/JavaScript/TypeScript camelCase and C# PascalCase. Identifier matching is
token-based: camel/snake boundaries form tokens, so `userRepository` matches the
`repository` qualifier and `statusById` matches the `ById` suffix, while
`mutable`, `bitmap`, `predict`, and `restore` do not collide with `table`, `Map`,
`dict`, or `store`.

## Classification policy

- A configured search call or `findBy*`/`getBy*`/`loadBy*`/`search*`/`query*`
  prefix emits established `SEARCH` only on a configured search receiver/type.
  Prefix matching respects token boundaries, so `getBytes`/`loadBytes` are not
  search-prefix matches; they stay ambiguous candidates. Bare or unresolved
  calls become candidates.
- Explicit `lookupCalls` entries win over search-prefix classification:
  `getByKey` is a lookup call even though it starts with `getBy`, so it emits
  no SEARCH source or candidate.
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
  a repository or other multi-record source that may return empty. The default
  `boundaryPrefixes` is empty, so the lookup rule does not emit a `LOOKUP`
  alternative; a project can opt in with an explicit `boundaryPrefixes` entry.
  A guard or validation before the call does not reclassify it as `LOOKUP`.
- Generic `get`, `find`, `fetch*`, `load*`, and similar wrappers are not
  established. Both rules emit `PACK_DOMAIN` candidates so review/AI can choose,
  and the established output stays conservative.
- A known-map `get` is established `LOOKUP` because the key identifies one known
  value; the search rule still adds a `SEARCH` candidate for the boundary.
- Index access that is the assigned target of an assignment, compound
  assignment, or increment/decrement emits no `LOOKUP`; that write boundary
  belongs to the mutation pack. Increment/decrement targets are selected from
  the write syntax rather than from operator characters in the target text, so
  `counts[i - 1]++` and `statusById["x-y"]++` are writes. Reads on the
  right-hand side (`copy = statusById[id]`) and nested reads inside a target
  (`codeMap[id].field = value`, `translationTable[statusById[id]] = value`,
  `statusById[otherById[id] - 1]++`) still emit `LOOKUP`.
- Unknown index access is not LOOKUP.

## Overlap policy

Intents are a set, not a mutually exclusive enum, so SEARCH and LOOKUP may
legitimately co-occur on different spans in the same function or call chain.

- `SERVICE_DELEGATION` describes responsibility transfer, not the delegated
  call's business meaning. The service pack may tag a thin wrapper while the
  inner repository/search call remains `SEARCH` or `LOOKUP`.
- `DATA_MODIFY` describes persistence. A persistence write is not silently
  re-labelled as search/lookup; the persistence boundary wins for writes. Calls
  in `compoundMutationCalls` (plus configured wrappers) are excluded from
  SEARCH/LOOKUP ambiguity so the mutation pack owns their classification:
  durable compound names as `DATA_MODIFY`, atomic `getAnd*` names as
  `LOCAL_VALUE_MODIFY`. Genuine reads such as `findByStatus`, `count`, and `get`
  remain read-shaped.
- The data-access rules are independent and composable; neither suppresses the
  other or the service/mutation packs.

## Registration and host coverage

The pack is declared in `analysis/default-rulepacks/src/main/resources/default-rulepacks/data-access/` as the `data-access` pack. Bundled discovery and runtime binding are covered by the pack, pipeline, and frozen-corpus parity tests. Classification itself is covered by the direct pack tests and the frozen-corpus parity suite.
