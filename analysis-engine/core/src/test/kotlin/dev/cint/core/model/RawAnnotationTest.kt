package dev.cint.core.model

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RawAnnotationTest {
    private val span = Span(0, 0, 0, 4)

    @Test
    fun mergeKeepsPerContributorProvenance() {
        val logging = raw("logging", "LOGGING")
        val errors = raw("error-handling", "ERROR_HANDLING")
        val merged = logging.merge(errors)
        val annotation =
            Annotation(
                role = merged.role,
                intents = merged.intents,
                evidence = merged.evidence,
                span = merged.span,
                source = merged.source,
                confidence = merged.confidence,
                startByte = merged.startByte,
                endByte = merged.endByte,
                provenance = merged.provenance,
            )

        assertEquals(setOf("LOGGING", "ERROR_HANDLING"), annotation.intents)
        assertEquals(listOf("logging", "error-handling"), annotation.provenance.map { it.rule })
        assertEquals("logging", annotation.rule)
        assertFalse(annotation.rule.contains("+"))
    }

    private fun raw(
        rule: String,
        intent: String,
    ): RawAnnotation =
        RawAnnotation.of(
            Contribution(
                rule = rule,
                role = Role.SOURCE_INTENT,
                intents = setOf(intent),
                evidence = rule,
                span = span,
                source = "treesitter",
                confidence = 1.0,
                startByte = 0,
                endByte = 4,
            ),
        )
}
