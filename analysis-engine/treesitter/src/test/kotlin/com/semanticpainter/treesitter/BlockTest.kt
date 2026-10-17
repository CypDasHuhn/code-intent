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
}
