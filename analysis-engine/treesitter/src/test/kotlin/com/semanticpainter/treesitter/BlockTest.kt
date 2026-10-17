package com.semanticpainter.treesitter

import com.semanticpainter.core.config.loadFeatureConfig
import com.semanticpainter.core.engine.AnalysisEngine
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.model.TagKind
import com.semanticpainter.core.oracle.NullOracle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockTest {
    private val config = loadFeatureConfig(null, "logging", "kotlin")

    @Test
    fun tagsInteractionsAndOnlyLoggingScaffolds() {
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                log.info("x")
                if (1 == 1) {
                    println("debug")
                }
                if (loggingEnabled) {
                    log.warn("y")
                }
                if (x > 0) {
                    log.error("z")
                }
            }
        """.trimIndent()

        val tree = KotlinParser().parse(SourceFile("Sample.kt", text))
        val spans = AnalysisEngine(LoggingBlocks.all()).analyze(
            SourceFile("Sample.kt", text),
            tree,
            NullOracle,
            config,
        )

        val interactions = spans.filter { it.kind == TagKind.INTERACTION }
        val controlFlow = spans.filter { it.kind == TagKind.CONTROL_FLOW }

        assertEquals(4, interactions.size)
        assertEquals(2, controlFlow.size)
        assertTrue(controlFlow.any { it.span.startLine == 3 })
        assertTrue(controlFlow.any { it.span.startLine == 6 })
        assertFalse(controlFlow.any { it.span.startLine == 9 })
    }

    @Test
    fun tagsOnlyLoggingCatchBlocks() {
        val text = """
            private val logger = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                try {
                    work()
                } catch (failure: Exception) {
                    logger.warn("failed", failure)
                }
                try {
                    work()
                } catch (e: Exception) {
                    logger.warn("failed", e)
                    throw e
                }
                try {
                    work()
                } catch (e: Exception) {
                    if (1 == 1) {
                        val s = ""
                        logger.warn("failed", e)
                    }
                }
            }
        """.trimIndent()

        val tree = KotlinParser().parse(SourceFile("Sample.kt", text))
        val spans = AnalysisEngine(LoggingBlocks.all()).analyze(
            SourceFile("Sample.kt", text),
            tree,
            NullOracle,
            config,
        )

        val controlFlow = spans.filter { it.kind == TagKind.CONTROL_FLOW }
        assertEquals(1, controlFlow.size)
        assertEquals("intent-scaffold-catch", controlFlow.single().rule)
        assertTrue(controlFlow.single().evidence.contains("catch"))
        assertEquals(3, spans.count { it.kind == TagKind.INTERACTION })
    }

    @Test
    fun tagsTryOnlyWhenEveryEntryIsLogging() {
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun allLogging() {
                try {
                    log.warn("attempt")
                } catch (e: Exception) {
                    log.warn("failed", e)
                }
            }
            fun mixed() {
                try {
                    work()
                    log.warn("attempt")
                } catch (e: Exception) {
                    log.warn("failed", e)
                }
            }
            fun work() {}
        """.trimIndent()

        val tree = KotlinParser().parse(SourceFile("Sample.kt", text))
        val spans = AnalysisEngine(LoggingBlocks.all()).analyze(
            SourceFile("Sample.kt", text),
            tree,
            NullOracle,
            config,
        )

        val controlFlow = spans.filter { it.kind == TagKind.CONTROL_FLOW }
        assertEquals(3, controlFlow.size)
        assertTrue(controlFlow.any { it.rule == "intent-scaffold-try" })
        assertEquals(2, controlFlow.count { it.rule == "intent-scaffold-catch" })
    }
}
