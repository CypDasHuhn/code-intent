package dev.cint.server.suggest.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.server.LiveServer
import java.io.BufferedWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

internal class Peer(
    private val server: LiveServer,
) {
    private val mapper = jacksonObjectMapper()
    private val output = StringWriter()
    private val writer = BufferedWriter(output)

    fun send(message: String) {
        server.handleMessage(message, writer)
    }

    fun open(
        uri: String,
        text: String,
        version: Int,
    ) {
        send(
            """{"jsonrpc":"2.0","id":11,"method":"textDocument/didOpen","params":{"uri":${quote(
                uri,
            )},"text":${quote(text)},"version":$version,"languageId":"kotlin"}}""",
        )
    }

    fun change(
        uri: String,
        text: String,
        version: Int,
    ) {
        send(
            """{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"uri":${quote(
                uri,
            )},"text":${quote(text)},"version":$version,"languageId":"kotlin"}}""",
        )
    }

    fun suggest(
        uri: String,
        version: Int,
        id: Int,
        requestId: String,
    ) {
        send(
            """{"jsonrpc":"2.0","id":$id,"method":"cint/suggest","params":{"uri":${quote(
                uri,
            )},"version":$version,"requestId":${quote(requestId)}}}""",
        )
    }

    fun decide(
        uri: String,
        version: Int,
        id: Int,
        candidateId: String,
        decision: String,
    ) {
        send(
            """{"jsonrpc":"2.0","id":$id,"method":"cint/candidates/decide","params":{"uri":${quote(
                uri,
            )},"version":$version,"candidateId":${quote(candidateId)},"decision":${quote(decision)}}}""",
        )
    }

    fun messages(): List<JsonNode> =
        output
            .toString()
            .trim()
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { mapper.readTree(line) }.getOrNull() }

    fun latest(method: String): JsonNode = messages().last { it["method"]?.asText() == method }

    fun lastResponse(id: Int): JsonNode =
        messages()
            .last {
                it["id"]?.asInt() == id && it["method"] == null
            }.path("result")

    fun waitForResponse(id: Int): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            val response =
                messages().lastOrNull {
                    it["id"]?.asInt() == id && it["method"] == null
                }
            if (response != null) return response.path("result")
            Thread.sleep(10)
        }
        error("timed out waiting for response $id; messages=${messages()}")
    }

    fun waitForCandidate(
        uri: String,
        intent: String,
        predicate: (JsonNode) -> Boolean,
    ): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            val candidate =
                messages()
                    .filter { it["method"]?.asText() == "cint/publishCandidates" }
                    .flatMap { message ->
                        message.path("params").path("candidates").filter { candidate ->
                            candidate.path("intents").any { it.asText() == intent } &&
                                candidate.path("path").asText().isNotEmpty() &&
                                predicate(candidate)
                        }
                    }.lastOrNull()
            if (candidate != null) return candidate
            Thread.sleep(10)
        }
        error("timed out waiting for candidate on $uri; messages=${messages()}")
    }

    private fun quote(value: String): String = mapper.writeValueAsString(value)
}
