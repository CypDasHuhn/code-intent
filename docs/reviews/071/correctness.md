# Correctness Review — Ticket 071

**Verdict: FINDINGS**

1. `analysis-engine-v2/index/src/main/kotlin/dev/cint/v2/index/InMemoryIndex.kt:118` — When `mtime` and `size` match, `ensureFile` reuses facts without reading or hashing the file. Same-size writes that preserve timestamps, coarse filesystems, and restored/clock-skewed times therefore silently return stale facts, contradicting `module-graph.md:311-313` ("handled by hashes, not metadata"). The test (`InMemoryIndexTest.kt:47-83`) only changes the timestamp, so it cannot detect this. Fix: hash the bytes on every ensure before reuse, using metadata only to avoid reparsing, and add a same-size/same-mtime content-change test.

2. `InMemoryIndex.kt:95-100,454-458` — An in-memory source without `path` is keyed by its content hash, so every text edit creates a new entry, passes `previous = null` to `parse`, and never produces an incremental node diff. Superseded entries and `lastChanges` entries are never evicted, and `snapshot`, `nodeHashes`, and `lastChange` cannot address these keys because they prepend `file:`. Fix: key by a stable document identity (caller id or language slot), replace/evict on edit, and expose queries by `SourceInput` or a stable key.

3. `InMemoryIndex.kt:68-83,116-120` — The resolved `LanguageSupport` is never part of cache validation. A path entry is reused whenever metadata matches even if `syntax`/`semanticProvider` changed language, grammar, or provider generation; `languageHint` is ignored. Fix: store syntax language and provider identity/epoch in `Entry`, rebuild on mismatch, and reject a tree whose `language` differs from `syntax.language`.

4. `InMemoryIndex.kt:289-311` — `fullRescan` clears `entries`, `reverseDependencies`, and `lastChanges` before rebuilding. A missing file, provider error, or cancellation mid-loop leaves a partial cache with entries marked complete and destroys the previous valid state. Fix: build staged maps/sources and swap only on success; preserve prior state on failure. Add failure/cancellation tests.

5. `InMemoryIndex.kt:180,439-458` — Dependency edges use `ImportFact.path` verbatim while invalidation looks up the verbatim `SourceInput` path. Relative, non-normalized, or differently formatted import paths never match, so dependents are not marked stale. Fix: define and enforce canonical paths in the contract or lexically normalize against the source path before keying; test a relative import.

6. `InMemoryIndex.kt:332-363` — `requestSemantic` accepts any `SemanticResult` returned for `DIAGNOSTICS_FOR` and stores it in `semanticFacts`; a provider returning `HoverAt`/`TypeAt` is silently accepted. Fix: verify the result subtype matches the request kind and return `ValidationError` otherwise.

7. Tests (`InMemoryIndexTest.kt:85-164`) prove one-hop stale marking and that the semantic provider is re-called, but not that an invalidated dependent's syntax facts are actually distinct, nor transitive chains, in-memory edits, support changes, or full-rescan failure. Fix: assert changed `semanticFacts`/epoch on revalidation and add the missing cases.

`IndexTypes.kt:35` is additive: source and JSON decoding remain compatible because of the default, and it belongs to 071 because `IndexedSource` must carry provider output. `SerializationTest.kt:347` only exercises the empty default, so add a non-empty round-trip.

Summary: The core design is sound—mandatory provider resolution, content-hash validation, Merkle validation, cancellation checks, and explicit provider/syntax failures are implemented and tested. The remaining gaps are metadata being trusted as authoritative, unstable/inaccessible in-memory identity, missing support identity in cache validation, destructive full rescan, path-sensitive dependency propagation, unchecked semantic result kinds, and test coverage that cannot distinguish some reuse from invalidation.

## Round 2

Verdict: CONCUR on all seven round-1 findings; one new finding.

1. CONCUR — `InMemoryIndex.kt:141-153` always reads and hashes bytes before reuse; same-size/same-mtime test at `InMemoryIndexTest.kt:474-494`.
2. CONCUR — `IndexTypes.kt:25,34-38` adds `documentId`; `InMemoryIndex.kt:105-129` passes the previous tree and `IndexModel.kt:29-55` adds by-source queries; test `:497-524`. Residual: the default below.
3. CONCUR — `InMemoryIndex.kt:198,305-319,632-640` stores/validates syntax and provider identity and languages; `Merkle.kt:83-88`; test `:526-557`.
4. CONCUR — `InMemoryIndex.kt:409-433` stages a new index and swaps only after success; failure/cancel tests `:640-691`.
5. CONCUR — `InMemoryIndex.kt:614-630,642-655` canonicalizes sources and resolves relative imports; transitive test `:693-740`.
6. CONCUR — `InMemoryIndex.kt:505-512` rejects mismatched result kinds; test `:595-608`.
7. CONCUR — new tests cover the requested gaps (`InMemoryIndexTest.kt:474,497,526,595,640,672,693`).

New: `IndexTypes.kt:25` defaults pathless `InMemory.documentId` to `language`, so distinct pathless documents of one language share `memory:<lang>:<lang>` (`InMemoryIndex.kt:647`) and overwrite each other's facts/hashes. Require an explicit stable id for pathless documents; add a collision test.

Residual: support identity uses object reference (`InMemoryIndex.kt:637-638`), `providerEpochs` retains providers indefinitely, pathless entries have no invalidate operation, and `SyntaxEngine.nodeHash`'s recursive-subtree requirement is undocumented (`SyntaxEngine.kt:23`).

## Round 3

CONCUR. The collision is genuinely fixed: pathless `documentId` is optional in the contract (`IndexTypes.kt:25`) but validated before keying (`InMemoryIndex.kt:91,679-687`), the key now includes it (`:648`), and new tests prove distinct ids stay separate (`InMemoryIndexTest.kt:748-765`) while missing/blank ids fail with `InvalidInput` before parsing (`:767-786`). Recursive subtree semantics are now documented (`SyntaxEngine.kt:23-28`). Residual: `snapshotSource`, `nodeHashesOf`, and `lastChangeOf` call `keyFor` without validation, so a pathless input with null `documentId` throws `IllegalArgumentException` at `InMemoryIndex.kt:648` instead of returning a contract error.
