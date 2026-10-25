# Vendored tree-sitter runtime

`lib/libtree-sitter.a`, `lib/libtree-sitter.so`, and `include/tree_sitter/api.h` are built from
the upstream tree-sitter `v0.27.1` source tarball. The native adapter links the static archive;
the shared object is retained for reference and for non-static consumers.

SHA-256:

```
5a597cb122aa6f2e686e3816a321cffe17afcd2251a8eac6f4fb24f7a4dcd8a7  libtree-sitter.a
efffa740ce0d960560551a6485ecea522d96686826d4ef49f3e4ba1a34b4b9ef  libtree-sitter.so
efffa740ce0d960560551a6485ecea522d96686826d4ef49f3e4ba1a34b4b9ef  libtree-sitter.so.0.27
f8480415ae20cb5e0fd519ba44c84268c5acb4c918106238fc1d328d91431783  include/tree_sitter/api.h
```

Regenerate with `analysis-engine/scripts/fetch-tree-sitter-runtime.sh`. The script pins the
upstream tarball SHA-256, builds static/shared libraries, and installs the matching header. The
runtime is MIT-licensed; see `LICENSE.tree-sitter`.

The JVM and native adapters record their runtime identity in `SyntaxTree.version` so consumers can
detect divergence.
