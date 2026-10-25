# Architecture Review — Ticket 071

**Verdict: FINDINGS**

Paths are relative to `analysis-engine-v2/`.

1. `index/src/main/kotlin/dev/cint/v2/index/Merkle.kt:15` (used at `InMemoryIndex.kt:154`) — Merkle hashes are computed in `index`, while `contracts/src/commonMain/kotlin/dev/cint/contracts/ports/SyntaxEngine.kt:23` exposes `nodeHash` and adapters implement different leaf-only semantics (`syntax-treesitter/src/nativeMain/kotlin/dev/cint/v2/syntax/treesitter/TreeSitterSyntaxEngine.kt:61`). `index` never calls it, so identity has two owners and the adapter method is dead. Fix: make the contract's `nodeHash` recursive and consume it, or remove it and document index-only hashing.

2. `index/src/main/kotlin/dev/cint/v2/index/InMemoryIndex.kt:96-99` (also `:117-120`, `:206-217`) — Cache validation ignores `LanguageSupport`, language/grammar identity, provider generation, and `SourceInput.version`. A hit can return another provider's facts, and version-only edits reuse semantic facts. Fix: store language/provider identity and epoch in `Entry`, require a match before reuse, and include version in semantic cache identity.

3. `InMemoryIndex.kt:174-177` (also `:338-344`, `:526-541`) — `index` hardcodes a single `DIAGNOSTICS_FOR` request at offset 0 and a fixed syntax query-id list. Query selection is pipeline/language policy, so this couples `index` to fact vocabulary and strips request context from stored results. Fix: accept a request/fact plan and only execute, cache, and key results.

4. `contracts/src/commonMain/kotlin/dev/cint/contracts/facts/IndexTypes.kt:35` — The change belongs in `contracts` (`IndexedSource` is the `Index` output and pipeline input) and is minimal/additive; the default avoids JSON and constructor churn. But `List<SemanticResult>` is request-scoped, carries `unavailable`, and has no request key/version, so a `COMPLETE` source can hold empty or unavailable semantics. Fix: store normalized facts keyed by request/span/node with provider epoch, and expose semantic completeness.

5. `IndexTypes.kt:18-25`, `InMemoryIndex.kt:454-459` — `SourceInput.InMemory` has no stable identity (`path` is optional), so the index keys pathless text by content hash. Every edit creates a new entry with no `previous` tree, diff, or addressable snapshot. Fix: add a required document id (or require a path) and key by it; use version/content for invalidation.

6. `index/src/main/kotlin/dev/cint/v2/index/IndexModel.kt:28-32` versus `contracts/src/commonMain/kotlin/dev/cint/contracts/ports/Index.kt:8-29` — Explicit full rescan lives only on the index-internal `IncrementalIndex`; runtime/fakes depend on implementation types and a persistent backend must duplicate it. Fix: add `fullRescan` or a rescan mode to `ports.Index`, keeping storage private.

Summary: The core boundary is correct: `index` depends only on `contracts`, imports no adapter/language code, receives `LanguageSupport`, and enforces the mandatory provider. Its cache is private, so persistence can replace it without public API change, apart from finding 6. The contracts change is correctly placed and additive. Remaining risk is duplicated hash ownership and index-owned policy (support identity, query selection, in-memory identity). Fix findings 5 and 6 before runtime/persistence, or workarounds will leak into `runtime`/`languages` and break the layering.

## Round 2

Paths relative to `analysis-engine-v2/`.

1. **CONCUR.** `Merkle.kt:34` now calls `SyntaxEngine.nodeHash` per node; `TreeSitterSyntaxEngine.kt:61` and `Fakes.kt:162` hash `childIds` recursively via `NodeHash.compute`. Identity has one owner and the index consumes it. Residual: contract has no doc and adapter memo resets per node (O(n²)); not a boundary issue.

2. **CONCUR.** `Entry` stores syntax/provider instances plus language strings (`InMemoryIndex.kt:41-52`), `sameSupport` gates reuse (`:559-565`), version mismatch calls `refreshSemantic` (`:206-217`), and `SemanticFact.providerEpoch` records epoch (`IndexTypes.kt:42-46`). Residual: epoch is a per-index `IdentityHashMap` counter, not durable; support descriptor identity still unkeyed. Acceptable for in-memory.

3. **CONCUR.** `IndexPlan` is injected (`InMemoryIndex.kt:33`); `build` passes `plan.syntaxQueryIds` (`:284`) and `requestSemantic` iterates `plan.semanticRequests` (`:482-535`); no `DIAGNOSTICS_FOR`/query list hardcode. Index imports only contract fact vocabulary, so language/query selection is external. Plan living in `index` (not the port) is right: policy belongs to composition, and `runtime -> index` is allowed.

4. **CONCUR.** All contract edits belong in `contracts`: `IndexedSource`/`SemanticFact` are port output, `SourceInput` identity is input, and `IndexInput`/`RescanResult` appear in the port signature. Request carries uri/version/offset/kind, so fact identity is explicit; default list is additive. `requestSemantic` rejects unavailable and enforces a non-empty plan, so emitted `COMPLETE` sources cannot hold unavailable/empty semantics.

5. **DISSENT (partial).** `documentId` is optional with default `path ?: language` (`IndexTypes.kt:25`), not required. Two pathless `InMemory` sources of one language therefore share key `memory:<lang>:<lang>` (`InMemoryIndex.kt:604-607`), so the requested stable per-document identity is still absent unless callers pass an explicit id (as the test does). Require a non-null distinct id or reject pathless input without one.

6. **CONCUR.** `fullRescan` is on `ports.Index` with `IndexInput`/`RescanResult`; `InMemoryIndex` stages and swaps only on success, keeping storage private.

Contracts placement and plan injection are correct. Only finding 5 remains.

## Round 3

Paths relative to `analysis-engine-v2/`.

**CONCUR.** Finding 5 resolved. `InMemoryIndex.ensure` calls `validateInput` before dispatch (`InMemoryIndex.kt:91`), rejecting pathless sources with a blank/missing `documentId` (`:680-683`), and the key includes it (`memory:<lang>:<id>`, `:648`), so two pathless same-language documents remain distinct. Residual: `documentId` stays nullable in the contract, so the requirement is runtime-enforced rather than type-enforced; acceptable, and consistent with the Round 2 accept-or-reject condition. Recursive `nodeHash` is now the sole identity owner: documented on `SyntaxEngine.kt:23-28`, implemented with memoized subtree hashing (`TreeSitterSyntaxEngine.kt:61-88`), and consumed by `Merkle.kt:34`. No new boundary issues.
