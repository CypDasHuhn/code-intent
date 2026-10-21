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
  maps, enums, configs, tables, or lookup registries.
- `knownMaps`, `knownMapSuffixes`: names/suffixes of static tables used for
  known-key resolution.
- `ambiguousCalls`, `ambiguousPrefixes`: calls such as `get`, `find`, and
  `fetch*` wrappers that produce candidates instead of established output.
- `boundaryPrefixes`: `findBy*`-style prefixes that also receive a LOOKUP
  boundary candidate.
- `indexKinds`: language node kinds for index/subscript/element access.

Method and qualifier matching is case-insensitive, so the same defaults serve
Kotlin/JavaScript/TypeScript camelCase and C# PascalCase.

## Classification policy

- A configured search call, `findBy*`/`search*`/`query*` prefix, or a configured
  call on a search receiver/type emits established `SEARCH`.
- Index access on a known map/table or lookup qualifier, and configured lookup
  calls on a known receiver/type, emit established `LOOKUP`.
- `findById`-style calls are explicitly `SEARCH`: they retrieve by criteria from
  a repository or other multi-record source that may return empty. The lookup
  rule emits a `LOOKUP` candidate on the same span as a reviewable boundary
  alternative.
- Generic `get`, `find`, `fetch*`, `load*`, and similar wrappers are not
  established. Both rules emit `PACK_DOMAIN` candidates so review/AI can choose,
  and the established output stays conservative.
- A known-map `get` is established `LOOKUP` because the key identifies one known
  value; the search rule still adds a `SEARCH` candidate for the boundary.
- Unknown index access is not LOOKUP.

## Overlap policy

Intents are a set, not a mutually exclusive enum, so SEARCH and LOOKUP may
legitimately co-occur on different spans in the same function or call chain.

- `SERVICE_DELEGATION` describes responsibility transfer, not the delegated
  call's business meaning. A later service pack may tag a thin wrapper while the
  inner repository/search call remains `SEARCH` or `LOOKUP`.
- `DATA_MODIFY` describes persistence. A persistence write is not silently
  re-labelled as search/lookup; the persistence boundary wins for writes. This
  pack only classifies read-shaped data access and does not model calls that both
  query and mutate.
- The current pack does not emit either intent; overlap is expressed by keeping
  the data-access intents independent and composable.

## Registration

The pack is intentionally not registered in this scaffold. Add this line to
`analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`
when wiring it into bundled discovery:

```text
dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
```
