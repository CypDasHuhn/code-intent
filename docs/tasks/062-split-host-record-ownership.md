---
name: split host record ownership for legacy records
status: todo
depends-on: [060]
reviewers: [correctness, architecture]
---

## Goal

Finish the split host/region ownership model by making one classifier decide
where an annotation or restriction belongs, independent of whether it has a
`unitFingerprint`. Status, region scoping, host analysis, and proposal
suppression must always agree, including for legacy records.

Deferred from ticket 060 round 13. The 060 cache and identity work is complete;
this ticket covers the remaining legacy/split edge cases and its residual test
gaps.

## Scope

- Add a shared ownership classifier: region identity (`regionKey`/ordinal) wins;
  otherwise a span contained in exactly one leaf region is legacy-region; a span
  with no leaf overlap is host; an overlapping or straddling span is dormant.
  It must not depend on `unitFingerprint`.
- Use the classifier in status resolution, region scoping, host-analysis
  callbacks, and proposal suppression so all four agree for records without a
  fingerprint and for straddling spans.
- Fix the host-context suppression branch so a no-region record must be proven
  host-owned before it can suppress a host-level proposal.
- Make short-circuit republishing use the same suppression predicate, or
  document the shortcut prominently.
- Replace the duplicated host-ownership predicate in the CLI, HTTP, and live
  callbacks with the shared helper.
- Extend host analysis to host-level source inside an outer `SplitHandler`
  region, or document that only the top-level host is analyzed.
- Tests: legacy no-fingerprint region and host records across status, scoping,
  and suppression; reverse-context suppression; CLI split-host end to end; an
  active host-level restriction (or document that the built-in Vue host has no
  host rule packs); exact sorted `languages` prompt set and the
  single-language omission case.
- Docs: legacy/no-fingerprint ownership, straddling spans, and the
  short-circuit suppression behavior.

## Acceptance

- A legacy region record without a fingerprint is applied, reported active, and
  suppresses only proposals in the same region.
- A legacy host record without a fingerprint is applied, reported active, and
  suppresses only host-level proposals.
- A straddling or ambiguous record is dormant in all four paths.
- CLI and both server hosts use the same ownership helper.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Reopening the 060 cache identity, invalidation, or persistence design.
- New AI discovery/restriction features.

## Notes

- Review evidence: `docs/reviews/060/correctness.md` round 13 finding 31;
  `docs/reviews/060/architecture.md` round 13 finding 31 and residuals.
- Also deferred residuals: overlapping split regions, legacy ordinal-only nested
  records, content-addressed region-key migration, nested worklist/type tests,
  filtered host-parent/declaration tests, external pack helper fingerprint
  coverage, process-local diff baselines, memory-only hints, single per-language
  cache header, and `aiEnv` not covering every suggestion-layer parameter.
