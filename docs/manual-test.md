# Manual test gate

Acceptance criteria that cannot be automated. A ticket may be `done` while an
entry is unverified, but the entry must exist and name the ticket. Superseded
rows stay for history and are annotated.

| id | criterion | ticket | status | note |
|---|---|---|---|---|
| m001 | Batch cutover parity: a `cint` run over `testing-grounds/` emits the same intent set as the pre-restructure engine | 015 | unverified | Not yet run; the pre-restructure baseline must be captured before 014 deletes the legacy path |
| m002 | Live daemon cutover: `cint-server` analyzes a buffer and publishes; the renderer paints it and survives a `:CintServerRestart` | 015, 016 | unverified | Requires a live socket and nvim; run after the host cutover and the new payload |
