package dev.cint.core.user

import dev.cint.api.Span
import dev.cint.core.user.support.*
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossWriterAnnotationLockTest {
    @Test
    fun heldKotlinLockBlocksNeovimUntilReleaseAndBothRecordsSurvive() {
        newHarness("held").use { harness ->
            val file = writeFixture(harness.root, defaultLines())
            UserAnnotationsFile.add(harness.root, annotation("kotlin-held", 100))

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val holder =
                Thread {
                    try {
                        UserAnnotationsFile.withFileLock(harness.root, acquireTimeoutMillis = 5_000) {
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    } catch (error: Throwable) {
                        errors.add(error)
                    }
                }
            holder.isDaemon = true
            holder.start()

            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))

                val started = harness.root.resolve("nvim-started")
                val blocked = harness.root.resolve("nvim-blocked")
                val spawned =
                    harness.processes.spawn(
                        harness,
                        file,
                        mode = "annotate",
                        line = 2,
                        intent = "PURE",
                        note = "nvim-held",
                        started = started,
                        blocked = blocked,
                    )
                awaitFile(started)
                awaitFile(blocked)

                assertTrue(spawned.process.isAlive, "neovim should wait for the held lock\n${readLog(spawned.log)}")
                assertEquals(
                    setOf("kotlin-held"),
                    notes(harness.root),
                    "neovim must not write while the Kotlin writer holds the lock",
                )
                assertTrue(Files.exists(lockPath(harness.root)), "the Kotlin writer should still hold the lock")

                release.countDown()
                holder.join(10_000)
                assertFalse(holder.isAlive, "the Kotlin lock holder should finish")
                assertTrue(errors.isEmpty(), errors.joinToString())

                awaitProcess(spawned)
                UserAnnotationsFile.save(
                    harness.root,
                    UserAnnotationsFile.load(harness.root) + annotation("kotlin-saved", 101),
                )

                assertEquals(setOf("kotlin-held", "nvim-held", "kotlin-saved"), notes(harness.root))
                assertFalse(Files.exists(lockPath(harness.root)), "the writers should release the shared lock")
            } finally {
                release.countDown()
                holder.join(10_000)
                harness.processes.terminateAll()
            }
        }
    }

    @Test
    fun deadHolderStaleLockIsRecoveredByBothWritersWithoutLosingAnnotations() {
        newHarness("stale").use { harness ->
            val file = writeFixture(harness.root, defaultLines())
            val helper = ProcessBuilder("sleep", "60").start()
            try {
                val pid = helper.pid()
                val start = waitForStartMarker(pid)
                helper.destroyForcibly()
                assertTrue(helper.waitFor(5, TimeUnit.SECONDS), "the dead-holder helper should exit")

                writeDeadLock(harness.root, pid, start)
                assertTrue(UserAnnotationsFile.isStaleForTest(harness.root))
                UserAnnotationsFile.add(harness.root, annotation("kotlin-stale", 100))
                assertFalse(
                    Files.exists(lockPath(harness.root)),
                    "the Kotlin writer should recover the stale lock",
                )
                assertEquals(setOf("kotlin-stale"), notes(harness.root))

                writeDeadLock(harness.root, pid, start)
                assertTrue(UserAnnotationsFile.isStaleForTest(harness.root))
                val spawned =
                    harness.processes.spawn(
                        harness,
                        file,
                        mode = "annotate",
                        line = 2,
                        intent = "PURE",
                        note = "nvim-stale",
                    )
                awaitProcess(spawned)

                assertFalse(
                    Files.exists(lockPath(harness.root)),
                    "the Neovim writer should recover the stale lock",
                )
                assertEquals(setOf("kotlin-stale", "nvim-stale"), notes(harness.root))
            } finally {
                if (helper.isAlive) helper.destroyForcibly()
                helper.waitFor(5, TimeUnit.SECONDS)
                harness.processes.terminateAll()
            }
        }
    }

    @Test
    fun concurrentNeovimAnnotateUnannotateAndKotlinAddsPreserveEveryRecord() {
        newHarness("concurrent").use { harness ->
            val file = writeFixture(harness.root, (0..8).map { index -> "fun op$index(): String = \"$index\"" })
            UserAnnotationsFile.add(
                harness.root,
                UserAnnotation(
                    path = "Sample.kt",
                    span = Span(4, 0, 4, 1),
                    intents = setOf("PURE"),
                    symbol = "op4",
                    note = "remove-me",
                ),
            )

            val intervalDir = Files.createDirectories(harness.root.resolve("intervals"))
            val conflictDir = Files.createDirectories(harness.root.resolve("conflicts"))
            val readyDir = Files.createDirectories(harness.root.resolve("ready"))
            val gate = harness.root.resolve("gate")
            val workers =
                listOf(
                    Worker("nvim-a", 1, "annotate"),
                    Worker("nvim-b", 2, "annotate"),
                    Worker("nvim-c", 3, "annotate"),
                    Worker("nvim-d", 4, "annotate-unannotate"),
                    Worker("nvim-remove", 5, "unannotate"),
                )

            val holderRelease = CountDownLatch(1)
            val holderEntered = CountDownLatch(1)
            val holderErrors = Collections.synchronizedList(mutableListOf<Throwable>())
            val holder =
                Thread {
                    try {
                        UserAnnotationsFile.withFileLock(harness.root, acquireTimeoutMillis = 5_000) {
                            holderEntered.countDown()
                            holderRelease.await(10, TimeUnit.SECONDS)
                        }
                    } catch (error: Throwable) {
                        holderErrors.add(error)
                    }
                }
            holder.isDaemon = true

            val writerCount = 8
            val executor = Executors.newFixedThreadPool(writerCount)
            val start = CountDownLatch(1)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val observer = FileLockObserver(intervalDir, conflictDir, "kotlin")
            var holderStarted = false

            try {
                val spawned =
                    workers.map { worker ->
                        harness.processes.spawn(
                            harness,
                            file,
                            mode = worker.mode,
                            line = worker.line,
                            intent = if (worker.mode == "unannotate") null else "PURE",
                            note = if (worker.mode == "unannotate") "remove-me" else worker.id,
                            ready = readyDir.resolve(worker.id),
                            gate = gate,
                            intervalDir = intervalDir,
                            conflictDir = conflictDir,
                            writerId = worker.id,
                        )
                    }
                workers.forEach { worker -> awaitFile(readyDir.resolve(worker.id)) }

                UserAnnotationsFile.lockObserver = observer
                holder.start()
                holderStarted = true
                assertTrue(holderEntered.await(5, TimeUnit.SECONDS), "the Kotlin holder should acquire the lock")

                repeat(writerCount) { index ->
                    executor.execute {
                        try {
                            start.await(10, TimeUnit.SECONDS)
                            UserAnnotationsFile.add(harness.root, annotation("kotlin-$index", 100 + index))
                        } catch (error: Throwable) {
                            errors.add(error)
                        }
                    }
                }

                Files.writeString(gate, "go\n")
                awaitAnyFile(conflictDir, "nvim-")
                start.countDown()
                awaitAnyFile(conflictDir, "kotlin-")
                holderRelease.countDown()
                holder.join(10_000)
                assertFalse(holder.isAlive, "the Kotlin holder should finish")
                assertTrue(holderErrors.isEmpty(), holderErrors.joinToString())

                spawned.forEach { item -> awaitProcess(item) }
                executor.shutdown()
                assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Kotlin writers should finish")
                assertTrue(errors.isEmpty(), errors.joinToString())
            } finally {
                UserAnnotationsFile.lockObserver = null
                start.countDown()
                holderRelease.countDown()
                if (holderStarted) holder.join(10_000)
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
                harness.processes.terminateAll()
            }

            val intervals = readIntervals(intervalDir)
            assertTrue(intervals.any { it.writer == "kotlin" }, "the Kotlin writer produced no lock interval")
            assertTrue(intervals.any { it.writer.startsWith("nvim-") }, "Neovim produced no lock interval")
            val overlaps = findOverlaps(intervals)
            assertTrue(overlaps.isEmpty(), overlaps.joinToString("\n"))
            assertIntervalValidatorRejectsOverlap()
            val conflicts =
                Files.list(conflictDir).use { stream ->
                    stream.map { it.fileName.toString() }.toList()
                }
            assertTrue(conflicts.any { it.startsWith("nvim-") }, "Neovim did not observe a lock conflict: $conflicts")
            assertTrue(conflicts.any { it.startsWith("kotlin-") }, "Kotlin did not observe a lock conflict: $conflicts")

            val expected = setOf("nvim-a", "nvim-b", "nvim-c") + (0 until writerCount).map { "kotlin-$it" }
            assertEquals(expected, notes(harness.root))
            assertFalse(Files.exists(lockPath(harness.root)), "all writers should release the shared lock")
        }
    }

    @Test
    fun failurePathTerminatesSpawnedNeovimBeforeRootCleanup() {
        newHarness("cleanup").use { harness ->
            val file = writeFixture(harness.root, defaultLines())
            val readyDir = Files.createDirectories(harness.root.resolve("ready"))
            var failureObserved = false
            try {
                val spawned =
                    harness.processes.spawn(
                        harness,
                        file,
                        mode = "annotate",
                        line = 1,
                        intent = "PURE",
                        note = "never-written",
                        ready = readyDir.resolve("cleanup"),
                        gate = harness.root.resolve("missing-gate"),
                    )
                awaitFile(readyDir.resolve("cleanup"))
                assertTrue(spawned.process.isAlive, "the cleanup control process should be alive")
                failureObserved =
                    runCatching {
                        awaitFile(harness.root.resolve("missing-marker"), timeoutMillis = 50)
                    }.isFailure
            } finally {
                harness.processes.terminateAll()
            }

            assertTrue(failureObserved, "the cleanup control should observe an early failure")
            assertTrue(
                harness.processes.snapshot().none { it.process.isAlive },
                "spawned Neovim processes must terminate before the root is removed",
            )
            assertFalse(
                Files.exists(lockPath(harness.root)),
                "the gate-only Neovim process should not take the lock",
            )
        }
    }
}
