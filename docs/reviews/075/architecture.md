## Round 1

Verdict: FINDINGS

1. `analysis-engine-v2/knowledge/src/main/kotlin/dev/cint/knowledge/KnowledgeLookup.kt:29-38` and `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/KnowledgeInputs.kt:70-94,150-160` — precedence has two owners and two semantics. The ticket assigns precedence to the pipeline, but knowledge gained a pack-default fallback overload that the resolver never calls; the resolver instead resolves by `matches` and confidence while `KnowledgeLookup` resolves by exact `identityKey`. Fix: keep one precedence helper, owned by the pipeline (or pure lookup in knowledge without pack fallback), and make the resolver call it. Align selector matching and status handling.

2. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/RulePlans.kt:39-46,48-82` with `core/src/main/kotlin/dev/cint/core/composition/CompositionConfig.kt:3-7` — pipeline restates core composition defaults (`true`, `0.5`) and invents cross-pack merge policy (`&&`, `maxOf`), silently dropping `pureAnnotations`. Selecting another pack can change composition semantics, and core changes require editing the pipeline. Fix: pass `CompositionSpec` through a contracts/core adapter and let core own defaults and merging; preserve `pureAnnotations`.

3. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/DefaultIntentPipeline.kt:22` and `RulePlans.kt:15-22` — the default registry is empty and no production `RuleEvaluator` exists; the “real bundled packs” test fabricates a no-op evaluator (`DefaultIntentPipelineTest.kt:206-216`). Binding every real 069 rule therefore fails unless runtime constructs evaluators, contrary to 075 owning evaluator selection and forcing 078 to know binding internals. Fix: provide a production evaluator catalog/registry in intent-pipeline (with 084 supplying parity implementations) and default the pipeline to it.

4. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/DefaultIntentPipeline.kt:76` with `contracts/src/commonMain/kotlin/dev/cint/contracts/ports/IntentPipeline.kt:20-25` — `request.options["fallbackAi"]` lets any caller enable AI, so off-by-default is not enforceable and runtime/IPC must know a magic string; 077’s dirty/uninitialized policy is bypassed. Fix: delete the option and inject a typed fallback-AI policy at construction, disabled by default.

5. `analysis-engine-v2/ai/src/main/kotlin/dev/cint/ai/FallbackAi.kt:16-18` with `DefaultIntentPipeline.kt:96-101` — AI returns final annotations that are injected directly as knowledge contributions, bypassing `KnowledgeStore` proposal/mutation semantics and 077’s “failure leaves knowledge untouched” guarantee. Fix: return knowledge-change proposals and apply accepted results through the mutation API before resolution.

Summary: the pipeline imports only declared modules (contracts, core, packs, knowledge, ai) and contains no parser/LSP/filesystem/IO access; the graph-guard test edge for `test-fixtures` matches `testImplementation`, and pack selection is deterministic. The pipeline remains the right composition layer for 078, but evaluator selection, composition translation, and fallback-AI gating must move behind pipeline-owned abstractions so runtime stays an orchestrator rather than a consumer of core and AI internals.

## Round 2

Verdict: CONCUR (five findings addressed; residual noted on 3)

1. CONCUR — `knowledge/.../KnowledgeLookup.kt:9-26` is now exact lookup only; precedence, status filtering, and matching live in `intent-pipeline/.../KnowledgeInputs.kt:62-122,139-178,348-363`. Residual: `KnowledgeLookup` remains a second lookup with different semantics; remove it or document it as non-precedence.
2. CONCUR — `CompositionSpec.pureAnnotations` is in contracts (`contracts/.../RulePackSource.kt:64`); `core/.../CompositionPlan.kt:5-17` owns defaults and merging, and `DefaultIntentPipeline.kt:99` delegates while `RulePlans.kt:35-52` only collects specs.
3. CONCUR (deferred) — `RuleEvaluatorCatalog` is pipeline-owned (`RulePlans.kt:11-24`) and is the default (`DefaultIntentPipeline.kt:25`), but it is empty. Ownership moved correctly; 078 must inject a populated catalog and 084 must own parity implementations.
4. CONCUR — `FallbackAiPolicy.DISABLED` (`ai/.../FallbackAi.kt:16-24`, `DefaultIntentPipeline.kt:28`) replaces the request option; `DefaultIntentPipelineTest.kt:161-180` shows options cannot enable AI.
5. CONCUR — the hook returns `KnowledgeChange` (`ai/.../FallbackAi.kt:26-28`), applied through `KnowledgeStore.proposeChange` then re-snapshotted (`DefaultIntentPipeline.kt:130-141`).

Boundaries hold: `CompositionPlan` is core-to-contracts only, the pipeline imports only declared modules, no parser/LSP/IO appears, and the `test-fixtures` edge remains test-only.
