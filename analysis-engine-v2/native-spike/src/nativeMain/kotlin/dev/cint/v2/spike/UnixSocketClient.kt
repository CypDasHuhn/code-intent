package dev.cint.v2.spike

import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual fun createUnixSocketClient(): UnixSocketClient = NativeUnixSocketClient()

@OptIn(ExperimentalForeignApi::class)
private class NativeUnixSocketClient : UnixSocketClient {
    override fun connect(socketPath: String): Boolean {
        // Stub implementation for native spike
        return false
    }

    override fun send(message: String): Boolean {
        return false
    }

    override fun receive(): String? {
        return null
    }

    override fun close() {
    }

    override fun roundTrip(socketPath: String, message: String): UnixSocketResult {
        return UnixSocketResult(
            socketPath = socketPath,
            messageSent = message,
            messageReceived = "",
            success = false,
            error = "Unix socket not implemented in native spike"
        )
    }
}