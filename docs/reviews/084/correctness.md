# 084 Correctness Review

Verdict: FINDINGS (blocking; do not accept/cut over)

1. **CLI parity is mostly failing but the test accepts it.** `CorpusParityTest.kt:262-269` only asserts `matchedAnnotations > 0` and that every defect carries a registered ticket; it never requires zero defects. The committed report records 51/211 annotations, 20/45 candidates, and 325 defects (`v2-parity-report.json:5105-5113`). The suite can pass while nearly every frozen annotation differs. Fix: fail on any defect not covered by an explicitly accepted allowlist.

2. **Deferrals are fictitious and circular.** `CorpusParityTest.kt:473-481` and `CLI_DIFFERENCES.md:50-62` assign all 325 defects to `084.fact-semantics`, `.composition-parity`, `.candidate-parity`, `.pack-defaults`; no such ticket files exist. `docs/tasks/README.md` lists only `084-v2-parity-integration`, whose scope explicitly owns fact semantics, pack defaults, composition, and non-Kotlin syntax. These are real gaps, not documented intentional differences. Fix: fix parity or add named tickets to the queue; acceptance must remain open.

3. **IPC replay checks only method sequences, not transcripts.** `FrozenProtocolParityTest.kt:84-118` never compares message payloads to the frozen NDJSON. `intentionalIds` (`:325-334`) returns a non-empty list for every scenario, making the mismatch branch at `:117` unreachable, so any sequence divergence is silently accepted. Fix: compare full messages against the V2 allowlist and verify each registered difference is actually exercised.

4. **Renderer parity is vacuous.** `FrozenProtocolParityTest.kt:183-232` only checks that selected V2 fields exist and that `renderer-visible-v1.json` exists; it never derives the projection from frozen transcripts or compares values. `merge-parity-report.py:48` converts this to `rendererChecksPassed=true`. Fix: derive and diff the projection against the frozen baseline with renderer-scope allowances.

5. **HTTP parity is not replayed.** `FrozenProtocolParityTest.kt:315-322` excludes every `-http` session although the corpus contains `protocol-http*.session.json`; no other test replays HTTP analyze or health. Fix: add HTTP replay or register an explicit intentional difference.

6. **Reduced comparison keys hide mismatches.** `CorpusParityTest.kt:378-399` compares only role/intents/source/confidence/span, omitting byte offsets, provenance, candidate confidence/identity, and other values; the multiset diff also ignores ordering. Wrong output can count as matched. Fix: compare full semantic JSON after applying the executable allowlist.

7. **IPC collection is timing-dependent.** `quietMessages` returns after 250 ms of idleness (`FrozenProtocolParityTest.kt:412-417`), so on slow CI didOpen publications can be lost or split across steps, producing nondeterministic scenarios and reports. Fix: wait for an expected terminal notification or message count instead of a fixed idle timeout.

8. **The checked-in report is neither regenerated nor verified by CI.** `justfile:95-97` invokes the merge without a path, writing `build/reports/parity/v2-parity-report.json` (`merge-parity-report.py:9`), while the deliverable is `analysis-engine-v2/parity/v2-parity-report.json`. Fix: make the recipe write or compare the committed report.

No V1 runtime dependency was found: the V2 build includes no V1 modules, and `SurfaceIntegrationTest.kt:241-243` guards two V1 classes. CI's `v2` job is now blocking (`ci.yml:77`), but findings 1-4 make it green despite 325 mismatches.

In summary, the wiring exists but the semantic gates do not: CLI parity tolerates 325 unexplained mismatches under non-existent sub-tickets, IPC parity compares only method names, and renderer parity checks shape rather than behavior. The suite is largely deterministic for corpus data, yet its fixed-timeout IPC reader introduces avoidable flakiness, and the committed report is not the artifact CI produces. The "remaining defects" are genuine parity gaps within 084's own scope, not legitimate intentional differences, so this ticket does not meet its acceptance criteria and must not unblock cutover.

## Round 2

1. **CONCUR (partial).** `CorpusParityTest.kt:281-298` now detects baseline count drift and full JSON comparison is real (`:408-433`). It still accepts all 117 defects and `closable:false`; counts alone can miss same-count substitutions.
2. **CONCUR tickets are real; DISSENT closure.** `docs/tasks/088`-`094` and `README.md:113-119` exist, but they depend on `[084]` (`088:3`) and `085` depends only on `084` (`085:3`), so closing 084 can unblock cutover with 117 gaps.
3. **DISSENT.** Payload diffs exist (`FrozenProtocolParityTest.kt:206-266`), but count/method mismatches are still guarded by `intentionalIds(scenario).isEmpty()`, which is never true (`:217-237`, `:325-333`); `:116-123` is likewise unreachable. Wrong/missing messages remain silent.
4. **DISSENT.** `merge-parity-report.py:26-42` derives the projection from the frozen transcripts and compares it to the baseline generated by the same `capture.renderer_snapshot`; that proves V1 self-consistency, not V2 output. The V2 check remains shape-only (`FrozenProtocolParityTest.kt:322-376`).
5. **CONCUR.** HTTP exclusion (`:459`) is now explicitly registered as `http.facade` (`:580`).
6. **CONCUR.** Full sorted semantic JSON including provenance is compared.
7. **CONCUR.** `readExpected` waits for an exact count (`:465-472`); no 250 ms idle wait remains.
8. **CONCUR wiring, DISSENT determinism.** `justfile:95-99` and `ci.yml:79-80` regenerate/diff the committed report, but IPC defect contexts embed random temp roots (`v2-parity-report.json:1461,1473,1485`), so regeneration drifts and CI will fail nondeterministically. Normalize paths.

Verdict: FINDINGS remains blocking. Detection improved to 155/211 and 42/45 as reported, and the tickets/HTTP/report wiring are real, but the IPC gate still suppresses structural mismatches, renderer "derivation" is tautological, the committed report is nondeterministic, and 084's own parity scope is deferred to tickets that depend on 084.

## Round 3

- **CONCUR:** defect identity/content signatures replace count-only baselines (`CorpusParityTest.kt:291-298,496-499`; IPC `FrozenProtocolParityTest.kt:200-207,461-468`); temp roots are normalized (`:444-459`), and the committed report contains no `/tmp` paths.
- **CONCUR:** scenario-wide allowlists are gone; method/count mismatches are now defects (`:236-246`), and every `DIFFERENCE_RULES` entry must be exercised (`:141-142`). Residual: `pathPrefix=""` still allowlists every initialize/error payload field (`:666,682-684`).
- **DISSENT (blocking):** 084 Notes say "blocked by 088-094" (`084:39-40`), but each follow-up depends on `[084]` (`088:4`), and 085 waits on both (`085:4`). This is a dependency cycle: 088-094 cannot start until 084 closes, while 084 must stay blocked until they finish.
- **CONCUR (partial):** renderer parity now projects V2 publishTags and diffs against the frozen baseline (`:324-383`), producing a real `renderer.annotations` defect. Only `tags.annotations` and `initialize.languages` are compared; baseline candidates/config/lifecycle/suggest/restrictions/status remain unchecked.
- **CONCUR:** 092/093 assignments and the 085 dependency update are real; the report correctly remains `closable:false` with 115 defects.

Verdict: FINDINGS remains blocking solely because of the 084↔088-094 dependency cycle. The IPC, renderer, and determinism fixes are substantive, but the documented workflow cannot resolve.
