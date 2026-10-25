---
name: V1 to V2 cutover
status: done
depends-on: [084, 088, 089, 090, 091, 092, 093, 094]
reviewers: [correctness, architecture]
---

## Goal

Promote V2 to the primary build and remove V1.

## Scope

- Move the V2 tree to `analysis-engine/`.
- Drop temporary -v2 executable suffixes.
- Update justfile, CI, scripts, and docs paths.
- Delete the V1 engine tree.

## Acceptance

- The repository builds only the V2 engine.
- Renderer tests run against the V2 server.
- V1 history remains in git; no -old directory is created.

## Out of scope

- Renderer repository extraction.

## Notes

- Do not cut over before 084 is green and 088-094 are closed.
