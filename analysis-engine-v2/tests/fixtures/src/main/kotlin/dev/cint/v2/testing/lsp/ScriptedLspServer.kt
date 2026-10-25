package dev.cint.v2.testing.lsp

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class ScriptedLspServer(
    private val input: InputStream,
    private val output: OutputStream,
    private val config: Config = Config(),
) : AutoCloseable {
    data class Config(
        var closeBeforeInitialize: Boolean = false,
        var initDelayMs: Long = 0,
        var hoverDelayMs: Long = 0,
        var crashOnHover: Boolean = false,
        var malformedOnHover: Boolean = false,
        var errorOnHover: Boolean = false,
        var hoverErrorCode: Int = -32000,
        var publishDiagnosticsOnOpen: Boolean = true,
        var syncKind: Int = 1,
        var openClose: Boolean = true,
        var blockAfterOpen: Boolean = false,
        var blockingDiagnosticsCount: Int = 0,
        var unchangedAfterFirstPull: Boolean = false,
        var diagnosticResultId: String = "scripted-diagnostics",
        var advertiseDiagnostics: Boolean = true,
        var publishDiagnosticsVersioned: Boolean = false,
    )

    val requests = CopyOnWriteArrayList<Map<String, Any?>>()
    val receivedMethods = CopyOnWriteArrayList<String>()

    @Volatile
    var shutdownSeen: Boolean = false
        private set

    @Volatile
    var exitSeen: Boolean = false
        private set

    @Volatile
    var receivedDidOpen: Boolean = false
        private set

    @Volatile
    var receivedDidChange: Boolean = false
        private set

    @Volatile
    var receivedDidClose: Boolean = false
        private set

    @Volatile
    var pullCount: Int = 0
        private set

    @Volatile
    var publishedCount: Int = 0
        private set

    @Volatile
    var lastDocumentText: String? = null
        private set

    @Volatile
    var lastChangeHadRange: Boolean = false
        private set

    @Volatile
    var lastOpenLanguageId: String? = null
        private set

    @Volatile
    var lastHoverLine: Int? = null
        private set

    @Volatile
    var lastHoverCharacter: Int? = null
        private set

    @Volatile
    var hoverCount: Int = 0
        private set

    @Volatile
    var readingPaused: Boolean = false
        private set

    @Volatile
    var lastDocumentVersion: Long? = null
        private set

    private val thread = Thread({ runServer() }, "scripted-lsp-${System.nanoTime()}").apply { isDaemon = true }

    fun start(): ScriptedLspServer {
        thread.start()
        return this
    }

    fun await(timeoutMs: Long = 2000) {
        thread.join(timeoutMs)
    }

    private fun runServer() {
        if (config.closeBeforeInitialize) {
            close()
            return
        }
        try {
            while (true) {
                val body = ScriptedLspFraming.read(input) ?: break
                val message = ScriptedJson.parse(String(body, StandardCharsets.UTF_8)).asObjectOrNull() ?: continue
                requests.add(message)
                val method = message.string("method") ?: continue
                receivedMethods.add(method)
                val id = message["id"]
                if (id == null) {
                    handleNotification(method, message["params"])
                    if (method == "exit") {
                        exitSeen = true
                        break
                    }
                } else {
                    handleRequest(id, method, message["params"])
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    private fun handleRequest(
        id: Any?,
        method: String,
        params: Any?,
    ) {
        when (method) {
            "initialize" -> {
                if (config.initDelayMs > 0) Thread.sleep(config.initDelayMs)
                reply(
                    id,
                    linkedMapOf(
                        "capabilities" to
                            linkedMapOf(
                                "textDocumentSync" to
                                    linkedMapOf(
                                        "openClose" to config.openClose,
                                        "change" to config.syncKind,
                                    ),
                                "hoverProvider" to true,
                                "definitionProvider" to true,
                                "referencesProvider" to true,
                                "documentSymbolProvider" to true,
                                "diagnosticProvider" to
                                    if (config.advertiseDiagnostics) {
                                        linkedMapOf(
                                            "interFileDependencies" to false,
                                            "workspaceDiagnostics" to false,
                                        )
                                    } else {
                                        null
                                    },
                            ),
                        "serverInfo" to linkedMapOf("name" to "scripted", "version" to "1"),
                    ),
                )
            }

            "shutdown" -> {
                shutdownSeen = true
                reply(id, null)
            }

            "textDocument/hover" -> {
                hoverCount++
                val position = params.asObjectOrNull()?.objectOrNull("position")
                lastHoverLine = position?.int("line")
                lastHoverCharacter = position?.int("character")
                handleHover(id)
            }

            "textDocument/definition" -> {
                val uri = params.asObjectOrNull()?.objectOrNull("textDocument")?.string("uri") ?: DEFAULT_URI
                reply(id, listOf(location(uri, 0, 0, 0, 3)))
            }

            "textDocument/references" -> {
                val uri = params.asObjectOrNull()?.objectOrNull("textDocument")?.string("uri") ?: DEFAULT_URI
                reply(id, listOf(location(uri, 0, 0, 0, 3), location(uri, 1, 0, 1, 3)))
            }

            "textDocument/documentSymbol" -> {
                val uri = params.asObjectOrNull()?.objectOrNull("textDocument")?.string("uri") ?: DEFAULT_URI
                reply(
                    id,
                    listOf(
                        linkedMapOf(
                            "name" to "holder",
                            "kind" to 12,
                            "range" to range(0, 0, 4, 0),
                            "selectionRange" to range(0, 0, 0, 6),
                            "children" to
                                listOf(
                                    linkedMapOf(
                                        "name" to "value",
                                        "kind" to 13,
                                        "range" to range(0, 0, 0, 12),
                                        "selectionRange" to range(0, 4, 0, 9),
                                    ),
                                ),
                        ),
                    ),
                )
            }

            "textDocument/diagnostic" -> {
                pullCount++
                val unchanged = config.unchangedAfterFirstPull && pullCount > 1
                reply(
                    id,
                    linkedMapOf(
                        "kind" to if (unchanged) "unchanged" else "full",
                        "resultId" to config.diagnosticResultId,
                        "items" to if (unchanged) null else diagnosticReport()["items"],
                    ),
                )
            }

            else -> {
                reply(id, null)
            }
        }
    }

    private fun handleHover(id: Any?) {
        if (config.crashOnHover) {
            close()
            return
        }
        if (config.hoverDelayMs > 0) Thread.sleep(config.hoverDelayMs)
        if (config.malformedOnHover) {
            val malformed = "{not-json".toByteArray(StandardCharsets.UTF_8)
            synchronized(output) {
                ScriptedLspFraming.write(output, malformed)
            }
            return
        }
        if (config.errorOnHover) {
            rawMessage(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "id" to id,
                    "error" to linkedMapOf("code" to config.hoverErrorCode, "message" to "hover failed"),
                ),
            )
            return
        }
        reply(
            id,
            linkedMapOf(
                "contents" to
                    linkedMapOf(
                        "kind" to "markdown",
                        "value" to "```kotlin\nval value: kotlin.Int\n```",
                    ),
            ),
        )
    }

    private fun handleNotification(
        method: String,
        params: Any?,
    ) {
        val objectParams = params.asObjectOrNull() ?: return
        val uri = objectParams.objectOrNull("textDocument")?.string("uri") ?: DEFAULT_URI
        when (method) {
            "textDocument/didOpen" -> {
                val textDocument = objectParams.objectOrNull("textDocument")
                lastDocumentText = textDocument?.string("text")
                lastOpenLanguageId = textDocument?.string("languageId")
                lastDocumentVersion = textDocument?.number("version")?.toLong()
                receivedDidOpen = true
                if (config.blockAfterOpen) {
                    readingPaused = true
                    Thread.sleep(100)
                    publishDiagnostics(uri, config.blockingDiagnosticsCount)
                    readingPaused = false
                } else if (config.publishDiagnosticsOnOpen) {
                    publishDiagnostics(uri)
                }
            }

            "textDocument/didChange" -> {
                lastDocumentText = applyChanges(lastDocumentText.orEmpty(), objectParams.listOrNull("contentChanges").orEmpty())
                lastDocumentVersion = objectParams.objectOrNull("textDocument")?.number("version")?.toLong()
                receivedDidChange = true
                if (config.publishDiagnosticsOnOpen) publishDiagnostics(uri)
            }

            "textDocument/didClose" -> {
                receivedDidClose = true
            }
        }
    }

    private fun publishDiagnostics(
        uri: String,
        count: Int = 1,
    ) {
        publishedCount++
        rawMessage(
            linkedMapOf(
                "jsonrpc" to "2.0",
                "method" to "textDocument/publishDiagnostics",
                "params" to
                    linkedMapOf(
                        "uri" to uri,
                        "version" to if (config.publishDiagnosticsVersioned) lastDocumentVersion else null,
                        "diagnostics" to diagnosticItems(count),
                    ),
            ),
        )
    }

    private fun applyChanges(
        previous: String,
        changes: List<Any?>,
    ): String {
        var text = previous
        for (change in changes) {
            val objectChange = change.asObjectOrNull() ?: continue
            val replacement = objectChange.string("text") ?: continue
            val range = objectChange.objectOrNull("range")
            if (range == null) {
                lastChangeHadRange = false
                text = replacement
                continue
            }
            lastChangeHadRange = true
            val start = range.objectOrNull("start") ?: continue
            val end = range.objectOrNull("end") ?: continue
            val startOffset = offsetAt(text, start.int("line") ?: 0, start.int("character") ?: 0)
            val endOffset = offsetAt(text, end.int("line") ?: 0, end.int("character") ?: 0)
            text = text.substring(0, startOffset) + replacement + text.substring(endOffset)
        }
        return text
    }

    private fun offsetAt(
        text: String,
        line: Int,
        character: Int,
    ): Int {
        var currentLine = 0
        var offset = 0
        while (currentLine < line && offset < text.length) {
            if (text[offset] == '\n') currentLine++
            offset++
        }
        return (offset + character).coerceAtMost(text.length)
    }

    private fun diagnosticReport(): Map<String, Any?> =
        linkedMapOf(
            "kind" to "full",
            "items" to diagnosticItems(1),
        )

    private fun diagnosticItems(count: Int): List<Map<String, Any?>> =
        List(count) {
            linkedMapOf(
                "range" to range(0, 0, 0, 3),
                "message" to "scripted warning",
                "severity" to 2,
                "code" to "W1",
                "source" to "scripted",
            )
        }

    private fun reply(
        id: Any?,
        result: Any?,
    ) {
        rawMessage(linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
    }

    private fun rawMessage(message: Map<String, Any?>) {
        val body = ScriptedJson.write(message).toByteArray(StandardCharsets.UTF_8)
        synchronized(output) {
            ScriptedLspFraming.write(output, body)
        }
    }

    private fun location(
        uri: String,
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int,
    ): Map<String, Any?> = linkedMapOf("uri" to uri, "range" to range(startLine, startCharacter, endLine, endCharacter))

    private fun range(
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int,
    ): Map<String, Any?> =
        linkedMapOf(
            "start" to linkedMapOf("line" to startLine, "character" to startCharacter),
            "end" to linkedMapOf("line" to endLine, "character" to endCharacter),
        )

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        const val DEFAULT_URI = "file:///tmp/Scripted.kt"
    }
}

object ScriptedLspServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val config =
            ScriptedLspServer.Config(
                closeBeforeInitialize = args.contains("--close-before-init"),
                hoverDelayMs = args.firstOrNull { it.startsWith("--hover-delay=") }?.substringAfter('=')?.toLongOrNull() ?: 0,
                crashOnHover = args.contains("--crash-on-hover"),
                malformedOnHover = args.contains("--malformed-on-hover"),
                errorOnHover = args.contains("--error-on-hover"),
            )
        ScriptedLspServer(System.`in`, System.out, config).start().await(60_000)
    }
}
