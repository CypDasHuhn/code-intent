package dev.cint.core.user

import dev.cint.api.Span
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class UserAnnotationsFileTest {
    @Test
    fun concurrentAddsPreserveEveryAnnotation() {
        val root = Files.createTempDirectory("cint-user-annotations-concurrent")
        try {
            val threads = 8
            val executor = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            repeat(threads) { index ->
                executor.execute {
                    start.await(2, TimeUnit.SECONDS)
                    UserAnnotationsFile.add(
                        root,
                        UserAnnotation(
                            path = "Sample.kt",
                            span = Span(index, 0, index, 1),
                            intents = setOf("INTENT_$index"),
                        ),
                    )
                }
            }
            start.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)

            val annotations = UserAnnotationsFile.load(root)
            assertEquals(threads, annotations.size)
            assertEquals((0 until threads).map { "INTENT_$it" }.toSet(), annotations.flatMap { it.intents }.toSet())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
