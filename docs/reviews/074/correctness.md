# Correctness Review — 074 V2 Languages Facade

Verdict: FINDINGS

1. `analysis-engine-v2/languages/src/main/kotlin/dev/cint/v2/languages/LanguageSupportFactory.kt:78-80` — A non-null but unknown `languageHint` silently falls back to extension lookup (`hint?.let(entry) ?: entryForPath`). An explicit bad hint can therefore analyze as a different language. Fix: fall back only when the hint is null; otherwise return `NotFound("language", hint)`.

2. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/facts/LanguageDescriptor.kt:14-16` — `matchesPath`'s MIME branch ignores `path`: for JavaScript/TypeScript the declared MIME equals `text/$id`, so every path matches (e.g. `JavaScript.matchesPath("Main.kt") == true`). The extension branch is also case-sensitive, unlike registry lookup, so the two APIs disagree. Fix: repair or remove MIME matching, normalize case, and share one matcher with the catalog.

3. `analysis-engine-v2/languages/src/main/kotlin/dev/cint/v2/languages/LanguageRoots.kt:18-21` and `TreeSitterLanguageRegistry.kt:90` — Discovery selects the nearest directory containing any marker and ignores marker order. A nested `.git` or `.csproj` can shadow a parent C# `*.sln`, although the entry lists `.sln` first; `.git` is not a true fallback. C# glob markers are untested. Fix: define marker precedence (search language markers across ancestors before `.git`) and add `.sln`/`.csproj` tests.

4. `LanguageSupportFactory.kt:84-89` — `SourceInput.InMemory` with `path == null` cannot resolve root unless the caller supplies one, so normal editor/unsaved buffers fail before provider selection. Fix: allow rootless in-memory/editor resolution (provider supports a null root) or require runtime to inject a root; cover this path.

5. `LanguageSupportFactory.kt:128,239-248` and `analysis-engine-v2/lsp/src/main/kotlin/dev/cint/v2/lsp/LspSemanticProvider.kt:343-351` — The facade starts a provider per resolve, but `LanguageSupport` exposes no close/shutdown; repeated resolves can leak managed processes. `shutdown` also closes a caller-supplied `HostSemanticChannel` whose ownership is unspecified. Fix: own only provider-opened channels, reuse/cache sessions at runtime, expose disposal, and test shutdown.

6. `TreeSitterLanguageRegistry.kt:96-111` — Duplicate ids/aliases/extensions are not rejected; `byName` is last-wins while `byExtension` is first-wins, so future additions silently misroute. Fix: validate uniqueness at construction and fail fast.

7. Tests — `LanguageSupportFactoryTest.kt:52-74` uses a fake provider that ignores mode, so the "matching provider" test only echoes config; managed/attached positive dispatch, `resolveForInput`, C# roots, and lifecycle are unverified. The default syntax factory also validates registration only, so a registered but unloadable grammar returns support and fails at parse. Add transport-backed mode tests, C#/in-memory root tests, shutdown assertions, and an unloadable-grammar test.

Kotlin's descriptor/grammar pairing, mode-to-config wiring, and pre-analysis failures for a missing grammar entry, missing provider, and missing root are otherwise sound. The main correctness risks are the hint fallback, the broken descriptor MIME matcher, root-selection ambiguity, rootless in-memory resolution, and lifecycle ownership; the tests miss several of these paths.

## Round 2

1. CONCUR — hint: `LanguageSupportFactory.kt:84-92`; test `:153-169`.
2. CONCUR — MIME/case: `LanguageDescriptor.kt:19-26`; `LanguageDescriptorTest.kt:18-24`.
3. CONCUR — priority/C#: `LanguageRootLocator.kt:23-37`; test `LanguageSupportFactoryTest.kt:355-371`.
4. DISSENT — discovery is opt-in: `LanguageSupportFactory.kt:49` defaults `rootLocator = null`, and `Languages` uses that factory (`:245`), so the shipped facade never discovers roots. Only `:347` injects `MarkerRootLocator`, masking the regression; managed roots fail, editor roots stay null.
5. CONCUR — rootless in-memory: `LanguageSupportFactory.kt:159-172`; test `:273-288`.
6. CONCUR — lifecycle: `LanguageSupport.kt:14`, `LanguageSupportFactory.kt:276`, `LspSemanticProvider.kt:345-351`.
7. CONCUR — collisions: `LanguageCatalog.kt:25-63`; test `:137-151`.
8. DISSENT — default Kotlin: `TreeSitterLanguageRegistry.kt:15` sets MANAGED with no command, and `providerConfig` adds none (`LanguageSupportFactory.kt:193-203`), so default resolve fails `PreconditionFailed`; `LanguageSupportFactoryTest.kt:49,67` hides this with a fake provider.

Round-1 issues are substantially fixed; default discovery and default Kotlin resolution remain broken and untested. Verdict: FINDINGS.

## Round 3

CONCUR — root discovery: `LanguageSupportFactory.kt:49` now defaults to `MarkerRootLocator`; `LanguageSupportFactoryTest.kt:46-71` resolves Kotlin without a configured root or injected locator, and `:397-422` exercises the default `Languages` facade for C#.

CONCUR — default Kotlin: `LanguageProviderDefaults.kt:11-16` supplies `MANAGED` with `kotlin-lsp --stdio`; `LanguageSupportFactory.kt:112,133-135` applies it before provider creation, and `LanguageSupportFactoryTest.kt:66-68` asserts the propagated command.

No new regression found: rootless editor in-memory resolution (`LanguageSupportFactory.kt:158-177`), lifecycle ownership, and catalog collision rejection remain intact; the new early `PreconditionFailed` validation (`:198-227`) covers missing command, endpoint, and channel. Verdict: PASS.
