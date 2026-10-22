---
name: intent pack fidelity hardening
status: done
depends-on: [037, 038, 039, 040]
reviewers: [correctness, detection-fidelity]
---

## Goal

Resolve the detection-fidelity gaps deferred from the normalization, data-access,
service, and mutation integration pass. The packs are registered and composed;
this ticket tightens target selection, constructor-injection recognition, service
versus factory boundaries, and persistence/read classification without adding
new intent names.

## Deferred findings

### 038: structural increment/decrement targets

`data-access:lookup` currently excludes write targets with a text-based `+`/`-`
filter in `DataAccessRulePack.writeTarget`. That misses structurally complex
targets such as:

```kotlin
counts[i - 1]++
statusById["x-y"]++
```

The `-` inside the index expression is mistaken for the decrement operator, so
the write target is not recognized and the index read can still establish
`LOOKUP`. Select the increment/decrement target from syntax/write facts rather
than scanning target text for operator characters, and keep nested reads inside
the target (`translationTable[statusById[id]] = value`) classified as lookup
reads.

### 039: remaining service-injection shapes and factory boundary

- Kotlin annotation-qualified constructors such as
  `class Foo @Inject constructor(private val service: UserService)` are still
  missed; only the plain primary-constructor parameter shape is recognized.
- TypeScript parameter properties such as
  `constructor(private readonly service: UserService)` are missed because the
  non-C# constructor path only accepts Kotlin-style `val`/`var` property
  parameters.
- `Factory` is part of the shared `ServiceLanguageConfig.serviceSuffixes`. As a
  result a thin `*Factory` call can establish `SERVICE_DELEGATION` even though
  factory construction belongs to `SERVICE_INIT`. Keep factory construction in
  init and require explicit delegation evidence before treating a factory call as
  service delegation.

Extend `constructorInjectionEvidence` (and the supporting parameter parsing) to
cover both shapes, and separate factory/init suffixes from delegation suffixes or
gate them per rule.

### 040: persistence typing and read-prefix ordering

- Generic persistence type names `Queue`, `Table`, and `Session` in
  `DEFAULT_PERSISTENCE_TYPES` misclassify in-memory uses. For example a
  `java.util.concurrent.BlockingQueue` receiver is established as `DATA_MODIFY`
  solely from the `Queue` suffix. Persistence must be proven by a durable
  qualifier/type/package or configured call, not by a generic collection or
  session type name alone.
- `MutationRulePack.candidate` applies the `READ_CALL_PREFIXES` filter before
  configured-call matching. This hides configured read-prefixed mutation calls
  and wrappers, including `findOneAndUpdate` and `findOneAndDelete` in the
  JavaScript/TypeScript defaults. Check configured persistence calls/prefixes
  before rejecting a call as read-shaped, while keeping genuine reads such as
  `findByStatus` and `count` out of established `DATA_MODIFY`.

## Acceptance

- Increment/decrement of `counts[i - 1]` and `statusById["x-y"]` produces no
  `LOOKUP` for the assigned index target, while reads in the same index
  expression remain eligible.
- Kotlin `@Inject constructor` properties and TypeScript `constructor`
  parameter properties with service types establish `SERVICE_INIT`.
- Construction/creation through `*Factory` establishes `SERVICE_INIT`, not
  `SERVICE_DELEGATION`; thin non-construction factory calls require explicit
  delegation evidence.
- In-memory `BlockingQueue`/`Queue`, `Table`, and `Session` uses do not establish
  `DATA_MODIFY` without durable proof; configured `findOneAndUpdate`/
  `findOneAndDelete` and configured read-prefixed mutation wrappers are
  classified as mutations, while true reads stay out.
- Per-language fixtures/tests cover each regression, overlap with SEARCH/LOOKUP
  and SERVICE_INIT/SERVICE_DELEGATION, and the existing registration and
  snapshot tests remain green.
- `just build`, `just test-engine`, and `just lint` pass.

## Out of scope

- Whole-program dataflow, alias analysis, or full ORM/library semantic models.
- Treating every generic collection operation as persistence.
- New intent names, new packs, or scheduler/contract changes.
- Broad constructor recognition without a configured service/init type.
- Remote AI provider work; candidate behavior stays deterministic.

## Notes

Files and symbols to check against the merged code:

- `analysis-engine/core/rulepacks/.../intents/dataaccess/DataAccessRulePack.kt`
  (`writeTarget`, `knownMapIndexes`).
- `analysis-engine/core/rulepacks/.../intents/service/ServiceRulePack.kt`
  (`constructorInjectionEvidence`, parameter parsing) and
  `ServiceConfig.kt` (`COMMON_SERVICE_SUFFIXES`, `serviceSuffixes`).
- `analysis-engine/core/rulepacks/.../intents/mutation/MutationRulePack.kt`
  (`PersistenceMatch.established`/`candidate`, `READ_CALL_PREFIXES`) and
  `MutationConfig.kt` (`DEFAULT_PERSISTENCE_TYPES`).

Keep data-access reads and mutation writes separate: a structural target is a
write concern and should not become `LOOKUP`; a genuine read inside a write
expression is still a read. Prefer configured evidence over generic type-name
suffixes, and add negative tests alongside the positive ones.
