---
name: protocolized config overlay
status: done
depends-on: [030]
reviewers: [correctness, api-fidelity]
---

## Goal

Make rulepack config a first-class protocol surface, and let the Neovim client
append/remove user symbols for a chosen pack config field through an additive
project overlay. The overlay changes pack config only; it must not create
source annotations, contributions, evidence, confidence, or magic comments.

## Scope

- `api`: make `ConfigSchema` describable (field key, value kind, language vs
  pack scope, nested language schema) and expose pack-level config descriptors
  with an additive accessor/default so existing packs compile unchanged.
- Host: load `<root>/.cint/config.overlay.yml`; apply it additively to the
  project pack config for packs that opt in (lists/sets union, maps recurse,
  scalars from the overlay win), keeping project/default values and reporting
  unsupported packs; support reloading pack config without restarting the
  daemon.
- Protocol: `initialize` returns loaded pack/rule/field descriptors; add
  `cint/config/read`, `cint/config/append`, and `cint/config/remove`. The
  server owns overlay writes, applies mutations, reloads affected packs, and
  republishes tags for open documents.
- Neovim: cache the protocol config descriptors; add a picker command that
  filters fields by the buffer language and string-set/list kind, labels each
  choice by owning rule/intent, and appends the word under the caret; add a
  matching remove command for user overlay values.
- Tests: descriptor shape, additive merge, reload, protocol round-trip, and the
  nvim command/picker behavior in headless specs.
- Docs: document the overlay file and live protocol additions; add a manual
  test entry if the live restart/round-trip path cannot be automated.

## Acceptance

- `initialize` publishes pack ids, rule ids/owned intents, supported languages,
  and config fields; the client needs no static pack/field list.
- `:CintConfigAdd` on `audit` in a Kotlin buffer can append `audit` to
  `logging`→`methods` for Kotlin; subsequent analysis uses the overlay, and the
  value survives daemon/editor restart.
- `:CintConfigRemove` removes that user value and restores the previous
  analysis behavior.
- For packs that opt into additive overlay (`configureAdditive`/
  `decodeAdditive`), overlay merge preserves base/default/project values and no
  user annotation/provenance/confidence contribution appears in output. All
  bundled packs opt in via `SingleIntentPack`; a non-opt-in pack keeps its
  project/default values and the server rejects and rolls back the mutation
  (`UNSUPPORTED_PACK_OVERLAY`).
- The picker lists only fields valid for the buffer language and only
  string-set/list fields; each label names the owning rule/intent.
- Engine build/tests, lint, and nvim tests pass.

## Out of scope

- Per-pack intent-to-field mapping or guessing which field a rule name means.
- Editing scalar/boolean fields through the overlay.
- Exposing effective config provenance beyond the user overlay.
- Config descriptors in the HTTP artifact; live protocol first.
- Replacing existing user span annotations.

## Notes

- Field metadata must come from the packs' own schemas; no client-side pack
  knowledge and no hardcoded built-in field list.
- Overlay writes are server-owned so there is one writer per project.
- Additive overlay is opt-in; the default `configureAdditive` rejects. The
  loader reports `UNSUPPORTED_PACK_OVERLAY` and keeps the project-configured
  pack, and the server rolls back and errors instead of silently replacing
  values. Bundled packs all use `SingleIntentPack`, so their overlay is additive.
- `HostPipeline` snapshots packs at construction; add an explicit reload path.
- `LanguageConfig.schema` currently hides the child schema in a custom decoder
  closure, so nested language descriptors need explicit support.
- Prefer an additive descriptor accessor with a default; if the contract must
  bump, update every bundled/test pack and say so in the outcome.
- Keep span annotations (`user-annotations.json`) untouched by this ticket.

## Outcome (2026-10-21)

Config descriptors are exposed over live protocol 2 and through
`RulePack.configDescriptor()` without an API version bump. The host reads
`<root>/.cint/config.overlay.yml`, applies additive overlay values for packs
that opt in via `configureAdditive`/`decodeAdditive`, preserves project and
default values, reloads packs in place, and rejects/rolls back overlays for
non-opt-in packs with `UNSUPPORTED_PACK_OVERLAY`. The server owns atomic overlay
writes and implements `cint/config/read|append|remove`; publishes carry a
revision so same-hash config changes repaint and stale lower revisions are
ignored. Nvim adds `:CintConfigAdd`/`:CintConfigRemove` with a language/kind
filtered picker labeled by owning intent and field. Span annotations are
untouched. Correctness and api-fidelity reviews closed after five rounds;
build, test-engine, lint, and test-nvim pass. The live editor/daemon round trip
remains manual as m011.
