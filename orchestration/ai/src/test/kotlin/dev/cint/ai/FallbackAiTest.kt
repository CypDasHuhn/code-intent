package dev.cint.ai

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.ports.FallbackAiRequest
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FallbackAiTest {
    private val now = Instant.parse("2024-05-01T00:00:00Z")
    private val clock: () -> Instant = { now }

    @Test
    fun proposesFunctionAnnotationsAndSpanOverridesThroughKnowledgeChanges() {
        val provider =
            ScriptedProvider {
                ResultFactory.success(
                    listOf(
                        AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.HIGH, "reads a user"),
                        AiProposal.Span("findUser", setOf(Intents.LOGGING), Confidence.ESTABLISHED, "this call logs"),
                    ),
                )
            }
        val ai =
            DefaultFallbackAi(
                provider,
                budget = FallbackAiBudget(maxConfidence = 0.7),
                clock = clock,
            )

        val batch = ai.propose(request(dirty = true)).getOrThrow() as KnowledgeChange.Batch

        val annotation = assertIs<KnowledgeChange.SetFunctionAnnotation>(batch.changes[0]).annotation
        assertEquals("findUser", annotation.selector.qualifiedName)
        assertEquals(setOf(Intents.LOOKUP), annotation.intents)
        assertEquals(Provenance.AI_FALLBACK, annotation.provenance)
        assertEquals(Status.SUGGESTED, annotation.status)
        assertEquals(0.7, annotation.confidence.value)
        assertEquals(now, annotation.createdAt)

        val override = assertIs<KnowledgeChange.SetSpanOverride>(batch.changes[1]).override
        assertEquals("Sample.kt", override.selector.path)
        assertEquals(HASH, override.selector.contentHash)
        assertEquals(now, override.createdAt)
        assertEquals("this call logs", override.rationale)
    }

    @Test
    fun skipsCleanInitializedKnowledgeAndRunsForUninitializedOrDirtyInputs() {
        val provider =
            ScriptedProvider {
                ResultFactory.success(listOf(AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.MEDIUM)))
            }
        val ai =
            DefaultFallbackAi(
                provider,
                budget = FallbackAiBudget(maxCalls = 10),
                clock = clock,
            )

        ai.propose(request(dirty = false, materialized = true)).getOrThrow()
        assertEquals(0, provider.calls)

        ai.propose(request(dirty = true, materialized = true)).getOrThrow()
        assertEquals(1, provider.calls)

        ai.propose(request(dirty = false, materialized = false)).getOrThrow()
        assertEquals(2, provider.calls)
    }

    @Test
    fun providerFailureReturnsFailureWithoutProposals() {
        val ai =
            DefaultFallbackAi(
                ScriptedProvider {
                    ResultFactory.failure(ContractError.DependencyUnavailable("fallback-ai", "offline"))
                },
                clock = clock,
            )

        val result = ai.propose(request(dirty = true))

        assertTrue(result.isFailure)
        assertEquals(
            ContractError.DependencyUnavailable("fallback-ai", "offline"),
            result.errorOrNull,
        )
    }

    @Test
    fun budgetsCapCallsProposalsSourceAndConfidence() {
        val proposals =
            listOf(
                AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.MAX, "function"),
                AiProposal.Span("findUser", setOf(Intents.LOGGING), Confidence.MAX, "span"),
                AiProposal.Function("findUser", setOf(Intents.SEARCH), Confidence.MAX, "extra"),
            )
        val provider = ScriptedProvider { ResultFactory.success(proposals) }
        val ai =
            DefaultFallbackAi(
                provider,
                budget =
                    FallbackAiBudget(
                        maxCalls = 1,
                        maxProposals = 2,
                        maxSourceChars = 10,
                        maxFunctionChars = 4,
                        maxConfidence = 0.6,
                    ),
                clock = clock,
            )

        val first = ai.propose(request(dirty = true)).getOrThrow() as KnowledgeChange.Batch
        assertEquals(2, first.changes.size)
        assertEquals(1, provider.calls)
        assertEquals(10, provider.lastRequest?.source?.length)
        assertEquals(
            4,
            provider.lastRequest
                ?.functions
                ?.single()
                ?.text
                ?.length,
        )
        val annotation = assertIs<KnowledgeChange.SetFunctionAnnotation>(first.changes.first()).annotation
        assertEquals(0.6, annotation.confidence.value)

        val second = ai.propose(request(dirty = true)).getOrThrow() as KnowledgeChange.Batch
        assertTrue(second.changes.isEmpty())
        assertEquals(1, provider.calls)
    }

    @Test
    fun cancellationStopsBeforeCallingTheProvider() {
        val provider =
            ScriptedProvider {
                ResultFactory.success(listOf(AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.HIGH)))
            }
        val ai = DefaultFallbackAi(provider, clock = clock)

        val result = ai.propose(request(dirty = true), CancellationToken.fromBoolean(true))

        assertTrue(result.isFailure)
        assertEquals(ContractError.Cancelled("operation"), result.errorOrNull)
        assertEquals(0, provider.calls)
    }

    @Test
    fun redactsPatternsAndRespectsPathAllowList() {
        val provider =
            ScriptedProvider {
                ResultFactory.success(listOf(AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.HIGH)))
            }
        val redaction =
            FallbackAiRedaction(
                allowedPathGlobs = listOf("Sample.kt"),
                redactPatterns = listOf("token=\\w+"),
            )
        val ai = DefaultFallbackAi(provider, redaction = redaction, clock = clock)

        ai.propose(request(dirty = true, text = "fun findUser() { token=abc123 }")).getOrThrow()

        val sent = provider.lastRequest
        assertFalse(sent!!.source.contains("abc123"))
        assertTrue(sent.source.contains("*".repeat("token=abc123".length)))
        assertFalse(
            sent.functions
                .single()
                .text
                .contains("abc123"),
        )

        val blocked =
            ScriptedProvider {
                ResultFactory.success(listOf(AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.HIGH)))
            }
        val restricted =
            DefaultFallbackAi(
                blocked,
                redaction = FallbackAiRedaction(allowedPathGlobs = listOf("Other.kt")),
                clock = clock,
            )
        restricted.propose(request(dirty = true)).getOrThrow()
        assertEquals(0, blocked.calls)
    }

    @Test
    fun doesNotOverwriteAcceptedKnowledge() {
        val accepted =
            dev.cint.contracts.knowledge.FunctionAnnotation.create(
                selector =
                    dev.cint.contracts.knowledge
                        .FunctionSelector(qualifiedName = "findUser"),
                intents = setOf(Intents.SEARCH),
                provenance = Provenance.USER,
                status = Status.ACCEPTED,
                createdAt = now,
            )
        val provider =
            ScriptedProvider {
                ResultFactory.success(listOf(AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.HIGH)))
            }
        val ai = DefaultFallbackAi(provider, clock = clock)

        val batch =
            ai
                .propose(
                    request(
                        dirty = true,
                        knowledge = snapshot(materialized = true, annotations = listOf(accepted)),
                    ),
                ).getOrThrow() as KnowledgeChange.Batch

        assertTrue(batch.changes.isEmpty())
    }

    @Test
    fun mergesSameSelectorProposalsInsteadOfLastWriteWins() {
        val provider =
            ScriptedProvider {
                ResultFactory.success(
                    listOf(
                        AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.LOW),
                        AiProposal.Function("findUser", setOf(Intents.SEARCH), Confidence.HIGH),
                    ),
                )
            }
        val ai = DefaultFallbackAi(provider, clock = clock)

        val batch = ai.propose(request(dirty = true)).getOrThrow() as KnowledgeChange.Batch
        val annotation = assertIs<KnowledgeChange.SetFunctionAnnotation>(batch.changes.single()).annotation

        assertEquals(setOf(Intents.LOOKUP, Intents.SEARCH), annotation.intents)
        assertEquals(Confidence.HIGH, annotation.confidence)
    }

    @Test
    fun budgetIsNotConsumedWhenThereAreNoCandidates() {
        val provider =
            ScriptedProvider {
                ResultFactory.success(listOf(AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.HIGH)))
            }
        val ai = DefaultFallbackAi(provider, budget = FallbackAiBudget(maxCalls = 1), clock = clock)

        ai.propose(request(dirty = true, source = source("fun findUser() {}", names = emptyList()))).getOrThrow()
        assertEquals(0, provider.calls)

        ai.propose(request(dirty = true)).getOrThrow()
        assertEquals(1, provider.calls)
    }

    @Test
    fun capsFunctionCountAndPromptChars() {
        val provider =
            ScriptedProvider {
                ResultFactory.success(listOf(AiProposal.Function("alpha", setOf(Intents.LOOKUP), Confidence.HIGH)))
            }
        val ai =
            DefaultFallbackAi(
                provider,
                budget = FallbackAiBudget(maxFunctions = 2, maxPromptChars = 160),
                clock = clock,
            )

        ai
            .propose(
                request(
                    dirty = true,
                    source = source("fun alpha() {} fun beta() {} fun gamma() {}", names = listOf("alpha", "beta", "gamma")),
                ),
            ).getOrThrow()

        assertEquals(listOf("alpha", "beta"), provider.lastRequest?.functions?.map { it.name })
    }

    @Test
    fun invalidRedactionPatternsFailAtConstruction() {
        val error =
            kotlin
                .runCatching {
                    FallbackAiRedaction(redactPatterns = listOf("["))
                }.exceptionOrNull()

        assertIs<IllegalArgumentException>(error)
    }

    @Test
    fun redactsBeforeTruncatingSoPartialSecretsCannotLeak() {
        val provider =
            ScriptedProvider {
                ResultFactory.success(listOf(AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.HIGH)))
            }
        val ai =
            DefaultFallbackAi(
                provider,
                budget = FallbackAiBudget(maxSourceChars = 100),
                redaction = FallbackAiRedaction(redactPatterns = listOf("token=\\w+")),
                clock = clock,
            )

        ai
            .propose(
                request(
                    dirty = true,
                    text = "x".repeat(90) + " token=abc123",
                    source = source("x".repeat(90) + " token=abc123"),
                ),
            ).getOrThrow()

        assertFalse(provider.lastRequest!!.source.contains("abc"))
    }

    @Test
    fun doesNotOverwriteAnotherWritersSuggestedKnowledge() {
        val suggested =
            dev.cint.contracts.knowledge.FunctionAnnotation.create(
                selector =
                    dev.cint.contracts.knowledge
                        .FunctionSelector(qualifiedName = "findUser"),
                intents = setOf(Intents.SEARCH),
                provenance = Provenance.USER,
                status = Status.SUGGESTED,
                createdAt = now,
            )
        val provider =
            ScriptedProvider {
                ResultFactory.success(listOf(AiProposal.Function("findUser", setOf(Intents.LOOKUP), Confidence.HIGH)))
            }
        val ai = DefaultFallbackAi(provider, clock = clock)

        val batch =
            ai
                .propose(
                    request(
                        dirty = true,
                        knowledge = snapshot(materialized = true, annotations = listOf(suggested)),
                    ),
                ).getOrThrow() as KnowledgeChange.Batch

        assertTrue(batch.changes.isEmpty())
    }

    private fun request(
        dirty: Boolean,
        materialized: Boolean = false,
        text: String = "fun findUser() { return lookup() }",
        knowledge: KnowledgeSnapshot = snapshot(materialized),
        source: IndexedSource = source(text),
    ): FallbackAiRequest =
        FallbackAiRequest(
            language = "kotlin",
            source = source,
            knowledge = knowledge,
            packDefaults = emptyList(),
            dirty = dirty,
        )

    private fun source(
        text: String,
        names: List<String> = listOf("findUser"),
    ): IndexedSource {
        val root =
            SyntaxNode(
                id = "root",
                kind = "file",
                startByte = 0,
                endByte = text.length,
                startLine = 0,
                startCol = 0,
                endLine = 0,
                endCol = text.length,
                text = text,
                childIds = names.indices.map { "fn-$it" },
            )
        val nodes = linkedMapOf(root.id to root)
        val functions =
            names.mapIndexed { index, name ->
                val node =
                    SyntaxNode(
                        id = "fn-$index",
                        kind = "function_declaration",
                        startByte = 0,
                        endByte = text.length.coerceAtMost(20),
                        startLine = 0,
                        startCol = 0,
                        endLine = 0,
                        endCol = text.length.coerceAtMost(20),
                        text = text,
                        parentId = root.id,
                    )
                nodes[node.id] = node
                FunctionFact(node.id, name, null, null)
            }
        val tree =
            SyntaxTree(
                rootId = root.id,
                nodes = nodes,
                language = "kotlin",
                version = "1",
                contentHash = HASH,
            )
        return IndexedSource(
            input = SourceInput.InMemory(text, "kotlin", 1, "Sample.kt"),
            facts = FactBatch(syntaxTree = tree, functions = functions),
            sourceEpoch = 1,
            completeness = dev.cint.contracts.data.Completeness.COMPLETE,
            contentHash = HASH,
        )
    }

    private fun snapshot(
        materialized: Boolean,
        annotations: List<dev.cint.contracts.knowledge.FunctionAnnotation> = emptyList(),
    ): KnowledgeSnapshot =
        KnowledgeSnapshot(
            functionAnnotations = annotations,
            spanOverrides = emptyList(),
            patternHypotheses = emptyList(),
            materialized = materialized,
            timestamp = 1,
        )

    private class ScriptedProvider(
        private val result: () -> ContractResult<List<AiProposal>>,
    ) : FallbackAiProvider {
        override val id: String = "scripted"
        var calls: Int = 0
            private set
        var lastRequest: AiProviderRequest? = null
            private set

        override fun propose(
            request: AiProviderRequest,
            cancellation: CancellationToken,
        ): ContractResult<List<AiProposal>> {
            val checked = cancellation.throwIfCancelled()
            if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
            calls += 1
            lastRequest = request
            return result()
        }
    }

    private companion object {
        const val HASH = "hash-1"
    }
}
