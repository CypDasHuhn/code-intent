package dev.cint.pipeline

import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.PropertyFact
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.packs.BundledRulePackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledRuleEvaluatorsTest {
    @Test
    fun registryCoversEveryDefaultEnabledBundledRule() {
        val source = BundledRulePackSource()
        val registry = BundledRuleEvaluators.registry()
        val expected = mutableSetOf<String>()
        for (manifest in source.manifests().getOrThrow().filter { it.defaultEnabled }) {
            for (spec in source.playbook(manifest.id).getOrThrow().rules) {
                expected += spec.id
                val evaluator = assertNotNull(registry.resolve(spec), "missing evaluator ${spec.id}")
                assertEquals(spec, evaluator.spec)
            }
        }
        assertTrue(expected.containsAll(EXPECTED_RULE_IDS))
    }

    @Test
    fun bundledEvaluatorsProduceIntentsThroughTheRealPipeline() {
        val tree = tree()
        val source =
            IndexedSource(
                input = SourceInput.InMemory("", "kotlin", 1, "Sample.kt"),
                facts =
                    FactBatch(
                        syntaxTree = tree,
                        calls =
                            listOf(
                                CallFact(
                                    nodeId = "call",
                                    siteId = "call",
                                    callee = "findUser",
                                    receiverId = null,
                                    path = "repository.findUser",
                                ),
                            ),
                        declarations =
                            listOf(
                                dev.cint.contracts.facts.DeclarationFact(
                                    nodeId = "property",
                                    kind = dev.cint.contracts.facts.DeclarationKind.PROPERTY,
                                    name = "count",
                                    nameNodeId = null,
                                    annotations = listOf("Bookkeeping"),
                                ),
                            ),
                        properties =
                            listOf(
                                PropertyFact(
                                    nodeId = "property",
                                    name = "count",
                                    nameNodeId = null,
                                    initializerId = null,
                                ),
                            ),
                    ),
                sourceEpoch = 1,
                completeness = Completeness.COMPLETE,
                contentHash = "hash",
            )
        val pipeline =
            DefaultIntentPipeline(
                BundledRulePackSource(),
                BundledRuleEvaluators.registry(),
            )

        val result =
            pipeline
                .analyze(
                    AnalysisRequest(targetPaths = listOf("Sample.kt"), language = "kotlin"),
                    source,
                    KnowledgeSnapshot(
                        functionAnnotations = emptyList(),
                        spanOverrides = emptyList(),
                        patternHypotheses = emptyList(),
                        timestamp = 1,
                    ),
                )
                .getOrThrow()

        val intents = result.annotations.flatMap { it.intents }.toSet()
        assertTrue(Intents.BOOKKEEPING in intents, "expected BOOKKEEPING in $intents")
        assertTrue(
            result.candidates.any { Intents.LOOKUP in it.intents },
            "expected a LOOKUP candidate in ${result.candidates}",
        )
        assertTrue(
            result.candidates.any { Intents.SEARCH in it.intents },
            "expected a SEARCH candidate in ${result.candidates}",
        )
        assertTrue(result.annotations.isNotEmpty())
    }

    private fun tree(): SyntaxTree {
        val root =
            SyntaxNode(
                id = "root",
                kind = "source_file",
                startByte = 0,
                endByte = 200,
                startLine = 0,
                startCol = 0,
                endLine = 10,
                endCol = 0,
                text = "",
                childIds = listOf("call", "property"),
            )
        val nodes =
            linkedMapOf(
                root.id to root,
                "call" to
                    SyntaxNode(
                        id = "call",
                        kind = "call_expression",
                        startByte = 10,
                        endByte = 40,
                        startLine = 1,
                        startCol = 4,
                        endLine = 1,
                        endCol = 34,
                        text = "repository.findUser()",
                        parentId = "root",
                    ),
                "property" to
                    SyntaxNode(
                        id = "property",
                        kind = "property_declaration",
                        startByte = 50,
                        endByte = 80,
                        startLine = 2,
                        startCol = 4,
                        endLine = 2,
                        endCol = 34,
                        text = "var count = 0",
                        parentId = "root",
                    ),
            )
        return SyntaxTree(
            rootId = root.id,
            nodes = nodes,
            language = "kotlin",
            version = "1",
            contentHash = "hash",
        )
    }

    private companion object {
        val EXPECTED_RULE_IDS =
            setOf(
                "logging:logging",
                "error-handling:error-handling",
                "bookkeeping:bookkeeping",
                "validation:validation",
                "data-access:search",
                "data-access:lookup",
                "normalization:normalization",
                "data-transformation:data-transformation",
                "mutation:data-modify",
                "mutation:local-value-modify",
                "service:delegation",
                "service:init",
                "return-value:return-value",
                "local-copy:local-copy",
            )
    }
}
