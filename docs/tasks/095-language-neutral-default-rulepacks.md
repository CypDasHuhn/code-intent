---
name: Language-neutral default rulepacks
status: todo
depends-on: [096]
reviewers: [architecture, detection-fidelity]
---

## Goal

Make detection live in `default-rulepacks`, one directory per pack, with rule
code that has no knowledge of languages, symbols, or syntax. Rules operate on
neutral facts; symbol/API knowledge lives in pack defaults; language-specific
syntax normalization lives behind `languages`.

## Scope

- Rename the `packs` module to `default-rulepacks` and give every pack its own
  directory: `logging`, `error-handling`, `bookkeeping`, `validation`,
  `data-access`, `normalization`, `transformation`, `mutation`, `service`,
  `return-value`, `chaining`, `local-copy`, `bool-combinatorics`.
- Each pack directory owns its manifest, playbook, and seed defaults. Defaults
  may be language-tagged data; the pack code may not contain language ids or
  symbol tables.
- Remove the monolithic evaluator file and its hardcoded vocabularies
  (`BundledRuleEvaluators.kt`: print/logger methods, `System.out`/`console`,
  `@Inject`/`ILogger`, search/lookup prefixes, persistence calls, coalesce
  tokens, raw-text parameter/type parsing, and similar).
- Rule code may only use neutral contracts. If a rule needs a **name**, it goes
  in pack defaults; if it needs **syntax**, it goes in the language/fact layer;
  if it needs **structure**, it uses generic neutral facts.
- Extend the neutral fact vocabulary where required so packs never parse raw
  source text: resolved call target/type, declaration annotations/decorators,
  constructors and parameters, writes with target/value, guards and exits,
  coalesce/rejection/callback facts, and similar. Keep the fact contract
  language-agnostic.
- Move language-specific AST normalization and fact profiles behind
  `languages`, selected by `LanguageDescriptor.profile`. `syntax-treesitter`
  remains the parser implementation, but rulepacks never import it and never
  select a language.
- Remove legacy evaluator/version naming from the detection layer.
- Add architecture guards: fail if rulepack code contains known language ids,
  known symbol vocabularies, raw syntax token tables, or imports of
  syntax/LSP/language adapters. Fail if a pack default references an
  unregistered language or a rule that is not in the playbook.
- Prove extensibility with a data-only language case: a new language descriptor
  plus defaults must be enough to run existing packs without changing rule code.
- Keep the frozen-corpus parity gate green. Regenerate the committed parity
  report and baselines when detection data changes; any intentional behaviour
  change must be explicit.

## Acceptance

- `default-rulepacks` exists with one directory per pack; the old `packs`
  module and `BundledRuleEvaluators.kt` are gone.
- No rulepack code contains language names, API/symbol names, or raw syntax
  token tables; guards enforce this.
- All symbol/API knowledge is data in pack defaults, and all language syntax
  knowledge is in the language/fact layer.
- Adding or removing a language requires only descriptors and defaults, not
  rule code.
- Build, engine tests, lint, graph guard, parity, and smoke pass; the parity
  report shows no unregistered regressions.

## Out of scope

- New intents, new detection semantics, or new languages.
- Reworking the core scheduler/composition beyond the facts needed here.
- Fallback AI behavior, renderer changes, or protocol changes.

## Notes

- This is a correction of the current design where manifests are labels for a
  hardcoded evaluator catalog, and symbol knowledge is duplicated in code.
- Keep the refactor parity-driven: move behaviour into data/facts first, then
  delete the corresponding code. Do not rewrite detection from scratch.
- Depends on 096 so the module is created in its final repository location.
