# Detection fidelity review — 090

Verdict: CONCUR (corpus evidence)

Evidence:
- Report summary: 211/211 annotations, 45/45 candidates, 16/16 CLI files, 0 defects, 0 IPC defects; both committed defect baselines are `total: 0`. `CorpusParityTest` routes scaffold/derivative-use annotations to `090-v2-composition-parity`; none appear in the defect list.
- The 090-owned IPC renderer sections `tags.annotations` and `documentLifecycle` have zero differences and pass. Composition fixtures (`Composition.kt`, `Scaffolds.kt`, `Catches.kt`, `Bookkeeping.kt`) exercise derivative spans/confidence, return-category guards, and if/catch/try/function scaffolds.
- `Composition.kt` tracks V1 `DerivativeComposition`/`ScaffoldComposition`/`ReturnComposition`: return guard selection, rejection/false neutrality, function scaffolding gates, branch unions, and derivative read-only confidence. `CompositionTest` covers these paths and guard certification.
- All 58 intentional differences are CLI/protocol/IPC scope; none are composition. No composition mismatch is hidden as an intentional difference.

Caveats (not 090 defects):
- The report retains a static 090 deferral entry even though no 090 defect remains.
- `ipc.renderer.passed` is false from 19 unmatched 091 candidate differences exempted via `registeredOnIpcSurface`; `closable: true` does not mean every renderer section passes.
- V2 read-only confidence omits V1 user `PURE`/`READONLY` call branches; the corpus does not exercise them, so parity evidence is corpus-limited.

Zero 090 defects is supported by the committed CLI and IPC evidence; no hidden composition intentional differences found.
