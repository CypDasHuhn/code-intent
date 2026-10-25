package dev.cint.v2.spike

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer
import java.nio.file.Paths

actual fun createUnixSocketClient(): UnixSocketClient = JvmUnixSocketClient()

private class JvmUnixSocketClient : UnixSocketClient {
    private var channel: SocketChannel? = null

    override fun connect(socketPath: String): Boolean {
        return try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            val address = UnixDomainSocketAddress.of(Paths.get(socketPath))
            channel?.connect(address)
            channel != null
        } catch (e: Exception) {
            false
        }
    }

    override fun send(message: String): Boolean {
        return try {
            channel?.write(ByteBuffer.wrap(message.toByteArray())) != null
        } catch (e: Exception) {
            false
        }
    }

    override fun receive(): String? {
        return try {
            val buffer = ByteBuffer.allocate(4096)
            val read = channel?.read(buffer)
            if (read == null || read <= 0) return null
            buffer.flip()
            String(buffer.array(), 0, read)
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        channel?.close()
        channel = null
    }

    override fun roundTrip(socketPath: String, message: String): UnixSocketResult {
        val success = connect(socketPath)
        if (!success) {
            return UnixSocketResult(
                socketPath = socketPath,
                messageSent = message,
                messageReceived = "",
                success = false,
                error = "Failed to connect to socket: $socketPath"
            )
        }

        val sendSuccess = send(message)
        if (!sendSuccess) {
            close()
            return UnixSocketResult(
                socketPath = socketPath,
                messageSent = message,
                messageReceived = "",
                success = false,
                error = "Failed to send message"
            )
        }

        val received = receive()
        close()

        return UnixSocketResult(
            socketPath = socketPath,
            messageSent = message,
            messageReceived = received ?: "",
            success = received != null,
            error = if (received == null) "No response received" else null
        )
    }
}