# Vendored tree-sitter grammar libraries

These are x86_64 Linux shared libraries extracted from pinned Maven artifacts. They are loaded
at runtime by `syntax-treesitter`; they are not linked into the executable.

| File | Maven artifact | Version | License |
|---|---|---|---|
| `lib/libtree-sitter-c.so` | `io.github.bonede:tree-sitter-c` | 0.24.1 | MIT |
| `lib/libtree-sitter-kotlin.so` | `io.github.bonede:tree-sitter-kotlin` | 0.3.8.1 | MIT |
| `lib/libtree-sitter-javascript.so` | `io.github.bonede:tree-sitter-javascript` | 0.23.1 | MIT |
| `lib/libtree-sitter-typescript.so` | `io.github.bonede:tree-sitter-typescript` | 0.23.2 | MIT |
| `lib/libtree-sitter-vue.so` | `io.github.bonede:tree-sitter-vue` | 0.2.1a | MIT |
| `lib/libtree-sitter-c-sharp.so` | `io.github.bonede:tree-sitter-c-sharp` | 0.23.1 | MIT |

Checksums are pinned in `../SHA256SUMS` and in
`analysis-engine-v2/scripts/extract-tree-sitter-grammars.sh`, which verifies both the downloaded
jar and the extracted library. Regenerate with that script.

Only x86_64 Linux binaries are vendored. Other architectures must provide their own grammar
libraries and point `CINT_TREE_SITTER_GRAMMAR_PATH` at them; unsupported architectures fail with
`DependencyUnavailable` rather than silently using another grammar.
