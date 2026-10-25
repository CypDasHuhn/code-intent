# 085 Correctness Review

Verdict: FINDINGS (non-blocking)

1. `docs/manual-test.md:12` tells reviewers to run `cint-server ... --no-lsp`, but V2 rejects it (verified: `unknown flag: --no-lsp`; flags are `--socket`, `--root`, `--lang`). Remove the flag.

Verified: V1 deleted; V2 only in `analysis-engine/`; no active `-v2` module/executable names; justfile/CI/scripts/docs paths unified; ktlint clean; test artifacts 640 tests/0 failures; renderer tests only external.

## Round 2

CONCUR. `docs/manual-test.md:12` no longer uses `--no-lsp`; m015 is now five columns with the V1 note folded into `note`; m017/m018 cover the distribution and external-renderer gates. No remaining correctness findings.
