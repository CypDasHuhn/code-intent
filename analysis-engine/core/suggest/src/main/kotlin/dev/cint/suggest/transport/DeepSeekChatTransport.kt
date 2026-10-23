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
    var lastFailure: String? = null
        private set

    fun post(payload: Map<String, Any?>): String? {
        lastFailure = null
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
            }.getOrNull() ?: run {
                lastFailure = "invalid provider URL"
                return null
            }
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                lastFailure = "request interrupted"
                return null
            } catch (error: Exception) {
                lastFailure = "request error: ${error.javaClass.simpleName}"
                return null
            }
        val status = response.statusCode()
        if (status !in 200..299) {
            val detail =
                response
                    .body()
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(200)
            lastFailure = if (detail.isEmpty()) "HTTP $status" else "HTTP $status: $detail"
            return null
        }
        return response.body()
    }
}
