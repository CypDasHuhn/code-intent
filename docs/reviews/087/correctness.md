# 087 Correctness Review

Verdict: FINDINGS (non-blocking)

1. `tests/run.sh:4` does not set `CINT_REQUIRE_SERVER=1`, and `tests/v2_server_spec.lua:11-24` skips successfully when `cint-server` is absent; there is no CI. The installed-engine test and explicit cross-repo failure are optional unless `scripts/compat.sh` is run manually. Require the server in the default test/CI path.

2. `doc/cint.txt:232` omits `c` from the defaults despite `lua/cint/core/config.lua:26`. Update the help text.

Verified: the full external suite passes against the installed V2 server; `protocol.lua` pins version 5/fingerprint `38959a7c...ca88`, matching `ipc/protocol.schema.json`; the engine has no Neovim code.

## Round 2

CONCUR. `tests/run.sh` now defaults `CINT_REQUIRE_SERVER=1` and runs `schema_pin_spec.lua`; `scripts/compat.sh` checks the checked-in schema and pin; README documents required-server behavior. `doc/cint.txt:233` now includes `c`. No remaining correctness findings.
