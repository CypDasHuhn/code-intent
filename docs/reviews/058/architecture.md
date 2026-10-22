# Architecture review — ticket 058 (AI expression discovery session)

## Round 1

Verdict: The boundary is directionally right. Expression enumeration is a core
syntax fact rather than a rulepack concern, the session lives in `suggest` while
the server owns the worklist and publication, AI output is still a
`CandidateEmit` and never enters the store/scheduler/composition, the module
dependency direction is unchanged (`suggest` → `api`/`core`, `server` →
`suggest`), and budgets, cancellation, and pass ownership sit at the
session/server seam where they belong. The provider/session contract is
incomplete, however: the system prompt is never transmitted, the transport
request embeds the annotation worklist, the documented privacy and protocol
contracts are only partly enforced, and the primary architecture document still
describes the pre-058 pipeline. Not ready to close.

### Findings

1. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/provider/DeepSeekIntentSuggestionProvider.kt:46-68` with `analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/AnnotationSession.kt:85-94,353-367` — the session system prompt never reaches the provider.**

   `AnnotationSession` builds `SYSTEM_PROMPT` and passes it as
   `AgentRequest.systemPrompt`, but `DeepSeekIntentSuggestionProvider.complete`
   constructs the wire payload from `request.messages` only. The fake provider
   ignores the field too, so the provider contract silently allows the prompt to
   be dropped. In the real DeepSeek path the model therefore receives only the
   batch payload, not the system instructions about untrusted source,
   unit containment, or the submit protocol.

   Suggested fix: have the session prepend a `system` `AgentMessage` containing
   `SYSTEM_PROMPT` (or make the provider contract require it and implement it in
   every provider), remove the redundant `systemPrompt` field if the message
   list is the source of truth, and add an assertion that the DeepSeek request
   body contains a `system` message.

2. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/SessionModel.kt:137-145` — the provider-neutral request carries the annotation worklist (`units`).**

   `AgentRequest` is the transport contract a later MCP endpoint would implement,
   but it exposes `List<SessionUnit>` in addition to messages, tools, prompt
   version, and timeout. The annotation worklist is session-domain state; a
   future MCP transport cannot supply it, and the fake provider currently uses it
   as a shortcut instead of consuming the tool/user surface. This keeps the
   abstraction provider-specific in exactly the place the design says it should
   be reusable.

   Suggested fix: drop `units` from `AgentRequest` and carry the batch metadata
   in the user message payload, or split a generic `AgentTransportRequest`
   (messages/tools/budgets) from the annotation-specific request. Update the
   fake/scripted providers accordingly.

3. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/SessionTools.kt:117-122` with `SuggestionService.kt:117-125` and `AnnotationSession.kt:117-119,182`; returned by `analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/suggestions/LiveServerSuggestions.kt:337-361` — the documented privacy gate is not applied to all session surfaces.**

   `lookup_type` returns the server's `typeLookup` map directly without passing
   through `sanitize`, so type names can leave the process unredacted even
   though `docs/ai-intents.md:47` and `docs/summary/annotations-and-ai.md` say
   every text result passes through `allowGlobs`/`redactPatterns` (and
   `allowGlobs` is in fact only a document-path gate in `discover`). Separately,
   `AnnotationSession` stores `AgentTurn.Message` content (truncated to 500
   chars) and exception/failure strings as session events, and
   `cint/suggest/log` returns those raw events to the client. That contradicts
   the design's “no provider/response logging by default” privacy
   rule, and `:CintAiLog` is specified to show tool/reuse/verdict events, not
   model prose.

   Suggested fix: run `lookup_type` values through the redaction path (or
   restrict the tool to non-source-derived facts), record only structured event
   metadata for turns/messages/errors, and add a test where a provider response
   contains a secret and `cint/suggest/log` never returns it. Clarify in the docs
   that `allowGlobs` gates the pass while `redactPatterns` transforms text
   results.

4. **`analysis-engine/hosts/server/src/main/kotlin/dev/cint/server/LiveServer.kt:172-183` with `LiveServerSuggestions.kt:92-93,265-285` — protocol 4 behavior is not gated on the client's protocol or an explicit capability opt-in.**

   `initialize` ignores the client's requested `protocol` and returns
   `LIVE_PROTOCOL_VERSION`; nothing records the negotiated/requested version per
   writer. `handleSuggest` therefore runs discovery whenever
   `discoveryEnabled` is true, and `cint/suggest/progress` is pushed to every
   caller, including a protocol-3 client (the test harness itself sends protocol
   3). The design says “Protocol 4 gates discovery and restriction features” and
   the ticket requires older clients to keep protocol-3 behavior, but the server
   has no such gate; capability flags are only advisory to the client.

   Suggested fix: capture the requested protocol per writer during
   `initialize`, and require protocol >= 4 (or an explicit discovery capability
   opt-in) before running discovery or emitting progress/log for that client.
   Add a server test that a protocol-3 client gets the legacy enrichment path
   and receives no `cint/suggest/progress`.

5. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/syntax/ExpressionUnits.kt:9-29` with `Syntax.kt:27-30,209-211` and `analysis-engine/languages/treesitter/src/main/resources/syntax/*.yaml` — the new expression-profile hooks are never populated, so all languages fall back to one heuristic.**

   `ExpressionNodes` prefers `profile.kinds(SyntaxGroups.EXPRESSION)` and uses
   `EXPRESSION_EXCLUDED`, but none of the five syntax YAML files defines either
   group. Every language therefore uses the same “named node minus declaration
   kinds” fallback, and only Kotlin has a test. The design and docs describe the
   enumeration as derived from the language profile; the profile-specific part
   is currently just the exclusion vocabulary.

   Suggested fix: add explicit `expression`/`expressionExcluded` entries to the
   Kotlin, JavaScript, TypeScript, Vue, and C# profiles and cover at least one
   non-Kotlin language with an assertion-bearing test, or remove the unused
   groups and document the generic named-node heuristic. This is the
   core/language-profile boundary the ticket was meant to establish.

6. **`analysis-engine/languages/treesitter/src/test/kotlin/dev/cint/treesitter/TempKindsTest.kt:1-70` — a debug dump is included as a new test.**

   The test writes every node kind to `/tmp/kinds.txt` and asserts nothing. It
   is not a regression test, writes outside the workspace, and will run as part
   of the treesitter suite. It also appears to be the only four-language
   investigation behind the fallback expression heuristic.

   Suggested fix: delete it before commit and replace it with the real
   per-language expression-unit assertions from finding 5.

7. **`analysis-engine/core/suggest/src/main/kotlin/dev/cint/suggest/session/SessionModel.kt:61-72` with `SessionTools.kt:166-192` and `AnnotationSession.kt:199-202` — remove-verdict parsing is speculative 059 work in a 058 ticket.**

   `SessionRemove`, `SessionVerdict.removes`, the JSON `remove` parser, and the
   “remove operations ignored in this pass” event implement part of the 059
   operation model even though the tool schema only advertises `none`/`add` and
   the design defers removals. This creates a half-contract: a model can send
   remove operations that are accepted as parsed but silently discarded, and the
   protocol/docs do not describe that behavior.

   Suggested fix: remove the remove types/parsing until 059 introduces the
   grouped operation model, or, if the forward compatibility is intentional,
   include it in the ticket scope and docs and test the ignored-remove path
   explicitly.

8. **`docs/architecture.md:253-281,325-345` with `docs/ai-intents.md:7`, `docs/engine.md:627`, and `docs/tasks/README.md:72` — the primary architecture doc and several secondary docs do not describe what was implemented.**

   `docs/architecture.md` is untouched and still says the AI boundary is
   “bounded excerpt plus candidate metadata” and that `suggest` only owns the
   provider SPI/transport; it does not mention expression units, the session/tool
   loop, the new budget layer, protocol 4, or discovery capability flags. The
   module table likewise omits the session. `docs/ai-intents.md` still calls the
   session “proposed,” `docs/engine.md` and `docs/ai-intents.md` describe
   `cint/suggest/log` as backing a `:CintAiLog` command that the nvim renderer
   does not implement (061), and the task index still marks 058 `todo`.

   Suggested fix: update the architecture AI boundary and module table with the
   worklist/session/tool-loop/budget/protocol shape, replace “proposed” with the
   implemented/partially implemented status, mark `:CintAiLog` as planned or
   implement it under 061, and update the ticket index when the status changes.

### Concurs / dissents

- Concurs: expression enumeration belongs in core and no rulepack was touched to
  enumerate expressions; session output remains candidate-only; no scheduler,
  composition, or annotation-store participation; `suggest` still depends only
  on `api`/`core`; the capability descriptor remains secret-free; the add-only
  `CandidateEmit` shape and protocol response are additive for protocol-4
  clients.
- Dissents: findings 1-8.

### Residuals (not filed)

- Untracked `.cint/*` files (including the `.bak-*` backups) are local project
  state, not part of this ticket; keep them unstaged and out of the commit.

## Round 2

Verdict: All eight Round 1 findings are addressed in substance. The system
prompt is now transmitted, the provider request is transport-neutral, redaction
and structured session logging are implemented with tests, protocol-4 behavior
is gated per client protocol with a protocol-3 legacy test, the expression
profiles are populated with non-Kotlin coverage, the debug test and speculative
remove parsing are gone, and the architecture/secondary docs describe the
implemented shape with `:CintAiLog` marked for 061. One new issue remains in
the expression enumeration: the profile fix still prunes real expressions and
keeps a hardcoded language-specific node-kind set in core. Not ready to close
because of new finding 9.

### Per-finding verification

1. Concur. `AnnotationSession.kt:65-72` prepends the system instructions as an
   `AgentMessage("system", ...)`; `AgentRequest` no longer has a
   `systemPrompt` field; `DeepSeekIntentSuggestionProvider` sends
   `request.messages` unchanged. `AnnotationSessionTest.systemPromptIsSentAsASystemMessage`
   and `DeepSeekSessionProviderTest` assert the system role/content on the wire.
2. Concur. `SessionModel.kt:130-136` no longer carries `units`;
   `FakeIntentSuggestionProvider.complete` derives unit ids from the user
   payload. The provider interface is now messages/tools/budgets only.
3. Concur. `SessionTools.kt:125-130` sanitizes every `lookup_type` map value,
   and `AnnotationSession.kt:106-123,187-212` records structured event strings
   instead of model prose or exception messages. The new
   `providerMessagesAndFailuresDoNotStoreRawProseInEvents`,
   `lookupTypeResultsAreSanitizedBeforeReachingTheProvider`, and
   `responseProseIsAbsentFromTheSessionLog` tests cover the three paths.
4. Concur. `LiveServer.kt:174-187` records the requested protocol per writer;
   `suggestionCapability(clientProtocol)` and `handleSuggest` require
   `clientProtocol >= 4`; `handleSuggestLog` returns an error below 4.
   `LiveSuggestionDiscoveryTest.protocolThreeKeepsLegacyEnrichmentAndGetsNoDiscoveryProgressOrLog`
   proves the protocol-3 path uses legacy enrichment and emits no progress.
5. Concur for the explicit groups and coverage: all five syntax profiles now
   define `expression` (Kotlin, JavaScript, TypeScript, C#, Vue), and
   `ExpressionUnitsTest` exercises every language. The remaining issue is new
   finding 9, not the original missing-group problem.
6. Concur. `TempKindsTest.kt` is absent from the worktree; the real
   per-language assertions replaced it.
7. Concur. `SessionRemove`, `SessionVerdict.removes`, and the `remove` parser
   are gone (`SessionModel.kt:61-65`, `SessionTools.kt:165-188`); the parser
   accepts only `none` or add operations, and invalid/empty verdicts stay
   uncovered.
8. Concur. `docs/architecture.md:253-301,345-366`, `docs/engine.md`,
   `docs/ai-intents.md`, `docs/summary/*`, and `docs/tasks/README.md` now
   describe the session, protocol 4, the protocol gate, config, and the
   discovery/session budgets; `:CintAiLog` is marked as planned for 061. One
   residual documentation gap is noted below.

### New findings

9. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/syntax/ExpressionUnits.kt:9-32,70-84,101-104` — the profile fix still puts language-specific type kinds in core and drops expressions inside parameters.**

   `skippedSubtrees` lists `kinds.parameterKinds` and
   `kinds.parameterListKinds`, and `expressionUnits` returns immediately for a
   skipped subtree. Default parameter-value expressions (for example
   `fun f(x: String = Regex("x").find(text))`) are therefore never enumerated,
   contradicting the “every expression in the document” worklist. The same list
   contains a hardcoded `kindNames` set (`type_annotation`, `predefined_type`,
   `generic_type`, `function_type`, ...) even though the new
   `SyntaxGroups.EXPRESSION_EXCLUDED` profile group exists and is unpopulated;
   core is carrying grammar-specific node names instead of the profiles.

   Suggested fix: move the type-node names into each profile’s
   `expressionExcluded` group and skip only declaration names/types within a
   parameter (as `isDeclarationName` already does for property/variable names),
   not the whole parameter subtree. Add a test with a default parameter-value
   expression and an annotation argument that must appear in the worklist.

### Concurs / dissents

- Concurs: the Round 1 boundary direction still holds; AI remains post-analysis
  and candidate-only, `suggest` still depends only on `api`/`core`, the server
  owns worklist construction and publication, and protocol-4 capability gating
  is now enforced server-side.
- Dissents: new finding 9. Round 1 findings 1-8 are resolved.
- Residuals (not filed): `nvim-renderer/README.md:417` still lists only
  `enabled`, `trigger`, `provider`, `model`, and `knownIntents` in the
  `initialize.suggestions` descriptor, omitting `discovery`, `restrictions`,
  `cache`, and `maxUnitsPerRequest`; update it when the protocol section is next
  touched. Untracked `.cint/*` state remains local and must stay out of the
  commit.

## Round 3

Verdict: Finding 9 is resolved. Expression enumeration is fully profile-driven:
core carries no grammar-specific type/parameter node names, and the worklist
descends into both default parameter-value expressions and annotation
arguments. The round-3 delta is confined to that enumeration, server-side
cancellation bookkeeping, and tests; it introduces no boundary or contract
regression. Zero open architecture findings from this dimension.

### Per-finding verification

9. **Concur — addressed.**
   - `ExpressionUnits.kt:23-26` now prunes only comments, imports, and
     `expressionExcluded`; `parameterKinds`, `parameterListKinds`, and
     `typeReferenceKinds` are no longer skipped subtrees, and the hardcoded
     type-kind set is gone (no grammar-specific node strings remain anywhere in
     `core/.../syntax`). At HEAD the parameter groups were only in `excluded`,
     so the substantive delta is annotation/modifier traversal; the remaining
     finding-9 conditions are nevertheless met.
   - Type-node names live in each profile's `expressionExcluded`
     (`kotlin.yaml:6`, `javascript.yaml:6`, `typescript.yaml:6`, `csharp.yaml:6`,
     `vue.yaml:5`); `ExpressionUnits` references them only through
     `ProfileSyntaxKinds.expressionExcludedKinds`.
   - Parameter handling (`ExpressionUnits.kt:59-63`) skips only children before
     the `=` that are identifier/type/excluded nodes and returns early for
     children after `=`, so default-value expressions are enumerated.
     Annotations and modifiers are no longer whole-subtree skips, and
     `isDeclarationName` (`:51-54`) prunes only annotation names/types, leaving
     annotation arguments as units.
   - Tests: `kotlinDefaultParameterExpressionsAreUnitsWithoutNamesOrTypes` and
     `typescriptDefaultParameterExpressionsAreUnitsWithoutNamesOrTypes` cover
     default values; new `kotlinAnnotationArgumentsAreUnitsWithoutAnnotationNames`
     covers class-, function-, and parameter-level annotation arguments (including
     a default `= "a"` beside a parameter annotation); new
     `profilesRouteTypeNodesThroughExpressionExcluded` asserts the
     `typeReference ⊆ expressionExcluded` invariant for all five profiles.
   - Docs already state "every expression node in the document, including
     leaves" (`ai-annotation-session.md:66`, `architecture.md:264`,
     `ai-intents.md:32`, `engine.md:305`), so annotation-argument and
     default-value units require no doc change.

### New findings

None. The server delta (removing `inFlightFutures`, cancelling via
`pass.cancelled` + `providerFutures`, identity-safe `remove(key, pass)`) stays
inside the server lifecycle seam and does not change the provider contract,
candidate-only AI boundary, or core/profile dependency direction.

### Concurs / dissents

- Concurs: Round 1 findings 1-8 and Round 2 finding 9 remain resolved. Expression
  units are a core syntax fact with all grammar vocabulary in profiles; AI output
  is still post-analysis `CandidateEmit`; `suggest` still depends only on
  `api`/`core`; the server owns pass registration, cancellation, worklist
  construction, and publication.
- Dissents: none.

### Residuals (not filed)

- Annotation-name exclusion is necessarily grammar-shaped: Kotlin annotation
  names are `user_type` and are skipped, while call-style decorators
  (JS/TS `@Component(...)`) expose the callee identifier as a leaf unit. This is
  consistent with "every expression including leaves"; exclude callees only if
  the design later asks for it, and that would need field-aware handling rather
  than a parent-kind check.
- `profilesRouteTypeNodesThroughExpressionExcluded` guards a real invariant but
  iterates a hardcoded five-language list and is vacuous for Vue (empty
  `typeReference`); a newly added profile is only covered if added to that list.
- Untracked `.cint/*` state remains local and must stay out of the commit.
