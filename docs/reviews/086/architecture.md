# Architecture review — ticket 086

Verdict: **FINDINGS**

1. **Architecture notes still deny protocol versioning.** `docs/rewrite/module-graph.md:62-63,301-302,417` says IPC is lockstep with "no version negotiation" and "not a version matrix", but `analysis-engine/ipc/src/main/kotlin/dev/cint/ipc/ProtocolFingerprint.kt:181-215` implements versions 1-5 and a compatibility matrix. Fix: replace those statements with the negotiated-version and fingerprint rules.

2. **The external contract mixes server notifications with requests.** `docs/ipc/PROTOCOL.md:84-87` lists `cint/publishTags`/`cint/publishCandidates` as methods, but `IpcMethods.all` excludes both and `analysis-engine/ipc/protocol.schema.json`'s `x-methods` omits them; `cint/suggest/progress` is absent. Fix: separate server-to-client notifications from routable requests.

## Round 2

CONCUR 1 — `docs/rewrite/module-graph.md:62-65,262,304,421` now states explicit negotiated versions, legacy 3-4, current 5, and fingerprint rules; no lockstep claim remains.

CONCUR 2 — `docs/ipc/PROTOCOL.md:58-110` and `analysis-engine/ipc/protocol.schema.json:37-68` now separate `x-requests` from `x-notifications`, including `publishTags`, `publishCandidates`, and `suggest/progress`.
