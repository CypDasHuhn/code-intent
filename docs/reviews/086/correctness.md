# 086 Correctness Review

Verdict: FINDINGS (non-blocking)

1. Legacy capability flags ignore the version: `SessionHandler.kt:551-564` hardcodes `restrictions=true`/`cache=true` for every protocol below 5 (`:82,91-92`). V1 exposed them only at >=4; the installed server now reports them for 1-4, so protocol-3 clients see unsupported features. Derive flags from the negotiated protocol/settings and test 1-3.

2. `ProtocolFingerprint.kt:182` accepts 1..5, but V1 evidence covers only 3/4 (`ipc/known-differences-v2.json:31`) and all legacy versions share one shape. `ProtocolCompatibilityTest.kt:39-51` checks only handshakes. Narrow the range to versions with real shape coverage, or implement per-version dispatch/tests.

3. `ProtocolMessages.kt:493-495` defaults `protocol` to 4, so an `initialize` without a version silently downgrades to legacy. Require the version (or reject omission).

Version 5 handshake, fingerprint-mismatch, schema publication, and drift test check out.

## Round 2

CONCUR. `SessionHandler.kt:551-564` now keys restrictions/cache off `protocol >= 4`, with `LiveServerTest.kt:372-387` asserting 3=false/4=true. `ProtocolCompatibility` is the explicit 3/4/5 matrix; 1-2 are rejected and schema/docs/tests agree. `InitializeParams.protocol` is required, with `missing-protocol` covered by `ProtocolCompatibilityTest.kt:59-80`. Residual: `ProtocolFingerprint.kt:63` still carries the unused legacy default, but the field is required so it cannot apply.
