package dev.cint.ipc

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonRpcCodecTest {
    private val codec = JsonRpcCodec()

    @Test
    fun decodesAndRoundTripsV1RequestNotificationResponseAndError() {
        val request = codec.decode("""{"jsonrpc":"2.0","id":7,"method":"cint/analyze","params":{"uri":"file:///test/A.kt"}}""")
        assertIs<JsonRpcMessage.Request>(request)
        assertEquals(JsonRpcId.NumberId(7), request.id)
        assertEquals("cint/analyze", request.method)
        assertEquals("file:///test/A.kt", request.params.getValue("uri").jsonPrimitive.content)
        assertEquals(request, codec.decode(codec.encode(request)))

        val notification = codec.decode("""{"jsonrpc":"2.0","method":"textDocument/didClose","params":{"uri":"file:///test/A.kt"}}""")
        assertIs<JsonRpcMessage.Notification>(notification)
        assertEquals(notification, codec.decode(codec.encode(notification)))

        val response = codec.decode("""{"jsonrpc":"2.0","id":"abc","result":true}""")
        assertIs<JsonRpcMessage.Response>(response)
        assertEquals(JsonRpcId.StringId("abc"), response.id)
        assertEquals(JsonPrimitive(true), response.result)
        assertEquals(response, codec.decode(codec.encode(response)))

        val error = codec.decode("""{"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"unknown method: cint/status"}}""")
        assertIs<JsonRpcMessage.ErrorResponse>(error)
        assertEquals(-32000, error.error.code)
        assertEquals(error, codec.decode(codec.encode(error)))
    }

    @Test
    fun acceptsNullIdOnlyForResponsesAndErrors() {
        val response = codec.decode("""{"jsonrpc":"2.0","id":null,"result":null}""")
        assertIs<JsonRpcMessage.Response>(response)
        assertEquals(JsonRpcId.NullId, response.id)
        assertEquals(JsonNull, response.result)
    }

    @Test
    fun rejectsMalformedJsonAndInvalidEnvelopes() {
        assertEquals(-32700, assertFailsWith<IpcProtocolException> { codec.decode("{") }.rpcError.code)
        assertEquals(-32600, assertFailsWith<IpcProtocolException> { codec.decode("[]") }.rpcError.code)
        assertEquals(
            -32600,
            assertFailsWith<IpcProtocolException> {
                codec.decode("""{"jsonrpc":"1.0","id":1,"method":"x"}""")
            }.rpcError.code,
        )
        assertEquals(
            -32600,
            assertFailsWith<IpcProtocolException> {
                codec.decode("""{"jsonrpc":"2.0","id":1,"method":"x","extra":true}""")
            }.rpcError.code,
        )
        assertEquals(
            -32600,
            assertFailsWith<IpcProtocolException> {
                codec.decode("""{"jsonrpc":"2.0","id":1}""")
            }.rpcError.code,
        )
        assertEquals(
            -32600,
            assertFailsWith<IpcProtocolException> {
                codec.decode("""{"jsonrpc":"2.0","id":1,"method":"x","result":true}""")
            }.rpcError.code,
        )
        assertEquals(
            -32600,
            assertFailsWith<IpcProtocolException> {
                codec.decode("""{"jsonrpc":"2.0","id":1,"result":true,"error":{"code":1,"message":"x"}}""")
            }.rpcError.code,
        )
        assertEquals(
            -32600,
            assertFailsWith<IpcProtocolException> {
                codec.decode("""{"jsonrpc":"2.0","result":true}""")
            }.rpcError.code,
        )
        assertEquals(
            -32600,
            assertFailsWith<IpcProtocolException> {
                codec.decode("""{"jsonrpc":"2.0","id":1.5,"method":"x"}""")
            }.rpcError.code,
        )
        assertEquals(
            -32600,
            assertFailsWith<IpcProtocolException> {
                codec.decode("""{"jsonrpc":"2.0","id":1,"error":{"code":"bad","message":"x"}}""")
            }.rpcError.code,
        )
        assertEquals(
            -32602,
            assertFailsWith<IpcProtocolException> {
                codec.decode("""{"jsonrpc":"2.0","id":1,"method":"x","params":[]}""")
            }.rpcError.code,
        )
    }

    @Test
    fun encodesStructuredErrorData() {
        val error =
            JsonRpcMessage.ErrorResponse(
                JsonRpcId.NumberId(4),
                JsonRpcError(-32601, "unknown method: nope", JsonObject(mapOf("kind" to JsonPrimitive("unknown-method")))),
            )
        val decoded = codec.decode(codec.encode(error))
        assertIs<JsonRpcMessage.ErrorResponse>(decoded)
        assertEquals(
            "unknown-method",
            decoded.error.data!!.jsonObject.getValue("kind").jsonPrimitive.content,
        )
    }

    @Test
    fun rejectsUnknownErrorFields() {
        val error = assertFailsWith<IpcProtocolException> {
            codec.decode("""{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"x","extra":1}}""")
        }
        assertTrue(error.rpcError.message.contains("unknown JSON-RPC error field"))
    }
}
