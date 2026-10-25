# Architecture review — ticket 070

Verdict: FINDINGS

1. `analysis-engine-v2/core/src/main/kotlin/dev/cint/core/rule/Rule.kt:13-20` with `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/ports/RulePackSource.kt:34-47` — the SPI is not bindable from packs. `Rule` takes a core `RuleView`, while `RuleSpec` carries only `config: Map<String, String>` and no matcher/detection vocabulary. Because `packs -> contracts` only, packs must either import core (graph violation) or 075 must implement detection, duplicating core evaluation and interpreting opaque config keys as language/pack knowledge. Fix: move the rule SPI (or a narrow binder/factory port) into contracts with a typed matcher/plan schema, and add a test that binds a 069 playbook through 075 without packs importing core.

2. `analysis-engine-v2/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:31-34,587-630,702-721` — core embeds source-language syntax: assignment operators, `null`/`undefined`/`false`/`0`/`-1`, quote/bracket sentinels, numeric and identifier tests, plus raw-text parsing in `assignmentValue`/`writeTargetName`. This is language-specific detection and parser-like logic in the kernel. Fix: have adapters emit neutral facts (operator/value role, literal kind, rejection flag) and consume those; remove operator and sentinel tables from core.

3. `analysis-engine-v2/core/src/main/kotlin/dev/cint/core/composition/Composition.kt:205-454` — core hardcodes adapter identities `"treesitter"` and `"cfg"` as contribution sources. The terminal must name no rule or pack, and core must not know which adapter supplied facts. Fix: carry source identity with facts/config from the pipeline or emit neutral provenance; do not fabricate adapter names in composition.

4. `analysis-engine-v2/core/src/main/kotlin/dev/cint/core/view/RuleView.kt:22,55-65,108-117` — capability scoping is incomplete. `facts` is public, and `covering`, `containing`, and `coverage` expose annotations/intents without applying `reads`/`optionalReads`. A pack rule can therefore observe undeclared intents and bypass the scheduler graph. Fix: keep facts behind narrow accessors and route annotation-derived accessors through the visible-intent projection (or make them non-public); add a two-pack undeclared-read test.

The core-to-contracts edge holds, no filesystem/network/AI/knowledge access appears, established and candidate outputs are cleanly separated, and annotation-store/terminal-composition ownership is placed in core as intended. The blocking architecture risks are the uninhabitable rule SPI and language/adapter details leaking into composition, both of which will force 069/075 to violate the graph or duplicate detection. The capability view needs tightening before pack rules can be trusted. Fix the SPI and neutral-fact boundary first; the remaining findings follow from those two decisions.

## Round 2

Verdict: FINDINGS (one residual)

1. DISSENT — `contracts/.../ports/RuleEvaluation.kt:62-71` and `RulePackSource.kt:41-47`: moving the SPI to contracts is justified (port plus DTOs, no JVM types) and core aliases it (`core/.../rule/Rule.kt:7-15`). But `RuleSpec` still carries only opaque `config`, and `RuleBindingTest.kt:24-38` binds a hand-supplied core-local evaluator map, not a 069 playbook through 075. Data-only packs still cannot express detection, and evaluator ownership is undefined. Add a typed matcher plan or registry port and bind from intent-pipeline.

2. CONCUR — neutral `WriteFact`/`ValueFact`/`StatementFact` (`SemanticFacts.kt:32-62`) replace operators and sentinels (`Composition.kt:271-274,488-490,635-648`). Residual: `normalizeAnnotationText` (`Composition.kt:689-707`) still encodes Kotlin use-site targets; move it to the language adapter.

3. CONCUR — `COMPOSITION_SOURCE` and `Provenance.DERIVED` replace adapter names (`Composition.kt:31,229,737`).

4. CONCUR — facts and covering/containing are private, and annotations/coverage/intentsAt project through `visible` (`RuleView.kt:20,52-57,64-117`).

The contract SPI is the right home and adds no wrong module edge; neutral facts, provenance, and capability scoping are fixed. Only the uninhabited pack-plan binding remains, plus the Kotlin-specific annotation normalizer.

## Round 3

Verdict: CONCUR — all Round-2 findings resolved; deferrals are legitimate.

1. CONCUR — `RuleEvaluation.kt:81-108` adds `RuleEvaluatorRegistry` and `bindRulePlan`, failing with `NotFound` for unbound specs and `ValidationError` for spec mismatch; `RuleBindingTest.kt:22-74` exercises both. KDoc at `:77-80` assigns registry construction, evaluator selection, and 069 binding to 075 and corpus parity to 084, so evaluator ownership is explicit. Matcher vocabulary remains 069/075 plan scope; no graph edge is added.

2. CONCUR — the use-site-target regex is gone (`Composition.kt:735-742`); the remaining normalization is documented as adapter-owned and deferred to 072 (`:733-735`).

3. CONCUR — neutral `COMPOSITION_SOURCE` and `Provenance.DERIVED` remain (`Composition.kt:31,737`).

4. CONCUR — capability scoping remains private and visibility-projected (`RuleView.kt:20,52-57,64-117`).

Contracts gains a port and pure helpers only; core still imports contracts alone, and packs remain contracts-only. No wrong dependency is introduced.
