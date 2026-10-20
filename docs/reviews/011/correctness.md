# Correctness review — ticket 011 (pack loading and degradation)

## Round 1

Verdict: The happy path is sound — `ServiceLoader` discovery, deterministic
ordering, per-pack version/rules validation, named surfacing of the 003 scheduler
errors, and skip-the-broken-pack degradation all work for well-behaved packs, and
the change introduces no runtime compiler, no new classloader and no hot-swap
machinery. Seven correctness gaps remain: the result returns the unvalidated
source packs instead of the validated rule snapshot; the API-version check is
non-binding for packs that rely on the new default; corrupt rule metadata and
`languages` escape the loader; pack/rule identity contracts are not checked;
collision winners are chosen by an unspecified lexical order; the
`ServiceConfigurationError` fallback re-instantiates valid providers and
misclassifies linkage failures; and `BundledRulePacks` is a second, unvalidated
loading path.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:67` and `:339-347` — the validated snapshot is discarded on return.**
   `settle` plans against `LoadedRulePack.cachedRules`, but `load()` returns
   `loaded.map { it.source }`. Every later consumer therefore calls the original
   `pack.rules()` again: `RuleScheduler.plan` does so at
   `analysis-engine/core/src/main/kotlin/dev/cint/core/scheduler/RuleScheduler.kt:95-96`,
   and the new tests themselves plan `result.packs`
   (`RulePackLoaderTest.kt:63,90,143`). A pack whose `rules()` is stateful, returns
   a different set, or throws on its second call passes validation, then
   reintroduces a duplicate-owner/unknown-dependency/cycle error (or crashes
   analysis) after the loader reported success. `id`, `languages` and `apiVersion`
   are likewise delegated live rather than snapshotted. This makes the loader's
   degradation guarantee non-binding and its cached-rule wrapper pointless.
   Fix: return the materialized wrappers (with `id`, `languages`, `apiVersion` and
   rules all cached) and make planning/execution consume that same immutable
   snapshot. Add a fixture whose second `rules()` call returns a conflicting list
   or throws, and prove `load()`'s result is stable and executable.

2. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Contract.kt:6`, `Model.kt:7`, `RulePackLoader.kt:206-231`, `ContractTest.kt:46` — the API-version check cannot detect the stale packs it exists for.**
   The default getter is compiled into the `api` module and returns that module's
   `CINT_API_VERSION` (the current class literally returns constant `1`). A pack
   that does not override `apiVersion` therefore has no version of its own: under a
   newer `api` it inherits the newer value and always satisfies
   `version == expectedApiVersion`. A pack compiled against an older contract that
   lacks the property will also inherit the current default and pass, or fail with
   `AbstractMethodError`/`NoSuchMethodError` and be classified `CORRUPT_PACK`. The
   named `INCOMPATIBLE_API_VERSION` path is reachable only for a pack that
   instantiates successfully and voluntarily overrides the number (as
   `FixtureMismatchedPack` does). `rulePackDefaultsToCurrentApiVersion` cements
   this vacuous behavior.
   Fix: make the packed version declarative rather than inherited — require an
   explicit, compile-time-baked `override val apiVersion`, or carry the target
   version in pack metadata (manifest/descriptor) read without constructing the
   provider — and define the compatibility rule. Add a fixture that omits/overrides
   the version and assert it degrades as incompatible rather than silently
   passing or being labelled corrupt.

3. **`RulePackLoader.kt:233-249` and `:252-292`, `RuleScheduler.kt:95-120` — corrupt rule metadata and `languages` escape graceful degradation.**
   `validate` guards only `pack.id`, `pack.apiVersion` and the call to
   `pack.rules()`; it never reads each `Rule.id`/`owns`/`reads`, and `settle`
   catches only `SchedulerException`. The scheduler then reads that metadata
   outside any loader `try`; a getter that throws (or a Java-interop rule that
   returns null) propagates an arbitrary exception out of `load()` instead of being
   reported and skipped. `pack.languages` is never validated either, and the
   `LoadedRulePack` wrapper delegates it live (`:343-347`), so a corrupt pack can
   crash the host when it enumerates languages.
   Fix: materialize and validate all pack and rule metadata inside the per-pack
   `try` (blank/unique ids, `owns`/`reads`, `languages`), rethrowing only
   `VirtualMachineError`; treat an unexpected throwable while planning as
   `CORRUPT_PACK` for the owning pack. Add fixtures for throwing/null rule
   metadata and a throwing `languages`.

4. **`RulePackLoader.kt:175-249`, `RuleScheduler.kt:95-120` — pack and rule identity contracts are not enforced.**
   §3 requires globally unique rule ids of the shape `<packId>:<localName>`, and
   §8 keys config by pack id, but the loader accepts two providers with the same
   pack id (disjoint rules both load) and accepts a rule id that is not prefixed
   by its pack (`"logging"` or another pack's `"other:logging"`). The scheduler
   checks only global uniqueness, so provenance/`Annotation.rule` can report a
   namespace that does not own the rule, and 012's pack-id-keyed config and the
   failure `packIds` become ambiguous.
   Fix: reject duplicate pack ids and rule ids not matching
   `^<packId>:.+$` with a named failure, and add tests for both.

5. **`RulePackLoader.kt:303-326` — collision losers are chosen by an unspecified lexical order, changing detection.**
   For duplicate owners/rule ids, `skip` keeps the first entry under `ruleOrder`
   and drops the rest; because rule ids start with the pack id, the lexically
   smaller pack wins. For a cycle, the lexicographically greatest pack is dropped.
   A third-party pack with a small id can therefore displace a bundled detector
   (or be displaced by one) and the engine continues with a different intent
   owner. §2 calls an owner collision a load error; here it becomes an arbitrary
   winner with only a failure entry. The outcome is deterministic but not
   specified, and it depends on pack naming rather than on bundled/third-party
   precedence.
   Fix: state the conflict policy (skip every participating pack or fail the load,
   or give bundled packs documented precedence), implement it deterministically,
   and test a bundled-vs-third-party collision in both orders.

6. **`RulePackLoader.kt:70-71,77-134` (classification at `:118`) — the fallback re-instantiates valid providers and misclassifies linkage failures.**
   Any `ServiceConfigurationError` makes `serviceLoaderProviders()` return `null`,
   discarding providers already obtained, and `manualProviders()` re-reads every
   name and constructs every provider again. Constructors are not required to be
   pure, so a single corrupt entry doubles side effects and resource retention for
   valid packs. The fallback also labels `NoClassDefFoundError` as
   `MISSING_PROVIDER`, although the provider class is present and its dependency is
   missing; that is a corrupt/broken pack, not an absent provider class.
   Fix: isolate failures per provider (`ServiceLoader.stream()` or one safe
   enumeration/instantiation pass), never retry providers that already loaded, and
   report the original `ServiceConfigurationError`. Classify only
   `ClassNotFoundException` as `MISSING_PROVIDER`; treat linkage failures as
   `CORRUPT_PACK`.

7. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/BundledRulePacks.kt:6-8` — bundled packs have a second loading path that bypasses all of the above.**
   `packs()` calls `ServiceLoader.load(...).toList()` directly, so version checks,
   validation, duplicate/cycle handling and graceful degradation are all skipped; a
   corrupt bundled provider throws `ServiceConfigurationError` out of `packs()`.
   It uses `BundledRulePacks`' defining classloader rather than the loader's context
   classloader, creates fresh provider instances on every call, and its service
   file is empty, so no bundled registration is exercised by any test. A host that
   combines this with `RulePackLoader` can instantiate the same bundled pack twice
   as distinct rule instances.
   Fix: make `RulePackLoader` the sole discovery path (host/015 calls it with the
   host classloader) and remove or reduce `BundledRulePacks` to non-loading
   metadata. Add a test that a bundled provider in `META-INF/services` is found
   through the same loader as a synthetic third-party pack.

### Concurs / Dissents

- Concurs: discovery is deterministic (sorted by pack id then class name) and pack
  order cannot change the plan; the loader surfaces each 003 scheduler error with a
  named kind and keeps the remaining packs.
- Concurs: `validate` checks the version before enumerating rules, so a
  self-declared mismatch is skipped without invoking `rules()`; the missing,
  corrupt, throwing-constructor and mismatch fixtures exercise the intended
  degradation.
- Concurs: `settle` terminates because every retry removes at least one pack, and
  the identity maps correctly avoid `Rule.equals`/`hashCode` semantics when
  attributing scheduler errors to packs.
- Concurs: the classloader claim holds for this ticket. `defaultClassLoader()`
  prefers the context classloader, `load()` neither replaces the TCCL nor creates a
  loader, and the `ServiceLoader`/provider instances are locally scoped, so there
  is no static classloader retention. The test's compiler-absence and
  TCCL-unchanged assertions are a reasonable documented check (residual: they do
  not exercise repeated loads or loader disposal, and finding 7 uses a different
  loader than the loader path).
- Concurs: no production caller exists yet; host integration is legitimately
  deferred to 015. The defects above are in the semantics that cutover would
  inherit.
- Dissents: findings 1-7 above.

### Residuals

- Even with the fixes above, version compatibility is only as good as the declared
  metadata; an `api` compatible change can keep the same version, so the number is
  a coarse gate rather than a proof of binary compatibility.
- `RulePackLoaderTest.loadingUsesNoRuntimeCompilerOrClassLoaderReplacement`
  depends on the core test `META-INF/services` file for `result.packs` to be
  non-empty, so a broken test-service resource would make the classloader check
  fail for an unrelated reason.
- The empty `rulepacks` service file is harmless until 006-009 register real
  bundled packs, but until then `BundledRulePacks.packs()` still returns an empty
  list and nothing proves bundled registration keeps working.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 2

Verdict: F1-F7 are addressed and their new tests exercise the intended paths:
the loaded result is now an immutable cached snapshot, `apiVersion` is explicit
with a pre-construction annotation check, all pack/rule metadata is read under
guards, pack ids and `<packId>:` rule ids are enforced, scheduler conflicts skip
all participants, discovery is a single `ServiceLoader.stream()` pass, and
`BundledRulePacks` is gone. Two new error-path gaps remain in the stream loop:
a class-loading `LinkageError` escapes `discover`, and the retry has no progress
guarantee for a persistent configuration error. I concur on F1-F7 and dissent on
F8-F9.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:75-96` (and the unguarded `getMethod` at `:123-135`) — class-loading linkage errors escape `discover` instead of being reported and skipped.**
   The iterator loop catches only `ServiceConfigurationError` around
   `hasNext`/`next`. `ServiceLoader` converts a missing provider *class* into
   `ServiceConfigurationError`, but its `Class.forName(cn, false, loader)` can also
   throw `NoClassDefFoundError` (missing superclass/interface/dependency),
   `UnsupportedClassVersionError` (pack built for a newer class-file version), or
   another `LinkageError`; those are not wrapped and propagate through
   `ServiceLoader`'s iterator to `load()`. The `provider.type()`/`get()` guards at
   `:93-156` are too late: the failure happens while the iterator is producing the
   provider. The fixtures cover only a `NoClassDefFoundError` thrown from the
   constructor (`FixtureLinkageFailurePack` at `RulePackFixtures.kt:235-243`,
   which `ProviderImpl.get()` wraps into `ServiceConfigurationError`), not a class
   that fails to load. A pack with an unresolved supertype or a newer bytecode
   version therefore crashes the daemon instead of degrading.
   Fix: catch `Throwable` around the iterator calls and `type.getMethod`
   (rethrow only `VirtualMachineError`), classify `NoClassDefFoundError` and other
   `LinkageError` as `CORRUPT_PACK` (keeping `MISSING_PROVIDER` for an absent
   class name), report and continue. Add a fixture/classloader that throws
   `NoClassDefFoundError` or `UnsupportedClassVersionError` while loading the
   provider class and assert `load()` returns a degraded result.

2. **`RulePackLoader.kt:75-96` — `continue` on a `ServiceConfigurationError` has no progress guarantee.**
   The loop assumes every caught error consumed an entry. Per-entry errors
   (missing class, illegal line, wrong type) do advance, but a failure while
   locating or reading the service resource does not. For example, a classloader
   whose `getResources` persistently throws `IOException` makes `ServiceLoader`
   report the same configuration error on every pass (JDK 21+ stores it as
   `nextError`, rethrows from `next()`, then retries the same failing lookup), so
   `load()` spins forever. The previous fallback terminated by reporting
   `CORRUPT_PACK`; the replacement regressed this path. A corrupt classpath entry
   or a broken loader can therefore hang the daemon rather than degrade.
   Fix: stop discovery (reporting the failure) on a `hasNext` error rather than
   continuing, and only retry `next` failures when progress is observable (or cap
   repeated identical failures). Add a classloader fixture whose `getResources`
   always fails and assert `load()` returns a degraded result rather than hanging.

### Concurs / Dissents

- F1: concurs — `load()` returns the `LoadedRulePack` wrappers; id, languages,
  apiVersion and rules, and each rule's id/owns/reads, are cached
  (`RulePackLoader.kt:526-549`), while `LoadedRule.run` only delegates.
  `loadedSnapshotIsStableWhenSourceRulesChange` confirms `rules()` runs once and
  repeated planning is stable.
- F2: concurs — `RulePack.apiVersion` is abstract and
  `ContractTest.rulePackApiVersionMustBeDeclaredByThePack` pins the absence of a
  default; `@RulePackApi` is checked before `provider.get()`, a provider without
  a declared getter is rejected via `declaringClass == RulePack`, and
  `annotatedApiMismatchIsRejectedBeforeConstruction` proves zero constructions.
- F3: concurs — `languages`, `rules()`, and each rule's id/owns/reads are
  materialized under per-pack guards and cached; `settle` also catches non-VM
  `Throwable`. The throwing-languages, throwing-rule-metadata and
  out-of-namespace fixtures cover the new paths.
- F4: concurs — duplicate pack ids are rejected together, and rule ids must be
  `<packId>:<localName>`; the duplicate-pack and unnamespaced/foreign fixtures
  pin both.
- F5: concurs — duplicate owners, duplicate rule ids and every cycle participant
  are skipped as a set, both provider orders for the duplicate-owner case are
  tested, and the remaining packs replan. The policy of dropping every
  participating pack (including a pack's unrelated rules) is intentional.
- F6: concurs — discovery is one `ServiceLoader.stream()` pass with per-provider
  failure isolation; `validProvidersBeforeABadEntryAreConstructedOnce` and
  `linkageFailureIsCorruptNotMissingProvider` pin the non-duplication and
  constructor-linkage paths. F8 is a separate class-loading escape, not the old
  fallback defect.
- F7: concurs — `BundledRulePacks` is removed and
  `BundledRulePackLoadingTest` loads a bundled provider through the shared
  `RulePackLoader`; the production `rulepacks` service file remains empty until
  006-009, which is expected.
- Concurs: the classloader claim still holds — no runtime compiler, no new or
  replaced classloader, TCCL preserved, and no static loader retention.
- Dissents: F8 and F9 above.

### Residuals (Round 2)

- The `@RulePackApi` pre-construction check is opt-in: an unannotated pack whose
  `apiVersion` is wrong is still constructed before `validate` rejects it. The
  property is explicit and required, so correctness is preserved, but the
  "pre-construction" guarantee applies only to annotated packs.
- Returning wrappers hides the concrete provider from the host. Ticket 012's
  typed pack config must be exposed through the `RulePack` contract (or the
  loader must retain/forward a provider handle), otherwise casting
  `result.packs` to a pack class will fail.
- `MISSING_PROVIDER` classification for a constructor-time
  `ClassNotFoundException`/`NoClassDefFoundError` walks the cause chain/message;
  a provider that is present but cannot resolve a dependency can be labelled
  missing. This is diagnostic only and does not affect skipping.
- `RulePackLoaderTest.loadingUsesNoRuntimeCompilerOrClassLoaderReplacement` still
  depends on the core test service file for a non-empty result.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 3

Verdict: F8 and F9 are addressed. Class-loading/linkage failures now have a
per-provider `Throwable` boundary and are reported as `CORRUPT_PACK`; the
persistent configuration failure terminates on the first lookup; raw rule-list
entries are rejected; and `just test-engine` now runs `:rulepacks:test`. Two new
correctness gaps remain in the bounded discovery handling: the repeated-failure
budget is keyed on error text and is neither progress-aware nor reset on success
(it can drop later valid packs and still spin on alternating errors), and
`isConfigurationFailure` treats any `IOException`-caused configuration error as
fatal, aborting discovery on a single unreadable service resource. I concur on
F8-F9 and dissent on F10-F11.

### Findings

1. **`analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:78-90`, `:92-121`, and `:584` — the repeated-failure budget is keyed on error class/message, not on progress.**
   `canContinueAfter` increments only when the same class+message repeats; it
   resets when a different error appears but never resets after a provider loads
   successfully. Two consequences:
   - A run of identical failures from *different* providers (for example several
     packs compiled for a newer JVM, all failing with
     `UnsupportedClassVersionError("class file version 99.0")`, or several packs
     missing the same shared type with the same `NoClassDefFoundError` message)
     trips the cap after the ninth failure and `break`s, dropping every later
     valid provider even though each failure advanced the iterator and the rest
     could have loaded. Interleaved successful packs do not reset the counter.
   - Alternating failure messages/classes (for example a loader throwing two
     different non-IOException `ServiceConfigurationError`s) never reach the cap,
     so the loop can still spin.
   The new test has only one failing provider
   (`providerClassLoadingLinkageErrorsAreReportedAndSkipped`, `:285-309`) and the
   persistent test covers only the configuration branch, so neither case is
   pinned.
   Fix: base continuation on observable progress (providers obtained, an available
   provider name/token, or iterator advancement), reset after each successful
   provider, and keep a global attempt cap for non-progressing iterators — or
   remove the text-keyed cap and rely on the JDK's per-entry advancement plus the
   configuration break. Add a fixture with 9+ distinct providers that fail with
   the same message followed by a good pack, and one with alternating errors.

2. **`RulePackLoader.kt:96-99`, `:110-113`, and `:513-515` — `isConfigurationFailure` is too broad and aborts discovery on a single unreadable service resource.**
   It returns true for any `ServiceConfigurationError` with an `IOException`
   anywhere in the cause chain. That covers the intended persistent
   `getResources` case ("Error locating configuration files"), but also the JDK's
   per-URL `parse` failure ("Error reading configuration file"). For the latter,
   `ServiceLoader` has already consumed the bad URL and can continue with the
   remaining service files; the loader instead breaks and drops all later valid
   packs. The old manual path handled each resource separately and continued.
   Fix: break only for the enumeration/`getResources` case (match the specific
   "Error locating configuration files" failure), and report-and-continue for
   per-file read/parse errors. Add a test whose `getResources` enumerates an
   unreadable service URL followed by a valid service file, and assert the valid
   provider loads.

### Concurs / Dissents

- F8: concurs — the loop's `Throwable` boundary around `hasNext`/`next`
  (`RulePackLoader.kt:92-121`) catches class-loading `LinkageError`s, and
  `discoverProvider` (`:126-178`) catches `type()`/annotation/`getMethod`/`get()`
  failures; both report `CORRUPT_PACK` and continue.
  `providerClassLoadingLinkageErrorsAreReportedAndSkipped` covers
  `NoClassDefFoundError` and `UnsupportedClassVersionError` through a
  classloader, and `linkageFailureIsCorruptNotMissingProvider` still covers the
  constructor path.
- F9: concurs — a persistent configuration failure now terminates:
  `isConfigurationFailure` breaks after the first error, and
  `persistentServiceResourceFailureTerminatesWithDegradedResult` pins the
  bounded lookup count (`lookups in 1..16`). F10/F11 are defects in the new
  bound/branch, not a recurrence of the unbounded loop.
- Concurs: `validateRules` now accepts `List<*>?` and rejects non-`Rule` entries
  (`element !is Rule`) before reading their metadata;
  `rawRuleListEntryIsCorrupt` uses the Java raw-list fixture and asserts
  `CORRUPT_PACK` with the pack id.
- Concurs: `just test-engine` includes `:rulepacks:test`
  (`justfile:18`), and `BundledRulePackLoadingTest` exercises the shared loader
  for a bundled provider.
- Concurs: F1-F7 remain addressed; the snapshot, explicit/annotated version
  gate, metadata guards, pack/rule identity checks, skip-all conflict policy,
  single stream pass, and single loader path are unchanged and still correct.
- Dissents: F10 and F11 above.

### Residuals (Round 3)

- The `canContinueAfter` counter is local to one `discover` call and the constant
  8 is arbitrary; if retained, the bound needs a progress definition independent
  of message text.
- `isConfigurationFailure`'s message matching is JDK-format-dependent; the test
  covers the current JDK wording, but the per-URL versus enumeration distinction
  should be based on the failure's origin or a stable marker.
- If a pack's `rules()` returns a `List` whose iterator itself throws,
  `discoverProvider` catches it as a provider failure (`CORRUPT_PACK` with no
  pack id) rather than a per-pack failure. It still degrades, so this is a
  diagnostic gap rather than a skip failure.
- The `@org.junit.Test(timeout = 5_000)` on the persistent-resource test relies
  on JUnit 4 being the active kotlin.test backend; the rest of the suite uses
  `kotlin.test.Test`.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 4

Verdict: F11 is addressed: `isConfigurationFailure` now matches only the
enumeration failure, per-resource read errors report and continue, and the new
unreadable-resource fixture proves a later valid pack loads. F10 is only partly
addressed: the counter is progress-based and no longer keyed on message text, and
the alternating-error case terminates, but progress is recorded only when
`ServiceLoader` returns a `Provider`. Per-entry failures that consume an entry —
class-loading `LinkageError`s and unreadable service resources — still increment
the counter, so 64 consecutive such failures before a valid pack still break
discovery and drop it. I concur on F11, dissent on the remaining F10 boundary, and
find no new correctness defect beyond it.

### Findings

- No new correctness finding beyond the remaining F10 boundary described below.

### Concurs / Dissents

- F10: dissents — `RulePackLoader.kt:77-86` defines progress only through
  `noteProgress()`, which is called at `:116` after `iterator.next()` returns a
  `Provider`; the class-loading catches at `:96-100` and `:110-114` increment
  `consecutiveNoProgressFailures` instead. In the JDK path a class-loading
  `NoClassDefFoundError`/`UnsupportedClassVersionError` consumes a service entry
  before it escapes (the iterator advances), but no provider is returned, so it
  is counted as no progress. A pack list with 64 such failures before a valid
  provider therefore reaches
  `MAX_CONSECUTIVE_NO_PROGRESS_FAILURES` (`:580`) on the 64th failure and breaks,
  dropping the later valid pack even though every failure advanced discovery.
  The same applies to per-resource read failures that are now correctly skipped
  for F11: 64 unreadable service resources would exhaust the same budget and drop
  later resources. The new regressions do not cover the boundary:
  `manyIdenticalClassLoadingFailuresDoNotDropLaterPacks` uses 12 failures (below
  64), `providerClassLoadingLinkageErrorsAreReportedAndSkipped` uses one, and
  `alternatingNoProgressFailuresTerminate` covers only the no-progress custom
  iterator. The alternating half is fixed.
  Fix: treat a per-entry iterator error as progress once the entry has been
  consumed (reset the counter for non-configuration errors), or remove the cap
  for per-entry errors and rely on `isConfigurationFailure` for the only known
  non-progressing case; if a backstop is retained, test the 63/64/65 boundary.
- F11: concurs — `isConfigurationFailure` (`RulePackLoader.kt:510-511`) now
  checks only `"Error locating configuration files"`, so per-resource
  `ServiceConfigurationError`s (for example "Error reading configuration file")
  are reported and skipped. `unreadableServiceResourceDoesNotAbortLaterResources`
  proves a missing resource URL followed by a valid service file still loads the
  valid provider, and `persistentServiceResourceFailureTerminatesWithDegradedResult`
  still pins the fatal enumeration case.
- F8: concurs — the per-provider `Throwable` boundary and the iterator
  `Throwable` catch still report class-loading failures as `CORRUPT_PACK` and
  skip them; the 12-failure and alternating tests additionally exercise this.
- F9: concurs — a persistent configuration failure still terminates; F10's
  remaining issue is the over-counting of advancing per-entry failures, not an
  unbounded loop.
- Concurs: F1-F7 and the raw-rule-list/`just test-engine` changes remain
  addressed and unchanged.
- New findings: none beyond the remaining F10 boundary.

### Residuals (Round 4)

- The 64-failure constant and the "provider returned equals progress" definition
  are the only things keeping the new tests green at 12 failures; a boundary test
  at 63/64/65 would pin the actual behavior.
- Per-resource read failures now continue correctly, but they still consume the
  same no-progress budget; if the counter is retained, they need the same
  progress treatment as class-loading failures.
- `isConfigurationFailure` remains JDK-message-dependent; the counter bounds a
  mis-detected persistent failure, but the message is still a fragile marker.
- The `@org.junit.Test(timeout = 5_000)` tests still rely on JUnit 4 as the
  kotlin.test backend.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 5

Verdict: The stated F10 cases are fixed: per-entry class-loading `LinkageError`s
and unreadable-resource errors now reset the counter, and the 80-consecutive-
failure regressions load the trailing valid pack/resource. F11 remains addressed.
However, the reset is inferred from error type/message rather than actual iterator
progress, which introduces a new termination hole (F12) and leaves the JDK's
illegal-syntax/illegal-provider-name per-entry errors counted as no progress
(remaining F10). I concur on the 80-case regressions and F11, dissent on the
remaining F10 under-reset, and report F12.

### Findings

1. **F12: `analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:82-129` and `:534-546` — a persistent entry-shaped error now loops forever because the reset is type/message-based, not progress-based.**
   `isPerEntryFailure` returns true for every `LinkageError` and for any
   `ServiceConfigurationError` whose message starts with
   `${RulePack::class.java.name}: Provider `, `...Illegal provider-configuration
   file`, or contains `configuration file`; the loop then calls `noteProgress()`
   and continues. The JDK's `ServiceLoader` advances past a real per-entry
   failure, so this is correct for a finite service enumeration, but a
   custom/broken classloader or iterator that throws the same `LinkageError` or
   entry-shaped `ServiceConfigurationError` without advancing (for example an
   enumeration whose `nextElement` always throws `NoClassDefFoundError` or
   `ServiceConfigurationError("dev.cint.api.RulePack: Provider X not found")`, or
   a persistent `"Error reading configuration file"`) now never reaches the
   64-failure bound and hangs `load()`. The previous counter would have stopped.
   `alternatingNoProgressFailuresTerminate` deliberately uses non-matching
   messages, so it does not cover this; the 80-failure tests are finite JDK
   enumerations.
   Fix: reset on observable progress (a provider/name token or an actual
   iterator-advance counter), and keep a global/attempt cap for repeated
   entry-shaped errors that never progress. Add a regression whose iterator
   throws the same entry-shaped error forever and assert termination.

### Concurs / Dissents

- F10: concurs for the stated class-loading and unreadable-resource cases — the
  80-failure tests load the trailing valid pack/resource and the counter no
  longer trips on those entries. Dissents on the remaining under-reset: the JDK
  reports per-URL parse failures as `"${service}: Illegal configuration-file
  syntax ..."` and `"${service}: Illegal provider-class name: ..."`, neither of
  which matches `isServiceEntryFailure`'s
  `startsWith("${prefix}Illegal provider-configuration file")` or
  `contains("configuration file")` (the former has a hyphen). Those per-entry
  failures still increment the no-progress counter, so 64 malformed service
  files before a valid pack still trip the cap and drop it; the new 80-failure
  tests cover only class-loading and unreadable-resource messages. Fix: match the
  actual JDK messages (or treat every non-configuration
  `ServiceConfigurationError` as per-entry and keep a separate global bound for
  non-progressing iterators), and add an 80-malformed-file regression.
- F11: concurs — `isConfigurationFailure` (`:528-529`) still breaks only on the
  enumeration failure, `isPerEntryFailure` skips per-resource read errors, and
  `unreadableServiceResourceDoesNotAbortLaterResources` /
  `manyUnreadableServiceResourcesDoNotDropLaterResources` prove the valid
  provider loads after 1 and 80 missing resources; the persistent `getResources`
  case is still fatal and bounded.
- F8: concurs — the iterator/per-provider `Throwable` boundaries still report
  class-loading failures as `CORRUPT_PACK` and skip them; the 80-failure
  regression exercises 80 such entries.
- F9: concurs — the persistent configuration failure still breaks on
  `"Error locating configuration files"`; F12 is the new over-reset hole, not a
  recurrence of the unbounded configuration loop.
- Concurs: F1-F7 and the raw-rule-list / `just test-engine` changes remain
  addressed and unchanged.
- New findings: F12; the remaining F10 under-reset above.

### Residuals (Round 5)

- `isServiceEntryFailure`'s message matching is JDK-wording-dependent and already
  misses the current illegal-syntax and illegal-provider-name messages; the
  unconditional `LinkageError` reset is not progress-based.
- The 80-failure tests rely on the JDK's finite service enumeration; a custom
  iterator with the same-shaped errors is not covered.
- The `@org.junit.Test(timeout = 5_000)` tests still rely on JUnit 4 as the
  kotlin.test backend.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 6

Verdict: F10 and F12 are addressed by the one-pass `META-INF/services` parser.
It validates each line/name, loads each provider under a `Throwable` boundary,
never retries an entry, and the 80-malformed-line, 80-class-loading-failure and
80-unreadable-resource regressions all load the trailing valid pack; a persistent
`getResources` failure still terminates with a degraded result. F11, F8, F9 and
F1-F7 remain addressed. One new correctness finding remains: the ASCII-only
provider-name regex is narrower than `ServiceLoader`'s Java-identifier validation,
so a legal pack with a Unicode class name is rejected and skipped. I concur on
F10/F12 and report F13.

### Findings

1. **F13: `analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:137` and `:630-631` — provider-name validation rejects legal Unicode Java identifiers.**
   The manual parser validates each service-file line against
   `Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")`, but
   `ServiceLoader`'s parser uses `Character.isJavaIdentifierStart`/
   `isJavaIdentifierPart`, which accept Unicode letters, digits, combining marks
   and currency symbols. A provider class with a legal non-ASCII name (or package
   segment) is therefore reported as `CORRUPT_PACK` ("illegal provider-class
   name") and never loaded, violating the acceptance that a pack dropped on the
   classpath is discovered. All fixtures and regressions use ASCII names, so the
   suite does not catch it.
   Fix: validate code points with `Character.isJavaIdentifierStart/Part` (mirror
   the JDK `parseLine` logic), or drop the regex and let `Class.forName` classify
   the name; add a Unicode-named provider fixture/test.

### Concurs / Dissents

- F10: concurs — the error-text/counter budget is gone; `providerNames` trims,
  strips comments, rejects empty/invalid lines, and continues, and
  `malformedServiceEntriesAreSkippedAndLaterPacksLoad` /
  `manyMalformedServiceLinesDoNotDropLaterPacks` (80 lines) load the valid
  provider. The JDK's illegal-syntax/name messages are now just per-line
  validation, so the prior under-reset cannot recur.
- F12: concurs — the `ServiceLoader.stream` retry/counter is gone; enumeration
  `Throwable`s are reported and terminate the enumeration loop,
  `discoverProvider` catches class-loading/construction errors per provider, and
  `providerClassLoadingLinkageErrorsAreReportedAndSkipped` /
  `manyIdenticalClassLoadingFailuresDoNotDropLaterPacks` (80) show no
  entry-shaped error can spin the loader. A throwing enumeration cannot loop
  because the loop breaks on the error.
- F11: concurs — per-URL read failures are reported and the loop continues;
  `unreadableServiceResourceDoesNotAbortLaterResources` and
  `manyUnreadableServiceResourcesDoNotDropLaterResources` (80) load the valid
  provider, while `persistentServiceResourceFailureTerminatesWithDegradedResult`
  keeps the `getResources` failure bounded.
- F8/F9: concurs — class-loading `LinkageError`s are caught in
  `discoverProvider`, and the `getResources`/per-URL boundaries report rather
  than escape; the 80-failure tests exercise the class-loading path.
- F1-F7, the raw-rule-list check and `:rulepacks:test` in `just test-engine`
  remain addressed and unchanged.
- New findings: F13.

### Residuals (Round 6)

- The loader is now a manual `META-INF/services` parser rather than
  `java.util.ServiceLoader`. Classpath behavior matches the tested cases, but
  named-module `provides` providers are not discovered, and the parser
  deliberately continues past malformed lines within a file where
  `ServiceLoader` would reject the whole file. Confirm this §9 wording deviation
  with the architecture reviewer.
- `providerNames` uses `url.openStream` rather than `URLConnection`
  `setUseCaches(false)`, and `discoverProvider` uses
  `getDeclaredConstructor().newInstance()` rather than `ServiceLoader`'s public
  constructor path; a same-package package-private provider could be accepted
  where `ServiceLoader` would reject it. These are semantic divergences, not
  skip failures.
- A custom classloader returning a non-throwing infinite resource enumeration,
  or a URL stream that never ends, would still hang; the parser relies on the
  classloader's enumeration/stream termination contract.
- Malformed-line failures carry an empty `packIds` and name the URL but not the
  line number; diagnostic only.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 7

Verdict: F13 is addressed: `isValidProviderName` now mirrors `ServiceLoader`'s
Java-identifier checks with `Character.isJavaIdentifierStart/Part` over code
points, and `unicodeProviderClassNameIsDiscovered` loads a `Ü`-named pack.
F10/F12/F11/F8/F9/F1-F7 remain addressed. One new verification finding remains:
the new 4096-line discovery bound is unexercised — the infinite-enumeration
regression hits the 256-resource bound first — so a regression in the line-limit
branch would pass. I concur on F13 and report F14 (test coverage for the line
bound).

### Findings

1. **F14: `analysis-engine/core/src/main/kotlin/dev/cint/core/rulepack/RulePackLoader.kt:151-159` and `:682` — the 4096-line discovery bound has no test.**
   `MAX_SERVICE_LINES = 4096` is enforced by the `lineCount` check that reports
   `DISCOVERY_LIMIT_EXCEEDED`, sets `stop = true`, and returns from `useLines`.
   The only bounded-discovery regression, `infiniteResourceEnumerationTerminates`,
   repeatedly serves a one-line service resource and therefore reaches
   `MAX_SERVICE_RESOURCES = 256` (`:136-146`) first; `lineCount` never exceeds 256.
   Nothing exercises the line-limit branch, so a bug that failed to stop reading,
   continued into later resources, dropped names already collected, or reported
   the wrong failure kind would pass the suite. The line limit is global across
   resources, so its partial-load behavior is also unpinned.
   Fix: add a service file with >4096 lines (comments/blank lines plus a valid
   provider, and a case with the valid provider after line 4096), or expose a
   test-only limit; assert the `DISCOVERY_LIMIT_EXCEEDED` failure, that discovery
   stops, and the expected loaded set. Optionally pin the 256-resource boundary
   with 257 resources and a valid 257th.

### Concurs / Dissents

- F13: concurs — `isValidProviderName` (`RulePackLoader.kt:193-215`) validates
  code points with `Character.isJavaIdentifierStart/Part`, advances by
  `Character.charCount`, handles dots/segments, and the `FixtureÜnicodePack`
  regression asserts a >127 code point and loads `fixture-unicode`. The explicit
  `$` allowance is redundant because `Character` already accepts `$`, but it
  does not widen the accepted set.
- F10/F12/F11/F8/F9 and F1-F7: concurs — the one-pass parser, per-line/per-
  provider Throwable boundaries, resource/line bounds and the previous
  behavior remain as reviewed; the new `DISCOVERY_LIMIT_EXCEEDED` kind is
  surfaced for the resource bound and is degraded.
- New findings: F14 (line-bound test coverage).

### Residuals (Round 7)

- The 256-resource / 4096-line limits are arbitrary denial-of-service bounds; a
  legitimate classpath exceeding either is partially ignored (with a named
  failure). Confirm the chosen limits/tradeoff, and consider documenting them.
- The manual `META-INF/services` parser residuals from Round 6 remain
  (named-module `provides` not discovered, whole-file invalid-line semantics
  replaced by per-line continuation, `openStream` caching, and
  `getDeclaredConstructor` permissiveness).
- The Unicode regression covers a BMP `Ü`; the code-point path for
  supplementary characters is implemented but untested.
- The line-limit branch reports before setting `stop = true`; a throwing
  `onFailure` callback would leave `stop` false and re-trigger the limit. The
  default callback does not throw, so this is a robustness residual.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 8

Verdict: F14 is addressed in substance. `serviceLineLimitStopsReadingAndKeepsEarlierProviders`
puts the valid pack on line 1, 4096 blank lines after it, and the counting pack
after the limit; the 4097th line triggers `DISCOVERY_LIMIT_EXCEEDED`, the earlier
pack loads, the counting pack is never constructed, and the failure kind/message
are asserted. F13 and F10/F12/F11/F8/F9/F1-F7 remain addressed. I concur on F14
and report one new test-coverage finding (F15): the regression does not pin the
exact 4096/4097 boundary or that the global `stop` prevents a later resource from
being read. The source behavior is correct (`>` and a global `stop`), so this is
coverage, not a source defect.

### Findings

1. **F15: `RulePackLoaderTest.kt:388-401` — the line-limit regression does not pin the boundary or the cross-resource stop.**
   The test's only valid provider is on line 1; line 4096 is a blank and line
   4097 (also a blank) triggers the limit, while the counting pack is on line
   4098. An implementation that used `>=` instead of `>` (triggering on line
   4096) would still load the line-1 pack, skip the later pack, and pass every
   assertion. Because the later provider is in the same service file, the test
   also does not prove that the `stop` flag prevents a subsequent resource from
   being read; a bug that stopped only the current file but continued to the next
   resource would pass. The current code uses `>` (`RulePackLoader.kt:152`) and
   `stop` is checked by the outer `while (!stop)` (`:102,160`), so the behavior is
   correct; it is just unpinned.
   Fix: place a valid provider on line 4096 (assert it loads) and add a second
   service resource after the limit file containing the counting pack (assert its
   constructor count stays 0).

### Concurs / Dissents

- F14: concurs — the new regression exercises the `MAX_SERVICE_LINES` branch,
  asserts `DISCOVERY_LIMIT_EXCEEDED` and the "4096 service lines" message, keeps
  the earlier `fixture-good`, and proves the counting pack after the limit is
  never constructed. The `stop`/`return@useLines` path is now covered for the
  current resource.
- F13: concurs — the `Character.isJavaIdentifierStart/Part` validator and the
  `FixtureÜnicodePack` regression remain correct.
- F10/F12/F11/F8/F9/F1-F7: concurs — unchanged from the prior rounds.
- New findings: F15 (boundary/cross-resource test coverage); no new source
  correctness defect.

### Residuals (Round 8)

- The exact `MAX_SERVICE_LINES` boundary (line 4096 allowed, 4097 rejected) and
  the cross-resource global `stop` remain untested; F15.
- The 256-resource / 4096-line limits are arbitrary denial-of-service bounds; a
  legitimate classpath exceeding either is partially ignored with a named
  failure. Confirm/document the limits.
- The manual `META-INF/services` parser residuals from Round 6 remain
  (named-module `provides`, whole-file invalid-line semantics, `openStream`
  caching, `getDeclaredConstructor` permissiveness).
- The Unicode regression covers a BMP `Ü`; supplementary code points and the
  redundant explicit `$` allowance are not exercised.
- The line-limit branch reports before setting `stop = true`; a throwing
  `onFailure` would leave `stop` false and re-trigger the limit. The default
  callback does not throw.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.

## Round 9

Verdict: F15 is addressed. `serviceLineLimitAllowsExactly4096Lines` and
`serviceLineLimitRejects4097Lines` pin the `>` threshold from both sides, and
`serviceLineLimitStopsBeforeLaterResources` proves the global `stop` prevents a
second service resource from being read (the counting pack is never constructed).
F13 and F10/F12/F11/F8/F9/F1-F7 remain addressed. I concur on F15 and find no new
source correctness defect; one residual precision remains: the 4096th line in the
allow test is a comment, not a valid provider, so the test pins "no limit at
4096" but not that the 4096th line's content is parsed.

### Findings

- No new correctness finding beyond the residual test precision noted below.

### Concurs / Dissents

- F15: concurs — the boundary pair distinguishes `>` from `>=`: exactly 4096
  lines produces no failure, 4097 lines produces a single
  `DISCOVERY_LIMIT_EXCEEDED` with the "4096 service lines" message, and the first
  provider still loads. `serviceLineLimitStopsBeforeLaterResources` puts the
  counting pack in a second service resource and asserts it is never constructed,
  covering the global `stop`/outer-while behavior. The `>=` off-by-one and the
  same-file-only stop from Round 8 are both closed.
- F13: concurs — `Character.isJavaIdentifierStart/Part` validation and the
  `FixtureÜnicodePack` regression remain correct.
- F10/F12/F11/F8/F9/F1-F7: concurs — unchanged from prior rounds.
- New findings: none.

### Residuals (Round 9)

- `serviceLineLimitAllowsExactly4096Lines` places a comment on line 4096, so it
  proves the limit does not fire at 4096 but does not prove the 4096th line is
  parsed; a valid provider on line 4096 would pin that final half of the
  boundary. The code currently iterates and parses it.
- The 256-resource / 4096-line limits remain arbitrary denial-of-service bounds;
  a legitimate classpath exceeding either is partially ignored (with a named
  failure). Confirm/document the limits.
- The manual `META-INF/services` parser residuals from Round 6 remain
  (named-module `provides`, whole-file invalid-line semantics, `openStream`
  caching, `getDeclaredConstructor` permissiveness).
- The Unicode regression covers a BMP `Ü`; supplementary code points and the
  redundant explicit `$` allowance are not exercised.
- The line-limit branch reports before setting `stop = true`; a throwing
  `onFailure` would leave `stop` false and re-trigger the limit. The default
  callback does not throw.

No build, test or formatter command was run by this role; the handover was treated
as green per `workflow.md`.
