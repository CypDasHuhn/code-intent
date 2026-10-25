# Architecture review — ticket 085

Verdict: **FINDINGS**

1. **Manual gate table is malformed and records an invalid V2 flag.** `docs/manual-test.md:24` has six cells under a five-column header, so the appended "V1-only" note becomes a phantom column; `docs/manual-test.md:12` records `--no-lsp`, which V2 rejects (`docs/engine.md:96`). Fix: fold the note into `note` and use a valid V2 command.

2. **The queue still reports completed cutover work as pending.** `docs/tasks/README.md:112-114` and the frontmatter of 085/086/087 say `todo` while `docs/summary/current-state.md` says implemented. Fix: mark them done (orchestrator owns status).

Boundaries otherwise hold: one `analysis-engine/` tree, V1 only in `frozen-v1-corpus` and legacy IPC paths, no renderer coupling in engine source.

## Round 2

CONCUR 1 — `docs/manual-test.md:12` drops `--no-lsp`, and `:24` is back to five columns with the V1 note folded into `note`.

DISSENT 2 — `docs/tasks/README.md:112-114` and the 085-087 frontmatter still say `todo`. Status is orchestrator-owned, so this is a handoff residual rather than an implementor defect.

## Round 3

CONCUR — status closure is an orchestrator action; no architecture residual.
