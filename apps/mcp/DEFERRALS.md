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
