package dev.cint.v2.server

import dev.cint.contracts.ports.Runtime
import dev.cint.ipc.IpcConnection
import dev.cint.ipc.JsonRpcMessage
import dev.cint.ipc.NdjsonCodec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

internal class ClientSession(
    private val channel: SocketChannel,
    runtime: Runtime,
    options: ServerOptions,
    codec: NdjsonCodec,
    gate: RuntimeGate,
    private val server: LiveServer,
    private val onClosed: (ClientSession) -> Unit,
) : Runnable {
    private val reader = BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))
    private val writer = BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))
    private val writeLock = Any()
    private val handler = SessionHandler(runtime, options, this, server, gate)
    private val connection = IpcConnection(handler)
    private val codec = codec

    @Volatile
    private var closed = false

    override fun run() {
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val messages = connection.feed(line + "\n")
                write(messages)
                if (handler.shutdownRequested) {
                    server.requestShutdown()
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    fun deliver(messages: List<JsonRpcMessage>) {
        if (messages.isEmpty() || closed) return
        runCatching { write(messages) }.onFailure { close() }
    }

    fun activeDocumentCount(): Int = handler.activeDocumentCount()

    fun close() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
        runCatching { handler.close() }
        runCatching { connection.disconnect() }
        runCatching { reader.close() }
        runCatching { writer.close() }
        onClosed(this)
    }

    private fun write(messages: List<JsonRpcMessage>) {
        if (messages.isEmpty()) return
        synchronized(writeLock) {
            if (closed) return
            for (message in messages) {
                writer.write(codec.encodeLine(message))
            }
            writer.flush()
        }
    }

    internal fun isClosed(): Boolean = closed
}
