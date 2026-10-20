package dev.cint.treesitter.composition

import dev.cint.api.Role
import dev.cint.core.facts.model.SourceFile
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import dev.cint.treesitter.support.ReplayPack
import dev.cint.treesitter.support.ReplayRule
import dev.cint.treesitter.support.analyzePipeline
import dev.cint.treesitter.support.emitFor
import dev.cint.treesitter.support.replaySource
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
        assertEquals(3, derivatives.size)
        assertTrue(derivatives.all { it.intents == setOf("LOGGING") })
        assertTrue(derivatives.any { it.evidence.contains("fun make") })
        assertTrue(derivatives.any { it.evidence.contains("val source") })
        assertTrue(derivatives.any { it.evidence.contains("val message") })
    }

    @Test
    fun newPipelineDoesNotPromoteImpureLocalsOrFunctions() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun make(): String = "x"
            fun f() {
                val source = make()
                val message = source
                log.warn(message)
                log.warn(source)
            }
            """.trimIndent()
        val annotations =
            analyzePipeline(text, listOf(ReplayPack("source", replaySource(text))))

        val derivatives = annotations.filter { it.role == Role.DERIVATIVE_USE }
        assertTrue(derivatives.any { it.evidence.contains("val message") })
        assertTrue(derivatives.none { it.evidence.contains("val source") })
        assertTrue(derivatives.none { it.evidence.contains("fun make") })
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
        assertTrue(derivatives.any { it.evidence.contains("val message") })
        assertTrue(derivatives.none { it.evidence.contains("fun text") })
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
                it.role == Role.DERIVATIVE_USE && it.evidence.contains("val message")
            }

        assertEquals(setOf("ERROR_HANDLING"), derivative.intents)
    }
}
