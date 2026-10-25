# 081 api-fidelity review

Verdict: **FINDINGS**

1. `analysis-engine-v2/apps/server/src/main/kotlin/dev/cint/v2/server/SessionHandler.kt:75` — Protocol 4 initialize advertises `config.packs: []` and zeroed `suggestions` (`knownIntents: []`, `restrictions/cache: false`, `maxUnitsPerRequest: 0`, `provider/model: {}` instead of V1 `null`). `IpcConnection.legacyInitializeResult` (`analysis-engine-v2/ipc/src/main/kotlin/dev/cint/ipc/IpcConnection.kt:349`) only strips V2 fields, so a frozen V1 renderer sees an empty server instead of the pack catalog and known intents captured in `protocol-kotlin-analyze.ndjson`. Fix: populate these from the runtime/pack catalog with V1 defaults in legacy mode, and assert the frozen initialize body in `LiveServerTest`.

2. `SessionHandler.kt:120` — explicit `cint/analyze` on an already-open document reuses the completed job (`jobs` entries are never removed) and returns `true` without notifications; the non-open path (`:140`) also returns `true` without publishing. Frozen V1 sessions (`protocol-kotlin-analyze.session.json` steps 2 and 5, `protocol-typescript-analyze` step 2) always emit `publishTags` + `publishCandidates` before the analyze result. Fix: re-run/republish on analyze, preserving notification-before-result ordering.

3. `analysis-engine-v2/apps/server/src/main/kotlin/dev/cint/v2/server/IpcMapping.kt:99` — `legacyPublishMessages` forwards raw runtime `source` strings for annotations, provenance, and candidates (`:99`, `:118`, `:176`, `:191`). The V2 runtime emits `knowledge` (`KnowledgeInputs.kt:291`), `rule`/`candidate` (`RuleEvaluation.kt:124`, `:141`), and `composition` (`CallChainComposition.kt:149`), while V1 renderer data uses `annotation`, `comment`, `treesitter`, `cfg`, `ai`; V2 docs also promise that standardized vocabulary (`frozen-v1-corpus/KNOWN_DIFFERENCES.md:164`). This difference is absent from `ipc/known-differences-v2.json`. Fix: map sources per protocol and register the vocabulary change.

4. `IpcMapping.kt:111` — legacy candidates drop `rationale`, `provider`, `model`, and `promptVersion`. V1 `publishCandidates` includes them for AI-enriched candidates (`protocol-suggest-flow.ndjson`, candidate `86b905...`), and the contract `Candidate` carries them. `CandidateOrigin` also has no `promptVersion`, so the V2 shape loses it without a registry entry. Fix: emit all four in the legacy map and add `promptVersion` to V2 origin or document its removal.

5. `SessionHandler.kt:330` — analysis failures put `ContractError.toString()` in both the message and `data.details` as a scalar. That is unstable and can expose internal error classes/paths, contrary to the structured-error contract. Fix: map `ContractError` to stable error messages/codes and structured details objects.

Handshake, fingerprint enforcement, legacy protocol negotiation, NDJSON framing, V2 publish shapes, status/metrics, and documented unsupported-operation errors otherwise match the contract. The main risk is that legacy mode is wire-shaped but semantically incomplete: initialize, explicit analyze republishing, source vocabulary, and AI candidate metadata diverge from the frozen V1 transcript without allow-list entries, so the renderer cannot distinguish these regressions from intentional V2 changes.

## Round 2

1. CONCUR — `initialize` now reads `runtime.packs()` (`SessionHandler.kt:84`) and derives intents (`:431-438`); V1 defaults are restored (`:461-472`); empty pack `fields` and manifest additions are registered (`known-differences-v2.json:121`); covered by `LiveServerTest.kt:323-334`.
2. CONCUR — analyze reschedules open documents and republishes before responding (`SessionHandler.kt:137-142`); non-open targets also publish (`:147-165`); ordering is tested (`LiveServerTest.kt:361-371`). Residual: non-open `file:` URIs hash through `Path.of` and fall back to the empty hash (`SessionHandler.kt:474-476`).
3. CONCUR — sources are mapped for both protocols (`IpcMapping.kt:293-318`) and the vocabulary change is registered (`known-differences-v2.json:114`).
4. DISSENT — legacy candidates now include the four fields, but unconditionally as `JsonNull` when absent (`IpcMapping.kt:143-146`). V1 omits absent optional fields on rule candidates (`protocol-typescript-analyze.ndjson`), so exact legacy output still differs. Fix: emit each field only when non-null, as the V1 encoder does.
5. CONCUR — `errorOf` maps every `ContractError` to stable codes/kinds with object details (`SessionHandler.kt:478-532`); verified by `LiveServerTest.kt:375-393`.

Verdict: four of five findings are closed; finding 4 still prevents exact V1 candidate reproduction because absent optional fields are serialized as explicit nulls.

## Round 3

- Finding 4: CONCUR — optional candidate metadata is now conditionally emitted (`IpcMapping.kt:143-146`), and `legacyOmitsAbsentCandidateMetadata` asserts the exact V1 key set (`LiveServerTest.kt:461-483`). Valued AI metadata is retained.
- Non-open hash residual: CONCUR — `contentHash` resolves `file:` URIs and hashes real file bytes, returning structured `InvalidInput`/`NotFound` on failure (`SessionHandler.kt:425-439`); `nonOpenAnalyzeFailsExplicitlyWhenTheTargetCannotBeRead` covers the failure (`LiveServerTest.kt:446-459`).

Verdict: all Round 2 open items are closed; no remaining api-fidelity findings.
