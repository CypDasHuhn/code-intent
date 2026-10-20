package dev.cint.rulepacks.tests.errorhandling

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.api.RulePack
import dev.cint.api.Span
import dev.cint.rulepacks.errorhandling.ERROR_HANDLING_INTENT
import dev.cint.rulepacks.errorhandling.ErrorHandlingConfig
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class ErrorHandlingHit(
    val intents: Set<String>,
    val span: Span,
    val evidence: String,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
)

private val errorHandlingHitOrder =
    compareBy<ErrorHandlingHit>(
        { it.startByte },
        { it.endByte },
        { it.evidence },
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
        assertTrue(current.any { it.evidence == "Debug.Assert(true)" })
        assertTrue(current.any { it.evidence == "Trace.Assert(true)" })
        assertTrue(current.any { it.evidence == "Contract.Assert(true)" })
        assertTrue(current.any { it.evidence == "System.Diagnostics.Debug.Assert(true)" })
        assertTrue(current.none { it.evidence == "c.Assert(true)" })
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
        assertTrue(current.none { it.evidence.startsWith("c.require") })
        assertTrue(current.any { it.evidence.startsWith("require(true)") })
        assertTrue(current.any { it.evidence == "throw Exception(\"x\")" })
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
        assertTrue(current.any { it.evidence == "assert(id, \"id required\")" })
        assertTrue(current.any { it.evidence == "invariant(id)" })
        assertTrue(current.none { it.evidence == "obj.assert(id)" })
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
        val check = current.single { it.evidence.contains("\"message\"") }

        assertEquals(2, check.span.endLine - check.span.startLine)
        assertTrue(check.evidence.startsWith("require(true)"))
    }

    @Test
    fun kotlinAssertIsDetected() {
        val text = """fun f(x: Int) { assert(x > 0) }"""

        val current = newHits("kotlin", text)
        assertTrue(current.any { it.evidence == "assert(x > 0)" })
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
    ): List<ErrorHandlingHit> = hits(analyzePack(language, text, listOf(pack)).annotations)

    private fun hits(annotations: List<Annotation>): List<ErrorHandlingHit> =
        annotations
            .asSequence()
            .filter { it.role == Role.SOURCE_INTENT }
            .flatMap { it.provenance.asSequence() }
            .filter { ERROR_HANDLING_INTENT in it.intents }
            .map {
                ErrorHandlingHit(
                    intents = it.intents,
                    span = it.span,
                    evidence = it.evidence,
                    source = it.source,
                    confidence = it.confidence,
                    startByte = it.startByte,
                    endByte = it.endByte,
                )
            }.sortedWith(errorHandlingHitOrder)
            .toList()
}
