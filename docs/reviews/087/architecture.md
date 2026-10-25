# Architecture review — ticket 087

Verdict: **FINDINGS**

1. **No released-engine pin or automated cross-repo gate.** `code-intent-nvim/scripts/compat.sh:5` and `code-intent-nvim/tests/v2_server_spec.lua:6-8` resolve a local `analysis-engine/.../build/install` path; the repo has no CI, and `code-intent-nvim/tests/run.sh:4` runs `v2_server_spec` without `CINT_REQUIRE_SERVER=1`, so the only real-server test may silently skip. Engine CI (`.github/workflows/ci.yml:41-59`) cannot detect renderer pin drift either. Fix: publish/tag an engine distribution, point compat at it, and run `compat.sh` with the server required in CI.

Boundaries hold: the renderer pins version 5 plus fingerprint (`code-intent-nvim/lua/cint/protocol.lua:1-5`), and the engine contains no Neovim code.

## Round 2

CONCUR 1 — `code-intent-nvim/scripts/compat.sh` now checks the pin against `protocol.schema.json` via `tests/schema_pin_spec.lua`, and `tests/run.sh:5-6` defaults `CINT_REQUIRE_SERVER=1`.

DISSENT 1 (residual) — no CI runs those checks, and compat still targets the sibling `build/install` binary, not a released artifact (`scripts/compat.sh:5`; `docs/ipc/PROTOCOL.md:115-122` acknowledges this). The released-engine acceptance remains unmet.

## Round 3

CONCUR — residual resolved. `just release` (`scripts/package-release.sh`) produces `cint-engine-<version>.tar.gz` plus `.sha256`, with `VERSION` and `RELEASE.json` carrying protocol version/fingerprint/commit. `code-intent-nvim/scripts/engine-env.sh` extracts and verifies `CINT_ENGINE_ARTIFACT`; `tests/run.sh` and `compat.sh` consume it; `.github/workflows/ci.yml` runs both. Minor residual: CI needs `CINT_ENGINE_ARTIFACT_URL` configured because no public release is hosted yet.
