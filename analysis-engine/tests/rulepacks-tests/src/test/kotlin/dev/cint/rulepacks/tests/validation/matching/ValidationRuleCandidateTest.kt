package dev.cint.rulepacks.tests.validation.matching

import dev.cint.api.CandidateReason
import dev.cint.rulepacks.tests.support.parseValidationInput
import dev.cint.rulepacks.tests.support.validationAnalysis
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.VALIDATION_RULE_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationRuleCandidateTest {
    @Test
    fun distantValidationCommentEmitsReviewOnlyGapCandidate() {
        val input =
            parseValidationInput(
                "kotlin",
                "Sample.kt",
                """
                fun f(): Boolean {
                    // cint: validation

                    if (true) {
                        work()
                    }
                    return false
                }
                """.trimIndent(),
            )

        val analysis = validationAnalysis(input)

        assertTrue(analysis.annotations.none { it.rule == VALIDATION_RULE_ID })
        val candidate = analysis.candidates(VALIDATION_INTENT).single()
        assertEquals(CandidateReason.GAP, candidate.reason)
        assertEquals("rule:$VALIDATION_RULE_ID", candidate.source)
        assertTrue(candidate.aiEligible)
        assertEquals(0.4, candidate.confidence)
    }

    @Test
    fun adjacentValidationCommentRemainsEstablishedAndNotCandidate() {
        val input =
            parseValidationInput(
                "kotlin",
                "Sample.kt",
                """
                fun f(): Boolean {
                    // cint: validation
                    if (true) {
                        work()
                    }
                    return false
                }
                """.trimIndent(),
            )

        val analysis = validationAnalysis(input)

        assertEquals(1, analysis.annotations.count { it.rule == VALIDATION_RULE_ID })
        assertTrue(analysis.candidates(VALIDATION_INTENT).isEmpty())
    }
}
