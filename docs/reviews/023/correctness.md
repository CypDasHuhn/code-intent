# Correctness review — ticket 023 (simplify logging pack)

## Round 1

Verdict: The rewrite preserves the logging matcher's behavior while removing
the pack's direct AST/emit plumbing. The heuristic order and oracle policy are
unchanged, the factory-symbol lookup still requires an exact initializer/call
span match, and the pack still owns only `LOGGING` with no declared reads. The
remaining differences are implementation-level (use of enriched `CallFact.path`
and `View.receiverType`) and are consistent with the API model; no correctness
defect was found.

### Findings

- None.

### Concurs / Dissents

- Concurs: `loggerInteraction` keeps the original precedence
  (`FACTORY`, `RECEIVER_NAME`, `QUALIFIED`, `IMPORTED`), the same confidences
  (0.8/0.6/0.8/0.5), and the same oracle policy: factory and qualified matches
  bypass the oracle, receiver-name and import-gated matches consult it, an
  unresolved type falls back to the heuristic, and a resolved non-matching type
  vetoes the call. The enum encoding reproduces the old `Match`/`rule` checks.
- Concurs: `view.receiverType(call)` is equivalent to the old
  `view.typeAt(receiver)` in the engine: `AnalysisContext.receiverType` and
  `CapabilityView.typeAt` both call the oracle with
  `syntax.receiverOffset(receiver)`, so the LSP offset, fallback, and veto
  behavior do not change.
- Concurs: `loggerSymbols` still resolves only the call whose node span exactly
  equals the property initializer's span. `CallFact.path` is populated by
  `CallFacts` with the same receiver-plus-callee derivation the old local
  `callPath` used, so factory discovery remains as strict as the round-2 006
  fix; the non-call-initializer regression test still guards that path.
- Concurs: `print` and `loggingState` preserve source, confidence, evidence,
  span, and byte offsets via `CallFact.emit` and `Emit.of`; `print` still emits
  `call.site` (including trailing lambdas) at `treesitter`/1.0 and guard
  conditions still emit at `treesitter`/0.7.
- Concurs: the new top-level constants preserve the pack id, intent, and rule id
  (`logging`, `LOGGING`, `logging:logging`), and `LoggingConfig.withOverrides`
  now uses the same pack id for decode/error paths. `LoggingRule` is private and
  is no longer exposed as a `Rule`; only the pack's single composed rule remains
  public through `rules()`.
- Concurs: the pack contains no grammar node kinds or AST traversal. The
  language defaults are in `LoggingConfig`, and `LANGUAGE_DEFAULTS` is a
  read-only map whose values are immutable data classes.
- Concurs: the existing logging tests cover factory bypass, unresolved-oracle
  fallback, import-gated fallback/veto, resolved type match, non-call
  initializer veto, print/guard detection, configuration overrides, and trailing
  lambda spans; the parity snapshots pin the heuristic source and confidence
  values.
- Dissents: none.

### Residuals (not findings)

- `loggerSymbols` now uses `associateBy` over call spans, so a duplicate call
  node span would resolve last-wins whereas the old `firstOrNull` resolved
  first-wins. Current syntax extraction does not produce duplicate call node
  spans, so this is only a theoretical difference.
- The pack now trusts `CallFact.path`; alternate `View` implementations must
  populate it correctly (the API default derives it from callee/receiver when
  omitted). This matches the ticket's enriched-call-fact direction and does not
  affect the engine.

No source edit or build/test/formatter command was run by this role; the
handover was treated as green per `workflow.md`.
