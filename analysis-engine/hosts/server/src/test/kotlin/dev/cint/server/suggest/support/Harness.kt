package dev.cint.server.suggest.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.host.HostPipeline
import dev.cint.host.HostSettings
import dev.cint.server.LiveServer
import dev.cint.server.SuggestionSettings
import dev.cint.server.SuggestionSettingsLoader
import dev.cint.server.live.rewriteDocumentTextForTest
import dev.cint.suggest.SuggestionService
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class Harness(
    root: Path,
    settings: HostSettings,
    pipeline: HostPipeline,
    suggestionSettings: SuggestionSettings?,
    service: SuggestionService,
) {
    private val mapper = jacksonObjectMapper()
    private val output = StringWriter()
    private val writer = BufferedWriter(output)
    private val lock = Any()
    val server =
        LiveServer(
            root = root,
            socketPath = Path.of("/tmp/opencode/cint-036-test.sock"),
            useLsp = false,
            defaultLanguage = "kotlin",
            packs = pipeline,
            settings = settings,
            suggestionSettings = suggestionSettings ?: SuggestionSettingsLoader.load(root),
            suggestionService = service,
        )

    fun send(message: String) {
        synchronized(lock) {
            server.handleMessage(message, writer)
        }
    }

    fun sendFrom(
        target: BufferedWriter,
        message: String,
    ) {
        server.handleMessage(message, target)
    }

    fun sendDirect(message: String) {
        server.handleMessage(message, writer)
    }

    fun newWriter(): BufferedWriter = BufferedWriter(StringWriter())

    fun rewriteDocumentTextForTest(
        uri: String,
        text: String,
    ) {
        server.rewriteDocumentTextForTest(writer, uri, text)
    }

    fun configAppendMessage(
        packId: String,
        language: String,
        field: String,
        value: String,
        id: Int,
    ): String =
        """{"jsonrpc":"2.0","id":$id,"method":"cint/config/append","params":{"packId":${quote(
            packId,
        )},"language":${quote(language)},"field":${quote(field)},"value":${quote(value)}}}"""

    fun initialize(protocol: Int = 4) {
        send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocol":$protocol}}""")
    }

    fun initializeResult(): JsonNode = lastResponse(1)

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

    fun analyze(
        uri: String,
        id: Int,
    ) {
        send("""{"jsonrpc":"2.0","id":$id,"method":"cint/analyze","params":{"uri":${quote(uri)}}}""")
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

    fun cancel(
        uri: String,
        id: Int,
        requestId: String,
    ) {
        send(
            """{"jsonrpc":"2.0","id":$id,"method":"cint/suggest/cancel","params":{"uri":${quote(
                uri,
            )},"requestId":${quote(requestId)}}}""",
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

    fun configAppend(
        packId: String,
        language: String,
        field: String,
        value: String,
        id: Int,
    ) {
        send(
            """{"jsonrpc":"2.0","id":$id,"method":"cint/config/append","params":{"packId":${quote(
                packId,
            )},"language":${quote(language)},"field":${quote(field)},"value":${quote(value)}}}""",
        )
    }

    fun messages(): List<JsonNode> =
        synchronized(lock) {
            output
                .toString()
                .trim()
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { mapper.readTree(line) }.getOrNull() }
        }

    fun latest(method: String): JsonNode = messages().last { it["method"]?.asText() == method }

    fun methodCount(method: String): Int = messages().count { it["method"]?.asText() == method }

    fun lastResponse(id: Int): JsonNode =
        messages()
            .last {
                it["id"]?.asInt() == id && it["method"] == null
            }.path("result")

    fun waitForResponse(id: Int): JsonNode = waitFor { lastResponseOrNull(id) }.path("result")

    fun waitForResponseDirect(id: Int): JsonNode =
        waitFor {
            messagesDirect()
                .lastOrNull {
                    it["id"]?.asInt() == id && it["method"] == null
                }?.path("result")
        }

    fun messagesDirect(): List<JsonNode> =
        output
            .toString()
            .trim()
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { mapper.readTree(line) }.getOrNull() }

    fun waitForCandidate(
        uri: String,
        intent: String,
        predicate: (JsonNode) -> Boolean,
    ): JsonNode =
        waitFor {
            messages()
                .filter { it["method"]?.asText() == "cint/publishCandidates" }
                .flatMap { message ->
                    message.path("params").path("candidates").filter { candidate ->
                        candidate.path("intents").any { it.asText() == intent } &&
                            candidate.path("path").asText().isNotEmpty() &&
                            predicate(candidate)
                    }
                }.lastOrNull()
        }

    fun waitFor(
        timeoutMs: Long = 3000,
        predicate: () -> JsonNode?,
    ): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            predicate()?.let { return it }
            Thread.sleep(10)
        }
        error("timed out waiting for condition; messages=${messages()}")
    }

    private fun lastResponseOrNull(id: Int): JsonNode? =
        messages().lastOrNull {
            it["id"]?.asInt() == id && it["method"] == null
        }

    private fun quote(value: String): String = mapper.writeValueAsString(value)
}
