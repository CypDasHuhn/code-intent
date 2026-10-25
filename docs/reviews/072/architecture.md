# Architecture review — ticket 072 (tree-sitter syntax adapter)

Verdict: FINDINGS

1. **`analysis-engine-v2/syntax-treesitter/src/commonMain/kotlin/dev/cint/v2/syntax/treesitter/TreeSitterLanguages.kt:12-101` — the adapter owns the language registry.** Aliases, extensions, grammar coordinates, library names, and JVM factories live in the adapter, and `forExtension` performs language detection. `module-graph.md` assigns descriptor binding and resolution to `languages`; ticket 074 will duplicate this table or reach into adapter internals. Fix: move the registry to `languages` and pass a descriptor into the adapter. Grammar node-kind profiles are legitimately adapter-side.

2. **`TreeSitterLanguages.kt:105`, `JvmTreeSitterSyntaxEngine.kt:176-188`, `build.gradle.kts:53-59` — grammar registration is scattered.** Adding a language needs a registry entry, a hardcoded JVM `when`, Maven coordinates, and extractor profiles; `jvmFactory` is unused. The no-arg `createSyntaxEngine()` silently defaults to C, a spike convenience on the production API. Fix: require an explicit language and make one registration site drive JVM and native construction.

3. **`SyntaxFactExtractor.kt:223-230` — unknown languages silently use the Kotlin profile.** This defeats "missing grammars fail explicitly" for any unprofiled tree and hides registration mistakes. Fix: return `InvalidInput`/`DependencyUnavailable` for unknown languages.

4. **`build.gradle.kts:53` vs `native-libs/tree-sitter/lib/libtree-sitter.so` — JVM and native run different tree-sitter runtimes (0.26.6 vs 0.28).** `SyntaxTree.version` records only the grammar, so consumers cannot detect divergence. This will complicate 078's cross-target cache/Merkle parity. Fix: pin one runtime or record runtime ABI/version and add a JVM/native structural snapshot.

5. **`scripts/extract-tree-sitter-grammars.sh:20-31`, `native-libs/tree-sitter-grammars/README.md`, `native-libs/tree-sitter/` — provenance is incomplete and not reproducible.** The script downloads Maven jars without SHA-256 pinning, rewrites in place, and runs `strip` non-deterministically; the vendored runtime has no README, script, checksums, or license; README says Vue 0.2.1 while the artifact/registry says 0.2.1a. Fix: check in hashes and licenses, verify downloads, and script the runtime's origin.

6. **`build.gradle.kts:15-24,30-37`, `TreeSitterLanguages.kt:31-82`, `src/nativeMain/cinterop/grammar_dl.h` — native loading is Linux/x86_64-only and not relocatable.** Absolute build paths are baked into rpath, `.so` names and POSIX `dlopen` are hardcoded in descriptors, and non-Linux hosts silently omit the native target. Fix: use `$ORIGIN`/env paths, abstract library naming, and make the def a task input rather than a configuration-time write.

The contract surface is preserved and the adapter is otherwise well bounded: it depends only on `contracts`, does no file IO, knowledge, semantic-provider, or facade access, and returns `DependencyUnavailable` for unregistered grammars. The main risk is that language knowledge, runtime choice, and loading policy are hardcoded in the adapter, pushing duplication and parity work into 074/078.

## Round 2

1. CONCUR — resolved: descriptors, aliases, and extensions now live in `languages/TreeSitterLanguageRegistry.kt`; the adapter accepts explicit ids. Grammar loading metadata stays adapter-side as grammar detail, with a test guarding table alignment.
2. CONCUR — resolved: `TreeSitterGrammars` is one registration site; JVM uses `jvmFactory`, native uses library/symbol; the no-arg factory is gone.
3. CONCUR — resolved: `Profiles.forLanguage` returns null and `extract` fails `InvalidInput` (`SyntaxFactExtractor.kt:223,248-253`); engines reject mismatched trees.
4. CONCUR — resolved: `SyntaxTree.version` records `/runtime-0.26.6` and `/runtime-0.27.1`, and shared fixture fingerprints run on both targets.
5. CONCUR — resolved: SHA-256 pins cover Maven jars, extracted libraries, and the 0.27.1 tarball; runtime README, license, and verify script exist. Residual: grammar licenses are documented, not vendored.
6. CONCUR — partially resolved: static runtime, `$ORIGIN` rpath, env override, and the generated def fix relocatability. Residual: descriptors still hardcode `.so`/POSIX `dlopen`, only Linux is built, and the def task lacks env inputs; production binaries still need grammar libs beside them or `CINT_TREE_SITTER_GRAMMAR_PATH`.

Boundaries remain clean: `syntax-treesitter` imports only `contracts` with no IO/knowledge/provider; `languages` has only contracts, syntax-treesitter, and lsp edges.

Verdict: PASS. Residuals are documented portability/licensing follow-ups, not boundary breaks; 074 has the facade and 078 has runtime identity.
