package dev.cint.suggest.transport

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal class DeepSeekChatTransport(
    private val baseUrl: String,
    private val timeoutMs: Long,
    private val apiKey: String,
    private val client: HttpClient,
    private val mapper: ObjectMapper,
) {
    fun post(payload: Map<String, Any?>): String? {
        val request =
            runCatching {
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(1L)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build()
            }.getOrNull() ?: return null
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: Exception) {
                return null
            }
        if (response.statusCode() !in 200..299) return null
        return response.body()
    }
}
