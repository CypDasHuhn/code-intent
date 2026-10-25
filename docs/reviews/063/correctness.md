# Correctness Review — Ticket 063

## Verdict: FINDINGS

1. **Root/convention dependencies bypass the scan.** `analysis-engine-v2/build.gradle.kts:251-263` reads only the 17 per-module build files, and `:123-148` validates only those sources. A `subprojects { dependencies { implementation(project(":ai")) } }` block in the root build script, or an applied convention/precompiled script, adds real edges to modules that are never scanned. Fix: derive each project's dependencies from Gradle's configuration/dependency model, or scan root/convention scripts and reject dependency blocks there.

2. **Alternate project-dependency syntax is invisible.** `analysis-engine-v2/build.gradle.kts:78-90` only matches textual `project(...)`. Gradle 9 type-safe accessors such as `implementation(projects.ai)` contain no `project(` and are neither matched nor counted as unresolved, so an illegal edge passes. Fix: use the resolved Gradle model, or additionally match `projects\.<name>` accessors and fail closed on unrecognised dependency forms.

3. **Comment stripping mishandles Kotlin raw strings and nested comments.** `analysis-engine-v2/build.gradle.kts:187-232` tracks `"`/`'` but not `"""`, and strips block comments non-nested. A raw string containing `/*` can cause the rest of a real build file (including an illegal dependency) to be treated as comment; nested `/* /* */ */` produces false positives. The self-test at `:314` only exercises `//`. Fix: handle triple-quoted strings and nested block comments, and add raw/block-comment self-test cases.

4. **Settings/config drift leaves new projects unscanned.** `analysis-engine-v2/build.gradle.kts:123-126,251-263` trusts `graph-guard.json` as the module list and never reconciles it with `settings.gradle.kts`. Any newly included project absent from the JSON (the unlisted `native-spike/` shows the pattern) has all of its edges unchecked. Fix: compare Gradle's project registry against `config.modules` and fail on unlisted projects.

5. **Cross-ticket contracts edits (065-owned).** `contracts/.../facts/IndexTypes.kt:11,19`, `knowledge/KnowledgeSnapshot.kt:15,17`, `util/Errors.kt:41-86`, and `commonTest/.../SerializationTest.kt` are ticket 065's surface. They appear necessary for a green `just v2-test`/`check`: at HEAD the `SourceInput` subclasses lack `@Serializable`, and `Result` is a non-serializable interface while tests encode `Result<String>` and look up subtypes; the edits add those annotations/serializers and make `Result` a sealed class. But they change 065's public contract (`Result` interface→sealed class, covariance via `Result<Nothing>`). Commit them under 065 (or a named follow-up) and rebase 063 onto it, keeping 063's diff to build, guard, CI and justfile.

The self-test is correctly wired into `check` (`:400`) and genuinely covers pass, illegal, actual-reverse, declared-cycle and missing-file cases; it is not vacuous, though it only certifies the narrow literal-scanning path above. The CI job is genuinely non-blocking (`continue-on-error: true`) and runs valid `just v2-build`/`just v2-test` commands against the executable wrapper and Java 21. V1 recipes, V1 build and V1 sources are untouched.

Summary: the literal-path guard works for the declared graph and the explicitly listed illegal/reverse/evasive forms, but it is a text scan: root/convention-injected edges, type-safe `projects.*` accessors, raw-string/nested-comment content, and projects missing from `graph-guard.json` remain bypasses. The contracts diff crosses into 065, looks required to make 063's `check` green, and should be owned and committed there rather than folded into 063.

## Round 2

1. **Round-1 #1 (root/convention bypass) — CONCUR (fixed).** `analysis-engine-v2/build.gradle.kts:53-80` now walks `root.allprojects` and every configuration, collecting real `ProjectDependency` paths; root-project deps are collected at `:67-68` and rejected in `buildSrc/src/main/kotlin/v2/graphguard/GraphGuard.kt:51-53`, while deps injected into subprojects via `subprojects {}` or convention scripts land in that project's configurations and are validated normally. Test: `GraphGuardValidatorTest.kt:71`.

2. **Round-1 #2 (alternate syntax) — CONCUR (fixed).** Model-based collection (`build.gradle.kts:60-65`) catches `project(...)`, type-safe `projects.*` accessors, and any other form that yields a `ProjectDependency`; the regex/comment scanner is gone.

3. **Round-1 #3 (comment stripping) — CONCUR (fixed).** No text/comment scanning remains; the raw-string and nested-comment hazards no longer exist.

4. **Round-1 #4 (settings/config drift) — CONCUR (fixed).** Modules now come from `root.allprojects` (`:60,72`) and `graph-guard.json` holds only edges; a newly included project becomes a validated module, and undeclared/unknown edges fail (`GraphGuard.kt:44-47,76`).

5. **Round-1 #5 (cross-ticket contracts) — DISSENT (incomplete).** The 065-owned contract edits are still present: `contracts/.../util/Errors.kt:41-55,75`, `commonTest/.../SerializationTest.kt:344-452`, `IndexTypes.kt:11,19`, `KnowledgeSnapshot.kt:15,17` remain modified; HEAD `ca01458` (065) does not contain them, and `docs/tasks/063-v2-build-skeleton.md` records no deferral (only the scope line changed). Committing 063 without them leaves `v2-test` red; committing them with 063 crosses tickets. Fix: commit these under 065 and rebase 063, or add an explicit named deferral and stage them out of 063.

The self-test is genuinely wired into `check` (`build.gradle.kts:108-116`) and ran 9 tests with 0 failures; CI adds the guard step and remains non-blocking (`ci.yml:56,72-76`).

## Round 3

**CONCUR.** `docs/tasks/063-v2-build-skeleton.md:37-39` now records the test-edge policy, the 065 ownership of the contracts hunks with the orchestrator dropping them at merge, and the 070 deferral of non-JVM boundary enforcement. That resolves Round-1 #5 as an explicit process deferral to a named ticket. Diffstat confirms no code changed since Round 2: only the three Notes lines were added; CI, guard, `graph-guard.json`, justfile and buildSrc are unchanged. No further correctness findings.
