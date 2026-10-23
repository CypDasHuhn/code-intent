# Current State and Continuation Notes

As of 2026-10-23, after tickets 041–061. Ticket 062 is queued and `017` remains
blocked. Branch `main` is clean apart from local `.cint/**` project state.

## Where the project is

CodeIntent is a Kotlin analysis engine plus a Neovim client.

- The engine parses Kotlin, JavaScript, TypeScript, Vue, and C# through
  tree-sitter, optionally queries `kotlin-lsp` for types, runs bundled
  rulepacks through a stratified scheduler, composes return/derivative/scaffold
  annotations, and publishes tags plus candidates.
- The client speaks a local Unix-socket JSON-RPC protocol 4 to a per-project
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
| 058 | AI expression discovery session: core expression units including leaves, internal tool loop, add-only verdicts with validation, budgets/coverage, protocol 4 progress/log, and a recall-oriented prompt. |
| 059 | AI restriction proposals: grouped remove+add operation groups, `.cint/ai-restrictions.json` persistence under the shared annotation lock, pre-composition application with `ai-confirmed` replacements, dormant/user-wins handling, and restriction inspection/removal. |
| 060 | AI verdict reuse cache: deterministic document short circuit, per-unit verdict reuse with diff invalidation and hints, normalized decision anchors, bounded `.cint/cache/ai-verdicts.json` persistence, and split/host record identity. |
| 061 | AI review UX: capability-gated passive/review modes, `+`/`−`/`− +` groups, quickfix review list and navigation, restrictions inspector/removal, AI session log, progress/cancel, and deduped completion notices. |

Recent follow-up fixes: `fix(nvim): connect before ai log and restrictions`
(`:CintAiLog`/`:CintRestrictions` connect on demand and report the actual
protocol) and `fix(suggest): surface provider failure details in ai log`
(structured safe failure detail instead of a generic event).

## Current behavior worth knowing

- `RETURN_VALUE` spans the whole `return` statement and propagates to local
  declarations whose values flow into the returned expression.
- `SEARCH` means querying a collection/repository where the result may be
  absent; prior validation does not turn it into a lookup. `LOOKUP` means
  retrieving a known-to-exist static/preset value (map/table/enum/config/
  registry). `findById`/`getById`/`loadById` are SEARCH without a default
  LOOKUP alternative; `getBytes` is not SEARCH; `getByKey` is LOOKUP.
- AI discovery is enabled by default and grades every expression unit, including
  leaves, through an internal tool loop over the current document plus LSP type
  facts. Verdicts may add intents, or remove an established source intent and add
  replacements as one reviewable group. Budget exhaustion publishes graded units
  and reports coverage. Setting `discoveryEnabled: false` restores
  pack-candidate enrichment, which remains uncapped by default.
- Confirmed restriction groups live in `.cint/ai-restrictions.json`. Analysis
  applies them after user annotations and before composition, writes replacements
  as `source = ai-confirmed`, and recomputes derivatives/scaffolds. Records whose
  anchor text is gone are dormant; user annotations and `PURE`/`READONLY` cannot
  be removed. `cint/restrictions/list` and `cint/restrictions/remove` inspect and
  remove active records.
- Unchanged documents reuse their cached analysis; AI verdicts are cached per
  expression unit in `.cint/cache/ai-verdicts.json`. Formatting reuses all units,
  a lint edit grades only changed units, and prompt/provider/annotation/
  restriction changes invalidate the relevant layer.
- Hover shows only the innermost span under the caret and never shows
  `UNTAGGED`.
- Protocol-4 discovery is quiet in passive mode: no AI undercurl/sign/badge or
  conceal, while status still counts additions, restrictions, reuse, and
  coverage. Review mode renders `+`/`−`/`− +` groups with confidence, source, and
  rationale; `:CintReviewList`, `]c`/`[c`, and `:CintReviewAccept`/`:CintReviewDeny`
  work from the quickfix window. Protocol-3 clients keep the legacy AI marks and
  arrival notification.
- `:CintAiLog` connects on demand and shows the provider failure detail for the
  built-in DeepSeek provider (missing API-key environment variable, HTTP status
  and error snippet, transport error class). Arbitrary provider prose is still
  not logged.

## Open work

- **062 (todo): split host record ownership for legacy records.** Deferred from
  060 round 13. Status, scoping, host analysis, and proposal suppression still
  disagree for records without a `unitFingerprint`; a shared ownership classifier
  (region identity, unique leaf containment, host, dormant) is the fix. Evidence
  and the full residual list are in
  [../tasks/062-split-host-record-ownership.md](../tasks/062-split-host-record-ownership.md).
- **017 (blocked): fixpoint metadata convergence.** Decide whether
  `source`/`confidence` metadata participates in convergence or whether
  metadata-only changes are non-convergent; the evidence field no longer
  exists.
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
  - Discovery grades every expression unit and large files can be slow/costly;
    budgets publish partial coverage, while an outer provider timeout cancels
    without partial results. Document and per-unit reuse bound repeated work;
    external type changes with unchanged text remain an accepted staleness limit.
  - Accepted OVERCROWDED roots: `analysis-engine`, `core/core`, and the
    `rulepacks-tests` package root; single-file packages are out of scope.
- **Local state:** `.cint/config.overlay.yml`, `.cint/suggestions*.json/yml`,
  `.cint/ai-restrictions.json*`, `.cint/cache/ai-verdicts.json*`, and
  `.cint/user-annotations.json*` are untracked runtime/test state. Do not commit
  them blindly.

## Handoff to another machine

The Neovim plugin is installed from GitHub (`CypDasHuhn/code-intent`), not from a
local checkout. On a new machine:

1. Pull `main` in `~/dotfiles` and let lazy install the plugin, or update the
   existing clone at `stdpath('data')/lazy/code-intent`.
2. Run `:Lazy update code-intent`, then `:Lazy build code-intent`. The build is
   `cd analysis-engine && ./gradlew :server:installDist`; the renderer lives in
   `<lazy>/code-intent/nvim-renderer`.
3. Export `DEEPSEEK_CHAT_KEY` (or the configured `apiKeyEnv`) in the environment
   that starts nvim; the daemon inherits it. Quick check:
   `:echo $DEEPSEEK_CHAT_KEY != ''`.
4. Run `:CintServerRestart` after updating the engine or provider defaults. The
   daemon is per project root and survives nvim restarts. `:CintServerStatus`
   shows its protocol; protocol 4 is required for discovery, restrictions, cache,
   and `:CintAiLog`.
5. If a pass fails, `:CintAiLog` now reports the missing environment variable or
   HTTP status. Daemon output is at `stdpath('log')/cint-live.log`.

Push all local commits before switching; `062` and `017` remain open.

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
