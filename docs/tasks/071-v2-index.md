---
name: V2 index and source facts
status: done
depends-on: [065]
reviewers: [correctness, architecture]
---

## Goal

Implement source metadata, content hashes, structural hashes, parse/fact caching, and invalidation.

## Scope

- SourceInput for path or in-memory text plus version.
- mtime/size prefilter, content-hash confirmation, and Merkle node hashes.
- IndexedSource assembly through SyntaxEngine and mandatory SemanticProvider.
- Incremental invalidation and full-rescan fallback.

## Acceptance

- Repeated analysis reuses unchanged facts and invalidates changed files.
- Index never resolves languages or imports adapters.
- A full rescan is correct and explicit.

## Out of scope

- SQLite/WAL persistence and Git acceleration.
- Language provider construction.

## Notes

- In-memory first; storage can follow without changing the API.
