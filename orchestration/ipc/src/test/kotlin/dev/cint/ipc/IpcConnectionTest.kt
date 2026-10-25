package dev.cint.ipc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IpcConnectionTest {
    private val codec = NdjsonCodec()

    private open class RecordingHandler : IpcHandler {
        val opened = mutableListOf<DocumentParams>()

        override fun initialize(params: InitializeParams): IpcResult =
            IpcResults.of(InitializeResult(server = ServerInfo(version = "test")))

        override fun status(): IpcResult = IpcResults.of(StatusResult(version = "test"))

        override fun metrics(): IpcResult = IpcResults.of(MetricsResult("# metrics"))

        override fun restrictionsList(params: RestrictionsListParams): IpcResult = IpcResults.of(RestrictionsListResult())

        override fun analyze(params: AnalyzeParams): IpcResult = IpcResults.of(AnalyzeResult())

        override fun didOpen(params: DocumentParams): List<JsonRpcMessage> {
            opened += params
            return emptyList()
        }
    }

    private fun initializeParams(
        protocol: Int = ProtocolFingerprint.CURRENT_PROTOCOL_VERSION,
        fingerprint: String? = ProtocolFingerprint.CURRENT,
    ): JsonObject = IpcJson.encode(InitializeParams(protocol, fingerprint, ClientInfo("test", "1"))).jsonObject

    private fun initialize(requestId: Long = 1): JsonRpcMessage.Request =
        JsonRpcMessage.Request(JsonRpcId.NumberId(requestId), IpcMethods.INITIALIZE, initializeParams())

    private fun request(
        id: Long,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonRpcMessage.Request = JsonRpcMessage.Request(JsonRpcId.NumberId(id), method, params)

    private fun feed(
        connection: IpcConnection,
        message: JsonRpcMessage,
    ): List<JsonRpcMessage> = connection.feed(codec.encodeLine(message))

    @Test
    fun requiresHandshakeAndAcceptsMatchingFingerprint() {
        val connection = IpcConnection(RecordingHandler())
        val before = feed(connection, request(1, IpcMethods.STATUS))
        assertEquals(JsonRpcErrorCodes.HANDSHAKE_REQUIRED, (before.single() as JsonRpcMessage.ErrorResponse).error.code)
        assertFalse(connection.initialized)

        val response = feed(connection, initialize(requestId = 2)).single()
        assertIs<JsonRpcMessage.Response>(response)
        assertTrue(connection.initialized)
        assertEquals(
            "test",
            response.result.jsonObject
                .getValue("server")
                .jsonObject
                .getValue("version")
                .jsonPrimitive.content,
        )
        assertEquals(emptySet(), connection.pending)
    }

    @Test
    fun acceptsLegacyProtocolFourWithoutFingerprint() {
        val connection = IpcConnection(RecordingHandler())
        val response =
            feed(
                connection,
                request(
                    1,
                    IpcMethods.INITIALIZE,
                    IpcJson.encode(InitializeParams(protocol = 4, fingerprint = null)).jsonObject,
                ),
            ).single()
        assertIs<JsonRpcMessage.Response>(response)
        assertTrue(connection.initialized)
    }

    @Test
    fun rejectsFingerprintAndProtocolMismatches() {
        val fingerprintConnection = IpcConnection(RecordingHandler())
        val mismatch =
            feed(
                fingerprintConnection,
                request(1, IpcMethods.INITIALIZE, initializeParams(fingerprint = "wrong")),
            ).single()
        val mismatchError = assertIs<JsonRpcMessage.ErrorResponse>(mismatch)
        assertEquals(JsonRpcErrorCodes.PROTOCOL_ERROR, mismatchError.error.code)
        assertEquals(
            "fingerprint-mismatch",
            mismatchError.error.data!!
                .jsonObject
                .getValue("kind")
                .jsonPrimitive.content,
        )
        assertFalse(fingerprintConnection.initialized)

        val protocolConnection = IpcConnection(RecordingHandler())
        val unsupported =
            feed(
                protocolConnection,
                request(1, IpcMethods.INITIALIZE, IpcJson.encode(InitializeParams(protocol = 0)).jsonObject),
            ).single()
        assertEquals(
            JsonRpcErrorCodes.PROTOCOL_ERROR,
            (unsupported as JsonRpcMessage.ErrorResponse).error.code,
        )
    }

    @Test
    fun rejectsASecondInitialize() {
        val connection = IpcConnection(RecordingHandler())
        feed(connection, initialize(1))
        val response = feed(connection, initialize(2)).single()
        assertEquals(
            JsonRpcErrorCodes.PROTOCOL_ERROR,
            (response as JsonRpcMessage.ErrorResponse).error.code,
        )
    }

    @Test
    fun rejectsDuplicateRequestIds() {
        val connection = IpcConnection(RecordingHandler())
        feed(connection, initialize(1))
        val duplicate = feed(connection, request(1, IpcMethods.STATUS)).single()
        val error = assertIs<JsonRpcMessage.ErrorResponse>(duplicate)
        assertEquals(JsonRpcErrorCodes.DUPLICATE_ID, error.error.code)
        assertEquals(
            "duplicate-id",
            error.error.data!!
                .jsonObject
                .getValue("kind")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun reportsUnknownMethodsAndInvalidParams() {
        val connection = IpcConnection(RecordingHandler())
        feed(connection, initialize())
        val unknown = feed(connection, request(2, "cint/nope")).single()
        val unknownError = assertIs<JsonRpcMessage.ErrorResponse>(unknown)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, unknownError.error.code)
        assertTrue(unknownError.error.message.contains("unknown method"))

        val invalidParams = feed(connection, request(3, IpcMethods.ANALYZE, buildJsonObject { })).single()
        val paramsError = assertIs<JsonRpcMessage.ErrorResponse>(invalidParams)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, paramsError.error.code)
    }

    @Test
    fun convertsHandlerExceptionsToInternalErrors() {
        val handler =
            object : RecordingHandler() {
                override fun status(): IpcResult = error("boom")
            }
        val connection = IpcConnection(handler)
        feed(connection, initialize())
        val response = feed(connection, request(2, IpcMethods.STATUS)).single()
        val error = assertIs<JsonRpcMessage.ErrorResponse>(response)
        assertEquals(JsonRpcErrorCodes.INTERNAL_ERROR, error.error.code)
        assertEquals("boom", error.error.message)
    }

    @Test
    fun handlesNotificationsWithoutResponses() {
        val handler = RecordingHandler()
        val connection = IpcConnection(handler)
        feed(connection, initialize())
        val notification =
            JsonRpcMessage.Notification(
                IpcMethods.DID_OPEN,
                IpcJson.encode(DocumentParams("file:///test/A.kt", "class A", 1, "kotlin")).jsonObject,
            )
        assertEquals(emptyList(), feed(connection, notification))
        assertEquals("file:///test/A.kt", handler.opened.single().uri)

        assertEquals(emptyList(), feed(connection, JsonRpcMessage.Notification("cint/nope")))
    }

    @Test
    fun handlesPartialReadsAndMalformedFrames() {
        val connection = IpcConnection(RecordingHandler())
        feed(connection, initialize())
        val line = codec.encodeLine(request(2, IpcMethods.STATUS))
        val split = line.length / 2
        assertEquals(emptyList(), connection.feed(line.substring(0, split)))
        val response = connection.feed(line.substring(split)).single()
        assertIs<JsonRpcMessage.Response>(response)

        val malformed = connection.feed("not json\n").single()
        val parseError = assertIs<JsonRpcMessage.ErrorResponse>(malformed)
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, parseError.error.code)
        assertEquals(JsonRpcId.NullId, parseError.id)

        assertEquals(emptyList(), connection.feed("{\"jsonrpc\":\"2.0\""))
        val unfinished = connection.finish().single()
        assertEquals(
            JsonRpcErrorCodes.PARSE_ERROR,
            (unfinished as JsonRpcMessage.ErrorResponse).error.code,
        )
    }

    @Test
    fun disconnectClosesTheConnectionExplicitly() {
        val connection = IpcConnection(RecordingHandler())
        feed(connection, initialize())
        assertEquals(emptyList(), connection.disconnect())
        assertTrue(connection.closed)
        val response = feed(connection, request(2, IpcMethods.STATUS)).single()
        val error = assertIs<JsonRpcMessage.ErrorResponse>(response)
        assertEquals(JsonRpcErrorCodes.CONNECTION_CLOSED, error.error.code)
        assertEquals(emptyList(), connection.disconnect())
    }

    @Test
    fun rejectsClientResponses() {
        val connection = IpcConnection(RecordingHandler())
        feed(connection, initialize())
        val response = feed(connection, JsonRpcMessage.Response(JsonRpcId.NumberId(2), JsonPrimitive(true))).single()
        assertEquals(
            JsonRpcErrorCodes.PROTOCOL_ERROR,
            (response as JsonRpcMessage.ErrorResponse).error.code,
        )
    }

    @Test
    fun failedRoutingDoesNotConsumeRequestIds() {
        val connection = IpcConnection(RecordingHandler())
        val preHandshake = feed(connection, request(1, IpcMethods.STATUS)).single()
        assertEquals(
            JsonRpcErrorCodes.HANDSHAKE_REQUIRED,
            (preHandshake as JsonRpcMessage.ErrorResponse).error.code,
        )
        val initializedResponse = feed(connection, initialize(requestId = 1)).single()
        assertIs<JsonRpcMessage.Response>(initializedResponse)

        val unknown = feed(connection, request(2, "cint/nope")).single()
        assertEquals(
            JsonRpcErrorCodes.METHOD_NOT_FOUND,
            (unknown as JsonRpcMessage.ErrorResponse).error.code,
        )
        val retried = feed(connection, request(2, IpcMethods.STATUS)).single()
        assertIs<JsonRpcMessage.Response>(retried)
    }

    @Test
    fun parameterlessMethodsRejectUnknownFieldsAndShapes() {
        val connection = IpcConnection(RecordingHandler())
        feed(connection, initialize())
        val unknownField =
            feed(connection, request(2, IpcMethods.STATUS, buildJsonObject { put("unexpected", true) })).single()
        assertEquals(
            JsonRpcErrorCodes.INVALID_PARAMS,
            (unknownField as JsonRpcMessage.ErrorResponse).error.code,
        )
        val shutdown = feed(connection, request(3, IpcMethods.SHUTDOWN, buildJsonObject { put("force", true) })).single()
        assertEquals(
            JsonRpcErrorCodes.INVALID_PARAMS,
            (shutdown as JsonRpcMessage.ErrorResponse).error.code,
        )
        val metrics = feed(connection, request(4, IpcMethods.METRICS, buildJsonObject { })).single()
        assertIs<JsonRpcMessage.Response>(metrics)
    }

    @Test
    fun preHandshakeNotificationsProduceNoReply() {
        val connection = IpcConnection(RecordingHandler())
        assertEquals(
            emptyList(),
            feed(connection, JsonRpcMessage.Notification(IpcMethods.DID_CLOSE, buildJsonObject { put("uri", "file:///x") })),
        )
    }

    @Test
    fun disconnectProcessesACompleteTrailingFrame() {
        val connection = IpcConnection(RecordingHandler())
        feed(connection, initialize())
        val line = codec.encodeLine(request(2, IpcMethods.STATUS))
        assertEquals(emptyList(), connection.feed(line.removeSuffix("\n")))
        val response = connection.disconnect().single()
        assertIs<JsonRpcMessage.Response>(response)
        assertTrue(connection.closed)
    }

    @Test
    fun legacyProtocolsNegotiateWithoutFingerprint() {
        val connection = IpcConnection(RecordingHandler())
        val response =
            feed(
                connection,
                request(1, IpcMethods.INITIALIZE, IpcJson.encode(InitializeParams(protocol = 3)).jsonObject),
            ).single()
        assertIs<JsonRpcMessage.Response>(response)
        assertEquals(3, connection.negotiatedProtocol)
        assertTrue(connection.isLegacy)
    }

    @Test
    fun routesBothRestrictionListNames() {
        val connection = IpcConnection(RecordingHandler())
        feed(connection, initialize())
        val getStyle = feed(connection, request(2, IpcMethods.RESTRICTIONS, buildJsonObject { })).single()
        assertIs<JsonRpcMessage.Response>(getStyle)
        val listStyle = feed(connection, request(3, IpcMethods.RESTRICTIONS_LIST, buildJsonObject { })).single()
        assertIs<JsonRpcMessage.Response>(listStyle)
    }
}
