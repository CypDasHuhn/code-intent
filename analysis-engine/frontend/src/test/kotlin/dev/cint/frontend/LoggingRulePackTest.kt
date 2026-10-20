package dev.cint.frontend

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.core.config.loadIntentConfig
import dev.cint.core.engine.AnalysisEngine
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.NullOracle
import dev.cint.core.oracle.SymbolFact
import dev.cint.core.oracle.TypeFact
import dev.cint.core.oracle.TypeOracle
import dev.cint.core.rules.IntentBlocks
import dev.cint.core.scheduler.RuleEngine
import dev.cint.rulepacks.LoggingRulePack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class CountingOracle : TypeOracle {
    var calls = 0

    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? {
        calls++
        return null
    }

    override fun definitionAt(
        file: SourceFile,
        byteOffset: Int,
    ): SymbolFact? = null
}

private class FixedOracle(
    private val fact: TypeFact?,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = fact

    override fun definitionAt(
        file: SourceFile,
        byteOffset: Int,
    ): SymbolFact? = null
}

private fun languageOf(path: Path): String =
    when (path.extension.lowercase()) {
        "kt" -> "kotlin"
        "js" -> "javascript"
        "ts" -> "typescript"
        "cs" -> "csharp"
        "vue" -> "vue"
        else -> error("unsupported fixture: $path")
    }

private fun legacyAnnotations(
    file: SourceFile,
    language: String,
    oracle: TypeOracle = NullOracle,
): List<Annotation> {
    val config = loadIntentConfig(null, language)
    val frontend = Frontends.require(language)
    val tree = frontend.parse(file)
    return AnalysisEngine(IntentBlocks.build(config)).analyze(file, tree, frontend.syntax(tree), oracle, config)
}

private fun newAnnotations(
    file: SourceFile,
    language: String,
    oracle: TypeOracle = NullOracle,
): List<Annotation> {
    val config = loadIntentConfig(null, language)
    val frontend = Frontends.require(language)
    val tree = frontend.parse(file)
    return RuleEngine().analyze(file, tree, frontend.syntax(tree), oracle, config, listOf(LoggingRulePack()))
}

private fun sourceFor(
    language: String,
    text: String,
): SourceFile {
    val extension =
        when (language) {
            "kotlin" -> "kt"
            "javascript" -> "js"
            "typescript" -> "ts"
            "csharp" -> "cs"
            else -> error("unsupported language: $language")
        }
    return SourceFile("Sample.$extension", text)
}

private fun newFor(
    language: String,
    text: String,
    oracle: TypeOracle = NullOracle,
): List<Annotation> = newAnnotations(sourceFor(language, text), language, oracle)

private val oracleVariants: List<Pair<String, TypeOracle>> =
    listOf(
        "null" to NullOracle,
        "resolved-logger" to FixedOracle(TypeFact("org.slf4j.Logger", "lsp", 0.9)),
        "resolved-non-logger" to FixedOracle(TypeFact("Service", "lsp", 0.95)),
    )

private data class LoggingHit(
    val startByte: Int,
    val endByte: Int,
    val source: String,
    val confidence: Double,
    val evidence: String,
)

private fun loggingSpans(annotations: List<Annotation>): List<LoggingHit> =
    annotations
        .filter { it.role == Role.SOURCE_INTENT && "LOGGING" in it.intents }
        .map { LoggingHit(it.startByte, it.endByte, it.source, it.confidence, it.evidence) }
        .sortedWith(compareBy({ it.startByte }, { it.endByte }, { it.source }))

private fun fixtureRoot(): Path =
    generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("testing-grounds") }
        .first { Files.isDirectory(it) }

class LoggingRulePackTest {
    @Test
    fun packDeclaresOneLoggingRule() {
        val pack = LoggingRulePack()
        assertEquals("logging", pack.id)
        assertTrue(setOf("kotlin", "javascript", "typescript", "csharp").all { it in pack.languages })
        assertFalse("vue" in pack.languages)
        val rule = pack.rules().single()
        assertEquals("logging:logging", rule.id)
        assertEquals("LOGGING", rule.owns)
        assertTrue(rule.reads.isEmpty())
    }

    @Test
    fun matchesLegacyLoggingOverTestingGrounds() {
        val files =
            Files.walk(fixtureRoot()).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
        assertFalse(files.isEmpty(), "no testing-ground fixtures found")

        for (path in files) {
            val text = path.readText()
            val language = languageOf(path)
            if (language == "vue") {
                val host = SourceFile(path.fileName.toString(), text)
                val regions = VueFrontend.regions(host)
                assertFalse(regions.isEmpty(), "no script region in $path")
                for (region in regions) {
                    val regionFile = SourceFile(host.path, region.text)
                    for ((oracleName, oracle) in oracleVariants) {
                        assertEquals(
                            loggingSpans(legacyAnnotations(regionFile, region.language, oracle)),
                            loggingSpans(newAnnotations(regionFile, region.language, oracle)),
                            "$path (${region.language} region, $oracleName oracle)",
                        )
                    }
                }
            } else {
                val file = SourceFile(path.fileName.toString(), text)
                for ((oracleName, oracle) in oracleVariants) {
                    assertEquals(
                        loggingSpans(legacyAnnotations(file, language, oracle)),
                        loggingSpans(newAnnotations(file, language, oracle)),
                        "$path ($oracleName oracle)",
                    )
                }
            }
        }
    }

    @Test
    fun printUsesLanguageSpecificCallsAndQualifiers() {
        val kotlin =
            """
            fun f() {
                println("plain")
                System.out.println("qualified")
                console.log("not kotlin logging")
                error("not logging")
            }
            """.trimIndent()
        val kotlinSpans = loggingSpans(newFor("kotlin", kotlin))
        assertEquals(2, kotlinSpans.size)

        val javascript =
            """
            function f() {
                console.log("console")
                console.error("console error")
                println("not javascript logging")
            }
            """.trimIndent()
        assertEquals(2, loggingSpans(newFor("javascript", javascript)).size)

        val csharp =
            """
            class C {
                void F() {
                    Console.WriteLine("console");
                    Debug.WriteLine("debug");
                    service.LogInformation("not print");
                }
            }
            """.trimIndent()
        assertEquals(2, loggingSpans(newFor("csharp", csharp)).size)
    }

    @Test
    fun factoryLoggerSymbolSkipsOracle() {
        val text =
            """
            private val logger = LoggerFactory.getLogger("x")
            fun f() {
                logger.info("x")
            }
            """.trimIndent()
        val file = sourceFor("kotlin", text)
        val legacyOracle = CountingOracle()
        val newOracle = CountingOracle()
        val legacy = loggingSpans(legacyAnnotations(file, "kotlin", legacyOracle))
        val current = loggingSpans(newFor("kotlin", text, newOracle))
        assertEquals(legacy, current)
        assertEquals(1, current.size)
        assertEquals(0, legacyOracle.calls)
        assertEquals(0, newOracle.calls)
    }

    @Test
    fun nonCallInitializerFactoryCallDoesNotBypassOracleVeto() {
        val text =
            """
            private val logger = if (true) LoggerFactory.getLogger("x") else null
            fun f() {
                logger.info("x")
            }
            """.trimIndent()
        val oracle = FixedOracle(TypeFact("Service", "lsp", 0.95))
        val file = sourceFor("kotlin", text)
        assertTrue(loggingSpans(legacyAnnotations(file, "kotlin", oracle)).isEmpty())
        assertTrue(loggingSpans(newFor("kotlin", text, oracle)).isEmpty())
    }

    @Test
    fun ambiguousReceiverConsultsOracleAndFallsBackWhenUnresolved() {
        val text =
            """
            fun f() {
                logger.info("x")
            }
            """.trimIndent()
        val file = sourceFor("kotlin", text)
        val legacyOracle = CountingOracle()
        val newOracle = CountingOracle()
        val legacy = loggingSpans(legacyAnnotations(file, "kotlin", legacyOracle))
        val current = loggingSpans(newFor("kotlin", text, newOracle))
        assertEquals(legacy, current)
        assertEquals(1, current.size)
        assertEquals(1, legacyOracle.calls)
        assertEquals(1, newOracle.calls)
    }

    @Test
    fun resolvedNonLoggerTypeVetoesReceiverHeuristic() {
        val text =
            """
            private val logger = Service()
            fun f() {
                logger.warn("x")
            }
            """.trimIndent()
        val oracle = FixedOracle(TypeFact("Service", "lsp", 0.95))
        val file = sourceFor("kotlin", text)
        assertTrue(loggingSpans(legacyAnnotations(file, "kotlin", oracle)).isEmpty())
        assertTrue(loggingSpans(newFor("kotlin", text, oracle)).isEmpty())
    }

    @Test
    fun resolvedLoggerTypeMatchesThroughOracle() {
        val text =
            """
            fun f() {
                thing.info("x")
            }
            """.trimIndent()
        val oracle = FixedOracle(TypeFact("org.slf4j.Logger", "lsp", 0.9))
        val file = sourceFor("kotlin", text)
        val legacy = loggingSpans(legacyAnnotations(file, "kotlin", oracle))
        val annotations = newFor("kotlin", text, oracle)
        assertEquals(legacy, loggingSpans(annotations))
        val annotation = annotations.first { it.role == Role.SOURCE_INTENT && "LOGGING" in it.intents }
        assertEquals("lsp", annotation.source)
        assertEquals(0.9, annotation.confidence)
    }

    @Test
    fun importGatedReceiverUsesOracle() {
        val text =
            """
            import org.slf4j.LoggerFactory
            fun f() {
                fake.info("x")
            }
            """.trimIndent()
        val file = sourceFor("kotlin", text)
        val fallback = loggingSpans(newFor("kotlin", text, NullOracle))
        assertEquals(loggingSpans(legacyAnnotations(file, "kotlin", NullOracle)), fallback)
        assertEquals(1, fallback.size)

        val vetoOracle = FixedOracle(TypeFact("FakeLogger", "lsp", 1.0))
        val vetoed = loggingSpans(newFor("kotlin", text, vetoOracle))
        assertEquals(loggingSpans(legacyAnnotations(file, "kotlin", vetoOracle)), vetoed)
        assertTrue(vetoed.isEmpty())
    }

    @Test
    fun loggingStateTagsConfiguredConditionNames() {
        val text =
            """
            fun f(loggingEnabled: Boolean) {
                if (loggingEnabled) {
                    work()
                }
            }
            fun work() {}
            """.trimIndent()
        val annotations = newFor("kotlin", text)
        assertTrue(
            annotations.any {
                it.role == Role.SOURCE_INTENT && "LOGGING" in it.intents && it.evidence == "loggingEnabled"
            },
        )
    }

    @Test
    fun loggingStateTagsLoopConditions() {
        val text =
            """
            function f(loggingEnabled) {
                while (loggingEnabled) {
                    work();
                }
            }
            """.trimIndent()
        assertTrue(
            newFor("javascript", text).any {
                it.role == Role.SOURCE_INTENT && "LOGGING" in it.intents && it.evidence == "loggingEnabled"
            },
        )
    }

    @Test
    fun callSpanIncludesTrailingLambda() {
        val text =
            """
            private val log = LoggerFactory.getLogger("x")
            fun f() {
                log.info("x") {
                    "detail"
                }
            }
            """.trimIndent()
        val annotation =
            newFor("kotlin", text).first {
                it.role == Role.SOURCE_INTENT && "LOGGING" in it.intents
            }
        assertTrue(annotation.evidence.contains("detail"))
        assertEquals(2, annotation.span.endLine - annotation.span.startLine)
    }
}
