# Materialized knowledge store format

The project knowledge file is JSON. The default path is `.cint/knowledge.json`;
the file location is a runtime concern. The `knowledge` module owns the document
codec and the `knowledge-persistence` module owns filesystem access.

## Version 1

```json
{
  "format": "cint-knowledge",
  "formatVersion": 1,
  "snapshot": {
    "functionAnnotations": [],
    "spanOverrides": [],
    "patternHypotheses": [],
    "packOrigins": {
      "FUNCTION|null|4:name|null|null|null|null": {
        "packId": "logging",
        "packVersion": "1.0.0"
      }
    },
    "version": 1,
    "timestamp": 1730000000000
  }
}
```

- `format` is always `cint-knowledge`.
- `formatVersion` is the file format version owned by the `knowledge` module
  (currently `1`). Missing or unsupported versions fail loading.
- `snapshot` is the contracts `KnowledgeSnapshot`, including its own version and
  positive epoch-millisecond timestamp.
- `packOrigins` is part of the snapshot and maps a function selector identity
  key to the pack id/version that seeded it. Origins must reference an existing
  annotation with `PACK_DEFAULT` provenance.
- Materialized pack defaults are ordinary function annotations whose
  `provenance` is `PACK_DEFAULT`.

The decoder validates every record (known intents, non-empty intents where
required, non-regressing timestamps), rejects duplicate selector identities, and
rejects pack origins that do not pair with a pack-default annotation.

## Mutation semantics

- A set replaces any record with the same selector identity key. Function
  selectors use all six fields (qualified name, import, receiver, declaring
  type, signature, structural pattern). Updates preserve `createdAt`, advance
  `updatedAt`, and reject a revision older than the stored one.
- Collections are defensively copied on write and snapshot, so caller-owned
  mutable collections cannot change stored knowledge.
- Removing a selector that is no longer present succeeds without creating a
  store entry. `pruneStale` explicitly drops annotations and overrides whose
  selectors are no longer live.
- `materializeDefaults` validates its inputs, is a no-op once `isMaterialized`
  is true, and on a new store never replaces an existing record. It forces
  `PACK_DEFAULT` provenance and records the pack origin. Duplicate selector
  defaults are resolved by confidence, status, pack id, pack version, then
  intents.
- Explicit non-pack writes clear the selector's pack origin.
- `proposeChange` validates and applies a change tree transactionally; a failed
  nested change leaves the store unchanged. `KnowledgeChange` covers function,
  span, pattern, default materialization, and batch changes.
