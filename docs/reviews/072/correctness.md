# Ticket 072 Correctness Review

Verdict: FINDINGS

1. **Native node end positions are wrong.** `analysis-engine-v2/syntax-treesitter/src/nativeMain/kotlin/dev/cint/v2/syntax/treesitter/TreeSitterSyntaxEngine.kt:310-313` reads both `endRow`/`endCol` from `start`, not `end`. Every native `SyntaxNode.span` is zero-length and disagrees with JVM. Fix: use `end.useContents` for both end values and add a JVM/native span assertion.

2. **Kotlin V1 parity is not proven.** `analysis-engine-v2/syntax-treesitter/src/jvmTest/kotlin/dev/cint/v2/syntax/treesitter/FrozenCorpusParityTest.kt:33-55` compares only total/named/ERROR counts against opaque hard-coded integers. No checked-in V1 capture, generator, or adapter invocation exists, so the numbers can just as easily have been produced by this adapter. `GRAMMARS.md:7-12` claims signatures were captured from V1, but nothing in the tree demonstrates that. Fix: check in a V1-generated tree/fact snapshot (kinds, spans, facts) and compare it, or add a capture script that runs the V1 adapter.

3. **No JVM/native parity is actually tested.** The corpus test is JVM-only (`FrozenCorpusParityTest.kt`, under `jvmTest`); the shared test (`commonTest/.../SyntaxEngineTest.kt`) asserts only generic properties. JVM uses tree-sitter 0.26.6 (`syntax-treesitter/build.gradle.kts:53`) while native links vendored 0.28 (`native-libs/tree-sitter/lib/libtree-sitter.so`), so trees can differ. Fix: run the frozen corpus and a cross-platform node/fact snapshot on both targets.

4. **Fact parity is absent, and unknown languages silently use the Kotlin profile.** The ticket requires parity with frozen V1 syntax facts, but the frozen corpus stores no syntax facts. `SyntaxFactExtractor.kt:223-230` maps any unlisted language to Kotlin, and `facts`/`nodeHash`/`diff` (`JvmTreeSitterSyntaxEngine.kt:64-121`, native `TreeSitterSyntaxEngine.kt:99-122`) never reject a tree whose language differs from the engine. Fix: fail explicitly on unknown/mismatched languages and add checked-in fact fixtures.

5. **Native grammar handles leak and are not synchronized.** `TreeSitterSyntaxEngine.kt:254-257,260-273`: the global handle/language maps have no mutex, unlike the JVM cache; `dlopen` handles leak when `dlsym` or the factory fails (`:263-269`), and successful handles are never `dlclose`d. Fix: synchronize access, `dlclose` on failure, and either document or implement process-lifetime cleanup.

6. **Vendored binary provenance and portability are weak.** `analysis-engine-v2/scripts/extract-tree-sitter-grammars.sh:20-31` downloads versioned Maven jars without a checksum/signature manifest; only x86_64 Linux `.so` files are vendored. `build.gradle.kts:23` embeds absolute checkout paths in the rpath, while `GRAMMARS.md:29-34` calls them project-relative and portable. Fix: add SHA-256 pins and use `$ORIGIN`-relative paths (or require explicit env paths), and state the architecture limit in the acceptance evidence.

The adapter itself is otherwise clean: no source file IO, knowledge, or semantic-provider access was found, parsers/trees are freed on normal paths, node hashes are stable Merkle hashes, incremental edits use byte-accurate prefix/suffix points, unknown query ids fail with `InvalidInput`, and unknown/unloadable grammars return `DependencyUnavailable`. The blocking correctness issues are the native span bug and the unproven parity story.

## Round 2

1. **CONCUR — fixed.** Native now reads `end` (`TreeSitterSyntaxEngine.kt:360-361`), and `SyntaxEngineTest.kt:65-77` asserts advancing end/byte length on both targets.
2. **CONCUR — snapshot added.** `capture-v1-syntax-snapshot.sh:19-22` runs V1 `KotlinParser`; the harness (`tools/v1-syntax-capture/V1SyntaxCaptureTest.kt:15,22-42`) emits the checked-in JSON, and tests consume its fingerprints (`SyntaxEngineTest.kt:54-63`, `FrozenCorpusParityTest.kt:31-39`). It is not self-fulfilling at test time; embedded sources match the frozen fixtures. Caveat: the script uses the mutable `analysis-engine` tree and records no V1 commit, so it is not pinned to frozen `3ccea9e`.
3. **CONCUR (Kotlin only).** The shared `commonTest` snapshot runs on JVM and native; non-Kotlin languages still have no native parity test.
4. **CONCUR on errors.** Unknown profiles (`SyntaxFactExtractor.kt:248-254`) and mismatched trees (`JvmTreeSitterSyntaxEngine.kt:106-134`, native `:107-148`) fail explicitly; fact parity is honestly deferred to 084 (`GRAMMARS.md:31-34`), so ticket acceptance remains partial.
5. **CONCUR.** Mutex plus `dlclose` on failure (`TreeSitterSyntaxEngine.kt:279-321`); process-lifetime handles are documented.
6. **CONCUR.** SHA checks pass (`native-libs/SHA256SUMS`), `$ORIGIN` rpath (`build.gradle.kts:28`), checksum-pinned scripts.

Verdict: Round 2 fixes the blocking native span bug and replaces the weak count assertions with a checked-in V1 snapshot exercised on JVM and native; error handling, grammar cleanup, and provenance are materially improved. The remaining correctness caveat is that capture provenance is not tied to the frozen V1 commit and non-Kotlin cross-target parity is untested; otherwise no new regression was found.

## Round 3

**CONCUR:** `capture-v1-syntax-snapshot.sh:10-29` pins `3ccea9e`, cross-checks the corpus manifest, and rejects dirty or drifted `analysis-engine`; snapshot and `V1KotlinSnapshot.v1Commit` carry it, and `FrozenCorpusParityTest.kt:34-40` matches the manifest.

**DISSENT:** Drift is still silent. `verify-v1-syntax-snapshot.sh:23-42` only checks snapshot/commit, frozen source text, and that each fingerprint equals its own `nodes`; it never re-runs V1. Editing `nodes`+`fingerprint` in `v1-kotlin-trees.json`, or editing `V1KotlinSnapshot.kt` fingerprints/sources, still passes; the verifier is also not invoked by CI or tests. Fix: re-run capture and diff in CI, and have the verifier assert the generated maps equal the snapshot plus a committed expected digest.

Verdict: commit pinning is real, but verification proves self-consistency, not V1 provenance; silent snapshot drift remains possible.

## Round 4

**CONCUR — resolved.** `verify-v1-syntax-snapshot.sh:54-66` now extracts pinned `3ccea9e` via `git archive`, regenerates both files by rebuilding that V1 tree through `regen-v1-syntax-snapshot.sh`, and byte-compares them against the checked-in snapshot/generated Kotlin. The digest file matches both artifacts, `render_snapshot.py` reproduces `V1KotlinSnapshot.kt` byte-for-byte, and the opt-in JVM test runs the verifier or skips explicitly. This is a fresh V1 parse, not self-consistency.

Residual risk: the CI step is `continue-on-error`, so detected drift would not block a merge; the verifier should eventually be blocking. No new correctness regression found.

Verdict: the Round 3 dissent is resolved; snapshot provenance is now reproducible from the pinned V1 commit.
