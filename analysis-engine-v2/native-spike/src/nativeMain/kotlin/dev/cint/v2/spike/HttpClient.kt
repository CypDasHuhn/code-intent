package dev.cint.v2.spike

import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual fun createHttpClient(): HttpClient = NativeHttpClient()

@OptIn(ExperimentalForeignApi::class)
private class NativeHttpClient : HttpClient {
    override fun get(url: String): HttpResult {
        // Stub implementation for native spike
        return HttpResult(
            url = url,
            method = "GET",
            statusCode = null,
            responseBody = null,
            success = false,
            error = "HTTP client not implemented in native spike",
            tlsUsed = url.startsWith("https://")
        )
    }

    override fun post(url: String, body: String?): HttpResult {
        return HttpResult(
            url = url,
            method = "POST",
            statusCode = null,
            responseBody = null,
            success = false,
            error = "HTTP client not implemented in native spike",
            tlsUsed = url.startsWith("https://")
        )
    }

    override fun request(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult {
        return HttpResult(
            url = url,
            method = method,
            statusCode = null,
            responseBody = null,
            success = false,
            error = "HTTP client not implemented in native spike",
            tlsUsed = url.startsWith("https://")
        )
    }
}