package dev.cint.server.suggest.support

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal data class ChatResponse(
    val status: Int,
    val body: String,
)

internal data class RecordedChatRequest(
    val method: String,
    val body: String,
)

internal class ChatEndpoint : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val executor = Executors.newCachedThreadPool()
    val requests = CopyOnWriteArrayList<RecordedChatRequest>()

    @Volatile
    var responder: (RecordedChatRequest) -> ChatResponse = { ChatResponse(200, """{"choices":[]}""") }

    val url: String
        get() = "http://127.0.0.1:${server.address.port}/chat/completions"

    init {
        server.createContext("/chat/completions") { exchange ->
            val body =
                exchange.requestBody.use {
                    String(it.readBytes(), StandardCharsets.UTF_8)
                }
            val recorded = RecordedChatRequest(exchange.requestMethod, body)
            requests += recorded
            val response = responder(recorded)
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = executor
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
