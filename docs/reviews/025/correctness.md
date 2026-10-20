# Correctness review — ticket 025 (config/API/output cleanup)

## Round 1

Verdict: The cleanup is behaviorally sound for the normal path. Artifact and
live annotation bodies now come from one serializer and remain byte-compatible
for finite values (same field order, null `nodeAnchor`, intent/provenance
ordering), the host config path is now a single strict `HostConfig.load`, and
the removed API/scheduler/oracle/store members have no remaining consumers. Two
contract edge cases remain: the `detectThrows` config alias was removed without
a migration record or rejection test, and the shared renderer emits non-finite
`confidence` values as invalid JSON in the live payload.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/errorhandling/ErrorHandlingRulePack.kt:60-64`, with `analysis-engine/rulepacks/src/test/kotlin/dev/cint/rulepacks/PackConfigMigrationTest.kt:46-59` and `analysis-engine/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/errorhandling/ErrorHandlingRulePackTest.kt:173-180` — removing the `detectThrows` alias silently degrades previously valid pack configs.**
   Ticket 022's outcome and its Round-4 review state that `detectThrows` is a
   supported alias for `throws`; this ticket removes the schema field but only
   deletes the alias test, replacing it with canonical-key coverage. A project
   config that still says `error-handling-kotlin: { detectThrows: false }` now
   reaches `ConfigSchema` as an unknown key, `RulePackLoader` records an
   `INVALID_PACK_CONFIG` failure, and the pack is skipped; the CLI/server keep
   running but lose all ERROR_HANDLING detection instead of reporting the old
   key. This is a config-contract break that is neither versioned nor recorded.
   Fix: document the alias collapse in the 025 outcome/migration notes and add a
   test that `detectThrows` is now rejected (or retain the alias for a
   deprecation cycle if silent detection loss is unacceptable).

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/artifact/Json.kt:34-36` with `AnnotationJson.kt:16,34` and `analysis-engine/server/src/main/kotlin/dev/cint/server/LiveServer.kt:197` — the shared renderer can emit invalid JSON for non-finite confidence.**
   `Emit.confidence` is a public `Double` and `RuleEngine.contributionsOf`
   (`analysis-engine/core/src/main/kotlin/dev/cint/core/runtime/scheduler/RuleEngine.kt:165-174`)
   validates only the intent set, so a rule pack can emit `Double.NaN` or an
   infinity. `Json.render` appends `Number.toString()` directly, yielding
   `"confidence":NaN`/`Infinity`. The old live path serialized through Jackson,
   whose default `QUOTE_NON_NUMERIC_NUMBERS` emits `"NaN"` as a valid JSON string;
   the new live path therefore turns a bad confidence into an invalid
   `cint/publishTags` line that the client cannot decode. The artifact path had
   the same raw-number behavior before this ticket, but the live contract
   regresses. Fix: reject non-finite confidence at the emit boundary, or render
   non-finite numbers as `null`/quoted strings in `Json.render`, and add a
   regression test.

### Concurs / Dissents

- Concurs: `Artifact.kt:31` and `LiveServer.kt:197` now share
  `AnnotationJson.render`; `AnnotationPayload.kt`/`AnnotationPayloadTest.kt` are
  gone, and the new serializer's field order, escaped strings, nullable
  `nodeAnchor`, provenance fields, and list ordering match the previous
  artifact/live output for normal values.
- Concurs: `HostConfig.load(path)` is now the only production config-file entry
  point; the unused language argument and `ProjectConfigLoader.load(path)` are
  gone, and missing/invalid configs still fail the CLI/server startup while
  pack-config failures remain degraded. The updated `HostConfigTest` and
  `ProjectConfigLoaderTest` exercise the new call shapes.
- Concurs: removed `Schedule`/`Scheduler`, `Emit.nodeAnchor`,
  `definitionAt`/`SymbolFact`, `LineIndex`, `dirty`/`clearDirty`, `isDegraded`,
  `configIssues`, `IntentDefinition`, `IntentConfig.intents`, `withCorePolicy`,
  and the test-only DSL have no remaining static references; `ExecutionPlan`
  carries `strata`/`relations` directly and its consumers still compile against
  the same shape.
- Concurs: bumping `CINT_API_VERSION` to 3 is appropriate for the removed public
  API surface; bundled packs derive the version from the constant.
- Dissents: findings 1 and 2 above.

### Residuals (not findings)

- `Annotation.nodeAnchor` and the `NodeAnchor` value class remain public and the
  wire key is still emitted, but no production path populates them; only
  `ArtifactTest`/`AnnotationSerializationTest` construct an anchor. Preserving
  the reserved field is consistent with the wire contract, but the "decide"
  part of the ticket should be recorded explicitly and/or pinned with a test
  that the engine/store never sets it.
- `HostConfig.load` still reports invalid project config with
  `IllegalArgumentException` (via `require`), whereas the pre-ticket path used
  `IllegalStateException` for parse issues. No in-repo caller distinguishes the
  types and the server catches `Exception`, but it is an error-path behavior
  change that is only pinned by changing the test expectation.
- The artifact's full byte layout is not pinned: `ArtifactTest` checks selected
  substrings and `LegacyParitySnapshotTest` compares annotations produced by the
  pipeline rather than `Artifact.render`, so framing/whitespace regressions in
  the artifact envelope would not be caught.
- `AnnotationJson.fields`/`contributionFields` are public but only `render`
  consumes them; the shared contract is effectively the rendered string.

No source edit, build, test, or formatter command was run by this role; the
handover was treated as green per `docs/workflow.md`.

## Round 2

Verdict: Both Round-1 findings are addressed. The `detectThrows` alias is back
in the error-handling schema with both decoding and end-to-end emission tests,
and `Json.render` now renders non-finite `Double`/`Float` values as quoted
strings so the live payload stays valid JSON. The v3 contract note and v2-pack
rejection test, the config-loader rename/internalization, the NodeAnchor
decision plus engine pin, and the privatized serializer helpers are all present.
No new correctness finding was found.

### Findings

- None. The restored `detectThrows` schema field (`ErrorHandlingRulePack.kt:60-69`)
  is exercised by `PackConfigMigrationTest.kt:61-66` and
  `ErrorHandlingRulePackTest.kt:182-190`, so existing configs no longer lose the
  pack. The `Double`/`Float` finite checks in `Json.kt:34-44` quote only
  non-finite values and leave finite output unchanged; `JsonTest.kt` pins the
  exact `"NaN"`/`"Infinity"`/`"-Infinity"` spelling. `ContractTest.kt:170-177`
  and `RuleEngineConvergenceTest.kt:38-56` pin the reserved `nodeAnchor` on
  annotations while proving emits and engine output never populate it.

### Concurs / Dissents

- Concurs: finding 1 is fixed at the schema and behavior levels. Both `throws`
  and `detectThrows` remain accepted, and the alias path is covered by a
  config-decode test and an end-to-end throw-suppression test, matching the
  contract restored by ticket 022.
- Concurs: finding 2 is fixed. `Json.render` handles `Double` and `Float`
  before the generic `Number` branch, quotes non-finite values, and the live
  and artifact paths both receive valid JSON for the same inputs. Finite
  confidence values still render identically to the previous serializer.
- Concurs: the v3 KDoc on `CINT_API_VERSION` records the removed `Emit`
  `nodeAnchor`, DSL/Predicate, and Scheduler/Schedule surface and the
  exact-equality recompile requirement; `FixtureVersionTwoPack` plus
  `versionTwoPacksAreRejectedByTheVersionThreeLoader` pins the gate.
- Concurs: `parseProjectConfigTolerant` now states the tolerant semantics, and
  `PackConfigLoader`/`LoadedPackConfig` are internal with their test renamed;
  the host still has one strict production path, `HostConfig.load`.
- Concurs: `Annotation.nodeAnchor` and `NodeAnchor` are documented as reserved
  for ticket 030, and the Annotation field is retained while `Emit.nodeAnchor`
  is absent. `AnnotationJson.fields`/`contributionFields` are now private, and
  `roleName` remains public only because the legacy-parity test reuses it.
- Dissents: none.

### Residuals (Round 2)

- `JsonTest` covers only `Double`; the newly added `Float` branch has no direct
  regression test.
- The v3 contract note now lives in the `CINT_API_VERSION` KDoc rather than the
  ticket outcome or `docs/rulepack-support-plan.md`; the code is authoritative,
  but the documentation trail remains split.
- The artifact envelope's full byte layout is still not pinned by a direct
  `Artifact.render` golden test; the existing checks remain substring-level.
- `HostConfig.load` still uses `IllegalArgumentException` for parse issues
  rather than the pre-ticket `IllegalStateException`; no in-repo caller relies
  on the distinction.

No build, test, or formatter command was run by this role; the handover was
treated as green per `docs/workflow.md`.
