# Architecture Review — 096

Verdict: approve with minor fixes. The six groups are present and coherent;
`settings.gradle.kts:4-40` maps canonical Gradle names to grouped paths. Native
assets are under `sources/syntax-treesitter/native/`, parity data under
`tests/parity/`, tools/scripts have single homes, and no `analysis-engine/` or
`native-spike` remains. CI and just paths are updated. Repo concerns are
separated cleanly.

## Findings

1. **Lint coverage regressed.** `justfile:26,28` omits `buildSrc`; the previous
   `analysis-engine/**/src/**/*.kt` scope included it, so graph-guard Kotlin is
   no longer linted. `docs/workflow.md:68` codifies the gap. Fix: add
   `buildSrc` to both ktlint globs and the doc.

2. **Stale location statement.** `docs/tasks/README.md:11` says the single
   engine "lives in `analysis-engine/`", contradicting the root layout and all
   updated docs. Fix: say repository root (096); similarly qualify line 82.

3. **095 naming is ambiguous.** `docs/rewrite/module-graph.md:114-115` says 095
   keeps "the same `:packs` project", while
   `docs/tasks/096-engine-root-and-module-tree.md:24` and
   `docs/tasks/095-language-neutral-default-rulepacks.md:17` require
   `:default-rulepacks`. Fix: state that 095 renames both the directory and the
   Gradle project, and name `settings.gradle.kts:27` plus graph-guard/document
   edges as the touch points.

4. **Two claimed authorities.** `docs/rewrite/module-graph.md:70-73` and
   `docs/summary/architecture.md:73-75` both declare their tree authoritative.
   They match today, but the ticket asks for one authority. Fix: keep
   module-graph authoritative; have the summary point to it instead of
   restating authority.

5. **095 drift risk.** `docs/engine.md:16`, `docs/data-access-intents.md:130`,
   `docs/intent-model.md:658`, `docs/notes.md:140`, and
   `docs/service-intents.md:101` hardcode `analysis/packs/...`. Update them in
   095, or centralize the pack path so the rename is mechanical.

The layout itself supports 095: only the `:packs` `projectDir` mapping changes
physically; project-name and guard-edge changes remain 095 work.

## Round 2

CONCUR. All five findings are fixed and verified.

1. `justfile:26,30` now includes `buildSrc`; `docs/workflow.md:68` matches.
2. `docs/tasks/README.md:11-13,83-84` describes the repository-root engine.
3. `docs/rewrite/module-graph.md:114-118` states 095 renames both directory and
   Gradle project, with settings/guard/doc touch points.
4. `docs/summary/architecture.md:22-23,47-49` links to module-graph instead of
   declaring a second authoritative tree.
5. Pack docs now reference the resource relative to the `packs` module
   (`docs/engine.md:55` and peers), removing drift-prone physical paths.

No new structural issues: six groups intact, canonical names mapped, native and
parity assets correctly homed, and no `analysis-engine/` remains.
