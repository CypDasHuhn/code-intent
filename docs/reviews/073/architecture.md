# Architecture review — 073 LSP provider

Verdict: FINDINGS

1. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/ports/SemanticProvider.kt:9-15` — the port exposes only `request`/`shutdown`; document sync lives only on `LspSemanticProvider.kt:31-54`. `Index` consumes `LanguageSupport` and cannot push in-memory or dirty text/version. Put sync on the port (or a sibling sync port returned via `LanguageSupport`) and make `LspSession` internal.

2. `LspSession.kt:120-143` — `ensureOpen` reads files from disk whenever a URI is closed, bypassing `SourceInput`, index change detection, and epoch/version identity; a request can analyze content different from the indexed source. Require caller-driven sync or explicit text, and fail when the document is not open.

3. `SemanticProvider.kt:36-39` — `HostChannelConfig` is never read, and editor mode only works through a directly injected channel (`LspSemanticProvider.kt:313-326`). No contracts port lets the server/IPC boundary supply a channel to `languages`, and the graph has no server→lsp edge. This blocks editor-provider selection in 074 and editor IPC in 081. Add a host-channel provider port, resolve it via config, and wire it through runtime/languages.

4. `LspSemanticProvider.kt:294,298,476-495` — `lsp` defaults the language to `"kotlin"` and `extractType` hardcodes Kotlin/Java fences and syntax. Language selection belongs to `languages` (074); require a descriptor-supplied language id and move type extraction to a per-language strategy.

5. `LspSemanticProvider.kt:403-409` and `LspSession.kt:54-61` — `rootUri` is never populated, so managed servers initialize without a workspace root; `SemanticProviderConfig` has no root field. Add a neutral root/workspace field and pass it through, or 074/078 managed behavior is incomplete.

6. `contracts/.../facts/SemanticTypes.kt:113,16` — `Diagnostic.source` defaults to `"lsp"`, and `SemanticRequest.offset` has no defined unit while `LspSession.kt:444-459` treats it as a UTF-8 byte offset (LSP columns are UTF-16). Define neutral provenance and a contract-level position convention before index integration.

7. `lsp/src/test/.../ScriptedLspServer.kt:8-13` — the fake server is private to the lsp test source set; `tests/fixtures` exists but has no lsp edge, so 074/078/084 cannot reuse it. Move it to shared fixtures or publish a test artifact.

Transport separation (`LspFraming`/`LspTransport`) and keeping protocol maps inside `lsp` are clean; hover/diagnostic mapping belongs in `lsp` per the module graph. The blockers are the missing sync port, editor-channel wiring, disk readback, and language/root assumptions.

Summary: the module respects `lsp -> contracts`, leaks no LSP protocol types into the pipeline, and all three modes implement `SemanticProvider`. But the port is narrower than the index contract needs, editor mode has no config/IPC wiring path, and `ensureOpen`, root handling, and `"kotlin"` defaults put language/index policy in the adapter. Resolve these before 074 and 078 build on it.

## Round 2

1. CONCUR — the port now has `openDocument`/`changeDocument`/`closeDocument` defaults (`SemanticProvider.kt:13-33`), overridden in `LspSemanticProvider.kt:80-109`; `LspSession` is internal. Additive, so fakes and index sync are unblocked.

2. CONCUR — `LspSession.documentFor` rejects unopened and version-mismatched documents (`LspSession.kt:169-199`); disk readback is gone.

3. CONCUR, wiring deferred — `HostSemanticChannelProvider` added (`SemanticProvider.kt:63-67`) and resolved from config (`LspSemanticProvider.kt:431-458`); 074/081 must still inject it.

4. CONCUR — blank language is rejected (`LspSemanticProvider.kt:405-410`); extraction is injectable via `LspTypeExtractor` (`:28-53`). Residual: the default heuristic still lives in lsp for 074 to replace per language.

5. CONCUR — neutral `rootPath` (`SemanticProvider.kt:31`) is converted to `rootUri` (`LspSemanticProvider.kt:540-568`).

6. CONCUR — `OffsetUnit` and neutral diagnostic provenance (`SemanticTypes.kt:21-28,140`) are honored at `LspSession.kt:596-620`; the field is additive, though the diagnostic source default changes.

7. DISSENT (deferred) — `ScriptedLspServer.kt` remains lsp-test-only; acceptable for 073, but 074/078/084 reuse will duplicate unless shared.

## Round 3

CONCUR — the deferral is legitimate. Ticket 084 owns the cross-surface integration suite, and the graph already permits `integration-tests -> lsp` and `integration-tests -> test-fixtures` (`graph-guard.json:37,45`); ticket 067's shared fakes explicitly forbid network/LSP/filesystem state, so a socket/process scripted server does not belong there yet. 074/078 can use 067's fake `SemanticProvider`/`LanguageSupport`, so no duplication is forced before 084. Residual: the move is not yet named in the 073/084 task text; add it to 084's scope so it is not lost.
