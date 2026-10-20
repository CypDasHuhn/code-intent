# API-fidelity review — ticket 032 (remove evidence from annotation contract)

## Round 1

Verdict: Evidence is removed from the public model, emit builders, store
aggregation/order, composition, serializers, payloads, renderer fixtures, and
the current prose contract, and the pack API is correctly bumped to v4 with the
v3 rejection pinned. The artifact and live payload now agree on the same
annotation/provenance shape with no `evidence` key. One fidelity problem
remains: the two historical pre-018 snapshot resources were rewritten and
re-pinned while the current docs still describe them as unchanged historical
captures, so their provenance is no longer truthful.

### Findings

1. **`analysis-engine/integration-tests/src/test/resources/snapshots/legacy-3f20435.json:2`, `pipeline-3f20435.json:2`, `analysis-engine/integration-tests/src/test/kotlin/dev/cint/integration/host/PipelineSnapshotTest.kt:25-31`, `docs/notes.md:461-468`, `docs/manual-test.md:16` — historical snapshots were rewritten but are still described as pre-018 history.**

   The diff removes `evidence` from both `legacy-3f20435.json` and
   `pipeline-3f20435.json` and updates `PRE_018_SNAPSHOT_DIGESTS` to the new
   bytes. Those files are documented as pre-018 (014-tree) resources restored
   from `739dff7`, not regenerated from a `3f20435` run, and ticket 027's
   acceptance is explicitly "snapshot provenance is truthful". After this
   change the bytes no longer reproduce the legacy/pre-018 shape (which included
   evidence), while the embedded `"commit":"3f20435"` / `"kind":"legacy"`
   metadata and the current notes/manual-test rows still claim they do. The
   digest test therefore passes over mutated history rather than preserving it.

   Fix: either leave the two pre-018 snapshot resources and their original
   digests untouched (they are historical fixtures and do not need to match the
   current contract), or relabel/rename them as post-032-normalized artifacts,
   update their provenance metadata, and update `docs/notes.md` and the m008 row
   in `docs/manual-test.md` to say they were rewritten for the v4 shape. Only
   `pipeline-current.json` should necessarily be regenerated to the new shape.

### Concurs / Dissents

- Concurs: `Emit`, `Contribution`, and `Annotation` no longer carry an
  `evidence` field; the private truncation helpers are gone, and
  `ContractTest.evidenceIsAbsentFromTheAnnotationContract` pins the absence on
  all three types.
- Concurs: store aggregation and capability restriction no longer derive or
  order by evidence, and contribution equality/deduplication now uses the
  remaining contract fields only. Composition helpers construct contributions
  without evidence.
- Concurs: `AnnotationJson` is the single artifact/live serializer, and neither
  annotation-level nor provenance-level output contains `evidence`.
  `ArtifactTest` and `AnnotationSerializationTest` assert the absence on the
  rendered payloads, while the live test checks both levels explicitly.
- Concurs: `CINT_API_VERSION` is 4, the KDoc records the exact-equality gate and
  required recompile, and `RulePackLoaderConfigTest` pins a v3 pack being
  rejected with the expected message. The versioned fixtures were updated
  coherently.
- Concurs: the renderer production path never read evidence, and removing it
  from `render_spec.lua` payloads keeps the decoded provenance fields intact.
- Concurs: current contract prose was updated in `analysis-engine/README.md`,
  `docs/architecture.md`, `docs/intent-model.md`, `docs/notes.md`,
  `docs/rulepack-support-plan.md`, ticket 017, and the task index. Historical
  review files were correctly left untouched.
- Dissents: the pre-018 snapshot provenance and the docs that describe it are
  no longer accurate (finding 1).

### Residual observations (not filed)

- The v4 KDoc says "Removing annotation `evidence`" but does not enumerate
  `Emit.evidence` / `Contribution.evidence` or call out that artifact and live
  consumers lose the key. The examples and tests make the shape change clear;
  the note is minimal but adequate.
- The new `Annotation.text(source)` test helpers in `CompositionTestSupport`,
  `PackTestHarness`, and `HostConfigWiringTest` slice a Kotlin `String` with
  tree-sitter byte offsets. Current fixtures are ASCII, so results match, but
  non-ASCII source would need UTF-8 offset conversion or a shared helper.
- Historical task contract notes for v2/v3 (tickets 019, 020, 025) still state
  their old versions. That is acceptable if they remain frozen historical
  notes; only current contract prose needed to move to v4.

### Verified

- No production, test, or serializer field/reference to annotation evidence
  remains; the only textual `evidence` references are the new absence
  assertions, the ticket, ticket 017, and historical reviews/task notes.
- Artifact and live payload field order and remaining fields are identical:
  `role`, `intents`, `source`, `confidence`, line/column, byte offsets,
  `nodeAnchor`, and `provenance`; provenance retains rule, role, intents,
  source, confidence, line/column, and bytes.
- Reviewer performed read/reason only; no build, test, formatter, or edit other
  than this review file was run.

## Round 2

Verdict: The Round-1 finding is resolved. The pre-018 snapshot resources are
restored byte-for-byte to their historical content, `pipeline-current.json` is
the only snapshot regenerated to the v4 evidence-free shape, and the historical
files are normalized only inside the comparison test. The new shared text helper
is UTF-8 byte-safe and is used by the converted evidence-replacement tests. No
new api-fidelity findings.

### Resolution of Round 1 finding

1. **Historical snapshot provenance — concurs.** `legacy-3f20435.json` and
   `pipeline-3f20435.json` are byte-identical to their pre-032 content and their
   original digests are back in `PRE_018_SNAPSHOT_DIGESTS`
   (`PipelineSnapshotTest.kt:37-43`); the digest test again freezes the actual
   historical bytes. `pipeline-current.json` alone follows the evidence-free
   contract, and `stripEvidence`/`snapshotFields`
   (`PipelineSnapshotTest.kt:25-35,199-209`) normalize the historical resources
   only for comparison. `docs/notes.md:461-470`, `docs/architecture.md:271-276`,
   `docs/intent-model.md:444-449`, and the m008 row in `docs/manual-test.md:16`
   now state that the historical files retain `evidence` and only the current
   snapshot follows v4. The provenance claim and the bytes agree again.

### Concurs / Dissents

- Concurs: the shared `sourceText` / `Annotation.text` / `Contribution.text`
  helper (`analysis-engine/test-fixtures/.../AnnotationText.kt`) slices the
  UTF-8 byte array rather than the UTF-16 string, and
  `PackTestHarnessTest.annotationTextUsesUtf8ByteOffsets` pins a multi-byte
  prefix.
- Concurs: the local `Annotation.text` helpers were removed and the converted
  test modules import the shared helper, so the evidence-free test assertions
  no longer use `String.substring` with tree-sitter byte offsets.
- Concurs: Round-1 evidence removal, v4 version pinning, artifact/live payload
  shape, and current prose contract remain intact. The only `evidence`
  occurrences in production/test sources are the absence assertions and the
  historical snapshot resources.
- Dissents: none.

### Residual observations (not filed)

- `PackTestHarnessTest.kt:75` still uses `text.substring(annotation.startByte,
  annotation.endByte)` for a non-blank assertion while the rest of the file uses
  the new helper. The fixture is ASCII, so it is harmless today, but using
  `annotation.text(text).isNotBlank()` would remove the last byte-offset slice
  in that test.
- The new historical-snapshot normalization test compares the `fields` metadata
  after stripping evidence, not the annotation data itself. That is appropriate
  here because the pre-018 and current snapshots intentionally differ in
  validation spans; it proves the normalization path works without pretending
  the historical output equals current output.
- The `stripEvidence` regex and the fields-order replacement are tailored to the
  known historical snapshot format. That is acceptable for a frozen-resource
  test, but the normalization should be revisited if the snapshot schema gains
  another field whose order changes.

### Verified

- `legacy-3f20435.json` and `pipeline-3f20435.json` hash to their original
  pinned digests; `pipeline-current.json` contains no `evidence` key.
- Artifact and live payloads still omit `evidence` at annotation and provenance
  levels, and `CINT_API_VERSION` remains 4 with the v3 rejection pinned.
- Reviewer performed read/reason only; no build, test, formatter, or edit other
  than this review file was run.
