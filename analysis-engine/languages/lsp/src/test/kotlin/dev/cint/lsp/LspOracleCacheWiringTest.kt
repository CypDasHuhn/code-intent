package dev.cint.lsp

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.OracleSession
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeLspServer {
    var ready = true
    var failure: Throwable? = null
    var type = "Acme.Type"
    var typeFromText = false
    var hoverCalls = 0
        private set
    var opens = 0
        private set
    var closes = 0
        private set
    val opened = mutableListOf<TextDocumentItem>()

    private val textDocumentService =
        Proxy.newProxyInstance(
            TextDocumentService::class.java.classLoader,
            arrayOf(TextDocumentService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "hover" -> {
                    hoverCalls++
                    val failed = failure
                    when {
                        failed != null -> CompletableFuture.failedFuture<Hover>(failed)
                        !ready -> CompletableFuture.completedFuture(null)
                        else -> CompletableFuture.completedFuture(hover())
                    }
                }

                "didOpen" -> {
                    opens++
                    opened += (args?.get(0) as DidOpenTextDocumentParams).textDocument
                    null
                }

                "didClose" -> {
                    closes++
                    null
                }

                else -> {
                    defaultValue(method.returnType)
                }
            }
        } as TextDocumentService

    val server =
        Proxy.newProxyInstance(
            LanguageServer::class.java.classLoader,
            arrayOf(LanguageServer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTextDocumentService", "textDocumentService" -> {
                    textDocumentService
                }

                else -> {
                    defaultValue(method.returnType)
                }
            }
        } as LanguageServer

    private fun hover(): Hover {
        val rendered = if (typeFromText) opened.lastOrNull()?.text.orEmpty() else type
        return Hover(MarkupContent("markdown", "```kotlin\nval value: $rendered\n```"))
    }

    private fun defaultValue(returnType: Class<*>): Any? =
        when {
            returnType == java.lang.Boolean.TYPE -> false
            returnType == java.lang.Integer.TYPE -> 0
            returnType == java.lang.Long.TYPE -> 0L
            returnType == java.lang.Double.TYPE -> 0.0
            returnType == java.lang.Float.TYPE -> 0f
            returnType == CompletableFuture::class.java -> CompletableFuture.completedFuture(null)
            else -> null
        }
}

private fun oracleFor(
    server: FakeLspServer,
    negativeTtlMs: Long = 500L,
    nanoTime: () -> Long = System::nanoTime,
): LspOracle = LspOracle(Path.of("."), KotlinLspSpec, null, null, server.server, 1_000L, negativeTtlMs, nanoTime)

class LspOracleCacheWiringTest {
    @Test
    fun unchangedTextDoesNotReopenAndLaterAnalysisHitsTheCache() {
        val fake = FakeLspServer()
        val oracle = oracleFor(fake)
        try {
            val file = SourceFile("Sample.kt", "fun f() {}")

            assertEquals("Acme.Type", oracle.typeAt(file, 0)?.fqName)
            assertEquals(1, fake.opens)
            assertEquals(1, fake.hoverCalls)

            assertEquals("Acme.Type", oracle.typeAt(file, 0)?.fqName)

            assertEquals(1, fake.opens)
            assertEquals(1, fake.hoverCalls)
            val stats = oracle.oracleStats()
            assertEquals(1L, stats.hoverCalls)
            assertEquals(1L, stats.delegateHits)
        } finally {
            oracle.close()
        }
    }

    @Test
    fun changedTextBumpsVersionInvalidatesOnlyThatUriAndRehovers() {
        val fake = FakeLspServer()
        val oracle = oracleFor(fake)
        try {
            val first = SourceFile("A.kt", "a")
            val other = SourceFile("B.kt", "b")
            oracle.typeAt(first, 0)
            oracle.typeAt(other, 0)
            assertEquals(2, fake.hoverCalls)

            oracle.typeAt(first, 0)
            assertEquals(2, fake.hoverCalls)

            oracle.typeAt(SourceFile("A.kt", "a changed"), 0)
            assertEquals(3, fake.hoverCalls)
            assertEquals(3, fake.opens)
            assertEquals(1, fake.closes)
            assertEquals(2, fake.opened.last().version)

            oracle.typeAt(other, 0)
            assertEquals(3, fake.hoverCalls)
        } finally {
            oracle.close()
        }
    }

    @Test
    fun coldEmptyHoverIsReusedWithinTheNegativeTtlAndRevalidatedAfterIt() {
        val fake = FakeLspServer()
        fake.ready = false
        var now = 0L
        val oracle = oracleFor(fake, negativeTtlMs = 100L, nanoTime = { now })
        try {
            val file = SourceFile("Sample.kt", "fun f() {}")

            assertNull(oracle.typeAt(file, 0))
            assertEquals(1, fake.hoverCalls)

            assertNull(oracle.typeAt(file, 0))
            assertEquals(1, fake.hoverCalls)
            assertEquals(1L, oracle.oracleStats().delegateHits)

            fake.ready = true
            now += 101_000_000L
            assertEquals("Acme.Type", oracle.typeAt(file, 0)?.fqName)
            assertEquals(2, fake.hoverCalls)
            assertEquals(2L, oracle.oracleStats().hoverCalls)
            assertEquals(1L, oracle.oracleStats().delegateHits)

            assertEquals("Acme.Type", oracle.typeAt(file, 0)?.fqName)
            assertEquals(2, fake.hoverCalls)
        } finally {
            oracle.close()
        }
    }

    @Test
    fun manyUnresolvedNegativesStayBoundedWithinTheTtlAndRevalidateAfterIt() {
        val fake = FakeLspServer()
        fake.ready = false
        var now = 0L
        val oracle = oracleFor(fake, negativeTtlMs = 100L, nanoTime = { now })
        try {
            val text = requireNotNull(javaClass.getResource("/fixtures/many-unresolved.kt.txt")).readText()
            val file = SourceFile("many-unresolved.kt", text)
            val offsets = 0 until 24

            offsets.forEach { offset -> assertNull(oracle.typeAt(file, offset)) }
            assertEquals(24, fake.hoverCalls)

            offsets.forEach { offset -> assertNull(oracle.typeAt(file, offset)) }
            assertEquals(24, fake.hoverCalls)
            assertEquals(24L, oracle.oracleStats().delegateHits)

            now += 101_000_000L
            offsets.forEach { offset -> assertNull(oracle.typeAt(file, offset)) }
            assertEquals(48, fake.hoverCalls)
            assertEquals(48L, oracle.oracleStats().hoverCalls)
        } finally {
            oracle.close()
        }
    }

    @Test
    fun aTimeoutIsMemoizedWithinAnAnalysisAndRetriedInALaterAnalysis() {
        val fake = FakeLspServer()
        fake.failure = TimeoutException("hover timed out")
        val oracle = oracleFor(fake)
        try {
            val file = SourceFile("Sample.kt", "fun f() {}")

            val first = OracleSession(oracle)
            assertNull(first.typeAt(file, 0))
            assertNull(first.typeAt(file, 0))
            assertEquals(1, fake.hoverCalls)
            assertEquals(1L, first.oracleStats().timeouts)
            assertEquals(0L, first.oracleStats().delegateHits)

            fake.failure = null
            val second = OracleSession(oracle)
            assertEquals("Acme.Type", second.typeAt(file, 0)?.fqName)
            assertEquals(2, fake.hoverCalls)
            assertEquals(0L, second.oracleStats().delegateHits)
        } finally {
            oracle.close()
        }
    }

    @Test
    fun concurrentTextsForTheSameUriRemainConsistent() {
        val fake = FakeLspServer()
        fake.typeFromText = true
        val oracle = oracleFor(fake)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val started = CountDownLatch(1)
            val futures =
                (0 until 40).map { index ->
                    val text = if (index % 2 == 0) "A" else "B"
                    executor.submit<String?> {
                        started.await()
                        oracle.typeAt(SourceFile("Sample.kt", text), 0)?.fqName
                    }
                }
            started.countDown()
            futures.forEachIndexed { index, future ->
                val expected = if (index % 2 == 0) "A" else "B"
                assertEquals(expected, future.get(5, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            oracle.close()
        }
    }
}
