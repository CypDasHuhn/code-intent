# Correctness review — ticket 022 (simplify error-handling and validation packs)

## Round 1

Verdict: Both packs are moved onto `View` facts and emit builders without an
obvious default-detection regression. Error handling still accepts configured
callees/qualifiers, throws, and trailing-lambda call spans; validation still
classifies guards by explicit comment, bookkeeping write, configured predicate,
or side-effect-free condition, with the same confidence/source/certificate
values. Two problems remain: the diff removes supported config keys as an
undocumented contract break, and the substantially rewritten magic-comment
lookup has no direct positive/scope regression test.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/errorhandling/ErrorHandlingRulePack.kt:52-65` and `analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:47-64` (pinned as intentional by `PackConfigMigrationTest.kt:60-64,117-121`) — removing `detectThrows` and `commentKinds` changes the pack-config contract beyond the ticket's parity goal.**

   The pre-diff `ErrorHandlingConfig.withOverrides` explicitly accepted both
   `throws` and `detectThrows`, so the alias was a supported config spelling,
   not dead code. `ValidationConfig.commentKinds` was likewise a typed field
   with a public default and was used to restrict which comments could certify
   a guard. Existing project configs that use either key now fail at load with
   `unknown key`; for `commentKinds` the previous behavior of excluding a
   comment kind is also no longer expressible because the rule now consults
   every profile comment. Removing the grammar-kind config is consistent with
   the ticket's "no grammar node kinds in these packs" rule, but the breaking
   removal/deprecation is not recorded in the ticket or README and the new
   tests only assert that the keys fail. Fix: either retain `detectThrows` as a
   deprecated alias and retain `commentKinds` as a deprecated restriction (or
   a documented no-op), or record the removal and the resulting behavior change
   as a deliberate contract change with a migration note; keep a positive test
   that canonical `throws` still controls throw emission.

2. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:151-173` with `analysis-engine/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/validation/ValidationRuleCommentTest.kt:8-49` — the rewritten comment certificate has only a negative test and one host-level happy path, so the old scope/precedence behavior is unpinned.**

   `hasValidationComment` used to search comments descended from the guard's
   function body and select the nearest preceding one. It now scans all
   `view.comments()` and keeps comments whose start is at or after the enclosing
   scope's start. The only rulepack test proves that string literals are not
   comments; `configuredValidationMagicCommentDrivesRuleAndReturnCategorization`
   covers only an immediately preceding custom comment. No test pins the
   default `// cint:validation` path, nearest-comment selection, the one-line
   gap, or that a comment in an outer/previous function is not used for a guard
   in a nested function. A scope or selection regression would therefore pass
   the suite. Fix: add direct `ValidationRule` cases asserting (a) a default
   magic comment before a guard emits `source = "comment"`, confidence `1.0`,
   and the condition span; (b) a comment more than one line above does not
   certify; (c) a comment in a preceding sibling function does not certify a
   guard in the next function; and (d) a configured `magicComment` still works.

### Concurs / Dissents

- Concurs: error-handling matching is equivalent for the supported call facts.
  `CallFact.path` is built as `receiver.text + "." + callee` (or bare callee),
  so `substringAfterLast('.')` recovers the simple callee and the two path
  forms in `accepts` reproduce the old receiver equality/suffix checks,
  including the fully qualified C# `Debug.Assert` case and the unqualified
  Kotlin/JS checks.
- Concurs: throw emission is unchanged apart from using `Emit.of` directly; the
  `detectThrows` branch still gates `view.throws()` and the evidence/span come
  from the throw node.
- Concurs: validation guard selection is equivalent for current profiles.
  Every profile's `if` kinds are a subset of its `guard` kinds, and
  `ControlFlowFacts.guards` derives `CONDITIONAL` facts from the same `ifs`
  used by the old loop, carrying the same condition/then-body. The predicate,
  purity, constant, bookkeeping, confidence, source-tier, and
  `certifiesReturn` logic is unchanged.
- Concurs: validation emit construction is equivalent. `Emit.of` derives the
  same span/bytes/evidence as the removed manual constructor, uses
  `"treesitter"` via `Emit.DEFAULT_SOURCE`, and preserves `certifiesReturn`.
- Concurs: default comment-kind parity holds: Kotlin's profile lists `comment`,
  `line_comment`, and `multiline_comment` (the old default), and JavaScript,
  TypeScript, and C# expose `comment`; no default language gains or loses
  comment shapes.
- Concurs: neither pack compares grammar node kinds or climbs raw parent
  chains; the remaining `SyntaxNode` use is fact containment (`startByte`/
  `endByte`) for bookkeeping/predicate checks.
- Dissents: findings 1 and 2 above.

### Residuals (not findings)

- The new comment filter checks only `comment.startByte >= scope.startByte` and
  not `comment.endByte <= scope.endByte`; it is safe while the guard is inside
  the scope, but it is narrower than the old descendant invariant and would be
  fragile if the scope calculation ever returns a non-containing node.
- `hasValidationComment` re-scans all comments for every guard, making the
  rule O(guards × comments); this is a performance regression only.
- The `detectThrows` Kotlin property remains on `ErrorHandlingConfig` while the
  config key is `throws`; keeping that asymmetry is fine only if the alias
  removal is intentional and documented.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `workflow.md`.

## Round 2

Verdict: The `detectThrows` half of finding 1 and finding 2 are addressed. The
alias is back in the schema and is now covered both as config decoding and as a
pack that suppresses throw emission; the new comment tests directly pin the
default magic comment's source/confidence, nearest-comment precedence, the
one-line gap, and isolation from a previous function. One correctness problem
remains: `commentKinds` is restored only as a stored setting, but the validation
rule never consults it, so the alias is a silent no-op that can widen detection
for existing configs.

### Findings

1. **`analysis-engine/rulepacks/src/main/kotlin/dev/cint/rulepacks/validation/ValidationRulePack.kt:19-24,66-70,172-180` with `analysis-engine/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/validation/ValidationRuleCommentTest.kt:160-181` — `commentKinds` is accepted and stored but ignored, so the restored alias does not preserve its old restriction.**

   `ValidationConfig.commentKinds` is decoded and retained (and the config
   migration test asserts the value is stored), but `hasValidationComment` selects
   from `view.comments()` without filtering `comment.node.kind` against
   `config.commentKinds`. The new test sets `commentKinds` to `line_comment` and
   uses a line comment, so it passes whether the setting is honored or ignored;
   it only proves the pack is not dropped. A config that previously used
   `commentKinds` to exclude block/doc comments now silently certifies guards from
   those comments, which is the widening the ticket says must be deliberate and
   documented. Accepting a key while ignoring it is also worse than a clear
   rejection because callers cannot detect the no-op. Fix: either honor the
   setting when choosing the nearest comment, or remove/deprecate the key with an
   explicit documented migration error; in either case add a negative regression
   (for example `commentKinds = ["block_comment"]` with a line-comment magic
   marker) that pins the chosen behavior rather than only the happy path.

### Concurs / Dissents

- Concurs: finding 1 (`detectThrows`) is addressed. Both `throws` and
  `detectThrows` map to `detectThrows`, `errorHandlingAcceptsDetectThrowsAlias`
  pins decoding, and `detectThrowsAliasControlsThrowEmission` pins that a
  configured pack actually suppresses throws.
- Concurs: finding 2 is addressed. `defaultMagicCommentCertifiesWithCommentSourceAndConfidence`
  pins the condition span, `comment` source tier, and `1.0` confidence;
  `nearestCommentWinsOverEarlierMagicComment` and
  `nearestMagicCommentWinsOverEarlierOrdinaryComment` pin nearest-comment
  precedence; `magicCommentMoreThanOneLineAboveDoesNotCertify` pins the gap; and
  `magicCommentInPreviousFunctionDoesNotCertifyNextFunction` pins the scope
  boundary.
- Concurs: the string-content negative cases and the default `commentKinds`
  set remain unchanged, and the validation/error-handling match and emit logic
  reviewed in Round 1 is otherwise untouched.
- Dissents: the `commentKinds` no-op in finding 1 above.

### Residuals (Round 2)

- The `commentKinds` schema field still carries grammar node-kind names, so
  honoring it in the pack would reintroduce the kind comparison ticket 022
  removes; if that is why it was made a no-op, the deprecation needs to be
  explicit rather than silent.
- `hasValidationComment` still scans all comments for every guard and still
  checks only the scope start, as noted in Round 1.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `workflow.md`.

## Round 3

Verdict: Round-2 finding 1 is addressed. `commentKinds` now filters through a
semantic `CommentKind`, the default mapping preserves the shipped-profile
comment sets, and the new line-only/block regressions distinguish honored from
ignored configuration. Two new issues remain in the semantic-kind support
itself: `CommentFact.kind` is populated by only one of the `View` comment
accessors, and the new field changes the frozen `CommentFact` constructor
without an API-version/contract guard.

### Findings

1. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Facts.kt:89-92` with `analysis-engine/core/src/main/kotlin/dev/cint/core/syntax/CallFacts.kt:123-127` and `analysis-engine/core/src/main/kotlin/dev/cint/core/syntax/DeclarationFacts.kt:284,354` — the same comment gets a semantic kind from `view.comments()` but `OTHER` from `view.commentBefore()` and `DeclarationFact.precedingComments`.**

   Only `SemanticFacts.comments` passes the computed kind to `CommentFact`.
   `DeclarationFacts.commentBlock` and `commentBefore` still call the single-node
   constructor, so a `// ...` line comment safely exposed by `view.comments()` as
   `CommentKind.LINE` is exposed as `CommentKind.OTHER` by the other public
   `View` accessors. The new `CommentKind` is therefore not a reliable fact: a
   rule that filters `commentBefore`/`precedingComments` by kind (or a future
   validation implementation that reuses those accessors) misclassifies the same
   source text. Fix: centralize comment-fact construction in one helper that
   computes the kind from the syntax profile and use it from `SemanticFacts`,
   `commentBlock`, and `commentBefore`; add a test that both accessors report the
   same `LINE`/`BLOCK` kind for the same magic comment.

2. **`analysis-engine/api/src/main/kotlin/dev/cint/api/Facts.kt:89-92` and `analysis-engine/api/src/main/kotlin/dev/cint/api/Model.kt:7` (with no new contract assertion in `api/src/test/kotlin/dev/cint/api/ContractTest.kt`) — `kind` is inserted before `magicComment` in the public `CommentFact` constructor at `CINT_API_VERSION = 2`, so the change is not additive for positional callers.**

   Existing rulepack code written as `CommentFact(node, "validation")` now binds
   the string to `kind` and fails to compile; already-compiled code linked
   against the old two-argument constructor also has no matching descriptor.
   The support plan requires API additions to be additive and versioned, and the
   contract tests still construct `CommentFact` only with one argument, so this
   break is unpinned. Fix: append `kind` after `magicComment` (or provide a
   compatible secondary constructor/factory), and add a contract test for the
   supported construction shapes and the new enum mapping.

### Concurs / Dissents

- Concurs: Round-2 finding 1 is addressed for the validation path. `commentKinds`
  is consulted before nearest-comment selection, `lineOnlyCommentKindsExcludeBlockMagicComment`
  proves a configured line-only set suppresses a block magic comment, and
  `legacyBlockCommentKindAcceptsBlockMagicComment` proves the legacy
  `multiline_comment` spelling still admits block comments.
- Concurs: the default `DEFAULT_COMMENT_KINDS` expands to `LINE`, `BLOCK`, and
  `OTHER` for the shipped profiles, matching the old per-node-kind default set
  (`comment`, `line_comment`, `multiline_comment`), so default detection is
  unchanged.
- Concurs: the Round-2 magic-comment regression suite (default source/confidence,
  nearest comment, line gap, previous-function isolation) and the
  `detectThrows` alias/emission tests remain correct and unchanged.
- Dissents: findings 1 and 2 above.

### Residuals (Round 3)

- `CommentKind.fromConfig` maps the generic `comment` name to every semantic
  kind, so a custom `commentKinds: [comment]` now admits line/block/doc/other
  comments whereas the old grammar-kind filter admitted only nodes literally
  named `comment`. This semantic widening appears intentional but is not
  documented.
- The node-kind-to-`CommentKind` mapping is hardcoded in core rather than derived
  from the syntax profile, so a future profile with another comment node spelling
  falls back to `OTHER` unless the mapping is extended.
- `hasValidationComment` still scans all comments per guard and checks only the
  scope start; both are pre-existing Round-1 residuals.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `workflow.md`.

## Round 4

Verdict: Both Round-3 findings are addressed. Comment facts now come from one
`commentFact` helper used by `comments()`, `commentBlock`, and `commentBefore`,
so every accessor reports the same semantic kind, and `CommentFact` again keeps
the legacy `(node, magicComment)` positional constructor through
`@JvmOverloads` with a contract test. The `commentKinds` filtering and all prior
detection regressions remain intact. No new correctness finding.

### Findings

- None. The Round-3 kind-inconsistency finding is fixed centrally (no remaining
  `CommentFact` construction path bypasses `commentFact`), and the constructor
  compatibility finding is fixed by ordering `node, magicComment, kind` and
  generating the legacy overloads; `commentFactKeepsLegacyPositionalConstructor`
  checks both JVM constructors and `allCommentPathsShareSemanticKind` checks
  `comments()`, `commentBefore()`, and `precedingComments` against each other.

### Concurs / Dissents

- Concurs: `SemanticFacts.comments`, `DeclarationFacts.commentBlock`, and
  `DeclarationFacts.commentBefore` all route through the same `commentFact`
  helper, so a line or block comment has one `CommentKind` regardless of which
  public `View` accessor produced it.
- Concurs: the constructor parameter order now preserves existing positional
  calls, `@JvmOverloads` emits the legacy one- and two-argument descriptors, and
  the contract test pins the field order, both constructors, and the default
  `OTHER` kind for legacy construction.
- Concurs: `ValidationRule.commentKinds` still filters `view.comments()` by the
  semantic kind, and the line-only and legacy-block regressions continue to
  distinguish the honored configuration from a no-op.
- Concurs: the `detectThrows` alias, `throws` key, magic-comment source/
  confidence/precedence/gap/scope tests, and the Round-1 error-handling and
  validation parity conclusions are unchanged.
- Dissents: none.

### Residuals (Round 4)

- Adding a property to the data class still changes the generated JVM `copy`
  descriptor (now `copy(node, magicComment, kind)`); source callers using named
  or default arguments are fine, but a rulepack compiled against the old `copy`
  would need recompilation. This is outside the constructor compatibility the
  round explicitly fixed, but it matters if full binary compatibility is
  required.
- `CommentKind.fromConfig` still treats `comment` as a wildcard over all
  semantic kinds, so custom `commentKinds: [comment]` is broader than the old
  literal node-kind match; the intended semantic widening remains undocumented.
- The node-kind-to-`CommentKind` mapping is still hardcoded in core rather than
  driven by the syntax profile, and `hasValidationComment` still scans all
  comments per guard with a scope-start-only check; these are unchanged
  residuals, not regressions from this round.
- Static review only; no source edit, build, test, or formatter command was run
  by this role, per `workflow.md`.
