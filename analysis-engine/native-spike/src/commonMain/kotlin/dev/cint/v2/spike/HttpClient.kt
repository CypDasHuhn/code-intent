package dev.cint.v2.spike

@kotlinx.serialization.Serializable
data class HttpResult(
    val url: String,
    val method: String,
    val statusCode: Int?,
    val responseBody: String?,
    val success: Boolean,
    val error: String?,
    val tlsUsed: Boolean,
)

interface HttpClient {
    fun get(url: String): HttpResult

    fun post(
        url: String,
        body: String?,
    ): HttpResult

    fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpResult
}

expect fun createHttpClient(): HttpClient
