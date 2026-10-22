package dev.cint.rulepacks.tests.intents.normalization

import dev.cint.api.CandidateReason
import dev.cint.api.Role
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.normalization.NORMALIZATION_INTENT
import dev.cint.rulepacks.intents.normalization.NORMALIZATION_RULE_ID
import dev.cint.rulepacks.intents.normalization.NormalizationRulePack
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
import dev.cint.rulepacks.logging.LOGGING_INTENT
import dev.cint.rulepacks.logging.LoggingRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.ValidationRulePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NormalizationRuleClassificationTest {
    @Test
    fun validationAndNormalizationCoexistOnOneExpression() {
        val text =
            """
            fun f(value: String): String {
                if (value.trim().isBlank()) {
                    return ""
                }
                return value.trim()
            }
            """.trimIndent()

        val analysis =
            analyze(
                "kotlin",
                text,
                listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ValidationRulePack(), NormalizationRulePack()),
            )

        val validation = analysis.source(VALIDATION_INTENT).single { "isBlank" in it.text(text) }
        val normalization = analysis.source(NORMALIZATION_INTENT)
        val guardedTrim = normalization.first { it.text(text) == "value.trim()" }

        assertEquals(2, normalization.count { it.text(text) == "value.trim()" })
        assertTrue(normalization.none { "isBlank" in it.text(text) })
        assertTrue(guardedTrim.startByte >= validation.startByte)
        assertTrue(guardedTrim.endByte <= validation.endByte)
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())

        val guard =
            analysis.annotations.first {
                it.role == Role.SCAFFOLD && "if (value.trim().isBlank())" in it.text(text)
            }
        assertTrue(VALIDATION_INTENT in guard.intents)
    }

    @Test
    fun normalizationDoesNotBecomeLoggingOrBookkeeping() {
        val text =
            """
            class C {
                @Bookkeeping
                var total = 0

                fun f(value: String) {
                    println(value.trim())
                    total++
                }
            }
            """.trimIndent()

        val analysis =
            analyze(
                "kotlin",
                text,
                listOf(BookkeepingRulePack(), LoggingRulePack(), NormalizationRulePack()),
            )

        val normalization = analysis.texts(NORMALIZATION_INTENT)
        val logging = analysis.texts(LOGGING_INTENT)
        val bookkeeping = analysis.texts(BOOKKEEPING_INTENT)

        assertEquals(listOf("value.trim()"), normalization)
        assertTrue(logging.any { "println(value.trim())" in it })
        assertTrue(bookkeeping.any { it == "total++" })
        assertTrue(normalization.none { "println" in it })
        assertTrue(logging.none { it == "value.trim()" })
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun multiStepTransformYieldsCandidateForTheUnconfiguredStep() {
        val text =
            """
            fun f(value: String): String {
                return value.trim().replace(" ", "")
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)
        val candidate = analysis.candidates(NORMALIZATION_INTENT).single()

        assertEquals(listOf("value.trim()"), analysis.texts(NORMALIZATION_INTENT))
        assertTrue(analysis.texts(NORMALIZATION_INTENT).none { "replace" in it })
        assertEquals(CandidateReason.PACK_DOMAIN, candidate.reason)
        assertEquals("rule:$NORMALIZATION_RULE_ID", candidate.source)
        assertEquals(0.4, candidate.confidence)
        assertTrue(candidate.aiEligible)
        assertTrue("replace(" in analysis.candidateText(text))
    }

    @Test
    fun formattingHelpersAreCandidatesWithoutAConfiguredType() {
        val text = "fun f(value: String) = value.format(value)"

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(NORMALIZATION_INTENT).isEmpty())
        val candidate = analysis.candidates(NORMALIZATION_INTENT).single()
        assertEquals(CandidateReason.PACK_DOMAIN, candidate.reason)
        assertTrue(candidate.aiEligible)
        assertEquals("value.format(value)", analysis.candidateText(text))
    }

    @Test
    fun formattingHelperOnAConfiguredTypeRemainsCandidate() {
        val text = "fun f(date: LocalDate) = date.format(null)"
        val input = parsePackInput("kotlin", text)

        val analysis =
            analyzePack(
                input,
                listOf(NormalizationRulePack()),
                FixedOracle(TypeFact("java.time.LocalDate", "lsp", 0.9)),
            )

        assertTrue(analysis.source(NORMALIZATION_INTENT).isEmpty())
        assertEquals("date.format(null)", analysis.candidateText(text))
    }

    @Test
    fun configuredTypeEstablishesPrefixCalls() {
        val text = "fun f(date: LocalDate) = date.parseCanonical()"
        val input = parsePackInput("kotlin", text)

        val analysis =
            analyzePack(
                input,
                listOf(NormalizationRulePack()),
                FixedOracle(TypeFact("java.time.LocalDate", "lsp", 0.9)),
            )

        assertEquals(listOf("date.parseCanonical()"), analysis.texts(NORMALIZATION_INTENT))
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun fullyEstablishedChainsStayCandidateFree() {
        val text = "fun f(value: String) = value.trim().uppercase()"

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("value.trim()", "value.trim().uppercase()"), analysis.texts(NORMALIZATION_INTENT).toSet())
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun nonTransformChainTailsStayCandidateFree() {
        val text =
            """
            fun f(value: String) {
                value.trim().isBlank()
                value.trim().length
                value.trim().substring(1)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(3, analysis.texts(NORMALIZATION_INTENT).count { it == "value.trim()" })
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun displayFormattingStaysCandidateByDefault() {
        val kotlin = analyze("kotlin", "fun f(value: String) = String.format(value)")
        assertTrue(kotlin.source(NORMALIZATION_INTENT).isEmpty())
        assertEquals("String.format(value)", kotlin.candidateText("fun f(value: String) = String.format(value)"))

        val javascript = analyze("javascript", "function f(value) { return JSON.stringify(value); }")
        assertTrue(javascript.source(NORMALIZATION_INTENT).isEmpty())
        assertTrue(javascript.candidates(NORMALIZATION_INTENT).single().aiEligible)
    }

    @Test
    fun broadPrefixesStayConservativeAcrossLanguages() {
        val kotlin =
            analyze(
                "kotlin",
                """
                fun f(value: String) {
                    value.parseDomainEvent()
                    value.normalizeLayout()
                    value.canonicalizeForDisplay()
                }
                """.trimIndent(),
            )
        assertTrue(kotlin.source(NORMALIZATION_INTENT).isEmpty())
        assertEquals(3, kotlin.candidates(NORMALIZATION_INTENT).size)

        val csharp =
            analyze(
                "csharp",
                """
                class C {
                    void F(string value) {
                        if (int.TryParse(value, out _)) {
                            work();
                        }
                    }
                }
                """.trimIndent(),
            )
        assertTrue(csharp.source(NORMALIZATION_INTENT).isEmpty())
        assertTrue(csharp.candidates(NORMALIZATION_INTENT).single().aiEligible)
    }

    @Test
    fun unrelatedCallsRemainUntagged() {
        val text =
            """
            fun f(value: String) {
                value.substring(1)
                value.replace("a", "b")
                value.isEmpty()
                logger.info(value.trim())
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(listOf("value.trim()"), analysis.texts(NORMALIZATION_INTENT))
        assertFalse(analysis.texts(NORMALIZATION_INTENT).any { "substring" in it || "replace" in it })
    }
}
