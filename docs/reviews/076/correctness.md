# Correctness Review: 076 V2 Function Propagation

Verdict: FINDINGS

1. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/KnowledgeCandidates.kt:124-128` — `CallFact.path` is always null from `SyntaxFactExtractor.kt:412-421`, so `pathName` is derived from callee text. For real chained navigation, callee text is `repository.findAll().map`; `substringBefore('(')` yields `repository.findAll` and `simpleName` becomes `findAll`, not `map`. Outer-call selectors, receiver types, overload and import matching therefore use the wrong name. `FunctionPropagationTest.kt:260-264` fabricates `path = "map"` and cannot catch this. Fix: derive the final member from the navigation suffix/AST and test with real extractor facts.

2. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/KnowledgeInputs.kt:133-152` — all chain nodes are marked emitted before confirming the root can emit. If the root is overridden or `rootTarget` is null, the whole chain is skipped while inner calls are excluded from the final direct-emission pass, silently dropping contracts. A root override also bypasses `composeCallChain` entirely (`:136-140`), so structural modifiers disappear; `FunctionPropagationTest.kt:77-78` asserts only `LOGGING`, locking in this over-suppression. Fix: mark members emitted only after emission, retain per-call direct fallback, and compose with the override as root base rather than aborting.

3. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/CallPropagation.kt:89-108` with `KnowledgeInputs.kt:128-130` — declaration contracts are keyed only by literal names, with no declaring-type, signature, or arity check. An annotation on one overload or one class propagates to every same-named call in the file, including unrelated receivers. Fix: resolve declarations by receiver/declaring type and signature/arity, with a deterministic overload tie-break.

4. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/KnowledgeCandidates.kt:232-236,238-243` and `KnowledgeInputs.kt:186-189` — selectors with equal status/confidence tie-break on `identityKey`/pack id, not specificity or provenance, so a broad token-prefix or structural selector can beat an exact qualified name. Scope is only a tie-break (`:369-374`), so a `FILE`-scoped annotation applies to every file. Fix: explicit specificity ranking and scope applicability by path (or reject `FILE` without a path).

5. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/KnowledgeInputs.kt:154-164` — any `importPath` selector that simply matches no call site in the current file is reported as `KNOWLEDGE_SELECTOR_UNSUPPORTED`. Unsupported forms and unmatched project-wide selectors are conflated, producing spurious diagnostics; `FunctionPropagationTest.kt:149-160` codifies this. Fix: distinguish unsupported from unmatched, deduplicate by selector, and warn only for genuinely unsupported selectors.

6. `analysis-engine-v2/intent-pipeline/src/main/kotlin/dev/cint/pipeline/CallPropagation.kt:79-84` — the containment fallback links any nested call contained in the receiver expression, including calls inside arguments. Unrelated calls become chains and receive `CHAINING`/`DATA_TRANSFORMATION`. Fix: require direct node/site/wrapper match and add a negative test.

7. Tests do not prove the key paths. The chain test (`FunctionPropagationTest.kt:39-52`) annotates `findUser`, which also matches the inner call directly, so `DeclarationContracts` could be removed and the test still pass. The override test covers only the chain root and omits inner-call/modifier assertions (`:54-79`). The AI test uses the default disabled policy and an empty playbook (`:82-105`), so the hook cannot be reached; determinism replays identical order (`:170-179`). Fix: use a declaration-only selector, test a nested override, assert derivation with an enabled-but-unused AI hook, and shuffle inputs.

Summary: status gating, deterministic sorting, cycle guards, and the absence of AI calls inside the resolver are sound. Correctness defects remain in real-fact call naming, chain/override emission, name-only declaration propagation, selector/scope precedence, unmatched-selector diagnostics, and chain over-association; the tests encode the synthetic happy path and miss them.

## Round 2

1. CONCUR: the extractor now emits final callee, path, and arity (`SyntaxFactExtractor.kt:284-317,473-481`; `SyntaxEngineTest.kt:176-188`). Residual regression: `argumentCount` ignores a trailing lambda when value arguments exist (`:306-316`), while `parameterCount` counts it, so `foo(1) {}` fails the arity check in `CallPropagation.kt:113-117`.
2. CONCUR no chain suppression; DISSENT override precedence: `CallChainComposition.kt:93-98` adds inherited SEARCH/DATA_TRANSFORMATION to the root regardless of a root override. `FunctionPropagationTest.kt:83-88` checks only membership and never asserts SEARCH is absent, so the override still does not win for its span.
3. CONCUR deterministic scoring (`CallPropagation.kt:99-120`). Residual: declaring type is compared with receiver expression text (`KnowledgeCandidates.kt:136-153`), so `repo` versus `Repository` fails; equal-arity overloads still fall back to node id.
4. CONCUR specificity/provenance/scope (`DefaultPrecedence.kt:11-53`; `KnowledgeCandidates.kt:226-244`). DISSENT FILE identity: `FunctionAnnotation.path` exists, but duplicate and store keys still use `selector.identityKey` alone (`KnowledgeValidation.kt:54-58`; `InMemoryKnowledgeStore.kt:102-111`), so the same selector in two files overwrites instead of coexisting.
5. CONCUR deduplication (`KnowledgeInputs.kt:54-56,142-148,176-210`).
6. CONCUR direct receiver identity (`CallPropagation.kt:49-63`; negative test `FunctionPropagationTest.kt:166-176`).
7. Partial: the declaration-only chain and reordering tests are genuine (`FunctionPropagationTest.kt:41-58,271-285`); the AI test still uses policy `{ false }` (`:91-110`).

Verdict: FINDINGS. Most claims are implemented, but override precedence, trailing-lambda arity, FILE-scoped identity, and receiver-type resolution remain incorrect.

## Round 3

1. CONCUR trailing-lambda arity (`SyntaxFactExtractor.kt:306-335`; `SyntaxEngineTest.kt:190-202`).
2. CONCUR exact override: inherited intents are suppressed for overridden calls (`CallChainComposition.kt:66,98`) and the test now asserts exactly `{LOGGING, CHAINING}` (`FunctionPropagationTest.kt:91-92`).
3. CONCUR resolved receiver types (`KnowledgeCandidates.kt:140-143,163-184`) and the unsupported diagnostic.
4. CONCUR path-aware identity in the model, validation, and store keys (`FunctionAnnotation.kt:21-28`; `KnowledgeValidation.kt:54-58`; `InMemoryKnowledgeStore.kt:42,102`). DISSENT: removal still deletes every FILE variant of a selector (`InMemoryKnowledgeStore.kt:127`), `pruneStale` ignores path (`:301-308`), and `KnowledgeLookup.kt:25-30` remains path-blind.
5. DISSENT: the AI test still uses `FallbackAiPolicy.DISABLED` (`FunctionPropagationTest.kt:104-110`), so the configured hook cannot run; it remains tautological rather than proving derivation-time AI absence.
6. CONCUR named 078 deferral (`CallPropagation.kt:67-70`).

Verdict: FINDINGS — the core fixes hold, but FILE-scoped removal/prune/lookup and the AI test evidence remain incomplete.

## Round 4

1. CONCUR path-aware removal: the port and both stores accept an optional path and delete only the matching FILE variant (`KnowledgeStore.kt:28-32`; `InMemoryKnowledgeStore.kt:121-137`; `FakeKnowledgeStore.kt:83-108`), with coexistence/removal regressions.
2. CONCUR path-aware prune and lookup (`InMemoryKnowledgeStore.kt:306-327`; `KnowledgeLookup.kt:10-33`; tests `InMemoryKnowledgeStoreTest.kt:456-501`).
3. CONCUR the AI test now enables the policy and uses a throwing hook (`FunctionPropagationTest.kt:93-113`).
4. DISSENT/new regression: the test passes because `applyFallbackAi` now returns for any materialized snapshot (`DefaultIntentPipeline.kt:125`), bypassing `FallbackAiPolicy` and contradicting the model's recently-changed-code fallback. This verifies materialized gating, not propagation's AI isolation; either exercise propagation without changing fallback semantics or document materialized-only fallback.

Verdict: FINDINGS — scoped removal/prune/lookup are now correct; the materialized AI bypass is an out-of-scope behavioral regression introduced for the test.

## Round 5

1. CONCUR: the materialized early return is gone and `applyFallbackAi` again evaluates `FallbackAiPolicy` (`DefaultIntentPipeline.kt:125-126`).
2. CONCUR: dirty input with an enabled policy invokes the hook exactly once; disabled invokes zero (`FunctionPropagationTest.kt:121-152`), so production eligibility is restored.
3. CONCUR: the clean COMPLETE test uses a dirty-only policy and throwing hook (`:94-118`), and the one-call dirty assertion covers propagation isolation.
4. Minor residual: the clean test passes no `knowledgeStore`, so its throwing hook is unreachable and a policy misfire would only add an unchecked `FALLBACK_AI_UNAVAILABLE` warning. Pass an existing store or assert `errors` is empty.

Verdict: PASS — the materialized regression is fixed; only a non-blocking test-reachability weakness remains.
