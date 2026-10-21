---
name: normalization intent
status: todo
depends-on: [036]
reviewers: [correctness, detection-fidelity]
---

## Goal

Add `NORMALIZATION`: converting a value into a canonical representation for
downstream use, without rejecting it and without persisting it.

Examples: `trim`, `lowercase`/`uppercase`, parse/coerce, date/time formatting,
path normalization, unit conversion, canonicalizing an identifier or enum-like
string.

## Scope

- New `normalization` rulepack owning `NORMALIZATION`; per-language config for
  normalizer calls, prefixes, qualifiers, and types.
- Deterministic emits for configured normalizers and obvious assignment/return
  canonicalization.
- `CandidateEmit`s for multi-step transforms and ambiguous formatting helpers
  (`aiEligible`), so AI can classify the block.
- Composition tests that normalization does not become validation, logging, or
  bookkeeping by accident.

## Acceptance

- Configured normalizers produce `NORMALIZATION` spans per language.
- Validation and normalization can coexist on one expression without either
  replacing the other.
- Ambiguous blocks yield candidates, not established tags.
- Rulepack config, fixtures, docs, and tests are added.

## Out of scope

- Display-only formatting policy; start with canonicalization for downstream
  use.
- Automatic inference from every `to*`/`format*` name without config or
  candidate review.

## Notes

- Prefer a config-driven pack over hardcoded language names.
- Mark broad/weak cases `aiEligible` rather than widening the default set.
