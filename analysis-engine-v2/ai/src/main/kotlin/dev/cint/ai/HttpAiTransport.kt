package dev.cint.ai

import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

class HttpAiTransport(
    private val endpoint: URI,
    private val apiKey: String? = null,
    private val timeoutMs: Long = 5_000,
    private val maxResponseBytes: Int = 1_000_000,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs.coerceAtLeast(1L)))
        .build(),
) : AiTransport {
    init {
        require(maxResponseBytes > 0) { "HttpAiTransport.maxResponseBytes must be positive" }
    }

    override fun post(
        body: String,
        cancellation: CancellationToken,
    ): ContractResult<AiHttpResponse> {
        val cancelled = cancellation.throwIfCancelled()
        if (cancelled.isFailure) return ResultFactory.failure(cancelled.errorOrNull!!)
        val request = runCatching {
            val builder = HttpRequest
                .newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(1L)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
            if (!apiKey.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            builder
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
        }.getOrElse {
            return ResultFactory.failure(
                ContractError.InvalidInput("Invalid AI provider endpoint", endpoint.toString()),
            )
        }
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        while (!future.isDone) {
            if (cancellation.isCancelled) {
                future.cancel(true)
                return ResultFactory.failure(ContractError.Cancelled("fallback-ai"))
            }
            try {
                Thread.sleep(CANCELLATION_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                future.cancel(true)
                return ResultFactory.failure(ContractError.Cancelled("fallback-ai"))
            }
        }
        if (cancellation.isCancelled) {
            future.cancel(true)
            return ResultFactory.failure(ContractError.Cancelled("fallback-ai"))
        }
        val response = try {
            future.get()
        } catch (_: CancellationException) {
            return ResultFactory.failure(ContractError.Cancelled("fallback-ai"))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            return ResultFactory.failure(ContractError.Cancelled("fallback-ai"))
        } catch (error: ExecutionException) {
            return ResultFactory.failure(
                ContractError.DependencyUnavailable(
                    "fallback-ai",
                    "provider request failed: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}",
                ),
            )
        }
        val bytes = try {
            response.body().use { readBody(it, cancellation) }
        } catch (error: Exception) {
            return ResultFactory.failure(
                ContractError.DependencyUnavailable(
                    "fallback-ai",
                    "response read failed: ${error.javaClass.simpleName}",
                ),
            )
        }
        if (bytes.isFailure) return ResultFactory.failure(bytes.errorOrNull!!)
        val bodyText = String(bytes.getOrThrow(), StandardCharsets.UTF_8)
        if (response.statusCode() !in 200..299) {
            val detail = bodyText.replace(Regex("\\s+"), " ").trim().take(200)
            return ResultFactory.failure(
                ContractError.DependencyUnavailable(
                    "fallback-ai",
                    if (detail.isEmpty()) "HTTP ${response.statusCode()}" else "HTTP ${response.statusCode()}: $detail",
                ),
            )
        }
        return ResultFactory.success(AiHttpResponse(response.statusCode(), bodyText))
    }

    private fun readBody(
        input: InputStream,
        cancellation: CancellationToken,
    ): ContractResult<ByteArray> {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            if (cancellation.isCancelled) {
                return ResultFactory.failure(ContractError.Cancelled("fallback-ai"))
            }
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > maxResponseBytes) {
                return ResultFactory.failure(
                    ContractError.SerializationError(
                        "AI provider response exceeds $maxResponseBytes bytes",
                    ),
                )
            }
            output.write(buffer, 0, read)
        }
        return ResultFactory.success(output.toByteArray())
    }

    private companion object {
        const val CANCELLATION_POLL_MS = 10L
        const val BUFFER_BYTES = 8_192
    }
}
