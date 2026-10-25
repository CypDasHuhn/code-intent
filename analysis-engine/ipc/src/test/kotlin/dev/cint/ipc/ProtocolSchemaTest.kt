package dev.cint.ipc

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolSchemaTest {
    private val ipcDir: java.nio.file.Path =
        Paths.get(System.getProperty("v2.ipcDir") ?: error("v2.ipcDir must be set"))

    @Test
    fun checkedInSchemaMatchesTheWireDtos() {
        val checkedIn =
            IpcJson.json
                .parseToJsonElement(
                    Files.readString(ipcDir.resolve(ProtocolSchema.FILE_NAME)),
                ).jsonObject
        assertEquals(ProtocolSchema.document(), checkedIn)
    }

    @Test
    fun schemaPublishesTheVersionCompatibilityMatrixAndMethods() {
        val schema = ProtocolSchema.document()
        assertEquals(ProtocolFingerprint.CURRENT_PROTOCOL_VERSION, schema.getValue("x-protocol-version").jsonPrimitive.int)
        assertEquals(ProtocolFingerprint.CURRENT, schema.getValue("x-schema-fingerprint").jsonPrimitive.content)
        assertEquals(
            IpcMethods.requests.sorted(),
            schema.getValue("x-requests").jsonArray.map { it.jsonPrimitive.content },
        )
        val notifications = schema.getValue("x-notifications").jsonObject
        assertEquals(
            IpcMethods.notifications.sorted(),
            notifications.getValue("clientToServer").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            IpcNotifications.serverToClient.sorted(),
            notifications.getValue("serverToClient").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            ProtocolCompatibility.matrix.size,
            schema.getValue("x-compatibility").jsonArray.size,
        )
        val defs = schema.getValue("\$defs").jsonObject
        for (name in listOf(
            "InitializeParams",
            "InitializeResult",
            "PublishTagsParams",
            "PublishCandidatesParams",
            "IpcAnnotation",
            "IpcCandidate",
        )) {
            val key = "dev.cint.ipc.$name"
            assertTrue(defs.containsKey(key), "schema missing $key")
        }
    }
}
