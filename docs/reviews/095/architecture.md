# Architecture Review — 095 Language-neutral default rulepacks

Verdict: layout, module edges, and profile routing are correct. The neutrality
claim is not yet enforced: pack code still parses raw source syntax.

## Verified

- `settings.gradle.kts:6,27` maps `:default-rulepacks` to
  `analysis/default-rulepacks`. `index.json` lists 13 packs and every directory
  has `manifest/playbook/defaults/rules.json`.
- `default-rulepacks` main imports only `contracts`; `graph-guard.json` matches,
  and root `check` depends on `graphGuard`/self-test. Old `:packs`,
  `BundledRuleEvaluators.kt`, and `bundled-packs.json` are gone.
- Profiles flow from `LanguageDescriptor.profile` through
  `LanguageSupportFactory.kt:245`; neutral facts grew appropriately
  (`FactBatch.kt`, `SemanticFacts.kt`).

## Findings

1. **Rule code still parses raw/source-specific syntax (high).**
   `RuleEvaluators.kt:1000` rewrites Kotlin `::` in pack code although
   `ParameterFact.referencedPaths` is already normalized in the fact layer
   (`SyntaxFactExtractor.kt:665`); `:1010` strips a C-style `;` from raw text;
   `:1014` and `:628-632` regex-parse type strings; `:129` matches
   `condition.text`. Fix: consume `referencedPaths`/`calleeTokens` and add
   neutral type/trim facts, moving `::`, `;`, and type tokenization behind
   `syntax-treesitter`.

2. **The monolithic evaluator was renamed, not removed (high).**
   `RuleEvaluators.kt` is one 1014-line `ConfiguredRuleEvaluator` with a
   `when(config.kind)` over 14 string kinds (`:61-77`) plus a 62-field
   `RuleConfig` (`RuleConfig.kt:11-74`) — the deleted
   `BundledRuleEvaluators.kt` shape. Split evaluators per pack/kind, or compose
   rule ops, so acceptance is structural rather than name-based.

3. **Guards are a weak denylist (medium).** `PacksBoundaryTest.kt:41-89` checks
   only hardcoded quoted language/symbol strings, missing finding 1. Its
   registered-language list (`:115-123`) is duplicated and already omits `c`
   from `TreeSitterLanguages.kt:22-72`. Derive ids from authoritative data
   (via contracts, since pack code cannot depend on `languages`) and reject
   `SyntaxNode.text`/regex parsing in pack sources.

4. **Extensibility proof is partial (medium).** `DataOnlyLanguageTest.kt:14-43`
   adds `rust` only to a manifest/default and resolves a spec; it registers no
   descriptor/profile and runs no pipeline. Rule vocabularies are also not
   language-tagged (e.g. Kotlin/C#/JS symbols share `logging/rules.json`), so a
   new language can still require rule-data edits. Add descriptor + fact batch
   + pipeline coverage.

5. **Stale live docs (low).** `docs/engine.md:16,55`, `docs/notes.md:140`,
   `docs/intent-model.md:658`, `docs/service-intents.md:101`, and
   `docs/data-access-intents.md:130` still cite the `packs` module and
   `packs/bundled-packs.json`. Update to the new module/per-pack layout.

## Round 2

CONCUR — the prior structural findings are substantially resolved.

- **No raw parsing.** Pack main sources contain no `.text`, `Regex`,
  `substring`, `split(`, or `tokenize(`; normalization moved to
  `RuleMatching.kt` and `SyntaxFactExtractor.kt` (`trimmedDeclarationEnd`,
  `typeNames`, `conditionNames`).
- **No monolith.** `RuleEvaluators.kt`/`RuleConfig.kt` are deleted; each pack
  owns a typed evaluator under `packs/*` with `PackRegistry.kt` dispatch.
  `PackSupport.kt` is shared emission plumbing only.
- **Guards improved.** `PacksBoundaryTest.kt:12-35` is an import allowlist and
  `:77-91` reads authoritative ids from `TreeSitterLanguageRegistry`.
  Residual: `:37-55` is still a marker denylist, and the test now adds a
  `default-rulepacks -> languages` edge in `graph-guard.json` (test-only).
- **Extensibility.** `LanguageNeutralDetectionTest` runs the real pipeline on
  rust facts/defaults; the descriptor is not routed through
  `LanguageSupportFactory`, so the parser/profile path stays simulated.
- **Docs.** Stale `packs`/`bundled-packs.json` references are fixed.
- **New (medium).** `kind` is dead data: typed evaluators ignore it, yet every
  `rules.json` retains it; `PackRegistry.kt:46-51` silently returns no
  evaluators for unregistered packs (`chaining`/`bool-combinatorics`
  `kind:none`). Drop the discriminator or fail explicitly.
