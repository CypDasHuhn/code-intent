---
name: striped mixed-intent rendering experiment
status: todo
depends-on: [016]
reviewers: [correctness, architecture]
---

## Goal

Experiment with a mixed-intent render mode that alternates tag colors over a
span instead of leaving it uncolored or using one blended meaning. The visual
effect is `ABABAB` striping across the span.

## Scope

- Opt-in handler/meaning (e.g. `STRIPE`) that applies only to spans with two or
  more intents; single-intent spans keep the normal handler.
- Split the span into bounded segments and alternate the intent colors:
  - default granularity: word/token boundaries or fixed-size chunks;
  - character-level `ABAB` is allowed only as an option, not the default;
  - cap segments per span and total extmarks per buffer.
- Use the existing per-intent highlight colors; no new palette required.
- Preserve conceal/vertical behavior precedence; striping does not override
  destructive meanings.
- Config: segment mode (`word`/`char`/`chunk`), chunk size, max segments,
  enabled/disabled per meaning.
- Tests: two-intent span alternation, three-intent cycling, cap, single-intent
  fallback, overlap with conceal, and performance guard.
- Docs: renderer README with the tradeoff (readability vs noise) and an example.

## Acceptance

- A mixed-intent span can be rendered as alternating tag-color segments.
- Single-intent spans are unchanged.
- Segment/extmark caps prevent pathological mark counts.
- The mode is off by default.
- Build, engine tests, lint, and nvim tests pass.

## Out of scope

- Gradients, blend modes, or true multi-color rendering per character.
- Changing engine intent sets or composition.
- Making striping the default.

## Notes

- Word-level striping is likely readable; character-level `ABABAB` is likely
  noisy and expensive, so treat it as an experiment.
- The renderer already skips color/highlight for multi-intent scaffolds; this
  gives those spans a visible treatment.
