---
name: logging rulepack
status: done
depends-on: [003, 004, 005]
reviewers: [correctness, detection-fidelity]
---

## Goal

Re-express logging detection as a single bundled `logging` rule on the new API
(architecture.md §11.3), owning `LOGGING`, replacing the current
`LoggingBlock`/`PrintBlock` paths.

## Scope

- One rule owns `LOGGING` and runs three internal matchers (private functions),
  keeping §2's 1:1 ownership strict:
  - `print` — configured call names and receiver qualifiers (`println`/`print`,
    `System.out`/`System.err`, `console.log`/`console.error`, ...).
  - `logger-interaction` — a call on a receiver whose LSP type is a configured
    logger type. Factory properties and qualifiers answer without LSP; ambiguous
    receiver names and import-gated matches consult the oracle; a resolved
    non-logger type vetoes the heuristic; an unresolved type falls back; live
    hover time is capped.
  - `logging-state` — conditions using configured logging-state names.
- Trailing lambda arguments are included in the call span.
- The rule's contribution is the union of matcher hits; it stays a pure
  `(View) -> List<Emit>` with no store mutation.

## Acceptance

- Detection parity with the current engine over `testing-grounds/` and the golden
  fixtures (the fixtures are a development check, not a compatibility contract).
- No core branching on language; all language specifics are pack config.
- `reads` declarations are honest; no undeclared intent access.
- `just test-engine` green.

## Out of scope

- Other intents; config migration (013).

## Notes

- Decision (README #1): `print`/`logger-interaction`/`logging-state` are matchers
  inside this one rule, not three rules; stratum 0 holds a single logging node.
  This refines `§4`'s worked example.
- Configuration values move to pack config in 013; keep them pack-local now.

## Contract note (2026-10-20)

The logging pack needs facts the frozen `View` did not expose. The following are
additive `api` members, recorded here and versioned by the pack-compatibility
check owned by 011:

- `View.language()` — lets one multi-language pack select pack-local defaults
  without core language branching.
- `View.typeAt(SyntaxNode)` — oracle-backed receiver type for
  `logger-interaction`, returning the `api` `TypeRef` projection of the core
  `TypeFact`.
- `View.guardConditions()` — exposes the existing core guard/loop condition fact
  used by `logging-state`.

External `View` implementations must implement these members; 011's API version
check is the compatibility gate for packs compiled against the previous surface.

## Outcome (2026-10-20)

Bundled `logging` pack (`LoggingRulePack.kt`) owns `LOGGING` with private
`print`, `logger-interaction` and `logging-state` matchers and pack-local
language defaults. `logger-interaction` uses the additive `View.typeAt` oracle
projection; `View.language`/`guardConditions` were added additively and recorded
in the contract note above, gated by 011's API version. Parity covers
`testing-grounds/` under null/resolved-logger/resolved-non-logger oracles,
including fallback, veto, factory shortcut and Vue script regions. Reviews
concur after rounds; golden-fixture parity is the unverified manual entry m003.
Registration/selection is deferred to 011/015.
