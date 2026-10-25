---
name: V2 detection fact parity
status: done
depends-on: [065, 066, 072]
reviewers: [detection-fidelity, correctness]
---

## Goal

Close the remaining V1/V2 detection-fact gaps recorded by the 084 parity report.

## Scope

- Port V1 logging, validation, data-access established/candidate, return-value, local-copy, and span/confidence semantics.
- Align heuristic source labels and node ranges with the frozen corpus.
- Keep candidate and composition gaps in their dedicated tickets.

## Acceptance

- Every defect in `analysis-engine/parity/v2-parity-report.json` assigned to this ticket is resolved or reclassified with evidence.
- `just parity` baseline is reduced and the committed report is regenerated.
