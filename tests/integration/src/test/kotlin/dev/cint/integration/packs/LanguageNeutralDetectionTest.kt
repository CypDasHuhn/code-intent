package dev.cint.integration.packs

import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.pipeline.DefaultIntentPipeline
import dev.cint.rulepacks.BundledPacks
import dev.cint.rulepacks.BundledRulePackSource
import dev.cint.rulepacks.DefaultRulePacks
import dev.cint.rulepacks.PackCatalog
import dev.cint.rulepacks.PackDefinition
import dev.cint.rulepacks.PackSeedDefault
import dev.cint.v2.languages.LanguageCatalog
import dev.cint.v2.syntax.treesitter.createSyntaxEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageNeutralDetectionTest {
    @Test
    fun aDataOnlyLanguageRunsExistingPacksThroughThePipeline() {
        val descriptor =
            LanguageDescriptor(
                id = "rust",
                name = "Rust",
                extensions = listOf("rs"),
                grammarName = "tree-sitter-rust",
                profile = "javascript",
            )
        val languages = LanguageCatalog.of(listOf(descriptor))
        assertEquals("javascript", languages.forLanguage("rust")?.profile)

        val logging = withLanguage(BundledPacks.all.first { it.manifest.id == "logging" }, Intents.LOGGING, "println")
        val dataAccess = withLanguage(BundledPacks.all.first { it.manifest.id == "data-access" }, Intents.LOOKUP, "find")
        val catalog = PackCatalog.of(listOf(logging, dataAccess))
        val source = BundledRulePackSource(catalog)

        val log = node("log", 10, 20, "println")
        val find = node("find", 30, 40, "find")
        val facts =
            FactBatch(
                syntaxTree = tree(listOf(log, find), "rust"),
                calls =
                    listOf(
                        CallFact(log.id, log.id, "println", null, "println", calleeTokens = listOf("println")),
                        CallFact(find.id, find.id, "find", null, "find", calleeTokens = listOf("find"), pathSegments = listOf("find")),
                    ),
            )
        val indexed =
            IndexedSource(
                input = SourceInput.InMemory("", "rust", 1, "Sample.rs"),
                facts = facts,
                sourceEpoch = 1,
                completeness = Completeness.COMPLETE,
                contentHash = "rust-hash",
            )
        val pipeline = DefaultIntentPipeline(source, DefaultRulePacks.registry(catalog))
        val result =
            pipeline
                .analyze(
                    AnalysisRequest(targetPaths = listOf("Sample.rs"), language = "rust"),
                    indexed,
                    KnowledgeSnapshot(
                        functionAnnotations = emptyList(),
                        spanOverrides = emptyList(),
                        patternHypotheses = emptyList(),
                        timestamp = 1,
                    ),
                ).getOrThrow()

        assertTrue(
            result.annotations.any {
                Intents.LOGGING in it.intents && it.startByte == log.startByte && it.endByte == log.endByte
            },
            "expected LOGGING annotation for the data-only language: ${result.annotations}",
        )
        assertTrue(
            result.candidates.any {
                Intents.LOOKUP in it.intents && it.startByte == find.startByte && it.endByte == find.endByte
            },
            "expected LOOKUP candidate for the data-only language: ${result.candidates}",
        )
        assertTrue(
            result.candidates.any {
                Intents.SEARCH in it.intents && it.startByte == find.startByte && it.endByte == find.endByte
            },
            "expected SEARCH candidate for the data-only language: ${result.candidates}",
        )
    }

    @Test
    fun returnLiteralBucketsAndDescendingLoopWritesArePreserved() {
        val text =
            """
            fun longValue(): Long { return 42L }
            fun hexValue(): Int { return 0xFF }
            fun floatValue(): Float { return 3.14f }
            fun loopValue(): Int {
                var i = 3
                while (i-- > 0) { }
                return i
            }
            """.trimIndent()
        val engine = createSyntaxEngine("kotlin")
        val tree = engine.parse(text.encodeToByteArray(), null).getOrThrow()
        val facts = engine.facts(tree, emptyList()).getOrThrow()
        val indexed =
            IndexedSource(
                input = SourceInput.InMemory(text, "kotlin", 1, "Regression.kt"),
                facts = facts,
                sourceEpoch = 1,
                completeness = Completeness.COMPLETE,
                contentHash = "regression-hash",
            )
        val pipeline = DefaultIntentPipeline(BundledRulePackSource(), DefaultRulePacks.registry())
        val result =
            pipeline
                .analyze(
                    AnalysisRequest(targetPaths = listOf("Regression.kt"), language = "kotlin"),
                    indexed,
                    KnowledgeSnapshot(
                        functionAnnotations = emptyList(),
                        spanOverrides = emptyList(),
                        patternHypotheses = emptyList(),
                        timestamp = 1,
                    ),
                ).getOrThrow()

        for (literal in listOf("42L", "0xFF")) {
            val start = text.indexOf(literal)
            assertTrue(
                result.annotations.any {
                    Intents.RETURN_VALUE in it.intents && start >= it.startByte && start < it.endByte
                },
                "expected RETURN_VALUE for $literal: ${result.annotations}",
            )
        }
        val float = text.indexOf("3.14f")
        assertTrue(
            result.annotations.none {
                Intents.RETURN_VALUE in it.intents && float >= it.startByte && float < it.endByte
            },
            "expected 3.14f to be suppressed as a literal: ${result.annotations}",
        )
        val decrement = text.indexOf("i--")
        assertTrue(
            result.annotations.any {
                Intents.LOCAL_VALUE_MODIFY in it.intents &&
                    decrement >= it.startByte &&
                    decrement < it.endByte
            },
            "expected LOCAL_VALUE_MODIFY for descending loop write: ${result.annotations}",
        )
    }

    private fun withLanguage(
        definition: PackDefinition,
        intent: String,
        symbol: String,
    ): PackDefinition =
        definition.copy(
            manifest = definition.manifest.copy(languages = (definition.manifest.languages + "rust").sorted()),
            seeds =
                definition.seeds +
                    PackSeedDefault(
                        selector = FunctionSelector(qualifiedName = symbol),
                        intents = setOf(intent),
                        languages = setOf("rust"),
                        confidence = Confidence.MEDIUM,
                    ),
        )

    private fun node(
        id: String,
        start: Int,
        end: Int,
        text: String,
    ): SyntaxNode =
        SyntaxNode(
            id = id,
            kind = "call_expression",
            startByte = start,
            endByte = end,
            startLine = 0,
            startCol = start,
            endLine = 0,
            endCol = end,
            text = text,
        )

    private fun tree(
        nodes: List<SyntaxNode>,
        language: String,
    ): SyntaxTree {
        val end = nodes.maxOfOrNull { it.endByte } ?: 0
        val root =
            SyntaxNode(
                id = "root",
                kind = "source_file",
                startByte = 0,
                endByte = end + 1,
                startLine = 0,
                startCol = 0,
                endLine = 0,
                endCol = end + 1,
                text = "",
                childIds = nodes.map { it.id },
            )
        return SyntaxTree(
            rootId = root.id,
            nodes = (nodes + root).associateBy { it.id },
            language = language,
            version = "1",
            contentHash = "tree-hash",
        )
    }

    @Test
    fun suffixedCSharpNumericLiteralsAreSuppressed() {
        val text =
            """
            class Sample {
                double D() { return 1D; }
                float F() { return 1F; }
            }
            """.trimIndent()
        val result = analyze("csharp", text, "Suffixes.cs")
        for (literal in listOf("1D", "1F")) {
            val start = text.indexOf(literal)
            assertTrue(
                result.annotations.none {
                    Intents.RETURN_VALUE in it.intents && start >= it.startByte && start < it.endByte
                },
                "expected $literal to be suppressed as a literal: ${result.annotations}",
            )
        }
    }

    @Test
    fun numericLiteralFormsFollowTheReturnValueBuckets() {
        val text =
            """
            function exponent() { return 1e3; }
            function positive() { return +1; }
            function fraction() { return .5; }
            """.trimIndent()
        val result = analyze("javascript", text, "Numeric.js")
        for (literal in listOf("1e3", "+1", ".5")) {
            val start = text.indexOf(literal)
            assertTrue(
                result.annotations.none {
                    Intents.RETURN_VALUE in it.intents && start >= it.startByte && start < it.endByte
                },
                "expected $literal to be suppressed as a literal: ${result.annotations}",
            )
        }
    }

    @Test
    fun guardContainsAndTextualPrefixMatchingArePreserved() {
        val text =
            """
            fun guard(loggingEnabled: Boolean): Boolean {
                val isLoggingEnabled = loggingEnabled
                if (isLoggingEnabled) {
                    println("x")
                }
                return isLoggingEnabled
            }

            fun formattedCandidate() {
                formatted()
            }
            """.trimIndent()
        val result = analyze("kotlin", text, "Matching.kt")
        val condition = text.indexOf("if (isLoggingEnabled)") + "if (".length
        assertTrue(
            result.annotations.any {
                Intents.LOGGING in it.intents && condition >= it.startByte && condition < it.endByte
            },
            "expected LOGGING for isLoggingEnabled guard: ${result.annotations}",
        )
        val formatted = text.indexOf("formatted()")
        assertTrue(
            result.candidates.any {
                Intents.NORMALIZATION in it.intents && formatted >= it.startByte && formatted < it.endByte
            },
            "expected NORMALIZATION candidate for formatted: ${result.candidates}",
        )
    }

    private fun analyze(
        language: String,
        text: String,
        path: String,
    ): dev.cint.contracts.data.AnalysisResult {
        val engine = createSyntaxEngine(language)
        val tree = engine.parse(text.encodeToByteArray(), null).getOrThrow()
        val facts = engine.facts(tree, emptyList()).getOrThrow()
        val indexed =
            IndexedSource(
                input = SourceInput.InMemory(text, language, 1, path),
                facts = facts,
                sourceEpoch = 1,
                completeness = Completeness.COMPLETE,
                contentHash = "$language-hash",
            )
        val pipeline = DefaultIntentPipeline(BundledRulePackSource(), DefaultRulePacks.registry())
        return pipeline
            .analyze(
                AnalysisRequest(targetPaths = listOf(path), language = language),
                indexed,
                KnowledgeSnapshot(
                    functionAnnotations = emptyList(),
                    spanOverrides = emptyList(),
                    patternHypotheses = emptyList(),
                    timestamp = 1,
                ),
            ).getOrThrow()
    }
}
