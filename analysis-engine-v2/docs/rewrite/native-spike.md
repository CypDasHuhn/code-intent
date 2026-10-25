# Kotlin/Native Platform Spike - Findings

**Date**: 2026-10-25
**Branch**: v2/064-native-spike
**Goal**: Prove the native-platform capabilities V2 depends on before adapters are scheduled.

This file lives at `analysis-engine-v2/docs/rewrite/native-spike.md`. The ticket refers to
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
SKIP: native HTTP/TLS is out of scope for this spike; see analysis-engine-v2/docs/rewrite/native-spike.md

SPIKE PASSED
```

Any required check that fails prints `FAIL` and the binary exits non-zero (`1`); a missing fixture
exits `2`.

## Contract alignment

`syntax-treesitter` no longer defines a parallel parser API. `commonMain` exposes
`expect fun createSyntaxEngine(): SyntaxEngine`, and the native actual implements
`dev.cint.contracts.ports.SyntaxEngine` from `contracts`:

- `parse(content: ByteArray, previous, cancellation)` returns `ContractResult<SyntaxTree>`
  using `contracts.facts.SyntaxTree` / `SyntaxNode`.
- The adapter performs no file IO. Fixture loading lives in `native-spike` only.
- `diff` returns `WHOLE_FILE` when content hashes differ, otherwise `EMPTY`.
- `nodeHash` computes a `NodeHash` from the requested node and returns `NotFound` for unknown ids.
- `facts` currently returns `FactBatch(syntaxTree = tree)` without extracted facts. Fact queries
  are a later adapter concern, not part of this spike.

The JVM actual returns `DependencyUnavailable` for tree-sitter operations instead of faking a
parse. It is not used as proof.

## Reproduce

The spike executable is opt-in. However, `syntax-treesitter` is a normal module and configures its
Linux `linuxX64` cinterop unconditionally, so any default build that configures or compiles that
target (including `just v2-build`) requires the `tree-sitter` and `tree-sitter-c` system
headers/libraries in `CINT_TREE_SITTER_INCLUDE`/`CINT_TREE_SITTER_LIB` (defaults `/usr/include`
and `/usr/lib`). The spike is not linked unless it is included with `-PnativeSpike`. From the
repository root:

```bash
cd analysis-engine-v2
./gradlew --no-daemon -PnativeSpike :native-spike:linkDebugExecutableLinuxX64
./native-spike/build/bin/linuxX64/debugExecutable/native-spike.kexe native-spike/fixtures/sample.c
```

Release form:

```bash
./gradlew --no-daemon -PnativeSpike :native-spike:linkReleaseExecutableLinuxX64
./native-spike/build/bin/linuxX64/releaseExecutable/native-spike.kexe native-spike/fixtures/sample.c
```

The fixture is `analysis-engine-v2/native-spike/fixtures/sample.c`. The binary also finds
`native-spike/fixtures/sample.c` automatically when no argument is passed.

`settings.gradle.kts` includes `native-spike` only when `-PnativeSpike` is set or a
`native-spike` task is named. The normal `just v2-build` graph therefore excludes it. The spike is
a throwaway leaf and no production module imports it.

## Exact environment and versions

- OS: EndeavourOS (Arch family), `Linux cyp-tower 7.1.8-arch1-3 x86_64`
- JDK: OpenJDK 26 (`/usr/lib/jvm/java-26-openjdk`)
- Gradle: 9.7.0 (wrapper)
- Kotlin: 2.4.10, Kotlin/Native prebuilt `kotlin-native-prebuilt-linux-x86_64-2.4.10`
- `kotlinx-serialization-json`: 1.7.3
- tree-sitter runtime: Arch package `tree-sitter 0.26.9-1`
  - `/usr/lib/libtree-sitter.so` -> `/usr/lib/libtree-sitter.so.0.26`
  - `/usr/include/tree_sitter/api.h`
- tree-sitter C grammar: Arch package `tree-sitter-c 0.24.1-2`
  - `/usr/lib/libtree-sitter-c.so` -> `/usr/lib/libtree-sitter-c.so.15.0`
  - `/usr/include/tree_sitter/tree-sitter-c.h`
- JVM HTTP fallback: ktor `3.1.0` (`ktor-client-apache`)

## Build and link configuration

`syntax-treesitter` is a Kotlin Multiplatform module (`commonMain`, `jvmMain`, `nativeMain`,
`linuxX64Main`) with the `tree-sitter` cinterop for `linuxX64`. It owns all include, library and
linker flags. `native-spike` declares no linker options.

The cinterop def is generated into `analysis-engine-v2/syntax-treesitter/build/cinterop/tree-sitter.def`
at configuration time from the environment:

```bash
CINT_TREE_SITTER_INCLUDE=/usr/include \
CINT_TREE_SITTER_LIB=/usr/lib \
./gradlew --no-daemon -PnativeSpike :native-spike:linkDebugExecutableLinuxX64
```

Both variables default to `/usr/include` and `/usr/lib`. The generated def contains:

```
headers = tree_sitter/api.h tree_sitter/tree-sitter-c.h
headerFilter = tree_sitter/api.h tree_sitter/tree-sitter-c.h
package = dev.cint.v2.syntax.treesitter.cinterop
compilerOpts.linux = -I$CINT_TREE_SITTER_INCLUDE
linkerOpts.linux = -L$CINT_TREE_SITTER_LIB -ltree-sitter -ltree-sitter-c
```

Because the linker options are recorded in the cinterop klib, any future consumer of
`syntax-treesitter` inherits them; `ldd` on the spike confirms `libtree-sitter.so.0.26` and
`libtree-sitter-c.so.15.0`.

### System packages are required

There is no vendored grammar fallback. The checked-in
`analysis-engine-v2/native-libs/tree-sitter` contains tree-sitter 0.28 headers/library but no
grammar, and it is not used here. A machine without `tree-sitter` and `tree-sitter-c` installed
must install distro packages whose headers/libraries can be pointed at with the variables above;
this spike does not provide a from-source vendoring recipe. That is a deliberate scope cut, not a
claim that the vendored directory is a working fallback.

### tree-sitter parsing path

`NativeTreeSitterSyntaxEngine`:

1. `ts_parser_new()`
2. `ts_parser_set_language(parser, tree_sitter_c())`
3. `ts_parser_parse_string(parser, null, source, byteLength)`
4. `ts_tree_root_node()`, recursive `ts_node_child_count()` / `ts_node_child()` walk into
   `SyntaxTree`/`SyntaxNode`, including byte/line/column spans and source text
5. Freed with `ts_tree_delete()` and `ts_parser_delete()`

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

- Native parser registers only the `tree-sitter-c` grammar and only supports language `c`.
- Other languages need grammar shared libraries and cinterop entries.
- Native TLS/HTTP remains JVM-only as described above.
- Fact extraction is not implemented; `facts` returns the syntax tree only.
- The Unix-socket server is an in-process `fork()` helper for the spike, not a reusable server API.
- The ticket path `docs/rewrite/native-spike.md` differs from the actual file location; the
  repo-root file is only a pointer, not a duplicate.

## Verification commands

```bash
# from repo root
just v2-graph-guard
just v2-build

# opt-in native spike
cd analysis-engine-v2
./gradlew --no-daemon -PnativeSpike :native-spike:linkDebugExecutableLinuxX64
./native-spike/build/bin/linuxX64/debugExecutable/native-spike.kexe native-spike/fixtures/sample.c
```
