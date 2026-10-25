package dev.cint.ipc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface NdjsonFrame {
    data class Text(
        val line: String,
    ) : NdjsonFrame

    data class Invalid(
        val error: JsonRpcError,
    ) : NdjsonFrame
}

class NdjsonFrameDecoder(
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {
    private val buffer = ByteArrayOutputStream()
    private var discardingOversized = false

    fun push(chunk: ByteArray): List<NdjsonFrame> {
        val frames = mutableListOf<NdjsonFrame>()
        for (byte in chunk) {
            if (byte == NEWLINE) {
                finishFrame()?.let { frames += it }
            } else if (!discardingOversized) {
                buffer.write(byte.toInt())
                if (buffer.size() > maxFrameBytes) {
                    buffer.reset()
                    discardingOversized = true
                }
            }
        }
        return frames
    }

    fun push(chunk: String): List<NdjsonFrame> = push(chunk.toByteArray(StandardCharsets.UTF_8))

    fun finish(): List<NdjsonFrame> {
        val frames = mutableListOf<NdjsonFrame>()
        if (discardingOversized) {
            frames += NdjsonFrame.Invalid(oversizedError())
            discardingOversized = false
            buffer.reset()
        } else if (buffer.size() > 0) {
            finishFrame()?.let { frames += it }
        }
        return frames
    }

    private fun finishFrame(): NdjsonFrame? {
        if (discardingOversized) {
            discardingOversized = false
            buffer.reset()
            return NdjsonFrame.Invalid(oversizedError())
        }
        val bytes = buffer.toByteArray()
        buffer.reset()
        if (bytes.isEmpty()) return null
        val withoutCarriageReturn =
            if (bytes.last() == CARRIAGE_RETURN) bytes.copyOf(bytes.size - 1) else bytes
        if (withoutCarriageReturn.isEmpty()) return null
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(withoutCarriageReturn))
                    .toString()
            } catch (error: Exception) {
                return NdjsonFrame.Invalid(IpcErrors.parse("invalid UTF-8 frame").rpcError)
            }
        return if (text.isBlank()) null else NdjsonFrame.Text(text)
    }

    private fun oversizedError(): JsonRpcError = IpcErrors.parse("NDJSON frame exceeds $maxFrameBytes bytes").rpcError

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES: Int = 1 shl 20
        private const val NEWLINE = '\n'.code.toByte()
        private const val CARRIAGE_RETURN = '\r'.code.toByte()
    }
}

class NdjsonCodec(
    private val codec: JsonRpcCodec = JsonRpcCodec(),
    private val maxFrameBytes: Int = NdjsonFrameDecoder.DEFAULT_MAX_FRAME_BYTES,
) {
    fun decode(line: String): JsonRpcMessage = codec.decode(line)

    fun encode(message: JsonRpcMessage): String = codec.encode(message)

    fun encodeLine(message: JsonRpcMessage): String {
        val encoded = codec.encode(message)
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= maxFrameBytes) {
            "encoded NDJSON frame exceeds $maxFrameBytes bytes"
        }
        return encoded + "\n"
    }

    fun encodeLines(messages: Iterable<JsonRpcMessage>): String =
        buildString {
            for (message in messages) append(encodeLine(message))
        }

    fun newDecoder(): NdjsonFrameDecoder = NdjsonFrameDecoder(maxFrameBytes)
}
