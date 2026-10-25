package dev.cint.v2.spike

import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking

actual fun createHttpClient(): HttpClient = JvmHttpClient()

private class JvmHttpClient : HttpClient {
    private val client = HttpClient(Apache)

    override fun get(url: String): HttpResult {
        return runBlocking {
            try {
                val response = client.get(url)
                val body = response.bodyAsText()
                HttpResult(
                    url = url,
                    method = "GET",
                    statusCode = response.status.value,
                    responseBody = body,
                    success = response.status.isSuccess(),
                    error = null,
                    tlsUsed = url.startsWith("https://")
                )
            } catch (e: Exception) {
                HttpResult(
                    url = url,
                    method = "GET",
                    statusCode = null,
                    responseBody = null,
                    success = false,
                    error = e.message,
                    tlsUsed = url.startsWith("https://")
                )
            }
        }
    }

    override fun post(url: String, body: String?): HttpResult {
        return runBlocking {
            try {
                val response = client.post(url) {
                    if (body != null) {
                        setBody(body)
                    }
                }
                val responseBody = response.bodyAsText()
                HttpResult(
                    url = url,
                    method = "POST",
                    statusCode = response.status.value,
                    responseBody = responseBody,
                    success = response.status.isSuccess(),
                    error = null,
                    tlsUsed = url.startsWith("https://")
                )
            } catch (e: Exception) {
                HttpResult(
                    url = url,
                    method = "POST",
                    statusCode = null,
                    responseBody = null,
                    success = false,
                    error = e.message,
                    tlsUsed = url.startsWith("https://")
                )
            }
        }
    }

    override fun request(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult {
        return runBlocking {
            try {
                val httpMethod = HttpMethod(method.uppercase())
                val response = client.request {
                    this.method = httpMethod
                    url(url)
                    headers.forEach { (key, value) -> this.headers.append(key, value) }
                    if (body != null) {
                        setBody(body)
                    }
                }
                val responseBody = response.bodyAsText()
                HttpResult(
                    url = url,
                    method = method,
                    statusCode = response.status.value,
                    responseBody = responseBody,
                    success = response.status.isSuccess(),
                    error = null,
                    tlsUsed = url.startsWith("https://")
                )
            } catch (e: Exception) {
                HttpResult(
                    url = url,
                    method = method,
                    statusCode = null,
                    responseBody = null,
                    success = false,
                    error = e.message,
                    tlsUsed = url.startsWith("https://")
                )
            }
        }
    }
}