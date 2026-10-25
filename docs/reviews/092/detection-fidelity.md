# Detection fidelity review — 092

Verdict: CONCUR

The committed `v2-parity-report.json` shows 211/211 annotations, 45/45
candidates, 16/16 CLI files, 0 CLI defects, 0 IPC defects, and `closable:
true`; no defect is assigned to 092. Service-init keys match the frozen corpus:
Kotlin `ServiceIntents.kt` constructor/lifecycle spans 0:21-57, 9:8-18,
10:21-33, 11:8-24, and 15:50-91; TypeScript 1:14-55 and 10:11-28; C# 11:19-42.
The Kotlin `initialize` evidence and scaffold/derivative provenance also match.

The IPC reattribution was legitimate. `e6d2491`'s fallback assigned unrelated
payload drift to 092; `0b2d878` routes only `SERVICE_INIT`/`service:init` there,
sending derivative-use/scaffold to 090 and candidate/hash/logging drift to
091/094. The observed pre-reattribution IPC defects contained no SERVICE_INIT
payload difference, and none remain.

Non-blocking caveats: each corpus constructor has only one wiring parameter, so
multi-parameter span behavior is not gate-covered, and V2 has no dedicated
multi-parameter test. Ticket 092/README still say `todo`, and the report's
deferral list still names 092; update that bookkeeping on closure.
