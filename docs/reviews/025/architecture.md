# Architecture review — ticket 025 (config/API/output cleanup)

## Round 1

Verdict: The cleanup is directionally correct and most of the intended surface is
gone. Artifact and live output now share one annotation serializer, the language
shim and duplicate config-file entry point are gone from `HostConfig`, the dead
API/scheduler/oracle/store members are removed, and the API version was bumped
for the breaking removals. The remaining gaps are about making the new
boundaries explicit: the v3 API boundary has no contract note, the "one config
loader" is one production path but still two public parsing/loading surfaces, the
`NodeAnchor` decision is implicit and leaves a test-only model field, and the
`detectThrows` alias removal is an unrecorded pack-config contract change.

### Findings

1. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Model.kt:7`, deleted
   `Dsl.kt`/`Scheduler` surface, `analysis-engine/api/src/main/kotlin/dev/cint/api/Contract.kt:109-118` —
   the breaking API bump to v3 has no contract note.**

   `CINT_API_VERSION` moved from 2 to 3 while the diff removes the public
   `Dsl`/`Predicate` types and the `Scheduler`/`Schedule` types, and removes
   `Emit.nodeAnchor`. This is a real compatibility break: `RulePackDiscovery`
   compares the declared version for exact equality, so every v2 pack is
   rejected and must be recompiled. Ticket 019 established the convention of a
   "Contract note (v2)" explaining exactly this kind of break; ticket 025 has no
   equivalent note or outcome text, and the acceptance criterion allows
   deliberate versioning only if it is recorded. Fix: add a v3 contract note to
   this ticket (and/or `docs/rulepack-support-plan.md`) listing the removed API
   surface, the exact-equality gate, and the required pack recompile. Keep the
   artifact/live schema separate from this note: it is preserved, not versioned.

2. **`analysis-engine/host/src/main/kotlin/dev/cint/host/HostConfig.kt:34-41`,
   `analysis-engine/core/src/main/kotlin/dev/cint/core/config/ProjectConfig.kt:34-40`,
   `analysis-engine/host/src/main/kotlin/dev/cint/host/ProjectConfigLoader.kt:15-24` —
   there is still more than one public config entry point, with different
   semantics and a misleading name.**

   `HostConfig.load` is the strict, all-or-nothing path used by CLI/server, but
   `parseProjectConfig` remains public in `core` and returns a usable
   `ProjectConfig` alongside `issues`, so any core consumer can bypass the
   strictness and run on a partially parsed config. `ProjectConfigLoader` no
   longer loads project config at all; it loads packs from a `ProjectConfig`, so
   its name now describes the old boundary. The strict/tolerant split is also
   undocumented: project-config issues are fatal, while pack-config failures are
   degraded through `RulePackLoadFailure`. Fix: make the host the only path that
   reads a config file (move the parser there, or rename the core function to
   `parseProjectConfigTolerant` and make its issues impossible to ignore), and
   rename `ProjectConfigLoader` to something like `PackConfigLoader` or fold it
   into `HostPipeline`. Record the intended split: project config is strict;
   pack config is tolerant/degraded.

3. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Model.kt:22-25,40-50`,
   `analysis-engine/core/src/main/kotlin/dev/cint/core/artifact/AnnotationJson.kt:23` —
   the `NodeAnchor` decision is not actually recorded and leaves a test-only
   public field.**

   Removing `Emit.nodeAnchor` removes the only production writer, but
   `Annotation.nodeAnchor` and the `NodeAnchor` value class remain public and
   are now populated only by `ArtifactTest`/`AnnotationSerializationTest`; the
   serializer still always emits the key. Architecture §10 and ticket 016 say
   the wire field is intentionally reserved until occurrence persistence, so
   keeping the wire key is defensible, but the ticket says "decide/remove" and
   the decision is nowhere stated. As it stands the model field is dead public
   API that invites accidental population. Fix: state the decision explicitly
   (wire key reserved, model field unused until occurrence persistence) and pin
   it with a test that the engine/store never populates the anchor; or remove
   `Annotation.nodeAnchor` and hardcode `null` in `AnnotationJson` while keeping
   the reserved wire field.

4. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/errorhandling/ErrorHandlingRulePack.kt:60-64` —
   removing the `detectThrows` alias is a pack-config contract change with no
   migration record or rejection test.**

   The alias was deliberately kept through ticket 022 (its outcome says
   "`detectThrows` remains an alias for `throws`"), and review 022 deferred the
   collapse to 025. Removing it means existing project configs using
   `detectThrows` now hit the strict schema as an unknown key; because pack
   config failures are degraded, the effect is a skipped/partly configured pack
   rather than a loud project error. The deleted alias test was not replaced by
   one asserting the new rejection. Fix: document the collapse as a deliberate
   config-contract change in the 025 outcome/migration notes and add an
   assertion that `detectThrows` is now an unknown key. If config compatibility
   is meant to be as deliberate as API compatibility, this needs the same
   versioning story.

5. **`analysis-engine/core/src/main/kotlin/dev/cint/core/artifact/AnnotationJson.kt:10,27` —
   the new shared serializer exposes schema-building helpers that no consumer
   uses.**

   `fields` and `contributionFields` are public but only `render` uses them;
   `Artifact` and `LiveServer` use only `render`. That is dead public surface
   introduced by the cleanup. Make them private (or internal and test through
   `render`, as the new tests already do) so the shared contract is the rendered
   output, not a mutable `Map<String, Any?>` construction API.

### Concurs / Dissents

- Concurs: the artifact and live paths now use one annotation serializer.
  `Artifact.kt:31` and `LiveServer.kt:197` both call `AnnotationJson.render`,
  `AnnotationPayload.kt`/`AnnotationPayloadTest.kt` are gone, and the live path
  embeds the same bytes through `RawValue`. The emitted field order, the always
  present nullable `nodeAnchor`, provenance, roles, intents, and spans match the
  previous wire shape, so the existing artifact/live contracts are preserved.
- Concurs: the API/scheduler dead-surface removal is clean. `Scheduler`/
  `Schedule` are gone and `ExecutionPlan` now owns `strata`/`relations`
  directly; `definitionAt`/`SymbolFact`, `LineIndex`, `dirty`/`clearDirty`,
  `isDegraded`, `configIssues`, and `HostSettings.issues` have no remaining
  consumers. Removing the stale `RawAnnotation` assertion in
  `PublicEngineSurfaceTest` is appropriate.
- Concurs: `IntentDefinition`, `IntentConfig.intents`, `withCorePolicy`, and the
  `HostConfig.load(path, language)` overload are gone; `IntentConfig` is now only
  language + purity annotations, and CLI/server call the single
  `HostConfig.load(path)`. The language argument was genuinely unused for config
  selection, so dropping it does not change per-language behavior.
- Concurs: bumping `CINT_API_VERSION` to 3 is the right response to removing
  `Emit.nodeAnchor`, the DSL, and the scheduler types; the exact-equality loader
  gate is consistent with the v2 policy.
- Dissents: none beyond the findings above.

### Residuals (not findings)

- `AnnotationJson` lives in `core/artifact` but now owns the live wire schema
  too. That is acceptable because `server` already depends on `core`, but the
  package name still reads as batch-artifact-only; a comment in the package or a
  rename to a shared output package would make the ownership clearer.
- `Json.render` is a generic `Any?` writer with a `toString` fallback. It is one
  serializer today, but the schema remains an untyped map, so a future enum or
  value-class field could silently render through `toString` rather than through
  the annotation schema. The rendered-output tests are the only guard.
- The pack-config alias removal is the only config change in the diff that can
  break an existing user file; project-config strictness itself is unchanged
  except for the exception type/message.

## Round 2

Verdict: All five Round 1 findings are addressed, and the follow-up changes fit
the intended boundaries. The v3 break is now documented at the constant and
pinned by a rejected-v2-pack test, the config entry points are named for their
strict/tolerant roles, the `NodeAnchor` reservation is documented and guarded at
both the API and engine level, the `detectThrows` alias is restored rather than
silently broken, and the serializer helpers are private. No new findings.

### Resolution of Round 1 findings

1. **API v3 note — concurs.** `Model.kt:7-13` documents the removed
   `Emit.nodeAnchor`, DSL/Predicate and Scheduler/Schedule surface, the
   exact-equality gate, and the recompile requirement;
   `RulePackLoaderConfigTest.versionTwoPacksAreRejectedByTheVersionThreeLoader`
   pins that a v2 pack is rejected and a v3 pack loads. Putting the note on the
   frozen constant is at least as discoverable as the historical task-doc
   contract notes.
2. **One config path — concurs.** `parseProjectConfig` is now
   `parseProjectConfigTolerant` (`ProjectConfig.kt:40`), making the tolerant
   parser explicit; `HostConfig.load` remains the strict production path
   (`HostConfig.kt:37-41`); and `ProjectConfigLoader` is now the internal
   `PackConfigLoader` (`PackConfigLoader.kt:15`), so it no longer claims to load
   project config. `HostPipeline` consumes it internally and the tests use the
   renamed helper.
3. **NodeAnchor decision — concurs.** `Model.kt:28-31,61` states that the field
   is reserved for client-authored annotations, that the engine never populates
   it, and that serializers keep emitting the key. `ContractTest` pins
   `NodeAnchor` on `Annotation` and its absence from `Emit`, and
   `RuleEngineConvergenceTest.engineNeverPopulatesReservedNodeAnchor` pins the
   engine path. The reserved wire contract and the model field now have one
   explicit meaning instead of being implicit dead surface.
4. **`detectThrows` — concurs.** The alias is restored in
   `ErrorHandlingRulePack.kt:65-69`, `PackConfigMigrationTest` again pins alias
   decoding, and `ErrorHandlingRulePackTest` covers both `throws` and
   `detectThrows` controlling throw emission. Restoring a compatibility alias is
   a valid resolution of the earlier finding because it avoids breaking existing
   configs; the duplicate schema spelling is now an intentional compatibility
   surface rather than an accidental leftover.
5. **Serializer helpers — concurs.** `AnnotationJson.fields` and
   `contributionFields` are private (`AnnotationJson.kt:10,27`); the rendered
   output remains the only public serializer contract.

### Residuals (not findings)

- The strict/tolerant split is now clear from the names and call paths but is
  still not written down; the 025 outcome should state "project config issues are
  fatal; pack config failures are degraded" so the next config change does not
  have to rediscover it.
- `HostConfig.load(null)` still duplicates the null branch already handled by
  `parseProjectConfigTolerant(null)`. Harmless, but it is a second default path
  rather than one call.
- `Json.render` now quotes non-finite doubles (`"NaN"`, `"Infinity"`) to keep
  the output valid JSON, which matches Jackson's live-server behavior. If a rule
  ever emits a non-finite confidence, the wire type becomes a string; that is a
  model-validation concern rather than a serializer concern.
- Retaining the `detectThrows` alias keeps two keys mapped to one property. If
  it is eventually removed, it should be treated as the same kind of deliberate
  config-contract change the v3 note now documents for the pack API.
