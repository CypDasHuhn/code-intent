package dev.cint.ipc

import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Role
import dev.cint.contracts.data.Span
import dev.cint.contracts.ipc.ClientInfo
import dev.cint.contracts.ipc.IpcMessage
import dev.cint.contracts.ipc.PublishedAnnotation
import dev.cint.contracts.ports.IpcReceive
import dev.cint.contracts.util.CancellationToken
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContractIpcTransportTest {
    private class MemoryChannel : IpcWireChannel {
        val inbound = ArrayDeque<String>()
        val outbound = mutableListOf<String>()
        var closed = false
        var failWrites = false

        override fun readLine(): String? = inbound.removeFirstOrNull()

        override fun writeLine(line: String) {
            if (failWrites) error("write failed")
            outbound += line
        }

        override fun close() {
            closed = true
        }
    }

    private val codec = NdjsonCodec()

    @Test
    fun portAdapterCorrelatesRequestsAndResponses() {
        val channel = MemoryChannel()
        val transport = ContractIpcTransport(channel)
        val result = transport.send(IpcMessage.StatusRequest("status-1"))
        assertTrue(result.isSuccess)
        val request = codec.decode(channel.outbound.single())
        assertIs<JsonRpcMessage.Request>(request)
        assertEquals(IpcMethods.STATUS, request.method)
        assertEquals(JsonRpcId.StringId("status-1"), request.id)

        channel.inbound +=
            codec.encodeLine(
                JsonRpcMessage.Response(
                    JsonRpcId.StringId("status-1"),
                    IpcJson.encode(StatusResult(status = "ok", version = "2", activeDocuments = 3, suggestionQueue = 1)),
                ),
            )
        val received = transport.receive().getOrThrow()
        val message = assertIs<IpcReceive.Message>(received).message
        val response = assertIs<IpcMessage.StatusResponse>(message)
        assertEquals("status-1", response.requestId)
        assertEquals(true, response.status?.isInitialized)
        assertEquals(3, response.status?.indexedFiles)
        assertEquals(1, response.status?.knowledgeEntries)
    }

    @Test
    fun handshakeNegotiatesFromTheActualResponseProtocol() {
        val channel = MemoryChannel()
        val transport = ContractIpcTransport(channel)
        val handshake =
            IpcMessage.Handshake(
                schemaVersion = 1,
                fingerprint = ProtocolFingerprint.CURRENT,
                clientInfo = ClientInfo("renderer", "1"),
            )
        assertTrue(transport.send(handshake).isSuccess)
        val request = assertIs<JsonRpcMessage.Request>(codec.decode(channel.outbound.single()))
        assertEquals(5, request.params.getValue("protocol").jsonPrimitive.int)

        channel.inbound +=
            codec.encodeLine(
                JsonRpcMessage.Response(
                    request.id,
                    IpcJson.encode(InitializeResult(protocol = 4, root = "/tmp", languages = listOf("kotlin"))),
                ),
            )
        val ack = assertIs<IpcMessage.HandshakeAck>(assertIs<IpcReceive.Message>(transport.receive().getOrThrow()).message)
        assertTrue(ack.accepted)
        assertEquals(4, ack.schemaVersion)
        assertEquals(4, transport.negotiatedProtocol)
    }

    @Test
    fun publishPreservesRevisionsAndByteOffsets() {
        val channel = MemoryChannel()
        val transport = ContractIpcTransport(channel)
        val annotation =
            PublishedAnnotation(
                path = "file:///test/A.kt",
                span = Span(1, 2, 1, 8),
                intents = setOf("LOGGING"),
                role = Role.SOURCE_INTENT,
                confidence = Confidence.HIGH,
                provenance = Provenance.RULE,
                startByte = 22,
                endByte = 28,
            )
        val publish =
            IpcMessage.PublishRequest(
                requestId = "p1",
                annotations = listOf(annotation),
                uri = "file:///test/A.kt",
                version = 7,
                hash = "hash",
                configRevision = 11,
                analysisVersion = 12,
            )
        assertTrue(transport.send(publish).isSuccess)
        val notification = assertIs<JsonRpcMessage.Notification>(codec.decode(channel.outbound.single()))
        val params = notification.params
        assertEquals(11L, params.getValue("configRevision").jsonPrimitive.long)
        assertEquals(12L, params.getValue("analysisVersion").jsonPrimitive.long)
        assertEquals(7, params.getValue("version").jsonPrimitive.int)
        val wireAnnotation = params.getValue("annotations").jsonArray.single().jsonObject
        assertEquals(22, wireAnnotation.getValue("startByte").jsonPrimitive.int)
        assertEquals(28, wireAnnotation.getValue("endByte").jsonPrimitive.int)
        assertEquals(2, wireAnnotation.getValue("startCol").jsonPrimitive.int)
        assertEquals(8, wireAnnotation.getValue("endCol").jsonPrimitive.int)
    }

    @Test
    fun publishWithoutRevisionsOrBytesReturnsExplicitUnsupportedError() {
        val channel = MemoryChannel()
        val transport = ContractIpcTransport(channel)
        val publish =
            IpcMessage.PublishRequest(
                requestId = "p1",
                annotations =
                    listOf(
                        PublishedAnnotation(
                            "file:///test/A.kt",
                            Span(1, 2, 1, 8),
                            setOf("LOGGING"),
                            Role.SOURCE_INTENT,
                            Confidence.HIGH,
                            Provenance.RULE,
                            startByte = 0,
                            endByte = 4,
                        ),
                    ),
                uri = "file:///test/A.kt",
                version = 1,
                hash = "hash",
            )
        val result = transport.send(publish)
        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull?.toString()?.contains("configRevision") == true)
        assertTrue(channel.outbound.isEmpty())
    }

    @Test
    fun suggestOffsetIsWrittenOnlyAsByteOffsets() {
        val channel = MemoryChannel()
        val transport = ContractIpcTransport(channel)
        assertTrue(transport.send(IpcMessage.SuggestRequest("s1", "file:///test/A.kt", 42)).isSuccess)
        val request = assertIs<JsonRpcMessage.Request>(codec.decode(channel.outbound.single()))
        val selection = request.params.getValue("selection").jsonObject
        assertEquals(0, selection.getValue("startLine").jsonPrimitive.int)
        assertEquals(0, selection.getValue("startCol").jsonPrimitive.int)
        assertEquals(42, selection.getValue("startByte").jsonPrimitive.int)
        assertEquals(42, selection.getValue("endByte").jsonPrimitive.int)
    }

    @Test
    fun analyzeResponseMapsToContractsAnalysisResult() {
        val channel = MemoryChannel()
        val transport = ContractIpcTransport(channel)
        assertTrue(transport.send(IpcMessage.AnalyzeRequest("a1", listOf("file:///test/A.kt"))).isSuccess)
        val request = assertIs<JsonRpcMessage.Request>(codec.decode(channel.outbound.single()))

        val provenance =
            IpcProvenance(
                ruleId = "logging:logging",
                packId = "logging",
                role = "source-intent",
                intents = listOf("LOGGING"),
                source = "treesitter",
                confidence = 0.8,
                startLine = 1,
                startCol = 0,
                endLine = 1,
                endCol = 4,
                startByte = 0,
                endByte = 4,
            )
        val annotation =
            IpcAnnotation(
                role = "source-intent",
                intents = listOf("LOGGING"),
                source = "treesitter",
                confidence = 0.8,
                startLine = 1,
                startCol = 0,
                endLine = 1,
                endCol = 4,
                startByte = 0,
                endByte = 4,
                nodeAnchor = "node-1",
                provenance = listOf(provenance),
            )
        val candidate =
            IpcCandidate(
                candidateId = "c1",
                path = "file:///test/A.kt",
                contentHash = "hash",
                intents = listOf("NORMALIZATION"),
                startLine = 2,
                startCol = 0,
                endLine = 2,
                endCol = 4,
                startByte = 10,
                endByte = 14,
                confidence = 0.4,
                origin = CandidateOrigin("rule", ruleId = "normalization:normalization"),
                category = "pack-domain",
            )
        val wireResult =
            AnalyzeResult(
                files = listOf(AnalyzedFile("file:///test/A.kt", annotations = listOf(annotation))),
                candidates = listOf(candidate),
                diagnostics = listOf(IpcDiagnostic("parse", "bad")),
            )
        channel.inbound += codec.encodeLine(JsonRpcMessage.Response(request.id, IpcJson.encode(wireResult)))
        val response =
            assertIs<IpcMessage.AnalyzeResponse>(
                assertIs<IpcReceive.Message>(transport.receive().getOrThrow()).message,
            )
        val result = response.result ?: error("missing mapped result")
        assertEquals(1, result.annotations.size)
        assertEquals(Role.SOURCE_INTENT, result.annotations.single().role)
        assertEquals("logging:logging", result.annotations.single().provenance.single().rule)
        assertEquals("node-1", result.annotations.single().nodeAnchor?.id)
        assertEquals(1, result.candidates.size)
        assertEquals(CandidateReason.PACK_DOMAIN, result.candidates.single().reason)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun invalidAnalyzeResultReturnsExplicitErrorInsteadOfWire() {
        val channel = MemoryChannel()
        val transport = ContractIpcTransport(channel)
        assertTrue(transport.send(IpcMessage.AnalyzeRequest("a1", listOf("file:///test/A.kt"))).isSuccess)
        val request = assertIs<JsonRpcMessage.Request>(codec.decode(channel.outbound.single()))
        channel.inbound += codec.encodeLine(JsonRpcMessage.Response(request.id, JsonObject(mapOf("unexpected" to JsonPrimitive("shape")))))
        val received = transport.receive()
        assertTrue(received.isFailure)
        assertTrue(received.errorOrNull.toString().contains("AnalyzeResult"))
    }

    @Test
    fun writeFailureCleansPendingAndAdapterExceptionsDoNotEscapeSend() {
        val channel = MemoryChannel()
        channel.failWrites = true
        val transport = ContractIpcTransport(channel)
        val failed = transport.send(IpcMessage.AnalyzeRequest("a1", listOf("file:///test/A.kt")))
        assertTrue(failed.isFailure)

        channel.failWrites = false
        channel.inbound +=
            codec.encodeLine(
                JsonRpcMessage.Response(JsonRpcId.StringId("a1"), IpcJson.encode(AnalyzeResult())),
            )
        val received = assertIs<IpcReceive.Message>(transport.receive().getOrThrow()).message
        assertIs<IpcMessage.Wire>(received)

        val unsupported =
            transport.send(
                IpcMessage.PublishRequest(
                    "p1",
                    listOf(
                        PublishedAnnotation(
                            "file:///test/A.kt",
                            Span(1, 0, 1, 1),
                            setOf("LOGGING"),
                            Role.SOURCE_INTENT,
                            Confidence.HIGH,
                            Provenance.RULE,
                        ),
                    ),
                ),
            )
        assertTrue(unsupported.isFailure)
        assertTrue(channel.outbound.isEmpty())
    }

    @Test
    fun portDistinguishesEmptyClosedAndCancelled() {
        val channel = MemoryChannel()
        val transport = ContractIpcTransport(channel)
        channel.inbound += ""
        assertEquals(IpcReceive.Empty, transport.receive().getOrThrow())
        assertEquals(IpcReceive.Closed, transport.receive().getOrThrow())

        val cancelled = transport.receive(CancellationToken.fromBoolean(true))
        assertTrue(cancelled.isFailure)
        assertTrue(transport.close().isSuccess)
        assertTrue(channel.closed)
    }
}
