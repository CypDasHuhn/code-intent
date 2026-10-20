# Correctness review — ticket 032 (remove evidence from annotation contract)

## Round 1

Verdict: The removal is complete and internally consistent. `Emit`,
`Contribution`, and `Annotation` no longer carry evidence; the emit builders,
store normalization/order, capability restriction, composition helpers, artifact
and live serializers, renderer fixture payloads, and current prose all agree on
the v4 shape. Re-deriving the three snapshot files from their pre-change
contents by deleting only the `evidence` keys/field entry reproduces the new
files byte-for-byte, so detection output and annotation ordering are otherwise
preserved. The source-based convergence tests preserve the ticket-017
metadata-only regression under the remaining contract. The version gate is
coherent: v4 is declared, a v3 pack is rejected with the exact message, and the
bundled packs inherit v4 through `SingleIntentPack`. One test-helper correctness
problem remains: the new span-text helpers treat tree-sitter UTF-8 byte offsets
as Kotlin string indices.

### Findings

1. **`analysis-engine/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/support/PackTestHarness.kt:26` (also `analysis-engine/integration-tests/src/test/kotlin/dev/cint/integration/composition/support/CompositionTestSupport.kt:81`, `analysis-engine/integration-tests/src/test/kotlin/dev/cint/integration/host/HostConfigWiringTest.kt:16`, and `analysis-engine/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/errorhandling/ErrorHandlingRulePackTest.kt:181`) — span text is sliced with UTF-8 byte offsets as UTF-16 `String` indices.**

   `Annotation.startByte`/`endByte` come from tree-sitter and are UTF-8 byte
   offsets (`TsSyntaxTree.text` decodes
   `source.text.toByteArray(Charsets.UTF_8)` at
   `analysis-engine/treesitter/src/main/kotlin/dev/cint/treesitter/KotlinParser.kt:49-53`),
   but every new helper is
   `source.substring(startByte, endByte)`. That is only equivalent for ASCII
   source. A non-ASCII fixture would make the extracted text a different slice,
   so span-text assertions can pass or fail against the wrong text, and the
   replacement for evidence would not be faithful. Existing tests happen to be
   ASCII, and `VuePipelineTest.kt:41` already demonstrates the correct
   char-index-to-UTF-8-byte conversion pattern. Fix: make the helper decode the
   byte range, for example
   `source.toByteArray(Charsets.UTF_8).copyOfRange(startByte, endByte).toString(Charsets.UTF_8)`,
   share one implementation instead of four copies, and add a non-ASCII case (or
   explicitly document the ASCII-only limitation).

### Concurs / Dissents

- Concurs with the concurrent api-fidelity finding on historical snapshot
  provenance (`legacy-3f20435.json`, `pipeline-3f20435.json`,
  `PipelineSnapshotTest.kt:25-31`, `docs/notes.md:461-468`,
  `docs/manual-test.md:16`). From the correctness side, the current-pipeline
  snapshot is correctly regenerated; the two pre-018 resources are now
  post-032-normalized bytes while still labelled as frozen pre-018 history, so
  their digest test no longer preserves the historical shape. I do not re-file
  it separately because the api-fidelity report already records the same work.
- Concurs: behavior preservation is otherwise exact. Comparing each old snapshot
  with the new file after removing `evidence` from annotations/provenance and
  from the `fields` list yields equality, including annotation order, spans,
  sources, confidences, and byte offsets.
- Concurs: artifact/live payload correctness. `AnnotationJson` no longer emits
  `evidence` at annotation or provenance level, and `ArtifactTest` plus
  `AnnotationSerializationTest` pin both levels while keeping the remaining key
  order and `nodeAnchor` behavior intact.
- Concurs: convergence is preserved. `sourceMetadataStabilizesAtStableIdentity`
  and `sourceChangeTriggeringNewIdentityIsReached` still vary metadata at a
  stable `(role, startByte, endByte, intents)` identity, retain the same run
  counts/expectations, and exercise the same derived fixpoint limit now that
  `source` replaces `evidence` as the mutable metadata.
- Concurs: the version gate is complete. `CINT_API_VERSION` is 4 with the
  breaking-removal note, `RulePackLoaderConfigTest.kt:104-113` rejects a v3 pack
  before configuration with `targets api version 3, expected 4`, and the older
  fixture tracks `CINT_API_VERSION - 1`.
- Concurs: current docs (`analysis-engine/README.md`, `docs/architecture.md`,
  `docs/intent-model.md`, `docs/notes.md`, `docs/rulepack-support-plan.md`,
  ticket 017, and the task index) describe the evidence-free shape; historical
  reviews and version-era task notes are reasonably left historical.
- Dissents: finding 1.

### Residual observations (not filed)

- `BookkeepingRulePackTest` replaces one exact evidence-prefix assertion with
  `startsWith` plus `contains` because span text preserves the newline that
  evidence used to flatten. The span still covers both fragments, so this is
  weaker prose but not a behavior change.
- `ConfigSupportTest.emit(label)` now carries its label in `intents` because
  evidence was the only remaining string field available for ordering checks.
  It is test-only and does not reach the engine, but a dedicated label or
  `source` would read better and avoid using intent names as arbitrary tags.
- `docs/notes.md` §20 still says the per-language error-handling packs are
  constructed by `BundledRulePackSelection`; that is stale from ticket 029 and
  unrelated to this diff.
- Historical task notes for v2/v3 (tickets 019, 020, 025) still state their old
  versions, which is acceptable if they remain frozen history.

No build, test, formatter, or source edit was run by this role; the handover is
assumed green per `docs/workflow.md`.

## Round 2

Verdict: The round-1 finding is addressed. Span text now comes from one shared
test-fixtures helper that slices and decodes the UTF-8 byte range, all four
local copies are gone, and a non-ASCII regression test pins the distinction
between byte offsets and string indices. The two historical snapshot resources
are restored to their original bytes and digests, only the current pipeline
snapshot carries the v4 shape, and the new comparison test strips evidence from
the historical files at comparison time rather than rewriting them. The current
snapshot still equals the pre-change snapshot with only `evidence` removed, and
the payload, version-gate, convergence, and docs conclusions from round 1 are
unchanged. I found no new correctness defect.

### Findings

- None.

### Concurs / Dissents

- Concurs with round-1 finding 1: `AnnotationText.kt:6-18` defines
  `sourceText` with `toByteArray(Charsets.UTF_8).copyOfRange(...)` and decodes
  back to `String`; `Annotation.text` and `Contribution.text` both delegate to
  it. Every former local helper and inline
  `substring(startByte, endByte)` use is replaced by
  `dev.cint.test.fixtures.text`, and
  `PackTestHarnessTest.annotationTextUsesUtf8ByteOffsets` checks
  `"πfun f() {}"` at bytes 2..8 yielding `"fun f("`, which fails under the old
  character-index slice.
- Concurs with the concurrent api-fidelity finding on historical snapshot
  provenance: `legacy-3f20435.json` and `pipeline-3f20435.json` now match their
  original HEAD digests again, `pipeline-current.json` remains the only snapshot
  updated to the evidence-free shape, and `PipelineSnapshotTest` keeps the
  historical digests while adding a normalization test. The normalization
  removes annotation-level evidence and the `fields` entry, leaves valid JSON,
  produces zero `"evidence"` occurrences, and yields the current fields list; I
  checked that behavior directly against both historical files.
- Concurs: the restored historical files are no longer passed off as the current
  shape. `docs/architecture.md`, `docs/intent-model.md`, `docs/notes.md`, and
  the m008 row in `docs/manual-test.md` now state that the historical snapshots
  retain evidence and only `pipeline-current.json` follows v4.
- Concurs: behavior preservation still holds for the payload that the v4
  contract covers. `pipeline-current.json` equals its pre-change contents after
  removing only `evidence`, so detection output, ordering, spans, sources,
  confidences, and byte offsets are unchanged.
- Concurs: no new payload or version-gate issue was introduced by the helper or
  snapshot rework. The artifact/live serializers, `CINT_API_VERSION = 4`, the
  v3 rejection, and the source-based convergence tests are all unchanged.
- No dissent.

### Residual observations (not filed)

- The new UTF-8 helper test covers `Annotation.text` only; `Contribution.text`
  is untested directly, though it delegates to the same `sourceText` function.
- `PackTestHarnessTest.loadsFixturesAndAssertsIntentSpans` still uses
  `text.substring(annotation.startByte, annotation.endByte)` for a non-blank
  check on an ASCII fixture; harmless, but it is the last byte-offset slice that
  does not use the shared helper.
- The round-1 residual about `docs/notes.md` §20 still describing per-language
  error-handling packs remains; it is unrelated to this ticket.
- Historical task notes for v2/v3 (tickets 019, 020, 025) still state their old
  versions, which remains acceptable as frozen history.

No build, test, formatter, or source edit was run by this role; the handover is
assumed green per `docs/workflow.md`.
