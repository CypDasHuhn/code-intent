package dev.cint.v2.lsp

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal object LspFraming {
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_MESSAGE_BYTES = 64 * 1024 * 1024

    fun read(input: InputStream): ByteArray? {
        val headers = LinkedHashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: return if (headers.isEmpty()) null else throw EOFException("LSP headers ended unexpectedly")
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) throw LspProtocolException("LSP header exceeds $MAX_HEADER_BYTES bytes")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) throw LspProtocolException("Malformed LSP header: $line")
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }

        val length =
            headers["content-length"]?.toIntOrNull()
                ?: throw LspProtocolException("Missing or invalid Content-Length header")
        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw LspProtocolException("Invalid LSP message length: $length")
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) throw EOFException("LSP message body ended after $read of $length bytes")
            read += count
        }
        return body
    }

    fun write(
        output: OutputStream,
        body: ByteArray,
    ) {
        val header = "Content-Length: ${body.size}\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                throw EOFException("LSP header line ended unexpectedly")
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }
}

internal class LspProtocolException(
    message: String,
) : RuntimeException(message)
