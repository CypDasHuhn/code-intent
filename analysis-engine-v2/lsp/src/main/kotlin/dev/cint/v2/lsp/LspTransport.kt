package dev.cint.v2.lsp

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface LspTransport : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    val description: String

    fun diagnosticText(): String? = null

    override fun close()
}

class StdioTransport(
    command: List<String>,
    environment: Map<String, String> = emptyMap(),
) : LspTransport {
    private val closed = AtomicBoolean(false)
    private val command: List<String> = command.toList()
    private val process: Process
    private val stderr = StringBuilder()
    private val stderrPump: Thread

    override val input: InputStream
    override val output: OutputStream
    override val description: String = "stdio ${this.command.joinToString(" ")}"

    init {
        require(this.command.isNotEmpty()) { "stdio transport requires a command" }
        val builder = ProcessBuilder(this.command)
        builder.environment().putAll(environment)
        process = builder.start()
        input = process.inputStream
        output = process.outputStream
        stderrPump =
            Thread({ pumpStderr() }, "cint-lsp-stderr-${process.pid()}").apply {
                isDaemon = true
                start()
            }
    }

    private fun pumpStderr() {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val count = process.errorStream.read(buffer)
                if (count < 0) return
                synchronized(stderr) {
                    if (stderr.length < MAX_DIAGNOSTIC_CHARS) {
                        stderr.append(String(buffer, 0, count, StandardCharsets.UTF_8))
                        if (stderr.length > MAX_DIAGNOSTIC_CHARS) {
                            stderr.setLength(MAX_DIAGNOSTIC_CHARS)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun diagnosticText(): String? =
        synchronized(stderr) {
            stderr.toString().trim().takeIf { it.isNotEmpty() }
        }

    internal fun processAlive(): Boolean = process.isAlive

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        process.destroy()
        if (!process.waitFor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
        }
        stderrPump.join(200)
    }

    private companion object {
        const val MAX_DIAGNOSTIC_CHARS = 8192
        const val CLOSE_GRACE_MS = 1000L
    }
}

internal class ManagedEndpointTransport(
    command: List<String>,
    private val endpoint: LspEndpoint,
    connectTimeoutMs: Long,
) : LspTransport {
    private val closed = AtomicBoolean(false)
    private val stderr = StringBuilder()
    private val process: Process = ProcessBuilder(command).start()
    private val stderrPump =
        Thread({ pumpStderr() }, "cint-lsp-stderr-${process.pid()}").apply {
            isDaemon = true
            start()
        }
    private val delegate: LspTransport

    override val input: InputStream
    override val output: OutputStream
    override val description: String = "managed:$endpoint"

    init {
        try {
            delegate = connectWithRetry(connectTimeoutMs)
        } catch (error: Throwable) {
            closeProcess()
            throw error
        }
        input = delegate.input
        output = delegate.output
    }

    private fun connectWithRetry(timeoutMs: Long): LspTransport {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                return when (endpoint) {
                    is LspEndpoint.Unix -> UnixSocketTransport(endpoint.path)
                    is LspEndpoint.Tcp -> TcpSocketTransport(endpoint.host, endpoint.port)
                }
            } catch (error: Throwable) {
                last = error
                Thread.sleep(50)
            }
        }
        throw last ?: java.io.IOException("Timed out connecting to $endpoint")
    }

    private fun pumpStderr() {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val count = process.errorStream.read(buffer)
                if (count < 0) return
                synchronized(stderr) {
                    if (stderr.length < MAX_DIAGNOSTIC_CHARS) {
                        stderr.append(String(buffer, 0, count, StandardCharsets.UTF_8))
                        if (stderr.length > MAX_DIAGNOSTIC_CHARS) stderr.setLength(MAX_DIAGNOSTIC_CHARS)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun diagnosticText(): String? =
        synchronized(stderr) {
            stderr.toString().trim().takeIf { it.isNotEmpty() }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { delegate.close() }
        closeProcess()
        stderrPump.join(200)
    }

    private fun closeProcess() {
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        process.destroy()
        if (!process.waitFor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val MAX_DIAGNOSTIC_CHARS = 8192
        const val CLOSE_GRACE_MS = 1000L
    }
}

class UnixSocketTransport(
    path: Path,
) : LspTransport {
    private val closed = AtomicBoolean(false)
    private val channel: SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX)

    override val input: InputStream
    override val output: OutputStream
    override val description: String = "unix:$path"

    init {
        try {
            channel.connect(UnixDomainSocketAddress.of(path))
        } catch (error: Throwable) {
            runCatching { channel.close() }
            throw error
        }
        input = Channels.newInputStream(channel)
        output = Channels.newOutputStream(channel)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { channel.close() }
    }
}

class TcpSocketTransport(
    host: String,
    port: Int,
    connectTimeoutMs: Int = 5000,
) : LspTransport {
    private val closed = AtomicBoolean(false)
    private val socket = Socket()

    override val input: InputStream
    override val output: OutputStream
    override val description: String = "tcp:$host:$port"

    init {
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }
}

internal sealed interface LspEndpoint {
    data class Unix(
        val path: Path,
    ) : LspEndpoint

    data class Tcp(
        val host: String,
        val port: Int,
    ) : LspEndpoint

    companion object {
        fun parse(raw: String): LspEndpoint {
            val endpoint = raw.trim()
            if (endpoint.isEmpty()) throw IllegalArgumentException("Empty LSP endpoint")
            if (endpoint.startsWith("unix:")) {
                var path = endpoint.removePrefix("unix:")
                if (path.startsWith("//")) path = path.removePrefix("//")
                if (path.isEmpty()) throw IllegalArgumentException("Unix endpoint requires a socket path: $raw")
                return Unix(Path.of(path))
            }
            if (endpoint.startsWith("tcp:")) {
                val remainder = endpoint.removePrefix("tcp:")
                if (remainder.startsWith("//")) {
                    val uri = java.net.URI(endpoint)
                    val host = uri.host ?: throw IllegalArgumentException("TCP endpoint requires a host: $raw")
                    val port = uri.port.takeIf { it > 0 } ?: throw IllegalArgumentException("TCP endpoint requires a port: $raw")
                    return Tcp(host, port)
                }
                return parseHostPort(remainder, raw)
            }
            return parseHostPort(endpoint, raw)
        }

        private fun parseHostPort(
            value: String,
            raw: String,
        ): Tcp {
            val separator = value.lastIndexOf(':')
            if (separator <= 0 || separator == value.lastIndex) {
                throw IllegalArgumentException("Expected tcp:host:port or host:port but got: $raw")
            }
            val host = value.substring(0, separator).removeSurrounding("[", "]")
            val port =
                value.substring(separator + 1).toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid TCP port in endpoint: $raw")
            if (port !in 1..65535) throw IllegalArgumentException("TCP port out of range in endpoint: $raw")
            return Tcp(host, port)
        }
    }
}

internal object CommandLine {
    fun parse(command: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        var quoteChar = ' '
        var escaped = false
        var tokenStarted = false

        for (char in command) {
            if (escaped) {
                token.append(char)
                escaped = false
                tokenStarted = true
                continue
            }
            if (char == '\\' && !quoted) {
                escaped = true
                tokenStarted = true
                continue
            }
            if (quoted) {
                if (char == quoteChar) {
                    quoted = false
                } else {
                    token.append(char)
                }
                tokenStarted = true
                continue
            }
            when {
                char == '"' || char == '\'' -> {
                    quoted = true
                    quoteChar = char
                    tokenStarted = true
                }

                char.isWhitespace() -> {
                    if (tokenStarted) {
                        result.add(token.toString())
                        token.setLength(0)
                        tokenStarted = false
                    }
                }

                else -> {
                    token.append(char)
                    tokenStarted = true
                }
            }
        }
        if (quoted) throw IllegalArgumentException("Unterminated quote in command: $command")
        if (escaped) token.append('\\')
        if (tokenStarted) result.add(token.toString())
        if (result.isEmpty()) throw IllegalArgumentException("Empty command")
        return result
    }
}
