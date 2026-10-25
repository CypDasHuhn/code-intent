# Architecture Review — 074 V2 Languages Facade

Verdict: FINDINGS

1. **Facade leaks an adapter type.** `analysis-engine-v2/languages/src/main/kotlin/dev/cint/v2/languages/LanguageSupportFactory.kt:22-28,36-43` exposes `LspTransport` in `LanguageOptions` and the public `LanguageProviderFactory`, while `analysis-engine-v2/languages/build.gradle.kts:8` declares `:lsp` as `implementation`. Callers cannot reference the type without adding a forbidden LSP edge, so the seam is unusable outside this module and ties the public facade to one adapter. Fix: remove `transport` from public options/seams (keep an internal or test-only constructor), or pass an opaque transport factory owned by `:lsp`.

2. **Root discovery belongs to orchestration.** `analysis-engine-v2/languages/src/main/kotlin/dev/cint/v2/languages/LanguageRoots.kt:6-35` performs filesystem traversal inside `languages`, and `LanguageSupportFactory.kt:97,131-160` runs it before provider resolution and fails when no root is configured — including `SourceInput.InMemory`/editor sources with no path. That contradicts the graph's "runtime owns when and where", makes the facade filesystem-dependent, and blocks 081 live/editor documents. Fix: keep markers as data, let runtime (or an injected root-locator port) discover and pass `rootPath`, and require a root only for modes that need one.

3. **Provider policy is split and partly hardcoded.** `LanguageSupportFactory.kt:162-176` chooses mode/command and falls back to `"kotlin-lsp --stdio"` (`TreeSitterLanguageRegistry.kt:19`), while `:lsp` separately validates modes. A binary command is environment policy, not language data, and repeats V1's hardcoded Kotlin path. Fix: the entry carries only default mode/profile/markers; runtime supplies `SemanticProviderConfig.command`/`endpoint`; keep mode validation in `:lsp`.

4. **Two descriptor shapes and three lookup APIs.** `analysis-engine-v2/languages/src/main/kotlin/dev/cint/v2/languages/LanguageEntry.kt:6-28` duplicates `id`/`parserId`/`extensions` from `LanguageDescriptor` and adds mode/markers/profile that the ticket assigns to descriptors; `LanguageCatalog` exposes both `entries` and derived `descriptors`; `TreeSitterLanguageRegistry.kt:113-129` keeps legacy `resolve/forExtension/forPath`. Each new language in 078/081 must update parallel representations. Fix: one entry type (enrich `LanguageDescriptor` or keep the entry alone), remove `descriptors`, legacy methods, and `parserId`.

5. **Catalog collisions are silent.** `TreeSitterLanguageRegistry.kt:96-111` uses `put`/`putIfAbsent`, so duplicate names/aliases overwrite and duplicate extensions keep the first. Adding languages will silently route files to the wrong provider. Fix: build maps with duplicate detection that fails fast, and cover it with a catalog test.

Otherwise the boundary holds: only `languages` imports `syntax-treesitter`/`lsp`; there is no `index`/`runtime`/analysis coupling; entries are flat data, not hierarchies; and host-channel wiring stays behind contract ports opened inside `:lsp`, which is correct. The main risk for 078/081 is that root/environment policy now lives in the facade and the catalog has two shapes; fixing findings 2-4 before runtime and server wire against it will keep composition in the intended module.

## Round 2

1. CONCUR — `LanguageSupportFactory.kt:22-28,36-43` no longer exposes `LspTransport`; it survives only in the `internal` constructor (`:51-56`), so the public facade is adapter-free.
2. CONCUR — root discovery is opt-in via `LanguageRootLocator` (`LanguageRootLocator.kt:6-11`); `resolveRoot` (`LanguageSupportFactory.kt:153-173`) requires a root only for non-editor modes, unblocking in-memory/editor resolution. Residual: `MarkerRootLocator` still ships filesystem traversal in `languages`; move it to runtime wiring in 078.
3. CONCUR — `managedCommand` is gone from `TreeSitterLanguageRegistry.kt`; `providerConfig` (`LanguageSupportFactory.kt:193-203`) only overlays mode/root, leaving command/endpoint to the caller and mode validation in `:lsp`.
4. CONCUR — `LanguageEntry` is deleted; `LanguageDescriptor` now carries mode/markers/profile/aliases (`LanguageDescriptor.kt:14-17`), and one `LanguageCatalog` lookup API replaces the parallel/legacy methods (`LanguageCatalog.kt:5-12`, `TreeSitterLanguageRegistry.kt:74-78`).
5. CONCUR — `DefaultLanguageCatalog` rejects duplicate ids, aliases, and extensions with `require` (`LanguageCatalog.kt:49-63`).

Boundaries still hold: only `languages` imports the adapters (native-spike excepted); `languages` has no index/runtime edge; host-channel ownership is now explicit in `:lsp`. No new leak blocks 078/081.

## Round 3

DISSENT — the facade now defaults to `MarkerRootLocator` (`LanguageSupportFactory.kt:49`), so filesystem traversal remains in `languages` by default. The claimed 078 deferral is not explicit: no ticket or doc records it, and `docs/tasks/074-v2-languages-facade.md` is unchanged. Also `LanguageProviderDefaults.kt:10-11` reintroduces the hardcoded `kotlin-lsp --stdio` that Round 2 finding 3 accepted as removed, moving environment policy back into `languages` behind a new seam. Fix: record the MarkerRootLocator-to-runtime move and default-command ownership as named 078 items in the 074/078 notes. The adapter boundary itself remains acceptable: only `languages` imports adapters, with no index/runtime edge.

## Round 4

CONCUR — the deferrals are now explicit in both tickets: `docs/tasks/074-v2-languages-facade.md:32-33` and `docs/tasks/078-v2-runtime.md:33-34` name root-location wiring and provider-command/default ownership, so the residual is tracked rather than implicit. `DefaultLanguageProviderDefaults` keeps the Kotlin command as a private overridable data map behind the injectable `LanguageProviderDefaults` seam (`LanguageProviderDefaults.kt:6-17`), and the early `PreconditionFailed` path still runs before provider construction (`LanguageSupportFactory.kt:133-135,198-227`; test `LanguageSupportFactoryTest.kt:323-353`). Default and override are both tested (`:64-68`, `:73-92`). The adapter boundary holds: only `languages` imports `lsp`/`syntax-treesitter` (native-spike exempt). No architecture findings remain.
