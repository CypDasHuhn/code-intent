package dev.cint.ipc

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProtocolRegressionTest {
    private val codec = NdjsonCodec()
    private val ipcDir: java.nio.file.Path =
        Paths.get(System.getProperty("v2.ipcDir") ?: error("v2.ipcDir must be set"))

    @Test
    fun legacyInitializeIsRewrittenToTheNegotiatedProtocolAndShape() {
        val handler =
            object : IpcHandler {
                override fun initialize(params: InitializeParams): IpcResult = IpcResults.of(InitializeResult())
            }
        val connection = IpcConnection(handler)
        val request =
            JsonRpcMessage.Request(
                JsonRpcId.NumberId(1),
                IpcMethods.INITIALIZE,
                IpcJson.encode(InitializeParams(protocol = 3)).jsonObject,
            )
        val response = assertIs<JsonRpcMessage.Response>(connection.feed(codec.encodeLine(request)).single())
        val result = response.result.jsonObject
        assertEquals(3, result.getValue("protocol").jsonPrimitive.int)
        assertFalse(result.containsKey("fingerprint"))
        assertFalse(result.containsKey("server"))
        assertFalse(result.containsKey("capabilities"))
        assertEquals(3, connection.negotiatedProtocol)
    }

    @Test
    fun legacyEncoderDoesNotAddV2OperationDefaultsOrNulls() {
        val operation =
            CandidateOperation(
                kind = CandidateOperationKind.ADD,
                startLine = 0,
                startCol = 0,
                endLine = 0,
                endCol = 1,
                startByte = 0,
                endByte = 1,
                confidence = 0.4,
                intent = "LOGGING",
                source = "rule:logging:logging",
            )
        val legacy = IpcJson.encodeLegacy(operation).jsonObject
        assertFalse(legacy.containsKey("intents"))
        assertFalse(legacy.containsKey("origin"))
        assertFalse(legacy.containsKey("rationale"))
        assertEquals("LOGGING", legacy.getValue("intent").jsonPrimitive.content)

        val v2 = IpcJson.encode(operation).jsonObject
        assertTrue(v2.containsKey("intents"))
        assertTrue(v2.containsKey("origin"))
    }

    @Test
    fun fingerprintIncludesOptionalityNullabilityAndActualDefaults() {
        val schema = ProtocolFingerprint.canonicalSchema()
        assertTrue(schema.contains("optional=true"))
        assertTrue(schema.contains("optional=false"))
        assertTrue(schema.contains("nullable=true"))
        assertTrue(schema.contains("InitializeResult[CLASS]"))
        assertTrue(schema.contains("protocol:"))
        assertTrue(schema.contains(":default=\"ok\""))
        assertTrue(schema.contains(":default=null"))

        val baseline = ProtocolFingerprint.fingerprintWithDefaults(emptyMap())
        val changed = ProtocolFingerprint.fingerprintWithDefaults(mapOf("StatusResult.status" to "\"degraded\""))
        assertEquals(ProtocolFingerprint.CURRENT, baseline)
        assertNotEquals(baseline, changed)
    }

    @Test
    fun v2OwnedRegistryCoversInitializeCandidatesDecideAndProtocolDifferences() {
        val registry =
            IpcJson.json.parseToJsonElement(
                Files.readString(ipcDir.resolve("known-differences-v2.json")),
            ).jsonObject
        val ids =
            registry.getValue("differences").jsonArray
                .map { it.jsonObject.getValue("id").jsonPrimitive.content }
                .toSet()
        for (id in listOf("initialize.additions", "candidates.decide", "legacy.protocol-range", "operation.fields", "fingerprint.schema", "error.handling", "suggest.protocol", "restrictions.protocol", "status.protocol")) {
            assertTrue(id in ids, "missing V2 difference $id")
        }
        assertTrue(IpcMethods.CANDIDATES_DECIDE in IpcMethods.all)
        val protocol = Files.readString(ipcDir.resolve("PROTOCOL.md"))
        assertTrue(protocol.contains("cint/candidates/decide"))
        assertTrue(protocol.contains("known-differences-v2.json"))
    }
}
