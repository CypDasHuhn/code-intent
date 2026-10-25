package dev.cint.ipc

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NdjsonFramingTest {
    private val codec = NdjsonCodec()

    @Test
    fun decodesFragmentedUtf8AndCrlf() {
        val text = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"caf\u00e9\",\"params\":{}}\r\n"
        val decoder = NdjsonFrameDecoder()
        val frames = mutableListOf<NdjsonFrame>()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            frames += decoder.push(byteArrayOf(byte))
        }
        val frame = assertIs<NdjsonFrame.Text>(frames.single())
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"caf\u00e9\",\"params\":{}}", frame.line)
        assertEquals("caf\u00e9", codec.decode(frame.line).let { (it as JsonRpcMessage.Request).method })
    }

    @Test
    fun decodesMultipleLinesAndIgnoresBlankLines() {
        val decoder = NdjsonFrameDecoder()
        val frames = decoder.push("\n{\"a\":1}\r\n\n{\"b\":2}\n")
        assertEquals(2, frames.size)
        assertEquals("{\"a\":1}", (frames[0] as NdjsonFrame.Text).line)
        assertEquals("{\"b\":2}", (frames[1] as NdjsonFrame.Text).line)
    }

    @Test
    fun flushesTrailingLineAtEof() {
        val decoder = NdjsonFrameDecoder()
        assertEquals(emptyList(), decoder.push("{\"a\":"))
        val frames = decoder.finish()
        assertEquals("{\"a\":", (frames.single() as NdjsonFrame.Text).line)
        assertEquals(emptyList(), decoder.finish())
    }

    @Test
    fun rejectsInvalidUtf8() {
        val decoder = NdjsonFrameDecoder()
        val frames = decoder.push(byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte()))
        val invalid = assertIs<NdjsonFrame.Invalid>(frames.single())
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, invalid.error.code)
    }

    @Test
    fun discardsOversizedFrameAndResynchronizes() {
        val decoder = NdjsonFrameDecoder(maxFrameBytes = 8)
        val frames = decoder.push("123456789\n{\"ok\":1}\n")
        assertEquals(2, frames.size)
        assertIs<NdjsonFrame.Invalid>(frames[0])
        assertEquals("{\"ok\":1}", (frames[1] as NdjsonFrame.Text).line)
    }

    @Test
    fun reportsUnterminatedOversizedFrameAtEof() {
        val decoder = NdjsonFrameDecoder(maxFrameBytes = 4)
        assertTrue(decoder.push("12345").isEmpty())
        val invalid = assertIs<NdjsonFrame.Invalid>(decoder.finish().single())
        assertTrue(invalid.error.message.contains("exceeds"))
    }

    @Test
    fun encodesOneLinePerMessage() {
        val request = JsonRpcMessage.Request(JsonRpcId.NumberId(1), IpcMethods.STATUS)
        val line = codec.encodeLine(request)
        assertTrue(line.endsWith("\n"))
        assertEquals(request, codec.decode(line.removeSuffix("\n")))
        assertEquals(
            2,
            codec
                .encodeLines(listOf(request, request))
                .lines()
                .filter { it.isNotBlank() }
                .size,
        )
    }

    @Test
    fun responseRoundTripsThroughTheFramingLayer() {
        val response = JsonRpcMessage.Response(JsonRpcId.NumberId(9), JsonPrimitive(true))
        val decoder = NdjsonFrameDecoder()
        val frame = assertIs<NdjsonFrame.Text>(decoder.push(codec.encodeLine(response)).single())
        assertEquals(response, codec.decode(frame.line))
    }
}
