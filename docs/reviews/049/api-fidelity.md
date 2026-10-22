# API-fidelity review — ticket 049 (user readonly directive)

## Round 1

Verdict: `READONLY` is correctly added as an open user intent, survives the
client/host round trip in both the intent set and provenance, appears in the
artifact vocabulary when present, keeps `nodeAnchor = null` and introduces no
evidence field, and is kept out of the PURE-only determinism path. The frozen
`api` module is untouched and `CINT_API_VERSION` is unchanged. Three contract
gaps remain: the view change also broadens PURE's validation behavior, the new
READONLY tests do not actually pin the promised confidence/composition
behavior, and the touched intent-model prose still claims annotations carry
`evidence`.

### Findings

1. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/facts/view/CapabilityView.kt:93-98` — the READONLY view change also changes PURE's validation behavior.**

   `isSideEffectFree` now includes `context.isUserPure(node)` and
   `context.isUserPureCall(node)` in addition to the two READONLY checks.
   Before this diff the method only delegated to syntax facts. Since
   `ValidationRulePack.emitFor` uses `view.isSideEffectFree(condition)` to take
   the `pure` branch and assigns confidence `0.6` there (versus `0.5` on the
   read-only fallback), a user-`PURE` call used as a rejecting guard condition
   now publishes a higher confidence than before. The ticket explicitly says
   PURE behavior is unchanged and only asks for READONLY to be added to
   `CapabilityView`; no test pins this PURE validation change.

   Fix: drop the two PURE terms from `CapabilityView.isSideEffectFree` and keep
   only `isUserReadOnly`/`isUserReadOnlyCall`, or, if treating user-PURE calls
   as side-effect-free in validation is intended, pin the confidence change
   with a validation test and record the behavior change in the ticket/docs
   rather than claiming PURE semantics are unchanged.

2. **`analysis-engine/languages/treesitter/src/test/kotlin/dev/cint/treesitter/language/UserAnnotationPureTest.kt:99-150` and `analysis-engine/tests/integration-tests/src/test/kotlin/dev/cint/integration/view/CapabilityViewTest.kt:169-215` — the READONLY tests do not pin the advertised 0.7 derivative confidence or validation composition.**

   The derivative test would pass with the READONLY annotation removed: an
   unrecognized call initializer is still "unknown read-only" and already
   produces derivative contributions at confidence `0.55`; the assertions only
   check that `val source`/`val message` have derivative spans. The only
   READONLY-specific effect in `DerivativeComposition.readOnlyConfidence` is
   raising that confidence from `0.55` to `0.7`, which is never asserted.
   Similarly, the `CapabilityView` test asserts `isSideEffectFree` on the call,
   but `view.isSideEffectFree(readOnly)` is also true from plain syntax facts,
   and no validation-pack test shows a READONLY call guard being classified as
   `VALIDATION` at the side-effect-free confidence. The acceptance claim that a
   READONLY call "qualifies as a side-effect-free read for validation
   composition" is therefore unpinned.

   Fix: assert the derivative confidence is `0.7` for the `val source` span
   (ideally against a no-annotation control at `0.55`), and add a validation
   composition test where a guard containing a READONLY call and a rejecting
   exit emits `VALIDATION` at `0.6`; add the corresponding PURE control if the
   PURE branches from finding 1 are kept.

3. **`docs/intent-model.md:25` — the touched contract prose still says annotations carry `evidence`.**

   The ticket edits this document to describe user directives, but the line
   immediately above the new section still says "Each annotation carries
   `rule`, `source`, `confidence`, `evidence`, and a span." Evidence was removed
   from the annotation/provenance contract in ticket 032 (API v4/v5); the api
   model and `AnnotationJson` have no `evidence` field. This now contradicts
   both the frozen contract and the new provenance wording in the READONLY
   section.

   Fix: remove `evidence` from that sentence (for example, describe the
   annotation fields plus contributor `provenance`). No code change is needed.

### Concurs / Dissents

- Concurs: the frozen `api` module is untouched (`git diff --name-only --
  analysis-engine/core/api` is empty) and `CINT_API_VERSION` remains `5`.
  Adding a user-layer directive name in `core` needs no version bump because
  intent names remain open.
- Concurs: `READONLY` is preserved as an open user intent. The resolver strips
  only `UNTAGGED`, so READONLY reaches the `SOURCE_INTENT` intents and the
  user contributor's provenance; `HostUserAnnotationsTest` now asserts both,
  and the removal assertion still covers the merged record.
- Concurs: the artifact vocabulary is coherent. `HostPipeline.userIntents()`
  unions the persisted user names into the top-level `intents` list, and the
  updated host test expects `READONLY` there alongside pack and `UNTAGGED`
  names.
- Concurs: user annotations still publish `nodeAnchor = null`, the user KDoc
  records that decision for both directives, and no `evidence` field is
  reintroduced in the user or artifact serializer paths.
- Concurs: READONLY is not confused with PURE for determinism. `isAnnotatedPure`
  and the pure-function derivative promotion remain PURE-only, and the
  READONLY derivative test asserts the annotated function itself is not
  promoted as a pure function.
- Concurs: a pack-owned intent named `READONLY` cannot be mistaken for the user
  directive because `UserAnnotationResolver.isUserReadOnly` requires the
  contribution rule to be `USER_CONTRIBUTOR_ID`; likewise the open name is not
  reserved from packs.
- Concurs: the renderer completes `PURE` and `READONLY`, the command remains
  range-aware, READONLY persists through the Lua writer and reload, and the
  renderer README documents the directive without claiming determinism.
- Dissents: findings 1-3.

### Residual observations (not filed as findings)

- Directive semantics are matched by exact annotation/declaration span via
  `covering`, so a range record confers READONLY semantics only when the stored
  range exactly equals the declaration node span. This mirrors the existing
  PURE limitation; if range-selected directives are meant to work generally,
  containment matching would be needed.
- `complete_user_intents` only completes the first intent token, while the
  README advertises comma-separated `:CintAnnotate PURE,READONLY`; the second
  token receives no completion.
- The `Annotation.nodeAnchor` KDoc still describes client-authored annotations
  as future work even though ticket 030 is now marked done; it remains accurate
  that the engine does not populate the field.

### Verified

- `git diff --name-only -- analysis-engine/core/api` is empty;
  `analysis-engine/core/api/src/main/kotlin/dev/cint/api/Model.kt:20` still
  defines `CINT_API_VERSION = 5`.
- No `evidence` field exists in the user model/resolver or
  `AnnotationJson`; no `nodeAnchor` population was added.
- `READONLY` appears in the resolver constants, host intent vocabulary,
  CapabilityView, derivative read-only confidence, renderer completion/tests,
  `docs/intent-model.md`, and `nvim-renderer/README.md`.
- Reviewer performed read/reason only; no build, test, formatter, source edit,
  stage, or commit was run, per `docs/workflow.md`.

### Summary for the implementor

The READONLY slice is structurally right: it is an open user intent, survives
resolution/provenance/artifact vocabulary, stays out of the PURE-only
determinism path, and is documented and completed by the renderer; the api
surface and version are untouched. Before closing, remove or explicitly pin the
extra PURE branches in `CapabilityView.isSideEffectFree` (they change published
PURE validation confidence), and make the READONLY tests discriminating by
asserting `0.7` derivative confidence and a `0.6` validation result — the
current derivative test passes even without the new READONLY branch. Also
correct the stale `evidence` sentence in `docs/intent-model.md`.

## Round 2

Verdict: All three Round-1 findings are resolved and no new api-fidelity
finding was introduced. The READONLY derivative test now discriminates the
`0.7` side-effect-free confidence from the `0.55` unknown-read-only control; the
validation test pins READONLY and PURE guards at `0.6` against a `0.5`
no-directive control. The PURE view arms are now a deliberate, recorded
clarification in the ticket rather than an unpinned behavior change. The
intent-model prose no longer claims annotations carry `evidence`, uses
"directive semantics", and includes user-readonly calls in the derivative
policy. The `api` module remains untouched and `CINT_API_VERSION` is still `5`.
The dimension can close.

### Findings

None. Round-1 findings 1-3 are addressed and are not re-reported.

### Concurs / Dissents

- Concurs: Round-1 finding 1 is addressed. `CapabilityView.isSideEffectFree`
  keeps the PURE arms, `ValidationReadOnlyTest.userDirectivesUseSideEffectFreeValidationConfidence`
  pins `pureGuard()` at `0.6` (with READONLY also `0.6` and `plainGuard()` at
  `0.5`), and the ticket now states that user-PURE side-effect-free validation
  status is an explicit clarification while the PURE-only derivative path is
  unchanged. That is a recorded contract decision rather than an accidental
  behavior change.
- Concurs: Round-1 finding 2 is addressed. The READONLY derivative test asserts
  `0.7` for the `val source` derivative and `0.55` for the same span with no
  directive, with the pure-function distinction still covered by the absence of
  a `fun make` derivative. The new validation-pack test covers the composition
  acceptance directly.
- Concurs: Round-1 finding 3 is addressed. `docs/intent-model.md` now describes
  annotations as carrying contributor `provenance`, not `evidence`, and the new
  user-directive section accurately distinguishes PURE determinism from
  READONLY's no-write claim. Remaining uses of the word "evidence" elsewhere in
  the document are generic reasoning prose, not the removed contract field.
- Concurs: the added `userAnnotations` support in `PackTestHarness` and
  `ValidationRulePackSupport` is test-only plumbing; it does not widen the
  production api or artifact surface.
- Concurs: the frozen `api` module is still untouched (`git diff --name-only --
  analysis-engine/core/api` is empty), `CINT_API_VERSION` remains `5`, user
  annotations still publish `nodeAnchor = null`, and no evidence field was
  reintroduced.
- Concurs: READONLY remains an open user intent, visible in the intent set and
  provenance, present in the artifact vocabulary when used, and not confused
  with PURE or a pack-owned intent.
- Dissents: none.

### Summary for the implementor

Round 2 closes the api-fidelity dimension. The remaining contract claims are
now pinned by discriminating tests: READONLY raises derivative confidence to
`0.7` versus the `0.55` control, READONLY/PURE guards use the `0.6`
side-effect-free validation confidence versus `0.5` for an unannotated call,
and the deliberate PURE validation clarification is recorded in the ticket.
The intent-model now matches the evidence-free provenance contract, and the api
module and `CINT_API_VERSION = 5` are unchanged. No further api-fidelity work is
required on this ticket.
