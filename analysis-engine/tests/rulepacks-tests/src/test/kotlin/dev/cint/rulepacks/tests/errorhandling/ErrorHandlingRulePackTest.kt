package dev.cint.rulepacks.tests.errorhandling

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.api.RulePack
import dev.cint.api.Span
import dev.cint.rulepacks.errorhandling.ERROR_HANDLING_INTENT
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class ErrorHandlingHit(
    val intents: Set<String>,
    val span: Span,
    val text: String,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
)

private val errorHandlingHitOrder =
    compareBy<ErrorHandlingHit>(
        { it.startByte },
        { it.endByte },
        { it.text },
        { it.intents.joinToString("\u0000") },
    )

class ErrorHandlingRulePackTest {
    @Test
    fun packMetadataMatchesContract() {
        val pack = ErrorHandlingRulePack()
        val rule = pack.rules().single()

        assertEquals("error-handling", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        assertEquals("error-handling:error-handling", rule.id)
        assertEquals(ERROR_HANDLING_INTENT, rule.owns)
        assertTrue(rule.reads.isEmpty())
    }

    @Test
    fun csharpQualifiedCallsAreDetectedAndArbitraryReceiverStaysUntagged() {
        val text =
            """
            class C {
                void F(Checker c) {
                    Debug.Assert(true);
                    Trace.Assert(true);
                    Contract.Assert(true);
                    System.Diagnostics.Debug.Assert(true);
                    c.Assert(true);
                    throw new Exception("x");
                }
            }
            """.trimIndent()

        val current = newHits("csharp", text)
        assertTrue(current.any { it.text == "Debug.Assert(true)" })
        assertTrue(current.any { it.text == "Trace.Assert(true)" })
        assertTrue(current.any { it.text == "Contract.Assert(true)" })
        assertTrue(current.any { it.text == "System.Diagnostics.Debug.Assert(true)" })
        assertTrue(current.none { it.text == "c.Assert(true)" })
    }

    @Test
    fun kotlinReceiverOnConfiguredCheckIsNotErrorHandling() {
        val text =
            """
            fun f(c: Checker) {
                c.require(true)
                require(true) { "required" }
                throw Exception("x")
            }
            """.trimIndent()

        val current = newHits("kotlin", text)
        assertTrue(current.none { it.text.startsWith("c.require") })
        assertTrue(current.any { it.text.startsWith("require(true)") })
        assertTrue(current.any { it.text == "throw Exception(\"x\")" })
    }

    @Test
    fun javascriptUnqualifiedChecksAreDetectedAndReceiverStaysUntagged() {
        val text =
            """
            function f(obj, id) {
                assert(id, "id required");
                invariant(id);
                obj.assert(id);
            }
            """.trimIndent()

        val current = newHits("javascript", text)
        assertTrue(current.any { it.text == "assert(id, \"id required\")" })
        assertTrue(current.any { it.text == "invariant(id)" })
        assertTrue(current.none { it.text == "obj.assert(id)" })
    }

    @Test
    fun trailingLambdaSpanIsIncluded() {
        val text =
            """
            fun f() {
                require(true) {
                    "message"
                }
            }
            """.trimIndent()

        val current = newHits("kotlin", text)
        val check = current.single { it.text.contains("\"message\"") }

        assertEquals(2, check.span.endLine - check.span.startLine)
        assertTrue(check.text.startsWith("require(true)"))
    }

    @Test
    fun kotlinAssertIsDetected() {
        val text = """fun f(x: Int) { assert(x > 0) }"""

        val current = newHits("kotlin", text)
        assertTrue(current.any { it.text == "assert(x > 0)" })
    }

    @Test
    fun disabledThrowsConfigSkipsThrows() {
        val text = """fun f() { throw Exception("x") }"""
        val pack = ErrorHandlingRulePack().configure(mapOf("kotlin" to mapOf("detectThrows" to false)))

        assertTrue(newHits("kotlin", text).isNotEmpty())
        assertTrue(newHits("kotlin", text, pack).isEmpty())
    }

    @Test
    fun throwsConfigControlsThrowEmission() {
        val text = """fun f() { throw Exception("x") }"""
        val pack = ErrorHandlingRulePack().configure(mapOf("kotlin" to mapOf("throws" to false)))

        assertTrue(newHits("kotlin", text).isNotEmpty())
        assertTrue(newHits("kotlin", text, pack).isEmpty())
    }

    @Test
    fun detectThrowsAliasKeepsExistingConfigsWorking() {
        val text = """fun f() { throw Exception("x") }"""
        val pack = ErrorHandlingRulePack().configure(mapOf("kotlin" to mapOf("detectThrows" to false)))

        assertTrue(newHits("kotlin", text).isNotEmpty())
        assertTrue(newHits("kotlin", text, pack).isEmpty())
    }

    private fun newHits(
        language: String,
        text: String,
    ): List<ErrorHandlingHit> = newHits(language, text, ErrorHandlingRulePack())

    private fun newHits(
        language: String,
        text: String,
        pack: RulePack,
    ): List<ErrorHandlingHit> = hits(analyzePack(language, text, listOf(pack)).annotations, text)

    private fun hits(
        annotations: List<Annotation>,
        text: String,
    ): List<ErrorHandlingHit> =
        annotations
            .asSequence()
            .filter { it.role == Role.SOURCE_INTENT }
            .flatMap { it.provenance.asSequence() }
            .filter { ERROR_HANDLING_INTENT in it.intents }
            .map {
                ErrorHandlingHit(
                    intents = it.intents,
                    span = it.span,
                    text = it.text(text),
                    source = it.source,
                    confidence = it.confidence,
                    startByte = it.startByte,
                    endByte = it.endByte,
                )
            }.sortedWith(errorHandlingHitOrder)
            .toList()

    @Test
    fun elvisThrowIsTaggedFromTheCoalesceOperator() {
        val source =
            """
            fun f(seed: String?): String {
                val value = seed ?: throw IllegalStateException("missing")
                return value
            }
            """.trimIndent()
        val analysis = analyzePack("kotlin", source, listOf(ErrorHandlingRulePack()))
        val texts = analysis.source(ERROR_HANDLING_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.trimStart().startsWith("?: throw") })
        assertTrue(texts.none { it.trimStart().startsWith("seed ?:") })
    }
}
