# MCP deferrals

## Ticket 083: durable change proposals

`changes.propose` and `changes.apply` persist staged proposals in the init
checkpoint document (`.cint/init-checkpoint.json` by default). Proposal ids are
canonicalized over normalized changes, so identical retries with different
client timestamps or ordering reuse one proposal; explicit ids with different
payloads are rejected. Re-applying an applied proposal is idempotent, and
knowledge is reconciled before replay so a crash between knowledge persistence
and checkpoint update does not lose or duplicate work.

Init checkpoints record completed steps, analyzed paths and content hashes,
pending proposals, and a revision. Checkpoint files hold multiple workflows and
use an exclusive file lock plus atomic writes so concurrent MCP processes merge
updates instead of overwriting each other.

Checkpoint persistence lives behind `InitCheckpointStore` in `contracts`; the
filesystem adapter lives in `knowledge-persistence` and is exposed to apps
through `runtime`, keeping MCP free of adapter imports.

Limitation: an MCP invocation without `--root` or `--checkpoint` uses the
in-memory checkpoint store, so init state does not survive process restarts.
Pass `--root` (or an explicit `--checkpoint` path) for durable init.

## Ticket 099: editor provider channels

`--provider-config` can describe an editor provider with `hostChannelConfig`,
and `repo.languages` reports whether that configuration is complete. The MCP app
does not yet inject a `HostSemanticChannelProvider`, so editor mode is not
executable through `cint-mcp`; managed and attached providers are the supported
startup modes. `repo.languages` marks editor entries unavailable and names the
missing host channel provider rather than probing or starting an LSP.
