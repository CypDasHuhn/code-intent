# Architecture review — ticket 011 (pack loading and degradation)

## Round 1

Verdict: The loader is in the right module and uses JVM `ServiceLoader` without a
runtime compiler, a new classloader, or hot-swap machinery. The module line is
otherwise intact: `core` owns discovery and validation, and `rulepacks` still
depends only on `api`. Seven architecture gaps remain: `BundledRulePacks` is a
second, unvalidated loading path; the version check cannot detect the incompatible
binaries it is meant to reject; the loader validates one rule snapshot and returns
the mutable source packs; corruption in rule metadata escapes graceful degradation;
pack ids are not unique; scheduler conflicts are resolved by an unspecified lexical
winner; and the `ServiceLoader` fallback instantiates providers twice. Host wiring
is legitimately deferred to 015, but these are the semantics that cutover will
inherit.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/BundledRulePacks.kt:6-8` — bundled and third-party packs do not share one validated loading path.**
   `packs()` calls `ServiceLoader` directly and returns raw providers, while
   `RulePackLoader` is the only path that applies API-version, duplicate, missing
   dependency, cycle, and per-pack degradation handling. Any caller of
   `BundledRulePacks.packs()` therefore bypasses the ticket's semantics, and it uses
   the `rulepacks` classloader rather than the loader's context classloader, so a
   third-party pack visible to the host is not necessarily visible to it. The
   `rulepacks` service file is also empty, so no bundled registration is exercised
   by a test. Core is the module that owns loading (§9), not `rulepacks`.
   Suggested fix: make `RulePackLoader` the sole discovery path. Remove
   `BundledRulePacks` (or reduce it to a non-loading marker) and have hosts/015 use
   `RulePackLoader` for both bundled and third-party packs. Add a `rulepacks`-module
   test that a bundled provider listed in `META-INF/services` is discovered through
   the same loader and produces the same result as a synthetic third-party pack.

2. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Contract.kt:6`, `Model.kt:7`, and `core/.../rulepack/RulePackLoader.kt:80-95,204-233` — the API-version check does not reliably reject an incompatible binary.**
   The version is read through a newly added default interface property after the
   provider has already been constructed. A pack compiled against a previous
   contract does not implement `apiVersion`: depending on the interface-method mode
   it either silently inherits the current `CINT_API_VERSION` and passes, or the
   call fails with `AbstractMethodError` and is classified `CORRUPT_PACK`, not
   `INCOMPATIBLE_API_VERSION`. Similarly, a pack whose constructor or static
   initializer references a removed/newer API member fails inside
   `ServiceLoader.next()` before the version can be read and is also reported as
   corrupt. The named mismatch failure only covers a pack that successfully
   instantiates and voluntarily reports a different number.
   Suggested fix: carry the contract version in metadata that can be read without
   constructing the provider (for example a JAR manifest attribute or a
   ServiceLoader-adjacent descriptor), check it before instantiation, and define
   the compatibility rule. Add fixtures for a provider with no `apiVersion`
   implementation and a provider whose constructor references a missing member,
   asserting they degrade as incompatible rather than crashing or being
   misclassified.

3. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:67,339-347` — the loader validates a cached rule snapshot but returns the mutable source packs, so the validated set is not the set that executes.**
   `settle` plans against `LoadedRulePack.cachedRules`, but `load()` returns
   `loaded.map { it.source }`. A later `RuleEngine.plan`/`analyze` calls
   `pack.rules()` again (`RuleScheduler.kt:95-96`), re-enumerating the unvalidated
   source. A pack that returns a different list, or throws, on a second call can
   therefore reintroduce a duplicate/unknown/cycle error after a "successful" load,
   or execute rules that were never checked. `id`, `languages`, and `apiVersion` are
   likewise delegated live rather than snapshotted.
   Suggested fix: return the wrapper/materialized record (with cached `id`,
   `languages`, `apiVersion`, and rules) from `load()`, and make both planning and
   execution consume that same immutable snapshot. Add a fixture whose `rules()`
   returns a different or broken list on a second call and prove the loaded result
   is stable.

4. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:176-249,252-294` — graceful degradation does not cover rule metadata or `languages`.**
   `validate` guards only `pack.id`, `pack.apiVersion`, and `pack.rules()`. The
   scheduler then reads `rule.id`, `rule.owns`, and `rule.reads`
   (`RuleScheduler.kt:95-120`) outside any loader `try`, while `settle` catches only
   `SchedulerException`. A corrupt or Java-interop pack whose rule getter throws or
   returns null, or whose `languages` getter throws when the host queries it, crashes
   `load()`/the host instead of being reported and skipped. Rule ids are also only
   checked for uniqueness, not for §3's `<packId>:<localName>` shape, so a pack can
   register a rule under another pack's namespace.
   Suggested fix: materialize and validate all pack and rule metadata inside the
   per-pack `try` (id, languages, version, rules, and each rule's id/owns/reads,
   including non-blank and pack-prefixed ids), rethrowing only `VirtualMachineError`.
   Treat an unexpected throwable from planning as a `CORRUPT_PACK` for that pack, and
   add fixtures for throwing/null rule metadata and throwing `languages`.

5. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:41-46,70-75,176-204` — duplicate pack ids are accepted.**
   Nothing checks that `RulePack.id` is unique. Two providers with the same id but
   disjoint rule ids both load, which makes the pack id an ambiguous config key
   (§8), collapses `RulePackLoadFailure.packIds` diagnostics, and leaves
   replacement/upgrade behavior between a bundled and a third-party copy of the same
   pack undefined.
   Suggested fix: add a named duplicate-pack-id failure, define the resolution policy
   (reject both, or deterministically skip a duplicate and report it), and test two
   packs sharing an id with disjoint rule names.

6. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:303-326` — scheduler load errors are downgraded to an unspecified lexical winner.**
   `DuplicateIntentOwner` and `DuplicateRuleId` keep whichever rule/pack sorts first,
   and `DependencyCycle` drops the lexicographically greatest pack. No pack or rule is
   "corrupt" in those cases, so this silently changes detection: a third-party pack
   whose rule id sorts before a bundled pack can displace the bundled detector, and
   the engine continues with a different intent owner than the architecture's
   collision-is-a-load-error rule implies. The behavior is deterministic but not
   specified, and it is especially sensitive to bundled/third-party symmetry.
   Suggested fix: define an explicit conflict policy in the ticket/architecture
   (fail the load, skip every participating pack, or give packs an explicit,
   documented precedence), implement it deterministically, and test a
   bundled-vs-third-party collision in both pack orders.

7. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:77-134` — one bad service entry re-instantiates every valid provider.**
   If any `ServiceConfigurationError` occurs, `serviceLoaderProviders()` discards all
   providers already obtained and `manualProviders()` re-reads the files and
   constructs every provider again. A single corrupt entry therefore runs the
   constructors of valid packs twice, which can duplicate side effects and retain
   resources in addition to the intended classloader. Constructors being pure is not
   part of the plugin contract.
   Suggested fix: load entries one at a time with per-provider failure isolation
   (for example `ServiceLoader.stream()` where possible, or a single safe
   enumeration/instantiation path), never retry providers that already loaded, and
   test that constructors of providers preceding a corrupt entry run once.

### Concurs / Dissents

- Concurs: the loader sits in `core`, imports only `api` plus scheduler types, and
  contains no detection logic; `:rulepacks` remains api-only, and the change does
  not move the core/rulepack line.
- Concurs: no runtime Kotlin compiler, no new classloader, and no hot-reload
  machinery is introduced. The default uses the context classloader, the test
  preserves it, and a pack change remains a daemon restart.
- Concurs: for a pack that does instantiate and self-report, a mismatched
  `apiVersion` is checked before its rules are enumerated, reported with a named
  failure, and skipped while other packs remain.
- Concurs: the 003 scheduler errors are surfaced with named load-failure kinds,
  discovery is sorted deterministically by pack id and class name, and the tests
  cover missing, corrupt, mismatched, duplicate-owner, duplicate-rule-id,
  reserved-intent, unknown-dependency, and cycle paths.
- Concurs: host integration is intentionally left to 015; the absence of a
  `RulePackLoader` consumer is not itself a finding, but the parallel
  `BundledRulePacks` path should not survive cutover.
- Dissents: findings 1-7 above.

### Verified

- No build, test, or formatter command was run by this role; the handover was
  treated as green per `workflow.md`. The review is limited to the uncommitted diff,
  the new loader/tests/service resources, and the adjacent scheduler and host code.

## Round 2

Verdict: F1-F7 are substantively addressed. There is now one loading path, an
explicit version contract checked before construction for old and annotated
providers, immutable snapshots, rule/pack identity validation, deterministic
skip-all conflict handling, and a single `ServiceLoader` stream pass. One new
architecture finding remains: the per-provider failure boundary still does not
cover every way a corrupt provider can fail during discovery/validation.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:78-143,291-306` — provider class-loading and raw rule-list failures can still escape `load()`.**
   The new pass isolates `ServiceConfigurationError` from `iterator.hasNext()` /
   `next()`, but `ServiceLoader`'s classpath iterator only wraps
   `ClassNotFoundException`; a `NoClassDefFoundError` or other `LinkageError`
   raised while loading the provider class (for example a missing superclass or a
   missing type referenced by the class) propagates out of `iterator.next()`.
   The loader catches that class of error only around `provider.type()`,
   `provider.get()`, and `pack` validation, so it crashes instead of reporting the
   pack as corrupt. Similarly, `validateRules` iterates `rules` and casts each
   element to `Rule` before entering the per-rule `try` block; a Java provider
   returning a raw list containing a non-`Rule` throws `ClassCastException` out of
   `validateRules` and out of `load()`. `type.getMethod("getApiVersion")` also
   catches only `NoSuchMethodException`, so a reflection `SecurityException` or
   linkage failure at that point escapes.
   Suggested fix: give each provider one outer `Throwable` boundary covering
   discovery through validation (rethrowing only `VirtualMachineError`), safely
   materialize the rule list with element type checks, and catch `Throwable` around
   the reflection probe. Add a fixture whose provider class itself has a missing
   superclass/type (not a constructor-thrown error, which is already covered) and
   a Java fixture returning a raw list with a non-`Rule` element.

### Concurs / Dissents

- F1: concurs — `BundledRulePacks` is removed and the `rulepacks` test discovers a
  bundled provider through `RulePackLoader` with the shared classloader, so there
  is one validated discovery path. The production service file remains empty until
  006-009 register real packs, which is expected for this ticket; the test resource
  exercises the registration shape.
- F2: concurs — the interface no longer supplies a default getter, the loader
  rejects a provider that inherits `getApiVersion` from `RulePack` before
  construction, and `@RulePackApi` metadata is checked before `provider.get()`.
  Residual: the annotation is optional, so an unannotated future-version pack is
  still constructed before its version value is read; if its constructor depends
  on missing API it will be reported as corrupt rather than incompatible. Making
  the annotation (or equivalent descriptor) mandatory would close that last
  pre-construction gap.
- F3: concurs — `load()` returns the cached `LoadedRulePack`/`LoadedRule` wrappers,
  `rules()` is materialized once, and the stateful-rules test proves the returned
  snapshot is stable across repeated planning. Residual: the wrapper now hides the
  original provider instance, so a future pack-owned config (§8) will need the
  wrapper to retain or delegate to that provider.
- F4: concurs — pack languages, pack/rule ids, owned intent, and read sets are all
  materialized inside guarded validation, namespacing is enforced, and unexpected
  scheduler throwables are converted to corrupt-pack failures. The new finding
  above covers the remaining unguarded discovery/validation edges.
- F5: concurs — duplicate pack ids are rejected together with a named
  `DUPLICATE_PACK_ID` failure, and the test uses two providers with disjoint rule
  names. Config keyed by pack id is no longer ambiguous.
- F6: concurs — duplicate-owner, duplicate-rule-id, and cycle failures now skip
  every participating pack rather than keeping a lexical winner. The two
  pack-order cases for duplicate ownership and the cycle fixture pin the
  deterministic behavior; the all-participants policy is explicit and
  order-independent.
- F7: concurs — the fallback is gone; one `ServiceLoader` stream pass preserves
  already-loaded providers, and the counting fixture proves a valid constructor
  runs once when a later entry is missing. The class-loading escape in the new
  finding is distinct from the fixed retry behavior.
- Concurs: the no-hot-reload and no-new-classloader claims still hold; the test
  preserves the context classloader and the production code creates no loader.
  Residual: `RulePackLoadResult.failures` retains `cause` and `schedulerError`
  references, which can keep a skipped pack's classes reachable if a host retains
  the result for the daemon lifetime; this is not a loader-created leak, but it
  should be documented or stripped before 015 stores the result.
- Residual: the new bundled-loading test lives in `:rulepacks:test`, which
  `just test-engine` does not include (`just build` does run `:rulepacks:build`).
  If the ticket's stated acceptance is the test-engine command alone, add
  `:rulepacks:test` there or move the assertion into a module that command covers.
- Dissents: the new finding above.

## Round 3

Verdict: F8 is addressed. `discoverProvider` now has one per-provider
`Throwable` boundary covering type lookup, reflection, construction, and
validation; `discover` catches class-loading failures from the iterator; raw rule
lists are checked element by element; the new regression tests cover
`NoClassDefFoundError`, `UnsupportedClassVersionError`, raw list entries, and a
persistent resource failure; and `just test-engine` now includes `:rulepacks:test`.
One new architecture finding remains: the new discovery bound is keyed to error
text rather than iterator progress, and configuration failures still terminate all
discovery.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:77-125,513-516,584` — the discovery failure bound can stop legitimate discovery early and does not bound a non-advancing iterator that alternates errors.**
   `canContinueAfter` keys failures by `javaClass.name + message`, only checks
   failures from `iterator.hasNext()`/`next()`, and never resets its key/count
   after a provider is successfully consumed. The bound is therefore not a
   progress bound: nine distinct corrupt providers that fail with the same
   `LinkageError` message (or one corrupt provider interleaved with valid packs
   enough times) can exhaust the count and make the loader stop, dropping valid
   packs that appear later. The doctrine is "reported and skipped; the rest
   analyze," so a finite classpath with several identically failing packs must not
   lose unrelated packs. Conversely, a non-advancing iterator/classloader that
   alternates error messages resets `lastFailureKey` each time and is never
   bounded. Separately, `isConfigurationFailure` breaks the whole discovery loop
   on any `IOException`, so one unreadable service resource prevents providers in
   later resources from being discovered.
   Suggested fix: bound failures/attempts since the last successfully consumed
   provider (or last observed iterator progress), reset the budget on progress,
   and keep a total no-progress cap as a backstop. Treat a single unreadable
   service resource as a reported, skipped resource within that budget rather than
   an unconditional end to discovery. Add regression tests with more than eight
   same-message corrupt providers interleaved with a valid pack, an iterator that
   throws alternating errors without advancing, and a broken service resource
   followed by a valid resource.

### Concurs / Dissents

- F8: concurs — the per-provider boundary in `discoverProvider` covers
  `provider.type()`, annotation and reflection lookup, `provider.get()`, and
  `validate`; class-load failures from the iterator are caught in `discover`; and
  `validateRules` checks `List<*>` elements before use. The new tests exercise the
  previously unguarded class-loading and raw-list paths.
- Concurs: the bundled-loading test is now part of `just test-engine` via
  `:rulepacks:test`, so the bundled/third-party symmetry check runs in the
  declared acceptance command.
- Concurs: F1-F7 remain as recorded in Round 2; the per-provider boundary and raw
  list validation do not weaken the single loading path, immutable snapshot,
  identity checks, or deterministic skip-all conflict policy.
- Residuals from Round 2 remain, none re-reported as new findings: `@RulePackApi`
  is optional before construction, the cached wrapper hides the source provider
  for future pack-owned config, failure objects retain causes/scheduler errors
  that can keep skipped pack classes reachable, and the production `rulepacks`
  service file is empty until 006-009 register real packs.
- Dissents: the discovery-bound finding above.
- No build, test, or formatter command was run by this role; the handover was
  treated as green per `workflow.md`.

## Round 4

Verdict: The Round 3 discovery-bound finding is addressed. The loader now counts
consecutive failures without a yielded provider, resets the count on iterator
progress, treats only the classloader-wide "Error locating configuration files"
failure as fatal, and skips per-resource `IOException`s so later resources are
still inspected. The new regressions cover twelve identical class-loading
failures followed by a valid pack, alternating no-progress errors, and a broken
resource followed by a valid one. No new architecture finding remains.

### Findings

- None.

### Concurs / Dissents

- Concurs: the discovery budget is no longer keyed to error text. Any yielded
  provider resets `consecutiveNoProgressFailures`, so distinct corrupt providers
  with identical messages no longer stop later packs; the alternating-failure
  test proves a non-advancing iterator terminates within the bound.
- Concurs: `isConfigurationFailure` is narrowed to the classloader-wide locating
  failure, while `Error accessing configuration file` is reported as a corrupt
  pack and discovery continues to later service resources. The unreadable-
  resource and persistent-failure tests pin both paths.
- Concurs: the Round 3 per-provider Throwable boundary and raw-list validation
  remain intact, and `:rulepacks:test` remains part of `just test-engine`.
- Concurs: F1-F7 remain as recorded in Round 2; the change does not alter the
  single loading path, immutable snapshots, identity checks, or deterministic
  skip-all conflict policy.
- Residual, not a finding: the 64-failure budget is a safety ceiling, so a
  pathological classpath with more than 64 consecutive provider-load failures
  still drops later valid packs; the `manyIdentical...` regression exercises 12
  failures, not the boundary. This is a bounded tradeoff rather than a new
  contract violation. The Round 2 residuals (optional pre-construction
  annotation, wrapper hiding the source provider, retained failure causes, and
  the empty production `rulepacks` service file until 006-009) also remain
  unchanged.
- Dissents: none.
- No build, test, or formatter command was run by this role; the handover was
  treated as green per `workflow.md`.

## Round 5

Verdict: The manual loader preserves the classpath `META-INF/services` discovery
shape, the per-provider validation/degradation boundary, and the no-new-
classloader/no-compiler/no-hot-reload properties. It is, however, a deliberate
deviation from the explicit ticket/§9 requirement that loading use the JVM
`ServiceLoader`, and the new enumeration has dropped the bounded no-progress
safeguard from Round 4. The deviation is defensible only as an explicitly
recorded implementation choice; as written it is not yet a contract-equivalent
replacement.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:80-163,165-238` — the loader no longer uses JVM `ServiceLoader`, contrary to the ticket and architecture §9, and its manual provider grammar is not fully equivalent.**
   `providerNames` reads `META-INF/services/dev.cint.api.RulePack` through
   `classLoader.getResources` and `discoverProvider` instantiates named classes with
   `Class.forName` plus `getDeclaredConstructor().newInstance()`. This preserves the
   classpath service-file format and the observable happy path, but it is not the
   JVM `ServiceLoader` the ticket names; no deviation is recorded in the ticket or
   architecture. The manual path also narrows the classpath provider-name grammar
   (`PROVIDER_NAME` accepts only ASCII identifiers, while `ServiceLoader` accepts
   Java identifier characters including Unicode) and uses declared rather than
   public-constructor lookup, so it is not a transparent substitute for all
   third-party packs.
   Suggested fix: either restore `java.util.ServiceLoader` behind the per-provider
   isolation wrapper, or record an explicit architecture/ticket decision to use a
   service-file-compatible loader and state its supported classpath subset
   (public no-arg constructor, grammar, duplicate handling, no module providers).
   Align the manual grammar with the JVM service format and add a regression for a
   non-ASCII provider name.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:98-151` — the one-pass resource enumeration has no progress or size bound, regressing the Round 4 liveness safeguard.**
   `providerNames` loops over `resources.hasMoreElements()` and `useLines` without
   any failure/attempt counter. It catches a throwing enumeration and continues,
   and the new tests cover 80 finite malformed lines/resources, but a
   non-terminating `getResources` enumeration that keeps returning elements, or a
   resource with an unbounded/huge set of lines, makes the loader hang or consume
   memory before any provider is validated. The previous alternating/no-progress
   regression test is gone, so the new path has no test that an infinite
   enumeration terminates.
   Suggested fix: retain a progress/failure budget while enumerating resources and
   lines (or cap the number of resources/lines), reset it only on real progress,
   and add an infinite-enumeration regression alongside the finite 80-resource
   case. Per-resource `IOException` skipping can keep its current behavior.

### Concurs / Dissents

- Concurs: per-provider validation and degradation are preserved. Each provider is
  still class-loaded, version-checked, constructed, and validated in isolation;
  malformed names/resources are reported rather than aborting all later packs;
  duplicate pack-id rejection, immutable snapshots, and skip-all scheduler
  conflicts are unchanged.
- Concurs: no runtime compiler, no new classloader, no TCCL replacement, and no
  hot-reload machinery were introduced; `Class.forName` uses the configured
  classloader, and the bundled/third-party test still exercises the shared path.
- Concurs: the Round 4 fatal-configuration and per-resource-`IOException`
  behaviors remain, and `:rulepacks:test` remains in `just test-engine`.
- Concurs: F1-F7 and the Round 3/4 findings remain addressed; the manual loader
  does not change the API surface, snapshot semantics, or scheduler conflict
  policy.
- Residuals from Round 2 remain, not re-reported as new findings: optional
  pre-construction annotation, wrapper hiding the source provider, retained
  failure causes, and the empty production `rulepacks` service file until
  006-009.
- Dissents: findings 1 and 2 above.
- No build, test, or formatter command was run by this role; the handover was
  treated as green per `workflow.md`.

## Round 6

Verdict: The liveness bound is addressed and the Unicode provider-name fix now
matches the JVM classpath service-file grammar. I concur with the manual
`META-INF/services` loader as a justified engineering deviation for deterministic
per-resource/per-provider degradation: it preserves the classpath discovery
contract, provider isolation, and the no-compiler/no-classloader/no-hot-reload
properties. I dissent only on leaving the normative contract unrecorded: the
ticket and architecture still state that loading uses JVM `ServiceLoader`.

### Findings

1. **`docs/tasks/011-pack-loading-and-degradation.md:10,16` and `docs/architecture.md:228` — the approved loading deviation is still absent from the normative docs.**
   The implementation deliberately reads
   `META-INF/services/dev.cint.api.RulePack` itself, but both the ticket goal/scope
   and architecture §9 still require JVM `ServiceLoader`. The deviation is
   defensible on its merits (deterministic per-resource parsing and per-provider
   degradation are not cleanly expressible with the plain `ServiceLoader`
   iterator), and the implementation now handles the classpath subset well, but
   without a recorded decision the repository has two contradictory contracts and
   future tickets cannot tell whether the manual loader is intentional or a
   regression. This role cannot edit docs.
   Suggested fix: have the scribe/orchestrator record the deviation in the ticket's
   outcome or a README resolved decision, and in architecture §9 if the decision is
   intended to outlive this ticket. State the supported subset explicitly:
   classpath `META-INF/services` only, Java-identifier provider names (now
   Unicode-compatible), public no-arg provider constructors, no module-provider
   factory methods, and the resource/line limits. If that decision is not approved,
   restore `ServiceLoader` instead.

### Concurs / Dissents

- Concurs on the deviation: the manual parser is now behaviorally close to the
  classpath `ServiceLoader` subset, `isValidProviderName` uses
  `Character.isJavaIdentifierStart`/`Part` and handles supplementary code points,
  and the Unicode regression passes. It retains the configured classloader and
  per-provider validation/degradation.
- Concurs on the liveness bound: `MAX_SERVICE_RESOURCES` and
  `MAX_SERVICE_LINES` bound both dimensions, `DISCOVERY_LIMIT_EXCEEDED` names the
  limit failure, the infinite-resource regression terminates, and per-resource
  `IOException`/malformed-line handling still skips and continues.
- Concurs: no runtime compiler, no new or replaced classloader, no TCCL change,
  and no hot-reload machinery were introduced; `:rulepacks:test` remains in
  `just test-engine`.
- Concurs: per-provider version/rule validation, immutable snapshots, duplicate
  pack-id rejection, deterministic skip-all scheduler conflicts, and F1-F7 remain
  unchanged from the prior rounds.
- Residual, not a finding: the line limit counts lines, not bytes, so a single
  unterminated or enormous line can still allocate past the line-count guard; this
  is relevant only to adversarial resource streams, not ordinary JAR service
  files. The resource/line caps are heuristics and can drop providers after the
  limit, as intended for a bounded loader. Round 2 residuals (optional
  pre-construction annotation, wrapper hiding the source provider, retained
  failure causes, and the empty production `rulepacks` service file until 006-009)
  also remain unchanged.
- Dissents: finding 1 only, on contract documentation; I concur with the
  implementation deviation itself.
- No build, test, or formatter command was run by this role; the handover was
  treated as green per `workflow.md`.
