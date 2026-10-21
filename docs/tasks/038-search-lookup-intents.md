---
name: search and lookup intents
status: todo
depends-on: [036]
reviewers: [correctness, detection-fidelity]
---

## Goal

Add two data-access intents:

- `SEARCH` — retrieve data by criteria from a source that may contain many
  records; the result may be empty or multiple.
- `LOOKUP` — retrieve one known value by key/constant from a static or
  effectively static table/map/enum/config; e.g. finding the DB id for an
  enum-like value.

## Scope

- New `data-access` pack with two rules, one per intent, and per-language config:
  search calls/prefixes/qualifiers, lookup calls/types, known tables/maps.
- Deterministic SEARCH: repository queries, `findBy*`, `search*`, `query*`,
  filters, SQL/query builders, external search APIs.
- Deterministic LOOKUP: map/dictionary get, enum/constant maps, config keys,
  static translation tables, known-key single-value resolution.
- Candidate emits for ambiguous calls (`get`, `find`, wrappers) and for the
  SEARCH/LOOKUP boundary; AI may propose the other intent as an alternative.
- Composition and overlap tests.

## Acceptance

- Config-driven repository/query calls emit `SEARCH`.
- Known-key table/map/enum resolution emits `LOOKUP`.
- `findById`-style calls are classified by an explicit rule (document whether
  known-key lookup or search), not by accident.
- Ambiguous calls become candidates; established output stays conservative.
- Fixtures, config, docs, and tests are added.

## Out of scope

- Whole-program dataflow or ORM semantic modeling.
- Treating every `get` as LOOKUP.
- Network/API call classification beyond configured search/delegation patterns.

## Notes

- SEARCH and LOOKUP may legitimately co-occur; keep them as a set, not a
  mutually exclusive enum.
- Document the overlap policy with `SERVICE_DELEGATION` and `DATA_MODIFY`.
