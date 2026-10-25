package dev.cint.ipc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolMessagesCompatibilityTest {
    @Test
    fun initializeResultRetainsFrozenV1FieldsAndAddsV2Extras() {
        val encoded =
            IpcJson
                .encode(
                    InitializeResult(
                        protocol = 4,
                        root = "/tmp/root",
                        languages = listOf("kotlin", "typescript"),
                        config = JsonObject(mapOf("packs" to JsonPrimitive("v1"))),
                        suggestions = JsonObject(mapOf("enabled" to JsonPrimitive(false))),
                        server = ServerInfo(version = "v2"),
                        capabilities = JsonObject(mapOf("diagnostics" to JsonPrimitive(true))),
                    ),
                ).jsonObject

        for (field in listOf("protocol", "root", "languages", "config", "suggestions", "fingerprint", "server", "capabilities")) {
            assertTrue(encoded.containsKey(field), "initialize result missing $field")
        }
        assertTrue(encoded.getValue("config").jsonObject.containsKey("packs"))
        assertTrue(encoded.getValue("suggestions").jsonObject.containsKey("enabled"))
        assertTrue(
            encoded
                .getValue("capabilities")
                .jsonObject
                .getValue("diagnostics")
                .jsonPrimitive.boolean,
        )
    }

    @Test
    fun flatSpanFieldsAreValidatedOnEveryWireDto() {
        assertFails {
            IpcAnnotation("role", listOf("LOGGING"), "treesitter", 0.5, -1, 0, 1, 0, 0, 1)
        }
        assertFails {
            IpcAnnotation("role", listOf("LOGGING"), "treesitter", 0.5, 2, 0, 1, 0, 0, 1)
        }
        assertFails {
            IpcProvenance(
                "rule",
                role = "role",
                intents = listOf("LOGGING"),
                source = "treesitter",
                confidence = 0.5,
                startLine = 0,
                startCol = 5,
                endLine = 0,
                endCol = 4,
                startByte = 0,
                endByte = 1,
            )
        }
        assertFails {
            CandidateOperation(
                CandidateOperationKind.ADD,
                listOf("LOGGING"),
                startLine = 0,
                startCol = 0,
                endLine = 0,
                endCol = 0,
                startByte = 5,
                endByte = 4,
                confidence = 0.5,
            )
        }
        assertFails {
            IpcCandidate(
                candidateId = "c",
                path = "file:///x",
                contentHash = "h",
                intents = listOf("LOGGING"),
                startLine = -1,
                startCol = 0,
                endLine = 0,
                endCol = 1,
                startByte = 0,
                endByte = 1,
                confidence = 0.5,
                origin = CandidateOrigin("rule"),
                category = "pack-domain",
            )
        }
        assertFails {
            Restriction("id", "file:///x", -1, 0, 0, 1)
        }
        assertFails {
            IpcJson.decode<IpcAnnotation>(
                buildJsonObject {
                    put("role", "role")
                    put("intents", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("LOGGING")) })
                    put("source", "treesitter")
                    put("confidence", 0.5)
                    put("startLine", 2)
                    put("startCol", 0)
                    put("endLine", 1)
                    put("endCol", 0)
                    put("startByte", 0)
                    put("endByte", 1)
                },
            )
        }
    }

    @Test
    fun restrictionDtoPreservesV1FieldsAndModelsV2Additions() {
        val frozen =
            """
            {"restrictions":[{"add":[{"confidence":0.8,"endOffset":7,"intent":"SEARCH","rationale":"fixture restriction","startOffset":0}],
            "contentHash":"fixture","createdAt":"2026-10-24T00:00:00Z","endCol":7,"endLine":0,"id":"fixture-restriction",
            "model":"fake-intent-v1","path":"file:///test/Bookkeeping.kt","promptVersion":"fake-prompt-v2","provider":"fake",
            "remove":[{"confidence":0.7,"endOffset":7,"intent":"LOGGING","rationale":"fixture restriction","startOffset":0}],
            "startCol":0,"startLine":0,"status":"dormant","symbol":null,"unitFingerprint":null}]}
            """.trimIndent()
        val decoded = IpcJson.decode<RestrictionsListResult>(IpcJson.json.parseToJsonElement(frozen).jsonObject)
        val restriction = decoded.restrictions.single()
        assertEquals("fixture-restriction", restriction.id)
        assertEquals("dormant", restriction.status)
        assertEquals("fake-intent-v1", restriction.model)
        assertEquals("fixture", restriction.contentHash)
        assertEquals("SEARCH", restriction.add.single().intent)
        assertEquals("LOGGING", restriction.remove.single().intent)
        assertEquals(null, restriction.scope)
        assertEquals(null, restriction.condition)

        val v2 = restriction.copy(scope = "project", condition = JsonObject(mapOf("fileExists" to JsonPrimitive(true))))
        val encoded = IpcJson.encode(v2).jsonObject
        assertEquals("project", encoded.getValue("scope").jsonPrimitive.content)
        assertTrue(
            encoded
                .getValue("condition")
                .jsonObject
                .getValue("fileExists")
                .jsonPrimitive.boolean,
        )
        assertTrue(encoded.containsKey("unitFingerprint"))
    }

    @Test
    fun candidateOperationsKeepLegacyIntentSourceAndRationale() {
        val legacy =
            """
            {"kind":"ADD","intent":"NORMALIZATION","startLine":29,"startCol":16,"endLine":29,"endCol":27,
             "startByte":448,"endByte":459,"confidence":0.4,"source":"rule:normalization:normalization","rationale":"legacy"}
            """.trimIndent()
        val operation = IpcJson.decode<CandidateOperation>(IpcJson.json.parseToJsonElement(legacy).jsonObject)
        assertEquals("NORMALIZATION", operation.intent)
        assertEquals("rule:normalization:normalization", operation.source)
        assertEquals("legacy", operation.rationale)
        val encoded = IpcJson.encode(operation).jsonObject
        assertTrue(encoded.containsKey("intent"))
        assertTrue(encoded.containsKey("source"))
        assertTrue(encoded.containsKey("rationale"))
        assertFalse(encoded.getValue("intents").jsonArray.isNotEmpty())

        val v2 = operation.copy(intents = listOf("NORMALIZATION"), origin = CandidateOrigin("rule", ruleId = "normalization:normalization"))
        val v2Encoded = IpcJson.encode(v2).jsonObject
        assertEquals(
            "rule",
            v2Encoded
                .getValue("origin")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun fingerprintCoversInitializeSuggestConfigStatusRestrictionsAndDiagnostics() {
        val schema = ProtocolFingerprint.canonicalSchema()
        for (token in listOf(
            "InitializeResult",
            "root",
            "suggestions",
            "SuggestResult",
            "selection",
            "ConfigResult",
            "StatusResult",
            "Restriction",
            "RestrictionOperation",
            "IpcDiagnostic",
            "capabilities",
        )) {
            assertTrue(token in schema, "fingerprint schema missing $token")
        }
        assertEquals(64, ProtocolFingerprint.CURRENT.length)
    }

    @Test
    fun legacyCompatibilityRangeIsMachineReadable() {
        assertTrue(3 in ProtocolCompatibility.legacyVersions)
        assertTrue(4 in ProtocolCompatibility.legacyVersions)
        assertFalse(2 in ProtocolCompatibility.legacyVersions)
        assertTrue(ProtocolFingerprint.CURRENT_PROTOCOL_VERSION in ProtocolCompatibility.supportedVersions)
        assertFalse(ProtocolFingerprint.CURRENT_PROTOCOL_VERSION in ProtocolCompatibility.legacyVersions)
    }
}
