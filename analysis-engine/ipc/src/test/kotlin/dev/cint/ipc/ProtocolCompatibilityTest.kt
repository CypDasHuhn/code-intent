package dev.cint.ipc

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolCompatibilityTest {
    private val codec = NdjsonCodec()

    private val handler =
        object : IpcHandler {
            override fun initialize(params: InitializeParams): IpcResult =
                IpcResults.of(
                    InitializeResult(
                        root = "/tmp/root",
                        languages = listOf("kotlin"),
                        server = ServerInfo(name = "cint-server", version = "test"),
                        capabilities = buildJsonObject { },
                    ),
                )
        }

    @Test
    fun supportedVersionsMatchTheCompatibilityMatrix() {
        assertEquals(listOf(3, 4, ProtocolFingerprint.CURRENT_PROTOCOL_VERSION), ProtocolCompatibility.supportedVersions)
        assertEquals(ProtocolCompatibility.supportedVersions, ProtocolCompatibility.matrix.map { it.version })
        assertEquals(listOf(3, 4), ProtocolCompatibility.legacyVersions)
        assertTrue(ProtocolCompatibility.matrix.all { it.status == "legacy" || it.status == "current" })
        assertTrue(ProtocolCompatibility.matrix.last().requiresFingerprint)
        assertTrue(ProtocolCompatibility.matrix.dropLast(1).none { it.requiresFingerprint })
        assertFalse(ProtocolCompatibility.isSupported(2))
        assertFalse(ProtocolCompatibility.isSupported(6))
    }

    @Test
    fun everySupportedLegacyVersionNegotiatesAStableHandshake() {
        for (version in ProtocolCompatibility.legacyVersions) {
            val connection = IpcConnection(handler)
            val response = initialize(connection, version, fingerprint = null)
            val result = assertIs<JsonRpcMessage.Response>(response).result.jsonObject

            assertEquals(version, result.getValue("protocol").jsonPrimitive.int)
            assertFalse(result.containsKey("fingerprint"))
            assertFalse(result.containsKey("server"))
            assertFalse(result.containsKey("capabilities"))
            assertEquals(version, connection.negotiatedProtocol)
            assertTrue(connection.isLegacy)
        }
    }

    @Test
    fun omittedProtocolFailsTheHandshakeExplicitly() {
        val connection = IpcConnection(handler)
        val request =
            JsonRpcMessage.Request(
                JsonRpcId.NumberId(1),
                IpcMethods.INITIALIZE,
                buildJsonObject { },
            )
        val error = assertIs<JsonRpcMessage.ErrorResponse>(connection.feed(codec.encodeLine(request)).single())
        assertEquals(JsonRpcErrorCodes.PROTOCOL_ERROR, error.error.code)
        assertEquals(
            "missing-protocol",
            error.error.data!!
                .jsonObject
                .getValue("kind")
                .jsonPrimitive.content,
        )
        assertTrue(error.error.message.contains("explicit protocol version"))
        assertFalse(connection.initialized)
        assertEquals(null, connection.negotiatedProtocol)
    }

    @Test
    fun currentVersionRequiresTheCurrentFingerprint() {
        val missing = initialize(IpcConnection(handler), ProtocolFingerprint.CURRENT_PROTOCOL_VERSION, fingerprint = null)
        val missingError = assertIs<JsonRpcMessage.ErrorResponse>(missing)
        assertEquals(JsonRpcErrorCodes.PROTOCOL_ERROR, missingError.error.code)
        assertEquals(
            "fingerprint-mismatch",
            missingError.error.data!!
                .jsonObject
                .getValue("kind")
                .jsonPrimitive.content,
        )

        val stale =
            initialize(IpcConnection(handler), ProtocolFingerprint.CURRENT_PROTOCOL_VERSION, fingerprint = "stale")
        val staleError = assertIs<JsonRpcMessage.ErrorResponse>(stale)
        assertEquals(JsonRpcErrorCodes.PROTOCOL_ERROR, staleError.error.code)

        val connection = IpcConnection(handler)
        val response = initialize(connection, ProtocolFingerprint.CURRENT_PROTOCOL_VERSION, ProtocolFingerprint.CURRENT)
        val result = assertIs<JsonRpcMessage.Response>(response).result.jsonObject
        assertEquals(ProtocolFingerprint.CURRENT_PROTOCOL_VERSION, result.getValue("protocol").jsonPrimitive.int)
        assertEquals(ProtocolFingerprint.CURRENT, result.getValue("fingerprint").jsonPrimitive.content)
        assertTrue(result.containsKey("server"))
        assertTrue(result.containsKey("capabilities"))
        assertEquals(ProtocolFingerprint.CURRENT_PROTOCOL_VERSION, connection.negotiatedProtocol)
        assertFalse(connection.isLegacy)
    }

    @Test
    fun incompatibleVersionsFailWithAnExplicitProtocolError() {
        for (version in listOf(0, ProtocolFingerprint.CURRENT_PROTOCOL_VERSION + 1)) {
            val response = initialize(IpcConnection(handler), version, fingerprint = null)
            val error = assertIs<JsonRpcMessage.ErrorResponse>(response)
            assertEquals(JsonRpcErrorCodes.PROTOCOL_ERROR, error.error.code)
            assertEquals(
                "unsupported-protocol",
                error.error.data!!
                    .jsonObject
                    .getValue("kind")
                    .jsonPrimitive.content,
            )
            assertTrue(error.error.message.contains("supported"))
            assertTrue(error.error.message.contains(ProtocolFingerprint.CURRENT_PROTOCOL_VERSION.toString()))
        }
    }

    private fun initialize(
        connection: IpcConnection,
        protocol: Int,
        fingerprint: String?,
    ): JsonRpcMessage {
        val request =
            JsonRpcMessage.Request(
                JsonRpcId.NumberId(1),
                IpcMethods.INITIALIZE,
                IpcJson.encode(InitializeParams(protocol = protocol, fingerprint = fingerprint)).jsonObject,
            )
        return connection.feed(codec.encodeLine(request)).single()
    }
}
