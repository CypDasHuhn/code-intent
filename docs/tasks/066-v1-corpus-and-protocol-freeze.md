---
name: Freeze V1 corpus and protocol behavior
status: done
depends-on: []
reviewers: [correctness, architecture]
---

## Goal

Capture the observable V1 behavior V2 must match before V1 is deleted.

## Scope

- Record V1 CLI outputs over testing-grounds and the current golden fixtures.
- Record live server protocol transcripts for analyze, publish, suggest, config, and status.
- Capture renderer-visible behavior and known deviations.
- Store V2-ready fixtures and a reproducibility command.

## Acceptance

- V2 tests can consume the frozen corpus without modifying V1.
- Protocol transcripts cover the renderer-critical paths.
- Known intentional V2 differences are listed explicitly.

## Out of scope

- Changing V1 behavior.
- A full renderer rewrite.

## Notes

- V1 is a behavioral reference, not an architectural template.
- This can run in parallel with the skeleton and contracts work.
