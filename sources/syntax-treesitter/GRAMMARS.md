# Tree-sitter Grammars

`syntax-treesitter` parses with real tree-sitter grammars on both JVM and Kotlin/Native.

## Language resolution

Language ids, aliases, extensions, and descriptors live in
`sources/languages` (`TreeSitterLanguageRegistry`). Ticket 074 owns the
`LanguageSupport` facade and provider/parser binding. The adapter only accepts an
explicit grammar id and owns grammar-specific loading metadata. There is no no-argument
default and no alias table in the adapter.

The adapter's `TreeSitterGrammars` table is the single registration site for grammar name,
version, native library/symbol, and JVM factory. JVM construction uses that factory field;
native construction uses the same entry's library and symbol.

## Kotlin syntax

The Kotlin grammar is `tree-sitter-kotlin` 0.3.8.1. The shared tests parse inline Kotlin
sources on both JVM and Kotlin/Native and assert the same syntax-tree shape and spans.
The fact tests prove the contract categories, filtering, unknown-profile rejection, and
engine/tree mismatch rejection. There is no external snapshot or V1 corpus dependency.

## Runtimes and grammars

JVM uses the pinned Maven artifacts `io.github.bonede:tree-sitter*` 0.26.6 and grammar jars
listed in `syntax-treesitter/build.gradle.kts`. Native uses the checked-in
`sources/syntax-treesitter/native/tree-sitter` runtime 0.27.1 plus grammar shared libraries in
`sources/syntax-treesitter/native/tree-sitter-grammars/lib`.

`SyntaxTree.version` records both grammar and runtime identity, for example
`tree-sitter-kotlin-0.3.8.1/runtime-0.26.6`. The runtime differs by target, so consumers can
detect it explicitly; the shared syntax assertions are runtime-agnostic and pass on both.

| Language | Library | Grammar version |
|---|---|---|
| C | `libtree-sitter-c.so` | 0.24.1 |
| Kotlin | `libtree-sitter-kotlin.so` | 0.3.8.1 |
| JavaScript | `libtree-sitter-javascript.so` | 0.23.1 |
| TypeScript | `libtree-sitter-typescript.so` | 0.23.2 |
| Vue | `libtree-sitter-vue.so` | 0.2.1a |
| C# | `libtree-sitter-c-sharp.so` | 0.23.1 |

The native adapter statically links the tree-sitter runtime and loads grammars at runtime
with `dlopen`/`dlsym`; grammar symbols are never linked into the executable. The native test
binary uses `--rpath=$ORIGIN`, and Gradle copies the pinned grammar libraries next to that
binary. Override the runtime search path with `CINT_TREE_SITTER_GRAMMAR_PATH` (colon
separated). Build-time override variables are `CINT_TREE_SITTER_INCLUDE`,
`CINT_TREE_SITTER_LIB`, and `CINT_TREE_SITTER_GRAMMAR_LIB`. Grammar handles are cached for
the process lifetime behind a mutex; failed loads are closed immediately.

A missing or unsupported grammar returns `ContractError.DependencyUnavailable` from
`parse`/`facts`; it is never silently replaced by another language. A tree whose language
does not match the engine fails `facts`/`nodeHash` with `InvalidInput`, `diff` falls back to
`WHOLE_FILE`, and no unknown language uses the Kotlin profile.

## Reproduce the vendored grammars

Checksums for jars and extracted libraries are pinned in
`scripts/extract-tree-sitter-grammars.sh` and
`sources/syntax-treesitter/native/SHA256SUMS`; the extraction script verifies both. Run:

```sh
scripts/fetch-tree-sitter-runtime.sh
scripts/extract-tree-sitter-grammars.sh
scripts/verify-native-libs.sh
```

The runtime script needs `make` and a C compiler; it builds the pinned tree-sitter 0.27.1
source tarball and verifies its SHA-256 first.

Only x86_64 Linux binaries are vendored. Other architectures must provide their own grammar
libraries through `CINT_TREE_SITTER_GRAMMAR_PATH`; the set is intentionally limited to the CI
target and is not silently extended.

## Incremental parsing

`parse(content, previous)` reconstructs the previous source from the contract tree, applies
a byte/point `TSInputEdit` computed from the common prefix/suffix, and reparses with the
edited previous tree. `diff` runs the same edit and returns tree-sitter changed ranges;
unchanged content returns `ChangedRanges.EMPTY`, and a missing previous source falls back to
`ChangedRanges.WHOLE_FILE`. `nodeHash` is a Merkle hash over node kind, child hashes, and
node text.

## Facts

`facts` performs batch extraction from the parsed contract tree. Query ids select fact
categories (`calls`, `writes`, `declarations`, `comments`, `scopes`, `guards`, `returns`,
`properties`, `functions`, `ifs`, `tries`, `imports`, `references`); an empty list extracts
all. Unknown query ids or unprofiled languages fail with `InvalidInput`. The adapter reads no
files, resolves no languages, and never calls knowledge or semantic providers.
