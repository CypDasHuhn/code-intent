# Detection fidelity review — 088

Verdict: CONCUR for 088; one report-level finding.

Verified at HEAD `51d9174`:

- The seven formerly-088 CLI defects are genuinely resolved. `v2-parity-report.json` shows 211/211 annotations, 45/45 candidates, and zero 088 defect entries; both defect baselines are empty. `CorpusParityTest.canonical` compares full annotation JSON — role, intents, source, confidence, byte/line spans, nodeAnchor, and provenance/rule — so logging, validation, data-access, return, local-copy, span, and confidence match exactly, not by count.
- Code reflects V1 semantics: `ReturnValueEvaluator` skips literals and spans statement to expression; `ValidationEvaluator` emits the 0.6 elvis guard; `LocalValueModifyEvaluator` emits local writes at 0.8; `durableCallback` establishes DATA_MODIFY at 0.9. The frozen corpus/manifest is unchanged, and the report shape matches `merge-parity-report.py`.

Finding: the merged report says `defects: 0`, `closable: true`, although `rendererDerivation.passed` is false and renderer section `candidates` has 19 unmatched, non-intentional differences (owner 091, still todo). `rendererSection` suppresses these because `registeredOnIpcSurface=true`, and the merger ignores `passed`. This does not undermine 088's CLI evidence, but it overstates report-wide closure.

## Round 2

CONCUR. Verified at `e8cfc4d`:

- `rendererSection` no longer accepts `registeredOnIpcSurface` and always emits a defect for unmatched paths; the IPC test asserts every renderer section passed and all checks true, and `merge-parity-report.py` adds a renderer gate defect for unverified/unpassed/check/unexercised failures.
- `projectCandidates` now projects candidate and group arrays plus uri/version/analysisRevision/suggestionRevision from the final published frame.
- Candidate gaps are registered only as `suggest.candidate-enrichment` (ticket 079) for candidates[6]/[9], groups[3]/[5], and suggestionRevision; every remaining difference is exact V1 fake-provider enrichment.
- The report shows 0 defects, 211/211 annotations, 45/45 candidates, renderer unmatched 0, `rendererPassed` true, `closable` true.

The prior suppression finding is resolved; no new 088 whitewash found.
