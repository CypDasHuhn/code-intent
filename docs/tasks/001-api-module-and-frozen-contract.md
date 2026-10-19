---
name: api module and frozen contract
status: done
depends-on: []
reviewers: [correctness, api-fidelity]
---

## Goal

Introduce a frozen `api` Gradle module holding the public contract plugins compile
against, and wire module dependencies so `core` and below churn freely while
plugins depend only on `api` (architecture.md §9).

## Scope

- New `:api` module in `settings.gradle.kts`, depending on nothing else in the
  tree.
- Contract types (`§9`, `§2`, `§3`, `§5`, `§6`):
  - `Intent` — open name; `UNTAGGED` reserved and never owned by a rule.
  - `Relation` — the resolved intent→rule dependency edge (see Notes).
  - `Rule` — `id: "<packId>:<localName>"` globally unique, `owns: Intent`
    (exactly one), `reads: Set<Intent>`, `run(view: View): List<Emit>`.
  - `RulePack` — `id`, `languages`, `rules()`.
  - `View` — the capability-scoped fact surface (`§5`).
  - `Emit`, `Annotation` — role, intents, span (0-based line/col + byte
    offsets), rule, evidence, source tier, confidence, reserved node anchor.
  - The scheduler contract the engine implements.
- Re-type the existing `core/.../model/Model.kt` shapes that belong on the frozen
  surface; leave behavior in `core`.
- Add a `:rulepacks` module (bundled packs depending only on `:api`) and fold
  `dsl` into `api` (README resolved decisions).
- Keep every existing module building.

## Acceptance

- `:api` builds; no module outside `api` defines a competing copy of these types.
- `Intent` names are open strings; `UNTAGGED` has no owner; `Rule.owns` is a
  single intent and `reads` is a set of intent names.
- `Annotation` carries a **reserved, unused** node anchor field (`§10`) and a
  structured provenance shape rather than the current `+`-joined `rule` string.
- The current engine still runs unchanged end to end.
- `just build` green.

## Out of scope

- Scheduler, store, rulepacks, config.
- Removing the legacy `rules/Blocks.kt` path.

## Notes

- `Relation` is the resolved intent→rule dependency edge (README #2), pinned by a
  test here.
- Bundled rulepacks live in a new `:rulepacks` module depending only on `:api`,
  so the `§1` boundary is structural (README #3).
- `dsl` folds into `api` for now (README #4).

## Outcome (2026-10-19)

Both reviews clean: round-1 findings (plugin emit boundary, core-only types in
`api`, empty/lossy `Annotation.rule`, competing `RawAnnotation`, untested split
provenance shift, tautological `Relation` test) were fixed and round 2 concurs.
`just build` and `just test-engine` green; forced reruns api 7 / core 3 /
treesitter 58 / frontend 4 / server 1 tests, 0 failures.

Contract decisions: `Emit` is source-only (no `role`/`rule`; the framework stamps
`SOURCE_INTENT` and the running rule id via `Rule.toContribution`); `Annotation`
requires non-empty `provenance`; `Contribution` carries byte offsets; the artifact
and live payloads publish `provenance` additively.

Deferred, named: duplicate-owner/cycle/unknown-rule load errors → 003; physical
store swap and `RawAnnotation` removal → 002; final renderer payload shape → 016.
