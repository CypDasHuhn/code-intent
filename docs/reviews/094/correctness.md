# 094 Correctness Review

Verdict: FINDINGS (blocking)

1. **Renderer candidate defects are hidden.** `FrozenProtocolParityTest.kt:441` passes `registeredOnIpcSurface=true`, and `:567` suppresses unmatched renderer differences for such sections. The committed report records `candidates` `passed:false`, `unmatchedCount:19`, `intentionalDifferenceIds:[]`, and `rendererDerivation.passed:false`, yet `defects:[]`, `ipcDefects:0`, `closable:true`. The 19 provider/enrichment/group differences are neither fixed nor registered as intentional. `merge-parity-report.py:45` adds a renderer defect only when `verified` is false, not when `passed` is false.

2. **Renderer drift is not gated.** The test asserts only hardcoded `verified:true` (`:510`), never `passed` or the `checks` values (`:202`). A regression in fingerprint/languages/diagnostics/proposals/span checks would leave the gate green. `rendererChecksPassed` is summary-only.

3. **IPC payload drift is gated.** The empty `ipc-defect-baseline.json` plus exact signature comparison makes any new non-allowlisted method/payload mismatch fail. Per-message initialize/error rules replaced whole-frame `pathPrefix=""`; all rules are exercised.

4. **No-auto-materialization fix works** (`Main.kt:74-75`, test `:797-798`): it removes the three prior `ipc.payload` defects and matches CLI no-init semantics. It changes live-server behavior, so it needs explicit ownership/documentation, but I found no masking there.

5. **Determinism:** temp roots and uptime are normalized; the committed report has no `/tmp` paths.

## Round 2

Verdict: CONCUR

- **CONCUR:** Prior finding 1 is fixed. `registeredOnIpcSurface` suppression is gone (`FrozenProtocolParityTest.kt:571`), candidates/groups are fully projected (`:618`), and unmatched renderer differences now become defects. The report shows `candidates` passed, 35 differences all `suggest.candidate-enrichment`/079, `unmatchedCount:0`, `defects:[]`, `closable:true`.
- **CONCUR:** Prior finding 2 is fixed. The test asserts `passed`, every `checks` value, and exercised rules (`:201-213`); `merge-parity-report.py:54-69` defects unverified, unpassed, drifted-check, and unexercised renderer states. Renderer drift now fails the gate.
- **CONCUR:** 211/211 annotations, 45/45 candidates, empty IPC defect baseline, per-message rules exercised, and normalized temp/uptime output. The report is deterministic and `closable:true` is supported.
- **Residual (non-blocking):** candidate/group allowlist rules use candidate/group prefixes, not field-level paths; a future non-enrichment change within candidates 6/9 or groups 3/5 would be masked. Tighten to enrichment fields or exact paths.
