package dev.cint.rulepacks.tests.logging

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.SymbolFact
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.logging.LoggingRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
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
): List<Annotation> =
    analyzePack(
        parsePackInput(
            language,
            text,
            sourceFor(language, text).path,
            IntentConfig(language, emptyMap(), emptyList()),
        ),
        listOf(LoggingRulePack()),
        oracle,
    ).annotations

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
    fun configuredLoggingPackUsesPackOverrides() {
        val pack = LoggingRulePack().configure(mapOf("kotlin" to mapOf("printCalls" to listOf("audit"))))
        val file =
            sourceFor(
                "kotlin",
                """
                fun f() {
                    audit("logged")
                    println("not configured")
                }
                """.trimIndent(),
            )
        val annotations =
            analyzePack(
                parsePackInput("kotlin", file.text, file.path, IntentConfig("kotlin", emptyMap(), emptyList())),
                listOf(pack),
            ).annotations

        val logging = loggingSpans(annotations)
        assertEquals(1, logging.size)
        assertTrue(logging.single().evidence.contains("audit"))
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
        val newOracle = CountingOracle()
        val current = loggingSpans(newFor("kotlin", text, newOracle))
        assertEquals(1, current.size)
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
        val newOracle = CountingOracle()
        val current = loggingSpans(newFor("kotlin", text, newOracle))
        assertEquals(1, current.size)
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
        val annotations = newFor("kotlin", text, oracle)
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
        val fallback = loggingSpans(newFor("kotlin", text, NullOracle))
        assertEquals(1, fallback.size)

        val vetoOracle = FixedOracle(TypeFact("FakeLogger", "lsp", 1.0))
        val vetoed = loggingSpans(newFor("kotlin", text, vetoOracle))
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
