# Manual test gate

Acceptance criteria that cannot be automated. A ticket may be `done` while an
entry is unverified, but the entry must exist and name the ticket. Superseded
rows stay for history and are annotated.

| id | criterion | ticket | status | note |
|---|---|---|---|---|
| m001 | Batch cutover parity: a `cint` run over `testing-grounds/` emits the same intent set as the pre-restructure engine | 015 | unverified | Not yet run; the pre-restructure baseline must be captured before 014 deletes the legacy path |
| m002 | Live daemon cutover: `cint-server` analyzes a buffer and publishes; the renderer paints it and survives a `:CintServerRestart` | 015, 016 | unverified | Requires a live socket and nvim; run after the host cutover and the new payload |
| m003 | Logging golden-fixture parity: replay the bundled `logging` rulepack against the golden `tags.json` fixtures and compare `LOGGING` source spans, tiers and confidence with the pre-restructure engine | 006 | unverified | No golden fixtures or `tags.json` are present in this worktree or its history; `testing-grounds/` parity is automated in `LoggingRulePackTest` |
| m004 | Live daemon runs on the scheduler pipeline: start `cint-server` on a Unix socket, open/change a buffer, and receive versioned `cint/publishTags` | 015 | verified | Run with `analysis-engine/build/install/cint-server/bin/cint-server --socket /tmp/opencode/cint-015.sock --root testing-grounds --no-lsp`; `initialize` + `didOpen` v1 + `didChange` v2 produced publishes with matching versions, content hashes, structured provenance, and `LOGGING`/`ERROR_HANDLING` intents; stopping and restarting the daemon on the same socket repeated the publish successfully (no `Phase` path). |
| m005 | Renderer annotation contract over the live socket: `cint/publishTags` carries per-contributor `provenance` and a reserved `nodeAnchor`; nvim paints source-intent, derivative-use and scaffold spans, and a pack-introduced unknown intent resolves through `resolve.default` without a client change | 016 | unverified | Requires a live `cint-server` socket and nvim; run after the 015 host cutover using `:CintLiveTags` and `cint.provenance_at()` |
