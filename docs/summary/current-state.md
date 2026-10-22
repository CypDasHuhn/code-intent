# Current State and Continuation Notes

As of 2026-10-22, after tickets 041–057. Branch `main` is clean apart from
local `.cint/**` project state.

## Where the project is

CodeIntent is a Kotlin analysis engine plus a Neovim client.

- The engine parses Kotlin, JavaScript, TypeScript, Vue, and C# through
  tree-sitter, optionally queries `kotlin-lsp` for types, runs bundled
  rulepacks through a stratified scheduler, composes return/derivative/scaffold
  annotations, and publishes tags plus candidates.
- The client speaks a local Unix-socket JSON-RPC protocol 3 to a per-project
  daemon (`cint-server`). It renders annotations through configurable meanings
  and handlers, supports user annotations, AI candidate review, intent labels,
  mixed-intent stripes, and a cursor hover inspector.
- The batch CLI and HTTP host share `HostPipeline` with the live server.

## Recently completed work

| ticket | result |
|---|---|
| 041 | Shared annotation lock hardened: liveness-based stale detection, ownership tokens, atomic steal claim, tombstones; one documented claim-reaping residual. |
| 042 | Rulepack fidelity: structural increment targets, `@Inject`/TS parameter-property injection, factory/init split, durable persistence typing, configured read-prefix ordering. |
| 044 | Oracle caching and budgeting: per-analysis receiver memoization, per-document-version LSP cache with bounded negative TTL, timeout/failure budget, diagnostics. |
| 048 | Mutable-local declaration coverage (`mutableLocalDeclarations`, default off), callback invocation effects, durable callback classification, SAM/alias handling. |
| 049 | `READONLY` user directive alongside `PURE`, both side-effect-free for composition; only `PURE` claims determinism. |
| 050 | Automated JVM+Neovim cross-writer lock test with interval records and conflict markers. |
| 051 | Size-hygiene refactor: no EXTREME files remain; server, rulepacks, core/user, API, suggest, host, and tests split by responsibility. |
| 052 | Neovim intent hover: non-focusable cursor-relative float, tag-colored intents, `CursorHold`/`:CintHover`, close on move/text/resize/scroll. |
| 053 | Hover narrows to the innermost annotation span; coincident spans combine. |
| 054 | Hover omits reserved `UNTAGGED`; untagged-only spans are skipped during selection. |
| 055 | AI arrival UI: distinct `AI` sign/badge/highlight, one arrival notification per AI-count increase, `(N AI)` status. |
| 056 | Scaffold `UNTAGGED` visibility, whole-statement `RETURN_VALUE`, nested return-flow propagation, SEARCH/LOOKUP clarification, broader AI prompt/defaults. |
| 057 | AI candidate/enrichment caps default to unlimited (`0`); positive caps remain configurable. |

## Current behavior worth knowing

- `RETURN_VALUE` spans the whole `return` statement and propagates to local
  declarations whose values flow into the returned expression.
- `SEARCH` means querying a collection/repository where the result may be
  absent; prior validation does not turn it into a lookup. `LOOKUP` means
  retrieving a known-to-exist static/preset value (map/table/enum/config/
  registry). `findById`/`getById`/`loadById` are SEARCH without a default
  LOOKUP alternative; `getBytes` is not SEARCH; `getByKey` is LOOKUP.
- AI enrichment is uncapped by default: every AI-eligible pack candidate is
  sent to the provider and every validated proposal is retained. Candidate
  generation is still pack-driven, not per-line. The provider timeout remains
  the safety valve, so large files can cost more and time out.
- Hover shows only the innermost span under the caret and never shows
  `UNTAGGED`.
- AI candidates render differently from deterministic candidates (AI sign,
  purple underline, ` AI` badge, arrival notification).

## Open work

- **058-061 (proposed): AI annotation session.** Expression-level discovery,
  reviewable remove+add restrictions, per-unit verdict reuse, and the renderer
  review UX. Design: [../ai-annotation-session.md](../ai-annotation-session.md).
- **017 (blocked): fixpoint metadata convergence.** Decide whether
  `source`/`confidence` metadata participates in convergence or whether
  metadata-only changes are non-convergent; the evidence field no longer
  exists.
- **023 and 028 file frontmatter says `todo`** while `docs/tasks/README.md`
  says `done`. Reconcile the status when touching docs.
- **Manual gate entries still unverified:** live editor/daemon restart paths
  (`m009`, `m010`, `m012`), live DeepSeek (`m014`), and live oracle latency
  (`m016`). Cross-writer compaction `m015` is verified and automated.
- **Known residuals:**
  - ticket 041 claim-reaping check-then-act window; a strict guarantee needs an
    OS advisory lock or unique-generation CAS.
  - Split analyses use one oracle budget per region (documented).
  - Return-flow propagation relies on the smallest covering annotation, so
    exotic nested-argument shapes can shadow the enclosing `RETURN_VALUE`.
  - Hover content is truncated at 12 lines/60 columns with no overflow marker.
  - AI with unlimited defaults can be slow/costly; timeout cancels without
    partial results.
  - Accepted OVERCROWDED roots: `analysis-engine`, `core/core`, and the
    `rulepacks-tests` package root; single-file packages are out of scope.
- **Local state:** `.cint/config.overlay.yml`, `.cint/suggestions*.json/yml`,
  and `.cint/user-annotations.json*` are untracked runtime/test state. Do not
  commit them blindly.

## Resume commands

```sh
just build
just test-engine
just lint
just test-nvim
just test-cross-writer      # focused JVM+Neovim lock test
just size                   # Kotlin file sizes (suspicious 300, extreme 400)
just dir-size               # directory crowding report
just lsp-fix                # apply first Kotlin LSP action per diagnostic
just lint-fix               # ktlint autocorrect
```

For a live smoke test, rebuild/install the daemon (`just install`), start
`cint-server` or let the client spawn it, then use `:CintServerRestart` after
changing engine/provider defaults.
