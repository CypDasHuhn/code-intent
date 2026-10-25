## Round 1

Verdict: FINDINGS

1. **Persistence adapter leaks into runtime.** `analysis-engine-v2/runtime/src/main/kotlin/dev/cint/runtime/PersistingKnowledgeStore.kt:19,91` and `DefaultRuntime.kt:20,265,282` make runtime own load/save and persistence-on-mutation policy around the concrete `KnowledgeStoreFiles` adapter; `runtime/build.gradle.kts:10` and `graph-guard.json:31` legalize an edge that `docs/rewrite/module-graph.md:160` still forbids as a dependency on "adapter classes". The edge was requested by 068, but code and docs disagree; a new persistence backend forces runtime edits. Fix: add a snapshot-repository port in `contracts`, move `PersistingKnowledgeStore` and path handling into `knowledge-persistence`, inject a factory through `RuntimeComponents`, and align the module graph with the allowed edge.

2. **Language wiring has overlapping injection points with inconsistent initialization.** `RuntimeComponents.kt:45-48` exposes `rootLocator`, `languageCatalog`, `languageFactory`, and `languageResolver`; `DefaultRuntime.kt:209,239-251` resolves through the resolver/factory, but `:288,296-302` always uses `components.languageCatalog` for default materialization. A fake or host resolver can analyze one language while initialization materializes another; the singleton `Languages` facade (`LanguageSupportFactory.kt:268-284`) is a second default path with different policy. Fix: expose one language-composition provider used by both resolution and initialization, and make runtime the only default entry.

3. **Injected pipeline can survive reload with stale wiring.** `RuntimeComponents.kt:50-52,62-72` prefers a caller-supplied `pipeline` over a factory, while `DefaultRuntime.kt:96-100` rebuilds packs and knowledge during reload. A reload with an injected pipeline then analyzes against the old knowledge store; injecting `knowledgeStore` similarly makes disk reload a no-op. Fix: use factory ownership for lifecycle-bound pipeline/knowledge, or reject reload when non-rebindable ports were injected.

4. **Runtime performs fact-level merging, not just orchestration.** `AnalysisMerge.kt:5-22,27,86-105` imports every `FactBatch` subtype and synthesizes merged trees and qualified IDs. New fact kinds or pipeline output changes require editing runtime, duplicating analysis shaping outside `intent-pipeline` and risking 084 parity drift. Fix: add multi-source analysis to the pipeline or a contracts-level result combiner; runtime should only order, invoke, and publish.

5. **The default evaluator catalog is empty.** `RuntimeComponents.kt:54` defaults to `RuleEvaluatorCatalog.bundled()`, which `RulePlans.kt:22` builds from an empty list. `RuntimeFactory.create()` can run syntax/index/knowledge yet return no intents; 080/082 inherit that. Fix: require an explicit registry at composition or fail fast when empty, with the production binding supplied by pipeline/runtime and 084 owning implementations.

The required boundaries mostly hold: runtime is the composition root, languages remains the sole importer of `syntax-treesitter`/`lsp`, runtime has no IPC/MCP imports, the test-only `test-fixtures` edge is correctly confined to `testImplementation`, and 074's root/provider-default ownership is now honored. The persistence wrapper, language wiring, reload injection, fact merging, and empty evaluator default remain risks for 080/081/082/084; `reload`, `status`, and `publish` stay in runtime, not apps.

## Round 2

Verdict: PASS (five CONCURs; two residuals)

1. CONCUR — `contracts/src/commonMain/kotlin/dev/cint/contracts/ports/KnowledgeRepository.kt:14-31` defines load/save and factories; `knowledge-persistence/src/main/kotlin/dev/cint/knowledge/persistence/FileKnowledgeRepository.kt:14-88` and `PersistentKnowledgeStore.kt:19-119` own path/file IO and persistence-on-mutation; `RuntimeComponents.kt:42-43` injects both factories and `DefaultRuntime.kt:371,394` only composes. `module-graph.md:133,160` and `graph-guard.json:31` now allow the edge. Residual: `DefaultRuntime.kt:23,41,292` still casts to the concrete `PersistentKnowledgeStore`, so a custom `KnowledgeStoreFactory` has no shutdown-save contract.

2. CONCUR — `RuntimeComponents.kt:36` exposes only `languageComposition`; `RuntimeLanguageComposition.kt:10-27` carries catalog and resolve, and `DefaultRuntime.kt:183,378` uses both; the `Languages` singleton is removed. Residual: a SAM `RuntimeLanguageComposition { ... }` inherits the empty catalog at `RuntimeLanguageComposition.kt:11-13`, so initialization still differs from resolution unless callers implement the object.

3. CONCUR — `DefaultRuntime.kt:108-115,293` rejects reload when pipeline/store are injected, covered by `reloadRejectsInjectedLifecyclePorts`; `RuntimeComponents.kt:54-55` keeps factory rebinding for reloadable wiring.

4. CONCUR — `AnalysisResultMerger` lives at `contracts/src/commonMain/kotlin/dev/cint/contracts/data/AnalysisResultMerge.kt:28-29`; `DefaultRuntime.kt:86` only calls it.

5. CONCUR — `RuntimeComponents.kt:44,59-65` requires an evaluator registry and fails with `PreconditionFailed`; `defaultFactoryRequiresAnExplicitEvaluatorRegistry` covers it.

Boundaries hold: runtime imports no syntax/LSP/IPC/MCP, languages remains the sole adapter importer, both added graph edges are documented and test-only where appropriate, and root/provider defaults remain runtime-owned. The residuals are contract polish, not blockers for 080/081/082/084.
