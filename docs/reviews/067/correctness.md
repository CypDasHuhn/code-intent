# Correctness review — 067 shared fakes

Verdict: **FINDINGS**

1. `tests/fixtures/.../fakes/FakeRuntime.kt:146-175` — multi-target analysis flat-merges facts and annotations but keeps only the first syntax tree. Node ids such as `fn-0-0` collide across files, so later facts reference nodes absent from the retained tree. Tests only analyze one path. Fix: return per-file results or qualify ids and retain a tree per file; add a two-file test.
2. `fakes/FakeIndex.kt:42-46` — the cache key is `path#contentHash`, omitting language and version. Re-ensuring identical text at a new version returns the old `IndexedSource` (old `input.version` and tree), and a different language/support can reuse the wrong parse. Fix: include language and version in the key (or rebuild the wrapper) and test version changes.
3. `fakes/FakeIndex.kt:61-70` — the `SemanticResult` is checked then discarded. Neither `IndexedSource` nor `AnalysisResult` carries semantic facts, so the end-to-end “semantic” leg only proves a call occurred; any provider value passes. Fix: represent semantic facts in the contract/result and have `FakeIntentPipeline` consume them, then assert values in the integration test.
4. `fakes/FakeSemanticProvider.kt:29-38` — requests ignore `request.deadline` and always return a value. `FakeClock` deadlines are never enforced, so timeout/`Unavailable` paths are untestable. Fix: inject `FakeClock`, check the deadline, return `Unavailable.timeout`, and add expired/unavailable tests.
5. `analysis-engine-v2/build.gradle.kts:63-67` + `GraphGuard.kt:93-97` — the guard merges all Gradle configurations, so a production module’s `testImplementation(project(":test-fixtures"))` is classified as a production edge and rejected. Module unit tests therefore cannot use the shared fakes, defeating 067’s purpose. Fix: collect edges per configuration and allow test-only targets from test configurations; test this case.
6. `tests/fixtures/.../FakePortsTest.kt:332-347` — `everyDeclaredPortHasAFake` only constructs eight classes. `IpcTransport`/`McpTransport` (`contracts/.../ports/Transports.kt:16,27`) have no fakes, and the scope’s fallback-AI fake/port is absent; the test passes regardless. Fix: enumerate ports explicitly or reflectively, add fake transports and an AI proposal fake, or record a deferral.
7. `fakes/FrozenV1Corpus.kt:78-90,111-124` — `protocol()` builds invalid paths for names ending `.session.json`; `locate()` uses ambient cwd/env, the integration build never pins `v2.frozenCorpusRoot`, and tests never verify manifest hashes. A different or corrupted corpus can pass. Fix: normalize suffixes, set the root property in the test task, verify hashes, and add missing-file assertions.
8. `fakes/FakeSyntaxEngine.kt:82-88` — line starts use UTF-16 `line.length + 1`, so `startByte`/`endByte` are wrong for non-ASCII or CRLF input, making spans, facts, and hashes inconsistent. Fix: derive starts from UTF-8 bytes and handle CRLF; add non-ASCII/CRLF tests.

The fakes are substantive rather than fixed-value stubs, the single-file end-to-end path is real, and the cancellation/IO checks are mostly meaningful. However, the multi-file merge is incoherent, the index cache is input-lossy, semantic data is discarded, deadline behavior is absent, the test-edge policy blocks the fakes’ intended consumers, and the corpus loader is ambient and partially broken. Correctness is therefore not complete.

## Round 2

1. CONCUR — `FakeRuntime.kt:83-171,184-235` now prefixes/qualifies every fact id and merges per-file trees; `FakePortsTest.kt:416-466` exercises two files.
2. CONCUR — `FakeIndex.kt:50-64` keys path/descriptor/language/version/hash; `FakePortsTest.kt:222-235` covers version and support changes.
3. CONCUR — `IndexTypes.kt:35` adds `semanticFacts`; `FakeIndex.kt:77-95` stores it; `FakeIntentPipeline.kt:47-64` consumes TypeAt confidence; `FakeEndToEndTest.kt:54-57` proves LOW.
4. CONCUR — `FakeSemanticProvider.kt:39-47` enforces FakeClock deadlines; `FakePortsTest.kt:245-258`.
5. CONCUR — `build.gradle.kts:59-90` splits production/test configurations; `GraphGuard.kt:80-115`; `GraphGuardValidatorTest.kt:190-237` covers the allow/deny matrix.
6. CONCUR — `FakeTransports.kt:15-116`; `FakePortsTest.kt:276-298,469-502`; AI deferral recorded at `FakeTransports.kt:114-116`. Catalog remains hand-maintained.
7. CONCUR — `FrozenV1Corpus.kt:78-135`; integration build pins `v2.frozenCorpusRoot`; `FrozenCorpusLoaderTest.kt:12-46` checks suffixes, hashes, missing files. Verified manifest hashes match.
8. CONCUR — `FakeSyntaxEngine.kt:79-121,184-205`; `FakePortsTest.kt:261-273` covers UTF-8/CRLF.

No new regressions or vacuous fixes. Non-blocking residuals: `FakeIndex.kt:61-63` does not refresh `latestByPath` on a cache hit, so `snapshot` can differ from the last ensure; `FakeIntentPipeline.kt:47-50` ignores `Unavailable` semantic results. Both are test-double limitations, not 067 blockers.

Verdict: CONCUR on all eight round-1 findings; the fixes are real and tested.
