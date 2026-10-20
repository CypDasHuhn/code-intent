package dev.cint.core.artifact

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.NodeAnchor
import dev.cint.api.Role
import dev.cint.api.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactTest {
    @Test
    fun renderPublishesEveryContributorInProvenance() {
        val span = Span(1, 2, 1, 9)
        val logging =
            Contribution("logging-state", Role.SOURCE_INTENT, setOf("LOGGING"), "loggingEnabled", span, "treesitter", 0.7, 12, 26)
        val validation =
            Contribution("validation-guard", Role.SOURCE_INTENT, setOf("VALIDATION"), "loggingEnabled", span, "treesitter", 0.5, 12, 26)
        val annotation =
            Annotation(
                role = Role.SOURCE_INTENT,
                intents = setOf("LOGGING", "VALIDATION"),
                evidence = "loggingEnabled",
                span = span,
                source = "treesitter",
                confidence = 0.7,
                startByte = 12,
                endByte = 26,
                provenance = listOf(logging, validation),
                nodeAnchor = NodeAnchor("node-1"),
            )

        val json =
            Artifact.render(
                "typescript",
                listOf("LOGGING", "VALIDATION"),
                listOf(FileEntry("Sample.vue", "hash", listOf(annotation))),
            )

        assertTrue(json.contains("\"nodeAnchor\":\"node-1\""))
        assertTrue(json.contains("\"provenance\":[{\"rule\":\"logging-state\""))
        assertTrue(json.indexOf("\"rule\":\"validation-guard\"") > json.indexOf("\"provenance\":["))
        assertTrue(json.contains("{\"role\":\"source-intent\",\"intents\":[\"LOGGING\",\"VALIDATION\"],\"evidence\""))
        assertFalse(json.contains("\"intents\":[\"LOGGING\",\"VALIDATION\"],\"rule\""))
        assertEquals(3, Regex("\"startByte\":12").findAll(json).count())
        assertEquals(3, Regex("\"endByte\":26").findAll(json).count())
        assertEquals(1, Regex("\"rule\":\"logging-state\"").findAll(json).count())
    }
}
