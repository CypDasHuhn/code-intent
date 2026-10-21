package dev.cint.integration.composition

import dev.cint.api.Role
import dev.cint.core.facts.model.SourceFile
import dev.cint.integration.composition.support.ReplayPack
import dev.cint.integration.composition.support.ReplayRule
import dev.cint.integration.composition.support.analyzePipeline
import dev.cint.integration.composition.support.emitFor
import dev.cint.integration.composition.support.replaySource
import dev.cint.test.fixtures.text
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DerivativeUseCompositionTest {
    @Test
    fun newPipelinePromotesAnnotatedPureFunctionsAndTransitiveDeclarations() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            @Pure
            fun make(): String = "x"
            fun f() {
                val source = make()
                val message = source
                log.warn(message)
            }
            """.trimIndent()
        val annotations =
            analyzePipeline(text, listOf(ReplayPack("source", replaySource(text))))

        val derivatives = annotations.filter { it.role == Role.DERIVATIVE_USE }
        assertEquals(4, derivatives.size)
        assertTrue(derivatives.all { it.intents == setOf("LOGGING") })
        assertTrue(derivatives.any { it.text(text).contains("private val log") })
        assertTrue(derivatives.any { it.text(text).contains("fun make") })
        assertTrue(derivatives.any { it.text(text).contains("val source") })
        assertTrue(derivatives.any { it.text(text).contains("val message") })
    }

    @Test
    fun newPipelineDoesNotPromoteKnownWrites() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            private var counter = 0
            fun f() {
                val source = run { counter++; "x" }
                val message = source
                log.warn(message)
                log.warn(source)
            }
            """.trimIndent()
        val annotations =
            analyzePipeline(text, listOf(ReplayPack("source", replaySource(text))))

        val derivatives = annotations.filter { it.role == Role.DERIVATIVE_USE }
        assertTrue(derivatives.none { it.text(text).contains("val source") })
    }

    @Test
    fun newPipelinePromotesUnknownReadOnlyInitializersWithLowerConfidence() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun fetch(): String = external()
            fun external(): String = "x"
            fun f() {
                val message = fetch()
                log.warn(message)
            }
            """.trimIndent()
        val annotations =
            analyzePipeline(text, listOf(ReplayPack("source", replaySource(text))))

        val derivative =
            annotations.first {
                it.role == Role.DERIVATIVE_USE && it.text(text).contains("val message")
            }
        assertEquals(setOf("LOGGING"), derivative.intents)
        assertTrue(derivative.confidence < 0.7)
    }

    @Test
    fun newPipelineAllowsExternalReadsWithoutCalls() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                val message = config.message
                log.warn(message)
            }
            """.trimIndent()
        val annotations =
            analyzePipeline(text, listOf(ReplayPack("source", replaySource(text))))

        val derivative =
            annotations.first {
                it.role == Role.DERIVATIVE_USE && it.text(text).contains("val message")
            }
        assertEquals(setOf("LOGGING"), derivative.intents)
        assertEquals(0.7, derivative.confidence)
    }

    @Test
    fun newPipelineRequiresFunctionReferencesToStayInsideDerivativeUse() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            @Pure
            fun text(): String = "x"
            fun f() {
                val message = text()
                log.warn(message)
                log.warn(text())
            }
            """.trimIndent()
        val annotations =
            analyzePipeline(text, listOf(ReplayPack("source", replaySource(text))))

        val derivatives = annotations.filter { it.role == Role.DERIVATIVE_USE }
        assertTrue(derivatives.any { it.text(text).contains("val message") })
        assertTrue(derivatives.none { it.text(text).contains("fun text") })
    }

    @Test
    fun derivativeUseResolvesTheSmallestContainingAnnotation() {
        val text =
            """
            fun f() {
                val message = "x"
                log.warn(message)
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val function = KotlinSyntaxModel.functions(tree).first().node
        val call = KotlinSyntaxModel.calls(tree).first { it.callee == "warn" }.site
        val logging =
            ReplayRule(
                id = "test:logging",
                owns = "LOGGING",
                emissions = listOf(emitFor("LOGGING", function)),
            )
        val errorHandling =
            ReplayRule(
                id = "test:error",
                owns = "ERROR_HANDLING",
                emissions = listOf(emitFor("ERROR_HANDLING", call)),
            )

        val annotations =
            analyzePipeline(
                text,
                listOf(ReplayPack("test", listOf(logging, errorHandling))),
            )
        val derivative =
            annotations.first {
                it.role == Role.DERIVATIVE_USE && it.text(text).contains("val message")
            }

        assertEquals(setOf("ERROR_HANDLING"), derivative.intents)
    }
}
