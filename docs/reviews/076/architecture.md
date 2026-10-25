# Architecture Review — 076

Verdict: FINDINGS

1. `intent-pipeline/.../CallPropagation.kt:110-143,176-178` with `KnowledgeInputs.kt:133-145` — chain composition is a second composition engine. `CHAINING`, `DATA_TRANSFORMATION`, triggers, and confidence capping are hardcoded in the pipeline, while `module-graph.md` assigns composition to `core`; 070/084 own its semantics. Core cannot explain composed intents and 084 must pin undocumented policy. Fix: pass resolved contracts plus a chain relation to core via a contracts composition spec.

2. `CallPropagation.kt:89-108`, `KnowledgeCandidates.kt:117-174` (used `KnowledgeInputs.kt:126-130`) — propagation falls back to textual simple names without import or declaring scope. Unrelated same-named calls inherit a declaration contract, and this duplicates call resolution that 078's index/provider will supply. Fix: carry a resolved callee identity; restrict name fallback to an explicit same-file case.

3. `KnowledgeCandidates.kt:232-236,369-374` with `KnowledgeInputs.kt:101-112` — `AnnotationScope` is a tie-break rank, not a filter. `FILE`/`PROJECT` records apply to every analyzed file because no path predicate exists, so 078's repo-wide runtime will leak file-scoped knowledge. Fix: have `knowledge` resolve records applicable to a source path/scope before matching.

4. `KnowledgeCandidates.kt:238-243` duplicates `knowledge/.../DefaultPrecedence.kt:5-11`, and `KnowledgeLookup.kt:8-38` remains a second lookup. Pack-default precedence has two owners with divergent matching, the residual flagged in 075. Fix: expose one precedence/lookup helper from `knowledge` and consume it, or delete the unused path.

5. `KnowledgeCandidates.kt:71-82,341-367` — declaring types and signatures are inferred by scanning AST kind strings and regexing `node.text` for language keywords. Language syntax knowledge sits in the pipeline, violating the language-neutral boundary and risking 084 parity. Fix: have `syntax-treesitter`/index emit normalized declaring-type and signature facts.

6. `KnowledgeInputs.kt:133-152` — chain composition suppresses inner-call contributions and re-emits all intents on the root span. Provenance can no longer identify which call supplied each intent, so evidence is lost while confidence is merged. Fix: emit per-call contributions or chain evidence referencing member spans.

No new storage, adapters, IO, or AI access was added; selector identity remains in `knowledge`/contracts and matching in `intent-pipeline`, matching the graph.

Summary: the change respects module edges, adds no privileged store or AI path, and exposes provenance/confidence through existing `Contribution` fields without contract churn. But it creates pipeline-local composition and call-resolution semantics, treats scope as preference, duplicates pack-default precedence, and reads language syntax from raw text. These will couple 078's resolved call targets/repo-wide scope and 084's parity suite to pipeline heuristics; move composition and scope semantics to their owners and consume normalized facts.

## Round 2

Verdict: FINDINGS (5 CONCUR, 1 partial DISSENT, 1 new).

1. CONCUR — chain composition moved to core (`core/.../CallChainComposition.kt:14-121`, wired at `Composition.kt:67,89`).
2. PARTIAL DISSENT — same-file fallback is narrowed by declaring type/arity/signature (`CallPropagation.kt:62-110`), but no named 078 deferral for cross-file callee identity exists; record it.
3. CONCUR — scope filter (`KnowledgeInputs.kt:101-113,226-230`), FILE path validation (`KnowledgeValidation.kt:23-30`), `FunctionAnnotation.path` (`:20`).
4. CONCUR — shared precedence (`DefaultPrecedence.kt:10-54`) consumed at `KnowledgeInputs.kt:257-258`; `KnowledgeLookup` is a test-only residual.
5. CONCUR — normalized facts (`SemanticFacts.kt:15-23,171-179`; `SyntaxFactExtractor.kt:284-333,374-381`) replace pipeline AST/text heuristics.
6. CONCUR — per-call contributions retained (`KnowledgeInputs.kt:131-140`); core emits per call (`CallChainComposition.kt:89-121`).
7. NEW — `FunctionAnnotation.path` is absent from `selector.identityKey`, but duplicate validation and store keys use that key (`KnowledgeValidation.kt:54-63`; `InMemoryKnowledgeStore.kt:42,102`). Two FILE annotations for one selector in different files collide or overwrite. Add scope/path to identity or use a composite key before 078.

Round 2 verdict: FINDINGS. Round-1 boundary concerns are resolved: composition is core-owned, normalized facts replace pipeline parsing, scope filters per path, precedence is shared, and per-call evidence survives. Two narrow issues remain: the 078 cross-file callee deferral is implicit, and `path` is not part of store identity, so FILE annotations cannot coexist across files. Fix those seams before 077/078.

## Round 3

Verdict: CONCUR.

1. CONCUR — FILE identity is composite: `FunctionAnnotation.identityKey` includes scope/path (`FunctionAnnotation.kt:22-27`), and validation, store init/set/remove/prune, ordering, and fakes key on it (`KnowledgeValidation.kt:55`; `InMemoryKnowledgeStore.kt:42,63,88,102,127,304`; `FakeKnowledgeStore.kt:37,55,79,90`). `fileScopedAnnotationsWithOneSelectorCoexistAcrossPaths` proves coexistence and snapshot validation.
2. CONCUR — the 078 deferral is named and justified at `CallPropagation.kt:67-70`: same-file declaration propagation only; cross-file semantic callee identity deferred to 078's runtime/index/provider targets.

Residual: `removeFunctionAnnotation(selector)` deletes every FILE variant for that selector (`InMemoryKnowledgeStore.kt:127`), so path-aware removal is not expressible; 078/MCP should own that API extension.

Round 3 verdict: CONCUR. Both Round-2 seams are closed; no architecture findings remain for 076.
