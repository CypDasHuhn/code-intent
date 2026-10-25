# 069 Correctness Review

Verdict: FINDINGS

1. `analysis-engine-v2/packs/src/test/kotlin/dev/cint/packs/PackKnowledgeIntegrationTest.kt:24-42` (fake at `:58-133`, conversion/merge at `:123-130`). The "materialize through KnowledgeStore" test uses a local fake that itself performs the `PackDefault.toFunctionAnnotation()` conversion and merges intents, so the assertions only re-check the fake; `analysis-engine-v2/knowledge` has no implementation. This does not satisfy the 068 integration acceptance, and the fake silently merges into existing records, contradicting 068's "never overwrite project knowledge". Fix: consume the real `:knowledge` store once 068 lands and assert existing annotations survive materialization; otherwise move this test to 068.

2. `analysis-engine-v2/packs/src/main/kotlin/dev/cint/packs/defaults/DataAccessDefaults.kt:39-47` declares a `getBy*` SEARCH prefix while `:49-57` declares exact `getByKey` LOOKUP. `BundledRulePackSource.kt:65-74` only dedupes equal selector+intent pairs, so both survive and materialization merges them into `{SEARCH, LOOKUP}` for `getByKey`, violating V1's explicit-lookup precedence and token-boundary prefix behavior. The wildcard also risks matching `getBytes` if matching is not token-aware. Fix: apply exact-call precedence when building defaults and use a token-boundary pattern, with tests for `getByKey`/`getBytes`.

3. `analysis-engine-v2/packs/src/main/kotlin/dev/cint/packs/PackCatalog.kt:88-91` (with `BundledRulePackSource.kt:18-19,48-64`) silently drops degraded packs from `manifests()` and `defaults()`. Duplicate/missing/cyclic issues exist only on the concrete `catalog.degradedPacks`, not through `RulePackSource`, so an interface consumer cannot distinguish a degraded pack from one that never existed. Fix: surface degraded issues through the source API (aggregate validation error or status-bearing manifest).

4. `analysis-engine-v2/packs/src/main/kotlin/dev/cint/packs/PackDefinition.kt:29-40` validates only playbook-internal duplicate rule ids; `PackCatalog.kt:21-27` validates only pack ids. Nothing enforces global rule-id/owned-intent uniqueness or that `seeds.intents` is a subset of `playbook.rules.owns`, which is the "defaults claim unsupported intents" case. Bundled data happens to be consistent, but a malformed pack is accepted. Fix: validate seeds against playbook ownership and enforce global rule-id/owns uniqueness in `PackCatalog`.

5. `analysis-engine-v2/packs/src/main/kotlin/dev/cint/packs/BundledPacks.kt:141-147` exposes `bool-combinatorics` and `chaining` as ordinary packs, but V1's bundled descriptor (`analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`) excludes both, and `PackDefaultsTest.kt:64-87` expects their defaults. This diverges from the stated V1 parity source. Fix: model enabled/default packs or omit them from the bundled catalog.

6. `analysis-engine-v2/packs/src/main/kotlin/dev/cint/packs/BundledRulePackSource.kt:40-46` returns success with an empty list for any unknown language (`PackDefaultsTest.kt:104-110` encodes this), while blank input errors. A typo silently yields no defaults. Fix: return `InvalidInput`/`NotFound` for unrecognized languages, or document empty as intentional.

Determinism, ordering, duplicate/missing/cycle detection, and the absence of classpath scanning/ServiceLoader are sound, and bundled seed intents match their playbooks. The blocking issue is the fake-only materialization test plus the parity and validation gaps above.

## Round 2

1. CONCUR (partial) — the fake is removed; `PackDefaultContractTest.kt:29-32` is an empty `@Ignore` with a clear 068 reason, and `:knowledge` still has no sources. This is an honest placeholder and legitimate given 068 is also `todo`/depends on 065, but the acceptance is not met: add depends-on 068 or keep 069 open until it is implemented.
2. CONCUR — `bundled-packs.json:1526` uses `token-prefix:getBy;except:getByKey`; `PackDefaultsTest.kt:70-93` covers `getByKey` versus `getBy*`/`getBytes`.
3. CONCUR on diagnostics; DISSENT on behavior — `PackCatalog.kt:259-260` and `BundledRulePackSource.kt:58` now include degraded definitions, so `PackDefaultsTest.kt:213-215` asserts defaults from a missing-dependency pack. A pack whose playbook is refused should not seed knowledge; filter seeds by `issue(id) == null`.
4. CONCUR — `PackCatalog.kt:96-134,137-188` validates seed ownership, languages, known intents, and global rule/intent uniqueness; `PackCatalogTest.kt:143-185` covers them.
5. CONCUR — `bundled-packs.json:5845,5911` set `defaultEnabled:false`; `PackCatalogTest.kt:38-46` matches V1's disabled chaining/bool descriptors.
6. CONCUR — `BundledRulePackSource.kt:50-52` errors on unknown languages; `PackDefaultsTest.kt:136-147` covers this. Exact-case lookup remains but is acceptable.
7. New: `RuleSpec.config` was removed from the 065 contract (`RulePackSource.kt:53-58`), unrelated to this ticket and removing a previously stable field. Restore it or land it via an explicit contract change.

Round 2 verdict: FINDINGS — most round-1 items are fixed; degraded-pack defaults and the incomplete 068 integration remain.

## Round 3

1. CONCUR — `PackCatalog.kt:261-262` builds `availableDefinitions` and `manifests` from non-degraded packs, and `BundledRulePackSource.kt:58` iterates only `availableDefinitions`; `PackDefaultsTest.kt:213-216` verifies broken packs are absent from manifests/defaults while diagnostics and the playbook error remain.
2. CONCUR — the deliberate `RuleSpec.config` removal is now owned and recorded in the 069 ticket Notes with the 075 replacement, satisfying the contract-change concern.
3. CONCUR — `PackDefaultContractTest.kt:34-45` exercises the real `KnowledgeChange.MaterializeDefaults` wire contract with pack-produced defaults and verifies round-trip, provenance, and status. It is meaningful but limited to serialization; the `@Ignore` real-store test remains honest, and 068 integration is still the only open acceptance item.

Round 3 verdict: PASS, conditional on the tracked 068 integration.
