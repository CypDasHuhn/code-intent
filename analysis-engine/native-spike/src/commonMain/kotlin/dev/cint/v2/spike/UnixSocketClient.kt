package dev.cint.v2.spike

@kotlinx.serialization.Serializable
data class UnixSocketResult(
    val socketPath: String,
    val messageSent: String,
    val messageReceived: String,
    val success: Boolean,
    val error: String?,
)

interface UnixSocketClient {
    fun connect(socketPath: String): Boolean

    fun send(message: String): Boolean

    fun receive(): String?

    fun close()

    fun roundTrip(
        socketPath: String,
        message: String,
    ): UnixSocketResult
}

expect fun createUnixSocketClient(): UnixSocketClient
