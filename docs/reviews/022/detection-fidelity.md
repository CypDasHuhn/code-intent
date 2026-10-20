# Detection-fidelity review — ticket 022 (simplify error and validation packs)

## Round 1

Verdict: Default detection parity holds. The error-handling rewrite preserves the
configured-call, qualifier, throw, and trailing-lambda behavior for host-produced
facts, and the validation rewrite resolves to the same conditional guards,
conditions, then-bodies, evidence, spans, source tiers, and confidence ladder as
before. The config narrowing is deliberate and test-pinned, but one consequence
is a real detection regression for existing project configs: the removed keys
now make the whole pack fail configuration and be dropped.

### Findings

1. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:19-24,47-64 with analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/errorhandling/ErrorHandlingRulePack.kt:52-65 and analysis-engine/core/src/main/kotlin/dev/cint/core/runtime/rulepack/RulePackSupport.kt:189-198 — removing `validation.commentKinds` and `error-handling-*.detectThrows` disables the entire pack for existing configs instead of narrowing one behavior.**
   `commentKinds` was a supported pack key at HEAD (it was exercised by
   `PackConfigMigrationTest` and filtered the validation comment scan), and
   `detectThrows` was accepted as an alias for `throws` by the schema. The new
   `PackConfigMigrationTest.kt:60-64,117-121` only asserts that both keys are now
   unknown. Because `configureRulePack` treats any `RulePackConfigException` as
   `INVALID_PACK_CONFIG` and returns `null`, a project carrying either key loses
   all `ERROR_HANDLING` or `VALIDATION` detection, not just the removed feature.
   The ticket allows a documented widening but does not record this narrowing,
   and `analysis-engine/README.md:128-141` only documents `throws`. Suggested
   fix: keep accepting the two keys as deprecated/no-op aliases until the config
   cleanup ticket (025) removes them with a migration note, or record the removal
   in the ticket/README and add a host-level test that pins the intended
   degradation so this is not silently discovered by users.

### Concurs / Dissents

- Concurs: error detection is parity-equivalent for host facts. `CallFacts`
  builds `path` as `receiver.text + "." + callee` (`CallFacts.kt:22-27,63-72`),
  so the new `path == "$qualifier.$callee"` / `path.endsWith(...)` test
  (`ErrorHandlingRulePack.kt:22-29`) yields the same result as the old
  receiver-text equality/suffix test for the Kotlin, JavaScript, TypeScript, and
  C# profiles, including `System.Diagnostics.Debug.Assert`; the C# arbitrary
  receiver test still stays untagged.
- Concurs: throws still come from `view.throws()`, calls still emit on
  `CallFact.site`, so the trailing-lambda span is unchanged, and both paths still
  use `Emit.of`/`CallFact.emit` with the same evidence, span, byte offsets,
  `"treesitter"` source, and `1.0` confidence.
- Concurs: validation's `guards().filter { it.kind == GuardKind.CONDITIONAL }`
  resolves to the same `if` nodes as the old `view.ifs()` for the shipped
  profiles, and `GuardFact` reuses the same `IfFact` condition/then-body, so the
  predicate/purity/bookkeeping/constant logic is unchanged.
- Concurs: the rewritten comment lookup is equivalent for the shipped profiles:
  old scope-plus-`config.commentKinds` and new `view.comments()` plus
  function-body byte filtering both reduce to the same comment set for Kotlin's
  three comment kinds and the single `comment` kind used by JS/TS/C#. The
  explicit-comment branch still emits `source = "comment"` and confidence `1.0`.
- Concurs: validation emission now uses `Emit.of(... certifiesReturn = true)`,
  preserving the condition span, truncated newline-normalized evidence, byte
  offsets, and source tier of the removed manual builder.
- Dissents: finding 1 only.

### Residuals (not findings)

- `ErrorHandlingConfig.accepts` now requires a non-null `CallFact.path` and
  derives the callee from it. Host `CallFacts` always sets `path`, but a
  third-party/synthetic `CallFact` that leaves `path` null while setting
  `callee`/`receiver` would no longer match; the API DSL helper currently keeps
  this from affecting the tests.
- The positive explicit-comment path is exercised only through
  `HostConfigWiringTest.configuredValidationMagicCommentDrivesRuleAndReturnCategorization`
  with a custom `magicComment`; no fixture pins the default `cint:validation`
  comment, `source = "comment"`, confidence `1.0`, span, or evidence after the
  comment-lookup rewrite. The snapshot fixtures contain no `cint:validation`.
- `hasValidationComment` still inlines function-body lookup and byte-range
  filtering rather than consuming a first-class guard/comment fact. Behavior is
  parity-equivalent, but this is still close to the ticket's "no manual scope
  walking" boundary and would be a cleaner support-layer API.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `workflow.md`.

## Round 2

Verdict: Round-1 finding 1's pack-drop regression is addressed. Both keys
configure successfully, `detectThrows` is functional again, and the new
magic-comment regressions pin the default source/confidence, nearest-comment
selection, one-line distance, and function-scope isolation. One new finding
remains: `commentKinds` is accepted but never consulted, so the key is a silent
no-op and comment detection widens for configurations that used it; the new
alias test cannot distinguish honored from ignored.

### Findings

1. **analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:158-181 with analysis-engine/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/validation/ValidationRuleCommentTest.kt:161-181 — `commentKinds` is restored in the schema but no longer filters comments, and the new test passes under either behavior.**
   `ValidationRule.hasValidationComment` uses every `view.comments()` result and
   never reads `config.commentKinds`; a repository search finds the key only in
   the data class, the schema, and tests. At HEAD the comment lookup filtered
   `it.kind in config.commentKinds`, so a config such as
   `commentKinds: [line_comment]` suppressed block-comment certification. After
   this change, a `/* cint:validation */` immediately above an otherwise
   non-certifying guard produces `source = "comment"` even though the configured
   kinds exclude block comments. The new
   `commentKindsAliasIsAcceptedWithoutDroppingThePack` uses a line comment, so it
   verifies only that configuration no longer drops the pack, not that the alias
   has any effect. Suggested fix: if this is intentionally a deprecated no-op
   compatibility key, record that in the ticket/README until 025 removes it,
   rename the test to say it is ignored, and pin the accepted widening with a
   block-comment case; if the filtering semantics must be preserved, filter
   `view.comments()` by the configured kinds and add a block-comment
   counterexample (noting that this reintroduces grammar node kinds into the
   pack and conflicts with the ticket's acceptance).

### Concurs / Dissents

- Round-1 finding 1: concurs on the regression it named. `detectThrows` is
  accepted by the schema and the new `detectThrowsAliasControlsThrowEmission`
  exercises it end to end; `commentKinds` is accepted and no longer causes
  `configureRulePack` to drop the pack, which was the reported detection loss.
- Concurs: `defaultMagicCommentCertifiesWithCommentSourceAndConfidence` pins the
  default `cint:validation` path, `source = "comment"`, and confidence `1.0`.
- Concurs: `nearestCommentWinsOverEarlierMagicComment`,
  `nearestMagicCommentWinsOverEarlierOrdinaryComment`, and
  `magicCommentMoreThanOneLineAboveDoesNotCertify` pin the nearest-comment and
  line-distance behavior.
- Concurs: `magicCommentInPreviousFunctionDoesNotCertifyNextFunction` pins the
  function-scope boundary; the rewritten comment lookup remains equivalent for
  the default profiles.
- Dissents: the new finding above; it is distinct from Round-1 finding 1, which
  was about the pack being dropped entirely.

### Residuals (not findings)

- The compatibility aliases reintroduce duplicate schema fields
  (`throws`/`detectThrows`) and an inert config value; ticket 025 should collapse
  or remove them. The `commentKinds` no-op should not survive that cleanup.
- The new magic-comment tests pin source/confidence and condition bytes but do
  not assert the evidence string or the emitted `Span`; the earlier snapshot
  fixtures still contain no `cint:validation`.
- The `CallFact.path` residual from Round 1 is unchanged; host facts always set
  `path`, but synthetic facts with `callee`/`receiver` and a null `path` no
  longer match.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `workflow.md`.

## Round 3

Verdict: The Round-2 finding is addressed. `commentKinds` now filters the
validation comment scan through a semantic `CommentKind`, and the new
line-versus-block regressions prove the alias is honored rather than merely
accepted. Default behavior is unchanged because the default configuration maps
to all semantic comment kinds, and the block/line split leaves the magic-comment
source, confidence, distance, and scope behavior intact. No new
detection-fidelity finding.

### Findings

- None.

### Concurs / Dissents

- Round-2 finding: concurs. `ValidationRule` builds
  `CommentKind.fromConfig(config.commentKinds)` and filters `view.comments()` by
  `it.kind` (`ValidationRulePack.kt:100,172-180`), so the key is no longer inert.
- Concurs: `lineOnlyCommentKindsExcludeBlockMagicComment` proves a
  `/* cint:validation */` block comment no longer certifies when the configured
  kinds are line-only, which was the exact widening the Round-2 finding named.
- Concurs: `legacyBlockCommentKindAcceptsBlockMagicComment` proves the legacy
  `multiline_comment` spelling still accepts block comments, so the compatibility
  key preserves the old behavior it was meant to.
- Concurs: the default set (`comment`, `line_comment`, `multiline_comment`) maps
  to all semantic kinds, so default `VALIDATION` detection over the existing
  fixtures is unchanged; the `comment` alias also keeps JS/TS/C# working, where
  the grammar emits only the `comment` node kind.
- Concurs: `CommentKind.fromConfig` handles the legacy grammar spellings and
  semantic spellings (`line`/`block`/`doc`), so rulepacks no longer compare raw
  node kinds.
- Concurs: the error-handling `path` behavior, conditional-guard selection,
  spans, evidence, source tiers, confidence, and `certifiesReturn` remain as
  reviewed in Round 1; no detection regression was introduced by the
  comment-kind work.
- Dissents: none.

### Residuals (not findings)

- The semantic mapping reinterprets the legacy config: `comment` now means all
  semantic kinds, and spellings such as `line_comment` now match JS/TS/C# line
  comments where the old node-kind filter matched nothing. This is a sensible
  widening for a semantic key, but it should be noted when 025 documents or
  removes `commentKinds`; the new regressions are Kotlin-only.
- `SemanticFacts.commentKind` hardcodes grammar comment node-kind names in core
  rather than sourcing them from syntax-profile roles. It covers the current
  profiles and preserves parity, but it duplicates grammar knowledge outside the
  profiles the support plan designates as its home.
- `DeclarationFacts` still constructs `CommentFact` from preceding comments
  without a kind, so those facts default to `OTHER` while `view.comments()`
  reports `LINE`/`BLOCK` for the same node. No current rule reads
  `DeclarationFact` comment kinds, so this does not affect detection, but the
  semantic fact is inconsistent for future consumers.
- `CommentFact` gained a constructor parameter without a `CINT_API_VERSION` bump;
  this is an api-fidelity/contract question rather than a detection-fidelity
  finding, but the contract reviewers should confirm the intended compatibility
  stance.
- The magic-comment tests still do not assert the emitted `Span` or evidence
  string, and the snapshot fixtures still contain no `cint:validation`; this is
  the same coverage residual carried from Round 2.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `workflow.md`.

## Round 4

Verdict: The Round-3 residuals around comment-fact consistency and constructor
compatibility are addressed. `CommentFact` construction and kind classification
are centralized in `commentFact`, both `view.comments()` and declaration
preceding comments now go through it, `CommentFact` keeps the legacy
`(node, magicComment)` positional constructor via `@JvmOverloads`, and contract
tests pin that surface. Validation detection is unchanged: the semantic kind now
comes from the same classifier on every comment path. No new detection-fidelity
finding.

### Findings

- None.

### Concurs / Dissents

- Round-3 residual on inconsistent comment kinds: concurs. `DeclarationFacts.commentBlock`
  and `commentBefore` now map through the shared `commentFact`
  (`DeclarationFacts.kt:284,354`), and `allCommentPathsShareSemanticKind` asserts
  that `view.comments()`, `view.commentBefore()`, and
  `declaration.precedingComments` all report `CommentKind.LINE` for the same
  comment.
- Round-3 residual on constructor compatibility: concurs. The primary
  constructor is reordered to `(node, magicComment, kind)` with `@JvmOverloads`;
  `commentFactKeepsLegacyPositionalConstructor` accepts `CommentFact(node,
  "bookkeeping")`, `CommentFact(node)`, and the three-argument form, and verifies
  both two- and three-argument JVM constructors.
- Concurs: validation's default behavior is still unchanged because the default
  `commentKinds` maps to all semantic kinds, and the line-only/block regressions
  continue to pin the filter.
- Concurs: error-handling behavior, conditional-guard selection, spans, evidence,
  source tiers, confidence, and `certifiesReturn` remain as reviewed in Rounds
  1-3; centralizing comment facts introduced no detection change.
- Dissents: none.

### Residuals (not findings)

- The data-class `copy` and `copy$default` signatures change with the added
  `kind` parameter, so the constructor test does not by itself guarantee binary
  compatibility for Kotlin callers using `copy`. This is an api-fidelity matter;
  source compatibility is preserved and detection behavior is unaffected.
- The semantic `commentKinds` mapping still reinterprets legacy grammar
  spellings across languages (`comment` means all kinds; `line_comment` now
  matches JS/TS/C# line comments). It is sensible for a semantic key but should
  be recorded when 025 documents or removes the compatibility surface.
- `SemanticFacts.commentKind` still hardcodes grammar comment node-kind names in
  core rather than sourcing roles from the syntax profiles; current profiles are
  covered and parity holds.
- The magic-comment tests still do not assert the emitted `Span` or evidence
  string, and the snapshot fixtures still contain no `cint:validation`.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `workflow.md`.
