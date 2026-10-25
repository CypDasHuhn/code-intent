# 069 Architecture Review

Verdict: FINDINGS

1. `packs/src/main/kotlin/dev/cint/packs/Languages.kt:3-15` (used at `BundledRulePackSource.kt:44`). Packs owns canonical language ids, the `JVM`/`WEB`/`JS_FAMILY` grouping, and normalization. Canonical ids and grouping belong to the language layer (`LanguageDescriptor`/`languages`); if it uses different ids (`cs`, `js`, `ts`), pack defaults silently miss and pack data must track detection. Fix: consume canonical ids supplied by `languages` (the already-normalized `LanguageDescriptor.id`), keep grouping there, and treat pack ids as opaque strings.

2. `BundledPacks.kt:164`, `PackDefinition.kt:29-40`. Every manifest declares `languages = Languages.ALL` regardless of actual coverage, and seed languages/intents are not validated against the manifest or playbook. Selection therefore cannot filter packs by language and packs can advertise capabilities they do not supply. Fix: derive `manifest.languages` from the seed language union; require seed languages ⊆ manifest languages and seed intents ⊆ playbook-owned intents.

3. `BundledPacks.kt:24`, `PackDefinition.kt:10-27`. Discovery is a hardcoded Kotlin list of pack-local `PackDefinition`/`PackSeedDefault`, not manifest/playbook data. This avoids classpath scanning but keeps pack content as JVM code in `packs`, requiring recompilation for authoring changes and bypassing the contract schema. Fix: ship manifests/playbooks/defaults as bundled resources parsed deterministically into contract types, leaving only validation in `packs`.

4. `contracts/src/commonMain/kotlin/dev/cint/contracts/ports/RulePackSource.kt:46`. `RuleSpec.config: Map<String, String>` is an untyped per-pack configuration surface, the shape ticket 069 removes. Fix: remove it; if a pack needs engine behavior, add a typed capability behind a contract in `core`.

5. `PackCatalog.kt:70-91`, `BundledRulePackSource.kt:18-19,48-64`. Dependency closure, cycle handling, and exclusion live in `packs`; degraded packs vanish from `manifests()`/`defaults()`, so `intent-pipeline` cannot distinguish invalid from absent and does not own selection policy. Fix: return raw content plus explicit diagnostics through the port and move closure/selection to `intent-pipeline`.

6. `BundledRulePackSource.kt:48-64`, `contracts/.../knowledge/PackDefault.kt:20`. `PackDefault.createdAt` defaults to `Clock.System.now()`, so defaults differ across source construction and can churn materialization/diff output. Fix: supply deterministic pack timestamps or exclude creation time from pack output identity.

The `packs -> contracts` edge, data-only separation, sorted discovery, and absence of ServiceLoader/classloader, rule evaluation, persistence, and per-pack config classes are sound. The remaining risks are language-taxonomy ownership, manifest/defaults alignment, data-as-code authoring, and selection policy inside packs, plus the untyped rule config map. Resolve these before `intent-pipeline` consumes the port, and add a graph test forbidding `packs` imports of `core`, `knowledge`, or `languages`.

## Round 2

1. CONCUR — `Languages.kt` is gone; `BundledRulePackSource.kt:16-18` accepts injected `knownLanguageIds` and `:50-53` matches opaque ids exactly, with no taxonomy or normalization. Residual: the default at `:16` still derives ids from manifests, so injection is optional rather than required.
2. CONCUR — `BundledPacks.kt:36,43` derives `manifest.languages` from defaults; `PackCatalog.kt:96-124` rejects unowned seed intents, undeclared seed languages, and manifest/playbook intent mismatch.
3. CONCUR — content is `packs/src/main/resources/packs/bundled-packs.json`, loaded at `BundledPacks.kt:17,22-32`; no Kotlin seed domain files remain (`PacksBoundaryTest.kt:38-46`).
4. CONCUR — `RulePackSource.kt:52-58` no longer has a `config` field.
5. CONCUR — `PackCatalog.kt:257-261` retains all manifests and exposes sorted diagnostics via `BundledRulePackSource.kt:24-25`, leaving selection policy to consumers. `playbook` remains gated at `:35-38`, which is validation rather than manifest filtering.
6. CONCUR — `BundledRulePackSource.kt:68,86-88` pins epoch timestamps; `PackDefaultsTest.kt:124-126` proves cross-construction equality.

All six findings are addressed. Residuals: bundled loading still uses JVM `Class.getResourceAsStream` (`BundledPacks.kt:24`), and the boundary test’s marker list does not cover it; acceptable for fixed resources, but it keeps `packs` JVM-bound. Selection must still honor `defaultEnabled` and diagnostics outside `packs`.
