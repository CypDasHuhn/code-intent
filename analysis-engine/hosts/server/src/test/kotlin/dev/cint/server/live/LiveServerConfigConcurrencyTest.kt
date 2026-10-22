package dev.cint.server.live

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.host.HostConfig
import dev.cint.host.HostRuntime
import dev.cint.rulepacks.BundledRulePackConfigResolver
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveServerConfigConcurrencyTest {
    @Test
    fun configMutationRepublishesDocumentsForOtherConnections() {
        val root = Files.createTempDirectory("cint-live-broadcast")
        try {
            val settings = HostConfig.load(null)
            val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
            val server = server(settings, pipeline, root, useLsp = false)
            val firstOutput = StringWriter()
            val first = BufferedWriter(firstOutput)
            val secondOutput = StringWriter()
            val second = BufferedWriter(secondOutput)

            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"textDocument/didOpen","params":{"uri":"Test1.kt","text":"fun f() {}","version":1,"languageId":"kotlin"}}""",
                first,
            )
            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"textDocument/didOpen","params":{"uri":"Test2.kt","text":"fun g() {}","version":1,"languageId":"kotlin"}}""",
                second,
            )
            firstOutput.buffer.setLength(0)
            secondOutput.buffer.setLength(0)

            server.handleMessage(
                """{"jsonrpc":"2.0","id":2,"method":"cint/config/append","params":{"packId":"logging","language":"kotlin","field":"methods","value":"audit"}}""",
                first,
            )

            assertTrue(firstOutput.toString().contains("\"cint/publishTags\""))
            assertTrue(secondOutput.toString().contains("\"cint/publishTags\""))
            assertTrue(secondOutput.toString().contains("\"revision\":1"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentDidChangeAndConfigMutationYieldsPostMutationAtHighestRevision() {
        val root = Files.createTempDirectory("cint-live-race")
        try {
            val settings = HostConfig.load(null)
            val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
            val server = server(settings, pipeline, root, useLsp = false)
            val output = StringWriter()
            val writer = BufferedWriter(output)
            val mutationOutput = StringWriter()
            val mutationWriter = BufferedWriter(mutationOutput)
            val source = "fun f() {\n    log.info(\"x\")\n    log.audit(\"y\")\n}\n"

            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"textDocument/didOpen","params":{"uri":"Race.kt","text":${jsonString(
                    source,
                )},"version":1,"languageId":"kotlin"}}""",
                writer,
            )
            output.buffer.setLength(0)
            val start = CountDownLatch(1)
            val change =
                thread {
                    start.await()
                    server.handleMessage(
                        """{"jsonrpc":"2.0","id":2,"method":"textDocument/didChange","params":{"uri":"Race.kt","text":${jsonString(
                            source,
                        )},"version":2,"languageId":"kotlin"}}""",
                        writer,
                    )
                }
            val mutation =
                thread {
                    start.await()
                    server.handleMessage(
                        """{"jsonrpc":"2.0","id":3,"method":"cint/config/append","params":{"packId":"logging","language":"kotlin","field":"methods","value":"audit"}}""",
                        mutationWriter,
                    )
                }
            start.countDown()
            change.join()
            mutation.join()

            val mapper = jacksonObjectMapper()
            val publishes =
                output
                    .toString()
                    .trim()
                    .lines()
                    .filter { it.contains("\"method\":\"cint/publishTags\"") }
                    .map { mapper.readTree(it) }
            val highestRevision = publishes.maxOf { it["params"]["revision"].asInt() }
            assertEquals(1, highestRevision)
            val message = publishes.last { it["params"]["revision"].asInt() == highestRevision }
            val loggingLines =
                message["params"]["annotations"]
                    .filter { annotation -> annotation["intents"].any { it.asText() == "LOGGING" } }
                    .map { it["startLine"].asInt() }
                    .toSet()
            assertTrue(loggingLines.containsAll(setOf(1, 2)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
