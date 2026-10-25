# 077 Correctness Review

Verdict: FINDINGS

1. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/DefaultIntentPipeline.kt:137-144,157-166` with `analysis-engine-v2/ai/src/main/kotlin/dev/cint/ai/DefaultFallbackAi.kt:95-96,134-138` — the overwrite guard inspects only the caller snapshot while proposals are applied to the store. A stale/empty snapshot lets an AI suggestion replace an accepted record; a user/MCP `SUGGESTED` record is overwritten even with a current snapshot. Fix: propose from the store snapshot and enforce in the store/apply layer that AI changes never replace another writer's knowledge.

2. `DefaultIntentPipeline.kt:167-177` with `KnowledgeInputs.kt:91-93,111-121` — refreshed AI `SUGGESTED` annotations are immediately eligible and become `SOURCE_INTENT` contributions in the same `analyze` result. Proposals leak into deterministic output without review. Fix: keep fallback output as proposals for a later reviewed apply, or exclude AI suggestions from in-run derivation.

3. `analysis-engine-v2/ai/src/main/kotlin/dev/cint/ai/DefaultFallbackAi.kt:74-86` — multiple proposals for the same function (different intents) produce multiple `SetFunctionAnnotation` entries for one selector. `KnowledgeChangeApplier.kt:34-41` applies them sequentially, so the last silently wins and other intents are lost. Fix: merge intents by selector before emitting the batch, or reject conflicting proposals.

4. `analysis-engine-v2/ai/src/main/kotlin/dev/cint/ai/HttpAiTransport.kt:46-47` and `DefaultFallbackAi.kt:57-64` — responses are read with an unbounded `BodyHandlers.ofString`, and the prompt includes every function even though only per-function and source caps exist. A large response or file can exhaust memory or exceed the provider limit. Fix: enforce a maximum response size and cap function count/total prompt bytes.

5. `HttpAiTransport.kt:26-27,46-50` and `DefaultIntentPipeline.kt:157-166` — cancellation is checked only before the blocking send, so a token flipped during the request is ignored until timeout; the pipeline then converts the returned `Cancelled` error into `FALLBACK_AI_FAILED` and continues analysis. Fix: use a cancellable/async send and propagate `Cancelled` out of `analyze`.

6. `analysis-engine-v2/ai/src/main/kotlin/dev/cint/ai/FallbackAiRedaction.kt:20-31` with `DefaultFallbackAi.kt:51` — invalid redaction regexes are silently dropped, failing open, and the source is truncated before redaction, so a pattern that no longer matches the cut prefix can leak. Fix: validate regexes at construction and redact before truncating.

7. `DefaultFallbackAi.kt:47-50` — the call budget is consumed before checking candidates, so a dirty file with no functions exhausts the budget and prevents AI on later files. Fix: consume the budget only when a provider call is actually made.

8. `analysis-engine-v2/ai/src/main/kotlin/dev/cint/ai/HttpFallbackAiProvider.kt:111-116` — malformed proposal JSON returns an empty successful list instead of a serialization failure, hiding provider corruption. Fix: fail on unparseable/non-array content.

Tests use `ScriptedProvider` and stop at the provider boundary (`FallbackAiTest.kt`, `HttpFallbackAiProviderTest.kt`); nothing exercises real HTTP JSON through `DefaultFallbackAi` into a store, so findings 2, 3, and 8 pass unnoticed.

Summary: policy defaults, provenance/status/confidence stamping, failure isolation, guarding accepted records under a current snapshot, and local-server HTTP tests are implemented, but gaps remain around stale-snapshot overwrites, proposal leakage, lost same-selector proposals, unbounded HTTP/prompt size, ineffective mid-flight cancellation, fail-open redaction, premature budget consumption, and silent parse fallback. Tests validate fakes rather than the full conversion/apply path, leaving these undetected.

## Round 2

1. CONCUR: pipeline reads and passes the store snapshot (`DefaultIntentPipeline.kt:154-162`); the store rejects AI replacement of non-AI-suggested knowledge (`InMemoryKnowledgeStore.kt:106-110,167-171`); covered by store and pipeline tests.
2. CONCUR: AI suggestions are excluded from derivation (`KnowledgeStatusPolicy.kt:10-11`; `KnowledgeInputs.kt:70,92`), and the pipeline returns caller knowledge (`DefaultIntentPipeline.kt:178`); the integration test asserts no same-run leak.
3. CONCUR: same-selector proposals merge (`DefaultFallbackAi.kt:165-211`); the integration test expects the union of intents.
4. CONCUR: response size is capped (`HttpAiTransport.kt:22,116-122`) and prompt/function budgets are capped (`FallbackAiBudget.kt:8-9`; `DefaultFallbackAi.kt:50-68`).
5. CONCUR: cancellation is polled during the async request and body read (`HttpAiTransport.kt:55-71,104-126`), and the pipeline propagates `Cancelled` (`DefaultIntentPipeline.kt:166-176`).
6. CONCUR: redaction regexes fail at construction (`FallbackAiRedaction.kt:10-18`), and source is redacted before truncation (`DefaultFallbackAi.kt:50-52`).
7. CONCUR: the call budget is consumed only when the provider is invoked (`DefaultFallbackAi.kt:67-68`).
8. CONCUR for invalid/non-array JSON (`HttpFallbackAiProvider.kt:111-120`), but `mapNotNull` at :120 still silently drops malformed elements, so an array of only invalid proposals succeeds with an empty list.

New regressions: `DefaultIntentPipeline.kt:154` snapshots before `shouldRun` at :163, so a disabled policy still reads the store and can emit `FALLBACK_AI_UNAVAILABLE` (:158) on snapshot failure. `HttpAiTransport.kt:89` does not catch body-read I/O failures, so an `IOException` escapes the `ContractResult` boundary and can abort `analyze` instead of isolating failure. No end-to-end test covers either path or malformed array elements.

Verdict: most round-1 findings are addressed, but partial malformed-proposal handling and two new regressions remain.

## Round 3

1. CONCUR: `HttpFallbackAiProvider.kt:120-128` now rejects any invalid proposal element with an indexed `SerializationError`; test `HttpFallbackAiProviderTest.kt:124-138`.
2. CONCUR: `DefaultIntentPipeline.kt:140-162` evaluates policy on the caller request before hook/store checks and before `store.snapshot`; test `DefaultIntentPipelineTest.kt:452-473` shows disabled fallback does not read a failing store and adds no error.
3. CONCUR: `HttpAiTransport.kt:89-98` maps body-read I/O failures to `DependencyUnavailable`; test `HttpFallbackAiProviderTest.kt:140-156`.
No new regressions found; the Round 2 CONCURs still hold.

Verdict: PASS.
