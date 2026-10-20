package dev.cint.server

import com.fasterxml.jackson.databind.util.RawValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.NodeAnchor
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.core.artifact.AnnotationJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationSerializationTest {
    @Test
    fun publishesEveryContributorInProvenance() {
        val span = Span(1, 2, 1, 9)
        val logging =
            Contribution("logging-state", Role.SOURCE_INTENT, setOf("LOGGING"), "loggingEnabled", span, "treesitter", 0.7, 12, 26)
        val validation =
            Contribution("validation-guard", Role.SOURCE_INTENT, setOf("VALIDATION"), "loggingEnabled", span, "treesitter", 0.5, 12, 26)
        val annotation =
            Annotation(
                Role.SOURCE_INTENT,
                setOf("LOGGING", "VALIDATION"),
                "loggingEnabled",
                span,
                "treesitter",
                0.7,
                12,
                26,
                listOf(logging, validation),
            )

        val parsed = jacksonObjectMapper().readTree(AnnotationJson.render(annotation))
        val provenance = parsed["provenance"]

        assertFalse(parsed.has("rule"))
        assertTrue(parsed.has("nodeAnchor"))
        assertTrue(parsed["nodeAnchor"].isNull)
        assertEquals(2, provenance.size())
        assertEquals("logging-state", provenance[0]["rule"].asText())
        assertEquals("validation-guard", provenance[1]["rule"].asText())
        assertEquals("source-intent", provenance[0]["role"].asText())
        assertEquals(12, provenance[1]["startByte"].asInt())
        assertEquals(26, provenance[1]["endByte"].asInt())
    }

    @Test
    fun livePayloadEmbedsTheSharedAnnotationJson() {
        val span = Span(0, 0, 0, 3)
        val contribution =
            Contribution("demo:logging", Role.SOURCE_INTENT, setOf("LOGGING"), "log", span, "treesitter", 1.0, 0, 3)
        val annotation =
            Annotation(
                role = Role.SOURCE_INTENT,
                intents = setOf("LOGGING"),
                evidence = "log",
                span = span,
                source = "treesitter",
                confidence = 1.0,
                startByte = 0,
                endByte = 3,
                provenance = listOf(contribution),
            )

        val parsed =
            jacksonObjectMapper().readTree(
                jacksonObjectMapper().writeValueAsString(
                    mapOf("annotations" to listOf(RawValue(AnnotationJson.render(annotation)))),
                ),
            )

        assertEquals(1, parsed["annotations"].size())
        assertEquals("source-intent", parsed["annotations"][0]["role"].asText())
        assertEquals("demo:logging", parsed["annotations"][0]["provenance"][0]["rule"].asText())
    }

    @Test
    fun nonFiniteConfidenceKeepsTheLivePayloadValidJson() {
        val span = Span(0, 0, 0, 3)
        val contribution =
            Contribution("demo:logging", Role.SOURCE_INTENT, setOf("LOGGING"), "log", span, "treesitter", Double.NaN, 0, 3)
        val annotation =
            Annotation(
                role = Role.SOURCE_INTENT,
                intents = setOf("LOGGING"),
                evidence = "log",
                span = span,
                source = "treesitter",
                confidence = Double.POSITIVE_INFINITY,
                startByte = 0,
                endByte = 3,
                provenance = listOf(contribution),
            )

        val parsed =
            jacksonObjectMapper().readTree(
                jacksonObjectMapper().writeValueAsString(
                    mapOf("annotations" to listOf(RawValue(AnnotationJson.render(annotation)))),
                ),
            )

        assertEquals("Infinity", parsed["annotations"][0]["confidence"].asText())
        assertEquals("NaN", parsed["annotations"][0]["provenance"][0]["confidence"].asText())
    }

    @Test
    fun publishesReservedNodeAnchorWhenPresent() {
        val span = Span(0, 0, 0, 3)
        val contribution =
            Contribution("demo:logging", Role.SOURCE_INTENT, setOf("LOGGING"), "log", span, "treesitter", 1.0, 0, 3)
        val annotation =
            Annotation(
                role = Role.SOURCE_INTENT,
                intents = setOf("LOGGING"),
                evidence = "log",
                span = span,
                source = "treesitter",
                confidence = 1.0,
                startByte = 0,
                endByte = 3,
                provenance = listOf(contribution),
                nodeAnchor = NodeAnchor("node-1"),
            )

        val parsed = jacksonObjectMapper().readTree(AnnotationJson.render(annotation))

        assertEquals("node-1", parsed["nodeAnchor"].asText())
    }
}
