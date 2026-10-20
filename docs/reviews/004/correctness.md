# Correctness review — ticket 004 (capability-scoped view)

## Round 1

Verdict: The common path is sound: all `View` members are present, core facts and
derived helpers delegate to the existing `SyntaxModel`/config without adding a
second fact layer, annotation-level gating on `owns + reads` works for a single
merged span, and the strict path throws for an undeclared requested intent.
Two correctness defects remain at the capability boundary: `intentsAt` filters
after the innermost-annotation selection, so a smaller undeclared annotation can
hide a declared (including the rule's own) intent; and the annotation copy keeps
top-level intents that the surviving provenance does not support. The new test
covers the merged-span case only, so neither defect is exercised.

### Findings

1. **analysis-engine/core/src/main/kotlin/dev/cint/core/view/CapabilityView.kt:64-65 — `intentsAt` can hide a declared/owned intent behind a smaller undeclared annotation.**
   `context.intentsAt(node)` (`core/engine/Engine.kt:201-220`) first selects the
   smallest covering annotation(s), and only then does the view filter the result
   through `visible`. With a small `LOGGING` annotation nested inside a larger
   `BOOKKEEPING` annotation, a view that owns or reads `BOOKKEEPING` receives
   `{LOGGING}` from the context and returns empty after filtering, even though the
   larger annotation carries its declared intent. That violates "the rule's own
   owned intent is always visible" and makes the answer depend on the presence and
   size of undeclared annotations. `CapabilityViewTest` seeds every contribution
   at the same `if` node (`CapabilityViewTest.kt:56-60`), so the overlap case is
   never exercised, and `failOnUndeclaredRead` does not catch it either because
   `intentsAt` never consults the flag.
   Fix: apply visibility before the innermost/union selection — filter the
   covering annotations to those carrying a visible intent and then choose the
   smallest, or add a visibility-aware `intentsAt(node, visible)` on the context.
   Add a test with a smaller undeclared annotation nested in a larger declared one
   asserting the declared intent is returned and the undeclared one never leaks.
   Decide explicitly whether strict mode should throw when only invisible
   annotations cover the node.

2. **analysis-engine/core/src/main/kotlin/dev/cint/core/view/CapabilityView.kt:76 — the restricted annotation copies `intents` from the original annotation instead of rebuilding it from the surviving provenance.**
   `restrictedTo` filters the contributor list and recomputes `evidence`, `source`
   and `confidence` from the survivors, but takes `intents` from the original
   top-level set. `Annotation` only requires a non-empty `provenance`; it does not
   enforce `intents == provenance.flatMap { it.intents }`
   (`api/.../Model.kt:37-51`). A context whose annotation advertises a visible
   intent that no contributor carries can therefore return an annotation with
   that intent but no rule or evidence behind it; the view then surfaces data the
   provenance does not support. This is not reachable through the current
   `AnnotationStore` (which computes the union), but `CapabilityView` is a public
   implementation over the `RuleContext` interface and should not rely on an
   unenforced invariant.
   Fix: derive the result with
   `intents = provenance.flatMap { it.intents }.toSet()` (optionally also assert
   the invariant in `Annotation.init`), and add a test using a hand-built
   annotation whose top-level intents exceed its provenance, asserting the
   view's returned intents equal the surviving contributor union.

### Concurs / Dissents

- Concurs: the frozen `api` surface is untouched and `CapabilityView` implements
  every §5 accessor with the frozen signatures; no detection or rule-specific
  logic was added to the core view.
- Concurs: the nine core facts delegate to the existing syntax facts, and
  `coverage`, `enclosingFunction`, `enclosingDeclaration`, `writes` and
  `isAnnotatedPure` delegate to the existing engine/syntax/config implementations.
- Concurs: `annotations(intent)` correctly rejects an undeclared requested intent
  with an empty list by default and `IllegalStateException` when
  `failOnUndeclaredRead` is set; an owned intent is readable even when not listed
  in `reads`.
- Concurs: `restrictedTo` preserves role, span, byte offsets and the reserved
  `nodeAnchor`, strips undeclared intents from both the top-level set and each
  contribution, keeps provenance non-empty, and recomputes aggregate fields the
  same way `AnnotationStore` does.
- Dissents: `intentsAt` is not capability-faithful for overlapping annotations
  (finding 1).
- Dissents: `restrictedTo` can return an annotation whose top-level intents are
  not backed by its provenance (finding 2).

### Residuals

- `RuleContext` still exposes unscoped `annotations()`/`intentsAt()`, and
  `view()` is not yet wired into rule execution; this matches the out-of-scope
  003/005 cutover, but until then the gating is only enforced for views built
  directly.
- `AnalysisContext.view` accepts caller-supplied `owns`/`reads` rather than
  deriving them from the running `Rule`; 003 should construct the view from the
  rule so every caller cannot choose its own capability set.
- The fact lists/maps are the context's cached objects (`calls`, `references`,
  etc. are read-only interfaces over mutable JVM collections); a rule that casts
  them can mutate state shared by later views. Not filed as a finding because the
  contract is read-only and rule code is trusted, but it is not an immutable
  snapshot.
- `coverage` remains intentionally ungated and exposes intents from other rules;
  architecture §5 and the ticket list it as an always-available derived fact, and
  the test pins that behavior.

## Round 2

Verdict: Both round-1 findings are addressed. `intentsAt` now builds the
candidate set from every containing `SOURCE_INTENT` annotation, applies
visibility per annotation, and only then chooses the smallest visible span, so a
smaller undeclared annotation can no longer mask a declared intent; the new
nested-span test pins that behavior. `restrictedTo` now rebuilds `intents` from
the surviving provenance, and the new hand-built-annotation test pins the
provenance/intents invariant. No new correctness findings.

### Findings

- F1: concurs — `CapabilityView.intentsAt`
  (`analysis-engine/core/src/main/kotlin/dev/cint/core/view/CapabilityView.kt:69-86`)
  uses the new `RuleContext.containingAnnotations`
  (`core/engine/Engine.kt:202-209`) and filters to `SOURCE_INTENT`, computes
  `visibleIntents` per annotation, drops annotations with no visible intent, and
  selects the minimum span only from the survivors. The
  `intentsAtSelectsVisibleAnnotationBeforeInnermost` test
  (`treesitter/.../CapabilityViewTest.kt:181-194`) nests a smaller `LOGGING`
  annotation inside a larger `BOOKKEEPING` one and asserts `BOOKKEEPING` is
  returned and `LOGGING` never is. The strict path is also explicit and tested
  (`strictModeFailsOnlyForUndeclaredSourceCoverage`).
- F2: concurs — `visibleProvenance`/`visibleIntents`/`restrictedTo`
  (`CapabilityView.kt:89-107`) now derive the returned `intents` from
  `provenance.flatMap { it.intents }` (sorted, as the store does), so the
  restricted annotation can no longer advertise a top-level intent with no
  surviving contributor.
  `core/.../view/CapabilityViewTest.kt:58-94` constructs exactly that
  inconsistent annotation and asserts the view returns only the
  provenance-backed intent and hides the unsupported one.

### Concurs / Dissents

- Concurs: both round-1 findings are fixed, with tests that fail if either fix
  is reverted.
- Concurs: the added role filter for `annotations`/`intentsAt`
  (`CapabilityView.kt:63,70`) keeps the plugin-facing reads to
  `SOURCE_INTENT`, matching architecture §1; the new
  `pluginFacingReadsIgnoreCoreOnlyRoles` test pins it. This is beyond the
  round-1 findings but consistent with the ticket.
- Concurs: core facts and derived helpers are unchanged and still delegate to
  the existing syntax/CFG/config; no detection logic entered the view.
- Dissents: none.

### Verified

- `intentsAt` no longer depends on the unfiltered `AnalysisContext.intentsAt`
  result; invisible annotations influence only the strict-mode error, not the
  returned set. A node covered by no annotation still returns empty, and a node
  covered only by core-only roles returns empty even in strict mode.
- `restrictedTo` preserves role, span, byte offsets, `nodeAnchor` and
  provenance non-emptiness; `evidence`, `source` and `confidence` remain
  consistent with `AnnotationStore` aggregation.
- The new core test compiles against the `RuleContext` interface with the added
  `containingAnnotations` member; no other implementer of `RuleContext` was
  missed.
- No `api` surface change was introduced by the round-2 edits.

### Residuals

- The strict `intentsAt` error fires whenever a node is covered only by source
  annotations whose intents are all invisible. That is a deliberate, tested
  reading of "fails loudly in dev", but a rule that inspects arbitrary nodes
  (rather than asking for a specific intent) will now fail in strict mode on
  nodes another rule legitimately tagged; this is a behavior policy to confirm
  when 003/006 wire rules to `View`.
- The round-1 residuals remain: legacy `RuleContext` still exposes unscoped
  `annotations()`/`containingAnnotations()`, `AnalysisContext.view` trusts
  caller-supplied `owns`/`reads`, shared fact collections are not immutable
  snapshots, and `coverage` is intentionally ungated.

