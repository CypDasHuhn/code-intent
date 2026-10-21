# Tickets

One file per unit of work. Status is owned by the orchestrator. See
[../workflow.md](../workflow.md) for format and pipeline, and
[../architecture.md](../architecture.md) for the target design.

New work is planned in [`rulepack-support-plan.md`](../rulepack-support-plan.md).
The queue follows architecture.md §11 (sequencing): contract → scheduler →
detection as rulepacks → composition → config → delete the legacy path, with
host and client cutover at the end.

| id | name | status | depends-on | scope |
|---|---|---|---|---|
| 001 | [api module and frozen contract](001-api-module-and-frozen-contract.md) | done | — | New `api` module; `Intent`, `Relation`, `Rule`, `RulePack`, `View`, `Emit`, `Annotation`, scheduler contract; module dependency line. |
| 002 | [annotation store and provenance](002-annotation-store-and-provenance.md) | done | 001 | Structured per-contributor provenance; per-rule contribution replacement; no removal API. |
| 003 | [scheduler stratification and fixpoint](003-scheduler-stratification-and-fixpoint.md) | done | 001, 002 | Rule graph over intent currency, load errors, topological strata, internal fixpoint, terminal composition. |
| 004 | [capability-scoped view](004-capability-scoped-view.md) | done | 001 | `View` over core facts + derived helpers; `reads`-gated `annotations`/`intentsAt`. |
| 005 | [thin rule dsl](005-thin-rule-dsl.md) | done | 001, 004 | Kotlin embedded DSL over `View`; infix `eq`/`ne`/`gt`/`lt`/`and`/`or`; lambda predicates. |
| 006 | [logging rulepack](006-logging-rulepack.md) | done | 003, 004, 005 | One `logging` rule owning `LOGGING`, with `print`/`logger-interaction`/`logging-state` as internal matchers. |
| 007 | [error-handling rulepack](007-error-handling-rulepack.md) | done | 003, 004, 005 | `throw` + configured checks, receiver/qualifier gating, trailing lambdas. |
| 008 | [bookkeeping rulepack](008-bookkeeping-rulepack.md) | done | 003, 004, 005 | Declared-once property + write inheritance. |
| 009 | [validation rulepack](009-validation-rulepack.md) | done | 003, 004, 005, 008 | Early-exit guard certificate; `reads = {BOOKKEEPING}` (stratum 1). |
| 010 | [core composition terminal](010-core-composition-terminal.md) | done | 003, 004 | Coverage, scaffolds, derivative use, return category as the terminal graph node. |
| 011 | [pack loading and degradation](011-pack-loading-and-degradation.md) | done | 003 | `ServiceLoader` loading, graceful degradation, collision/unknown-dep/cycle load errors, reload = restart. |
| 012 | [typed pack config](012-typed-pack-config.md) | done | 006 | Per-pack typed `Config` + project file keyed by pack id + `core:` policy section. |
| 013 | [config migration off intents.yaml](013-config-migration-off-intents-yaml.md) | done | 006, 007, 012 | Move detection values and language overrides into pack config. |
| 014 | [delete legacy config and phase](014-delete-legacy-config-and-phase.md) | done | 010, 013 | Remove `intents.yaml`, the `Phase` enum, and `rules/Blocks.kt`. |
| 015 | [host cutover: cli and server](015-host-cutover-cli-and-server.md) | done | 003, 010, 011 | Batch CLI and live daemon publish from the new engine. |
| 016 | [renderer annotation contract](016-renderer-annotation-contract.md) | done | 002, 011 | Structured provenance, open intent names, reserved node anchor in the live contract. |
| 017 | [fixpoint metadata convergence](017-fixpoint-metadata-convergence.md) | blocked | 003 | Define bounded convergence for source/confidence changes at a stable annotation identity; evidence removed by 032. |
| 018 | [return parity for non-direct-exit guards](018-return-parity-non-direct-exit.md) | done | 009, 010 | Restore ReturnBlock parity for nested/else-edge validation returns without direct then-exit guards. |

| 019 | [rulepack semantic foundation](019-rulepack-semantic-foundation.md) | done | — | Profile semantic roles, View facts, emit builders. |
| 020 | [rulepack skeleton and config support](020-rulepack-skeleton-and-config.md) | done | 019 | Single-intent skeleton, typed config decoding, shared test harness. |
| 021 | [simplify bookkeeping pack](021-simplify-bookkeeping-pack.md) | done | 020 | Pilot semantic-fact rewrite; target <80 lines. |
| 022 | [simplify error and validation packs](022-simplify-error-and-validation-packs.md) | done | 020 | Call/guard facts; remove traversal/config boilerplate. |
| 023 | [simplify logging pack](023-simplify-logging-pack.md) | done | 020 | Call/type/config predicates; remove call extraction. |
| 024 | [core structural boundaries](024-core-structural-boundaries.md) | done | — | Resource ownership, package cycles, host boundary guard. |
| 025 | [config api and output cleanup](025-config-api-and-output-cleanup.md) | done | 019, 024 | Dead API/config removal; one serializer and config path. |
| 026 | [test topology and hermetic tests](026-test-topology-and-hermetic-tests.md) | done | 020 | Tests by ownership; hermetic fixtures/LSP. |
| 027 | [docs snapshots and tooling](027-docs-snapshots-and-tooling.md) | done | 021, 022, 023 | Truthful snapshots, docs reconciliation, CI and just recipes. |
| 028 | [renderer structural cleanup](028-renderer-structural-cleanup.md) | done | — | Split Lua monolith; live transport tests; docs config. |
| 029 | [rulepack registration consistency](029-rulepack-registration-consistency.md) | done | 023 | Uniform pack identity/selection. |
| 030 | [client-authored annotations](030-client-authored-annotations.md) | todo | — | Experimental: runtime user annotations persisted per project. |
| 031 | [size hygiene after support refactors](031-size-hygiene-after-support.md) | done | 019, 020, 021, 026, 029 | Split new EXTREME files; reduce new overcrowded dirs. |
| 032 | [remove evidence from annotation contract](032-remove-evidence-from-contract.md) | done | 019, 025 | Drop evidence from API/provenance/output/tests; API v4. |
| 033 | [renderer package layout](033-renderer-package-layout.md) | done | 028 | Group lua/cint modules into subpackages. |
| 034 | [engine module layout](034-engine-module-layout.md) | done | 024, 027 | Move Gradle modules under analysis-engine/modules. |
| 035 | [protocolized config overlay](035-protocolized-config-overlay.md) | done | 030 | Config descriptors over the live protocol plus additive user symbol overlay. |
| 036 | [AI intent candidates via secondary emits](036-ai-intent-candidates.md) | done | 035 | v5 rule output split, candidate channel, fake AI provider, review UX. |
| 037 | [normalization intent](037-normalization-intent.md) | done | 036 | `NORMALIZATION` pack and candidate triggers. |
| 038 | [search and lookup intents](038-search-lookup-intents.md) | done | 036 | `SEARCH` and `LOOKUP` data-access pack. |
| 039 | [service delegation and init intents](039-service-intents.md) | done | 036 | `SERVICE_DELEGATION` and `SERVICE_INIT` service pack. |
| 040 | [data and local mutation intents](040-mutation-intents.md) | done | 036 | `DATA_MODIFY` and `LOCAL_VALUE_MODIFY` mutation pack. |
| 041 | [annotation lock protocol hardening](041-annotation-lock-hardening.md) | todo | 036 | Fix shared annotation lock acquisition/release races. |
| 042 | [intent pack fidelity hardening](042-intent-pack-fidelity-hardening.md) | todo | 037, 038, 039, 040 | Deferred structural write targets, DI shapes, factory boundary, persistence typing, and read-prefix ordering. |
| 043 | [DeepSeek intent provider](043-deepseek-intent-provider.md) | done | 036 | Real DeepSeek-backed AI intent candidates, confirmable only. |
| 044 | [analysis oracle caching and budgeting](044-analysis-oracle-caching.md) | todo | 040 | Memoize receiver types, cache LSP results, gate/limit oracle queries. |
| 045 | [composition intent coverage and read-only semantics](045-composition-intent-coverage.md) | done | 037, 038, 040 | Propagate initializer intents, add BOOL_COMBINATORICS/DATA_TRANSFORMATION, read-only guards. |
| 046 | [structural intents: return value, chaining, local copy](046-structural-intents.md) | done | 037, 038, 040, 045 | Add RETURN_VALUE, CHAINING, LOCAL_COPY as additive structural intents. |
| 047 | [striped mixed-intent rendering experiment](047-striped-mixed-intent-rendering.md) | done | 016 | Opt-in ABABAB tag-color striping for active multi-intent spans. |
| 048 | [local mutation and callback effect coverage](048-local-mutation-callback-coverage.md) | todo | 040, 046 | Stronger mutable-local declarations and callback effect classification. |

## Resolved decisions

Pinned before implementation. They refine architecture.md where it is ambiguous
or internally inconsistent; architecture.md is not rewritten.

1. **Logging ownership** — one `logging` rule owns `LOGGING` and internally runs
   the `print`, `logger-interaction` and `logging-state` matchers. §2 (one owner
   per intent; a `reads` name resolves to its single owning rule) stays strict;
   the §4 worked example's three stratum-0 names become matchers inside one rule,
   so stratum 0 is `{logging, error-handling, bookkeeping}`. Affects 003, 006.
2. **`Relation`** — the resolved intent→rule dependency edge (the graph edge
   produced when a `reads` intent name is resolved to its owning rule). Affects
   001.
3. **Bundled rulepacks** — a dedicated `:rulepacks` module depending only on
   `:api`; `core` holds engine/scheduler/composition with no detection. Affects
   001, 006–009.
4. **`dsl` module** — folded into `api` initially (§9). Affects 001, 005.
5. **Cutover** — the legacy `rules/Blocks.kt`, `intents.yaml` and `Phase` are
   deleted in 014, after composition and config land, not at step 3. Affects 014.
6. **Rulepack discovery** — `META-INF/services` resources are parsed explicitly
   for deterministic per-entry degradation instead of `ServiceLoader` stream
   iteration. Affects 011, 015.
