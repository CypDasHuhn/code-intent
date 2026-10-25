# Detection fidelity review — 091

Verdict: FINDINGS — do not close.

1. **Candidate IPC parity is still red and hidden from the gate.** `FrozenProtocolParityTest.kt:433-441` marks the `candidates` renderer section `registeredOnIpcSurface = true`; `:567` suppresses every unmatched difference for such sections. The committed report records `candidates: passed=false`, 19 differences, 19 unmatched, `intentionalDifferenceIds=[]`, owner 091. `merge-parity-report.py:45,79` only defects an unverified renderer, so `closable=true`/`defects=0` despite a failed 091 section.

2. **The 19 differences are in 091 scope.** Two candidates expect `source=ai:fake`, confidence `0.75`, provider/model/promptVersion/rationale, and `aiEligible=false`; V2 emits `rule:service:delegation`, `0.4`, no enrichment, `aiEligible=true`. `groupCount` and `groups` are absent because `projectCandidates` (`FrozenProtocolParityTest.kt:612-626`) never projects them, so grouping parity is untested.

3. **CLI parity is real but partial evidence.** `CorpusParityTest.kt:138-157` compares full candidate keys: 16/16 files, 211/211 annotations, 45/45 candidates, zero CLI candidate defects. Identity and spans hold for that corpus only.

No registered intentional difference cites these differences (`candidate.shape` is not referenced). Ticket 091's IPC acceptance remains unmet.

## Round 2

CONCUR. The gate bypass is closed: `registeredOnIpcSurface` is removed, `rendererSection` defects every unmatched path (`FrozenProtocolParityTest.kt:571-579`), `projectCandidates` emits uri/version/analysisRevision/suggestionRevision/counts/candidates/groups (`:618-648`), and tests assert renderer passed, checks, and exercised rules (`:202-217`, `:142-145`); `merge-parity-report.py:45-67` also converts renderer gate failures into defects.

The committed report shows 211/211 annotations, 45/45 candidates, 59 intentional differences, zero defects, renderer passed with 74 differences and 0 unmatched, `closable: true`. All 35 candidate-section differences are the two V1 fake-provider enrichments plus `suggestionRevision`, correctly cited as `suggest.candidate-enrichment`/079; candidate identity, spans, counts, ordering, and group structure otherwise match. Output is sorted/normalized, with no temp paths; I did not rerun builds.

Caveat: rules use whole `candidates[N]`/`groups[N]` prefixes, so future non-enrichment drift at those indices would also be masked. Non-blocking for 091.
