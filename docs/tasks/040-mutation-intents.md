---
name: data and local mutation intents
status: done
depends-on: [036]
reviewers: [correctness, detection-fidelity]
---

## Goal

Add two mutation intents:

- `DATA_MODIFY` — a change that persists beyond the current process: database
  writes/updates/deletes, durable file writes, queue/event publication, external
  durable side effects.
- `LOCAL_VALUE_MODIFY` — mutation of a local variable, field, or in-memory
  object without persistence.

## Scope

- New `mutation` pack with two rules and per-language config for persistence
  APIs/types/qualifiers, mutable collection methods, and assignment patterns.
- Deterministic DATA_MODIFY: repository save/update/delete, ORM writes, file
  write/delete, configured durable side effects.
- Deterministic LOCAL_VALUE_MODIFY: existing write facts (assignment,
  increment/decrement) and configured mutable collection operations.
- Candidate emits for wrappers, hidden persistence, and meaningful-vs-noise
  local mutations (`aiEligible`).
- Explicit precedence with `BOOKKEEPING`: bookkeeping writes stay bookkeeping and
  do not also get painted as generic local modification unless configured.
- Overlap tests with `BOOKKEEPING`, `SEARCH`, and `SERVICE_DELEGATION`.

## Acceptance

- Configured persistence calls emit `DATA_MODIFY`; the same call shape used only
  in memory does not.
- Local assignment/mutation can emit `LOCAL_VALUE_MODIFY` without painting every
  loop counter or bookkeeping write.
- Ambiguous wrappers and mutation-via-method-call yield candidates.
- Fixtures, config, docs, and tests are added.

## Out of scope

- Full dataflow or alias analysis for every reference.
- Treating every `add`/`put`/`remove` as meaningful state change.
- Cross-service transaction semantics.

## Notes

- Persistence boundary wins over local mutation when both could apply; intents
  are still a set, so co-occurrence is allowed only when justified.
- LOCAL_VALUE_MODIFY should be candidate-heavy to avoid visual noise.

## Outcome (2026-10-21)

Implemented `MutationRulePack` with `mutation:data-modify` (`DATA_MODIFY`) and
`mutation:local-value-modify` (`LOCAL_VALUE_MODIFY`), plus `MutationConfig` for
persistence calls/paths/qualifiers/types/prefixes, mutable operations/types/
receivers, and assignment patterns. Persistence calls require durable receiver
evidence or configured paths; local writes cover declaration-local assignments
and increments/decrements while excluding bookkeeping writes and loop counters.
`MutationDataModifyRuleTest`, `MutationLocalValueModifyRuleTest`,
`MutationOverlapTest`, and `MutationConfigTest` cover classification, candidates,
overlap with SEARCH/LOOKUP and SERVICE_DELEGATION, and config. The pack is
registered in `META-INF/services/dev.cint.api.RulePack`; bundled host tests assert
both intents, and the current pipeline snapshot includes mutation spans from
`testing-grounds/kotlin/Mutation.kt`. Generic `Queue`/`Table`/`Session`
persistence typing and read-prefix ordering around configured mutation calls were
deferred to ticket 042.
