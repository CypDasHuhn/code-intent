## Round 1

Verdict: FINDINGS

1. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/KnowledgeInputs.kt:162-165` (used at `:136,155`; `analysis-engine-v2/knowledge/src/main/kotlin/dev/cint/knowledge/KnowledgeLookup.kt:54`) — `FunctionFact.selector` populates only `qualifiedName`, so the actual selector has null `importPath`, `receiverType`, `declaringType`, `signature`, and `structuralPattern`. Because `FunctionSelector.matches` requires equality on every non-blank expected field, bundled defaults using those fields (e.g. `bundled-packs.json:602-604`, `:615-617`, `:1508-1511`) can never match and are silently skipped. Fix: resolve the full selector from declaration/import facts and implement structural-pattern matching; if a form is unsupported, return an error/diagnostic instead of dropping it.

2. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/DefaultIntentPipeline.kt:59-60` with `KnowledgeInputs.kt:79-93` — raw `selected.defaults` are reapplied on every analysis whenever no snapshot annotation covers a function. Pack defaults are seed data meant to be materialized once; after a user deletes a default it silently reappears, and updated pack defaults take effect without the required proposed diff. Fix: consume persisted pack-default annotations, and use raw defaults only when the store is uninitialized (or have runtime materialize/propose first).

3. `DefaultIntentPipeline.kt:76-101` with `analysis-engine-v2/ai/src/main/kotlin/dev/cint/ai/FallbackAi.kt:16-18` — `request.options["fallbackAi"]` overrides the constructor flag, so off-by-default is not enforceable. When enabled, AI `FunctionAnnotation`s are converted by `functionAnnotationContributions` into established `SOURCE_INTENT` contributions with no status check, so AI output shares the deterministic settlement and can duplicate or override persisted knowledge instead of remaining proposals/candidates. Fix: gate AI behind a typed injected policy, and emit candidates or apply proposals through `KnowledgeStore`.

4. `KnowledgeInputs.kt:64-68,150-160` and `analysis-engine-v2/knowledge/src/main/kotlin/dev/cint/knowledge/DefaultPrecedence.kt:6-11` — status is ignored. Rejected/suppressed overrides, annotations, and defaults remain eligible, and `bestAnnotation` ranks confidence before status, so a rejected high-confidence record can beat an accepted one. Fix: exclude rejected/suppressed entries and encode status policy centrally.

5. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/PackSelection.kt:45` — duplicate manifest ids silently collapse through `associateBy`, dropping one pack's playbook and defaults; the winner also depends on source order. Fix: detect duplicate ids and return `ValidationError` before indexing.

6. Tests (`analysis-engine-v2/intent-pipeline/src/test/kotlin/dev/cint/pipeline/DefaultIntentPipelineTest.kt:198-230`, `KnowledgePrecedenceTest.kt:24-122`) exercise only qualified-name selectors, a no-op evaluator, and no rejection/removal transitions, so findings 1, 2 and 4 would pass unnoticed. Add real bundled-selector, status, and deletion cases.

Summary: pack filtering/closure ordering, deterministic sorting, unbound/mismatched binding failure, and the normal off-by-default AI path are sound. The correctness gaps are selector forms that are silently inapplicable, raw defaults that can resurrect deleted knowledge, status-blind precedence, AI proposals promoted into established output, and a duplicate-manifest drop, with tests unable to detect them.

## Round 2

1. CONCUR (residual): selector/import/annotation/token-prefix matching is implemented and unsupported structural/`declaringType` selectors now emit errors (`KnowledgeInputs.kt:206-327`; tests `KnowledgePrecedenceTest.kt:212-327`). But `receiverType` still silently no-matches the production `FunctionFact` shape because `nameNode.text` is unqualified (`SyntaxFactExtractor.kt:323`); the receiver test uses a synthetic dotted name (`KnowledgePrecedenceTest.kt:231`).
2. CONCUR: raw defaults are gated by `snapshot.materialized` (`KnowledgeInputs.kt:109-121`; `KnowledgeSnapshot.kt:15`; `InMemoryKnowledgeStore.kt:74`; test `:183-210`).
3. CONCUR: the request option is gone, policy is typed, and proposals go through `KnowledgeStore` (`DefaultIntentPipeline.kt:117-142`; `FallbackAi.kt:16-27`; test `:160-206`). Residual: enabled AI is silently skipped when `knowledgeStore` is null, and refresh failure is swallowed (`DefaultIntentPipeline.kt:125-126,140-141`).
4. CONCUR on the pipeline path (`KnowledgeInputs.kt:333-363`; test `:127-180`), but status policy is not central: `KnowledgeLookup.effectiveIntents` and `DefaultPrecedence` remain status-blind (`KnowledgeLookup.kt:14-25`; `DefaultPrecedence.kt:6-11`).
5. CONCUR: duplicate ids fail (`PackSelection.kt:44-58`; test `:78-95`).
6. CONCUR: real selector/status/materialization tests added.

New regression: import-derived selectors can annotate a local declaration that merely shares an imported name (`KnowledgeInputs.kt:226-231`); restrict imports to call resolution or prefer the local selector.

Verdict: FINDINGS — round-1 items are addressed, but receiver matching, split status policy, and import-derived false matches remain.

## Round 3

1. CONCUR: receiver types are derived from declaration syntax and unresolved receivers now emit `KNOWLEDGE_SELECTOR_UNSUPPORTED` instead of silently no-matching (`KnowledgeInputs.kt:218-230,302-305`; test `KnowledgePrecedenceTest.kt:328-350`). Simple-vs-qualified receiver normalization remains 076.
2. CONCUR: the `snapshot.materialized` gate still suppresses raw defaults (`KnowledgeInputs.kt:108-121`; test `:183-210`).
3. CONCUR: missing hook/store and refresh failures surface as `FALLBACK_AI_*` warnings while deterministic output survives (`DefaultIntentPipeline.kt:125-158`; tests `DefaultIntentPipelineTest.kt:213-260`).
4. CONCUR: `KnowledgeStatusPolicy` is authoritative in pipeline, lookup, precedence, and materialization (`KnowledgeStatusPolicy.kt:5-16`; `KnowledgeLookup.kt:14-31`; `DefaultPrecedence.kt:5-10`; `InMemoryKnowledgeStore.kt:261-264`; test `InMemoryKnowledgeStoreTest.kt:416-448`).
5. CONCUR: duplicate manifest ids still fail explicitly (`PackSelection.kt:44-58`).
6. CONCUR: import-derived false annotation is fixed and `importPath` selectors report a 076 deferral (`KnowledgeInputs.kt:292-296`; test `KnowledgePrecedenceTest.kt:252-294`).

No new regression found. Verdict: PASS.
