# Kotlin/Native Platform Spike - Findings

**Date**: 2026-10-25
**Branch**: v2/064-native-spike
**Goal**: Prove the native-platform capabilities V2 depends on before adapters are scheduled.

This file lives at `analysis-engine/docs/rewrite/native-spike.md`. The ticket refers to
`docs/rewrite/native-spike.md`; a pointer lives at that repo-root path.

## Result

The native spike is real, not stubbed, for tree-sitter parsing, JSON, Unix-domain sockets and
child-process spawning. HTTP/TLS remains JVM-only and is documented below as a limitation.

Native run result (excerpt, exit code `0`):

```
=== Kotlin/Native Platform Spike ===

--- tree-sitter (tree-sitter-c, SyntaxEngine) ---
fixture=native-spike/fixtures/sample.c
language=c root=translation_unit nodes=74 errorNodes=0
hash=23696e636c756465 version=tree-sitter-c-0.24.1
PASS: tree-sitter parses C fixture

--- JSON round trip ---
encoded={"name":"spike","value":42,"nested":{"array":[1,2,3],"ok":true}}
decoded={"name":"spike","value":42,"nested":{"array":[1,2,3],"ok":true}}
PASS: JSON round trip preserves document

--- child process spawn ---
exit=0 stdout='child-ok' stderr=''
PASS: spawn captures real stdout and exit code
nonzero exit=7
PASS: spawn reports non-zero exit code

--- Unix domain socket round trip ---
path=/tmp/cint-native-spike-2084619.sock sent='hello-native-socket' received='pong'
PASS: Unix socket listener/client round trip

--- HTTP/TLS ---
native result: status=null error=native HTTP/TLS unavailable: ktor-client-curl needs libcurl/OpenSSL cinterop; use the JVM ktor client fallback
SKIP: native HTTP/TLS is out of scope for this spike; see analysis-engine/docs/rewrite/native-spike.md

SPIKE PASSED
```

Any required check that fails prints `FAIL` and the binary exits non-zero (`1`); a missing fixture
exits `2`.

## Contract alignment

`syntax-treesitter` no longer defines a parallel parser API. `commonMain` exposes
`expect fun createSyntaxEngine(language: String): SyntaxEngine`, and the native actual implements
`dev.cint.contracts.ports.SyntaxEngine` from `contracts`:

- `parse(content: ByteArray, previous, cancellation)` returns `ContractResult<SyntaxTree>`
  using `contracts.facts.SyntaxTree` / `SyntaxNode`.
- The adapter performs no file IO. Fixture loading lives in `native-spike` only.
- `diff` returns tree-sitter changed ranges for an edited previous tree, `EMPTY` for identical
  content, and `WHOLE_FILE` when the previous source cannot be reconstructed.
- `nodeHash` computes a Merkle `NodeHash` over kind, child hashes, and text, and returns
  `NotFound` for unknown ids.
- `facts` extracts the contract's `FactBatch` categories in batch.

The JVM actual uses the same grammar set through the pinned `io.github.bonede:tree-sitter*`
artifacts. Ticket 072 replaced the earlier JVM `DependencyUnavailable` placeholder with a real
implementation; see `syntax-treesitter/GRAMMARS.md`.

## Reproduce

The spike executable is opt-in. `syntax-treesitter` builds against the checked-in
`analysis-engine/native-libs/tree-sitter` runtime and loads grammar libraries from
`analysis-engine/native-libs/tree-sitter-grammars/lib` at runtime. The spike is not linked
unless it is included with `-PnativeSpike`. From the repository root:

```bash
cd analysis-engine
./gradlew --no-daemon -PnativeSpike :native-spike:linkDebugExecutableLinuxX64
CINT_TREE_SITTER_GRAMMAR_PATH="$PWD/native-libs/tree-sitter-grammars/lib" \
  ./native-spike/build/bin/linuxX64/debugExecutable/native-spike.kexe native-spike/fixtures/sample.c
```

Release form:

```bash
./gradlew --no-daemon -PnativeSpike :native-spike:linkReleaseExecutableLinuxX64
CINT_TREE_SITTER_GRAMMAR_PATH="$PWD/native-libs/tree-sitter-grammars/lib" \
  ./native-spike/build/bin/linuxX64/releaseExecutable/native-spike.kexe native-spike/fixtures/sample.c
```

The fixture is `analysis-engine/native-spike/fixtures/sample.c`. The binary also finds
`native-spike/fixtures/sample.c` automatically when no argument is passed.

`settings.gradle.kts` includes `native-spike` only when `-PnativeSpike` is set or a
`native-spike` task is named. The normal `just build` graph therefore excludes it. The spike is
a throwaway leaf and no production module imports it.

## Exact environment and versions

- OS: EndeavourOS (Arch family), `Linux cyp-tower 7.1.8-arch1-3 x86_64`
- JDK: OpenJDK 26 (`/usr/lib/jvm/java-26-openjdk`)
- Gradle: 9.7.0 (wrapper)
- Kotlin: 2.4.10, Kotlin/Native prebuilt `kotlin-native-prebuilt-linux-x86_64-2.4.10`
- `kotlinx-serialization-json`: 1.7.3
- tree-sitter runtime: vendored `analysis-engine/native-libs/tree-sitter` (0.27.1)
- tree-sitter grammars: vendored from `io.github.bonede` Maven artifacts; versions and
  reproduction steps are in `syntax-treesitter/GRAMMARS.md`
- JVM HTTP fallback: ktor `3.1.0` (`ktor-client-apache`)

## Build and link configuration

`syntax-treesitter` is a Kotlin Multiplatform module (`commonMain`, `jvmMain`, `nativeMain`,
`linuxX64Main`) with the `tree-sitter` cinterop for `linuxX64`. It owns all include, library and
linker flags. `native-spike` declares no linker options.

The cinterop def is generated by the `generateTreeSitterDef` task, not at configuration time.
It points at the vendored runtime and uses an `$ORIGIN` rpath for grammar libraries:

```
headers = tree_sitter/api.h grammar_dl.h
headerFilter = tree_sitter/api.h grammar_dl.h
package = dev.cint.v2.syntax.treesitter.cinterop
compilerOpts.linux = -I<native-libs/tree-sitter/include> -I<syntax-treesitter/src/nativeMain/cinterop>
linkerOpts.linux = <native-libs/tree-sitter/lib/libtree-sitter.a> -ldl --rpath=$ORIGIN
```

The native test task copies the pinned grammar libraries next to the test executable. At
runtime the adapter loads a grammar with `dlopen`/`dlsym`; `CINT_TREE_SITTER_GRAMMAR_PATH` can
override the grammar search path. `CINT_TREE_SITTER_INCLUDE`, `CINT_TREE_SITTER_LIB`, and
`CINT_TREE_SITTER_GRAMMAR_LIB` can override build-time directories. A missing grammar produces
`DependencyUnavailable` instead of falling back to another language.

### tree-sitter parsing path

`NativeTreeSitterSyntaxEngine`:

1. `dlopen`/`dlsym` the language's grammar library and resolve its `tree_sitter_*` entry point
2. `ts_parser_new()`, `ts_parser_set_language(...)`
3. for incremental input, reconstruct the previous source, apply `TSInputEdit`, and call
   `ts_parser_parse_string(parser, oldTree, source, byteLength)`
4. `ts_tree_root_node()`, recursive `ts_node_child_count()` / `ts_node_child()` walk into
   `SyntaxTree`/`SyntaxNode`, including byte/line/column spans and source text
5. `ts_tree_get_changed_ranges()` for `diff`; freed with `ts_tree_delete()` and
   `ts_parser_delete()`

The spike asserts root `translation_unit`, more than 10 nodes, and zero `ERROR` nodes. The fixture
parse produced 74 nodes.

### Unix-domain socket path

`NativeUnixSocketClient.roundTrip()` binds and listens on an `AF_UNIX`/`SOCK_STREAM` socket,
`fork()`s a short-lived server child that accepts one connection, reads the client message and
writes `pong`, while the parent connects (retrying briefly), sends `hello-native-socket`, receives
the reply, reaps the child with `waitpid()` and unlinks the socket. The check requires the exact
reply `pong`.

### Child-process path

`NativeProcessSpawner` uses `fork()` + `execvp()` with two pipes for stdout and stderr, then
`waitpid()`. The check runs `/bin/sh -c "printf child-ok"` and requires exit code `0` and stdout
`child-ok`; it also runs `exit 7` and requires exit code `7`.

### JSON path

`kotlinx-serialization-json` encodes/decodes on native. The check parses the decoded document and
requires it to equal the original JSON tree, so it is not a print-only success.

## Known limitation: native HTTP/TLS

Native HTTP/TLS is **not implemented** in this spike. The native client returns an explicit error:

- Exact blocker: `ktor-client-curl` requires libcurl and OpenSSL native development libraries and
  a working native cinterop link setup in the build. This spike intentionally does not add that
  dependency, so the native actual returns
  `native HTTP/TLS unavailable: ktor-client-curl needs libcurl/OpenSSL cinterop`.
- Concrete fallback: use the JVM `ktor-client-apache` implementation (`JvmHttpClient`) for V2
  HTTP/TLS until a native libcurl/OpenSSL cinterop is added. A future native option is
  `ktor-client-curl` with `libcurl`/`openssl` packages and the corresponding linker flags.

## Module graph note

`native-spike` is not a production module. It has one dependency, `syntax-treesitter`, and is not
listed in the production module graph in `docs/rewrite/module-graph.md`; see the spike exception
there. Removing the unused `native-spike -> contracts` edge also removed the false graph claim;
contract types reach the spike through `syntax-treesitter`'s `api(project(":contracts"))`.

## Residual gaps

- Native TLS/HTTP remains JVM-only as described above.
- Vendored native grammar binaries cover x86_64 Linux only; other architectures must supply
  their own libraries through `CINT_TREE_SITTER_GRAMMAR_PATH`.
- The Unix-socket server is an in-process `fork()` helper for the spike, not a reusable server API.
- The ticket path `docs/rewrite/native-spike.md` differs from the actual file location; the
  repo-root file is only a pointer, not a duplicate.

## Verification commands

```bash
# from repo root
just graph-guard
just build

# opt-in native spike
cd analysis-engine
./gradlew --no-daemon -PnativeSpike :native-spike:linkDebugExecutableLinuxX64
CINT_TREE_SITTER_GRAMMAR_PATH="$PWD/native-libs/tree-sitter-grammars/lib" \
  ./native-spike/build/bin/linuxX64/debugExecutable/native-spike.kexe native-spike/fixtures/sample.c
```
