package dev.cint.core.user

import dev.cint.core.user.support.*
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserAnnotationsFileRecoveryTest {
    @Test
    fun fileLockSerializesDirectCallers() {
        val root = Files.createTempDirectory("cint-user-annotations-contention")
        try {
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val conflict = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val inside = AtomicInteger(0)
            val maxInside = AtomicInteger(0)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val executor = Executors.newFixedThreadPool(2)

            executor.execute {
                try {
                    UserAnnotationsFile.withFileLock(root) {
                        enter(inside, maxInside)
                        firstEntered.countDown()
                        releaseFirst.await(5, TimeUnit.SECONDS)
                        inside.decrementAndGet()
                    }
                } catch (error: Throwable) {
                    errors.add(error)
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            executor.execute {
                try {
                    UserAnnotationsFile.withFileLock(root, onAcquireConflict = { conflict.countDown() }) {
                        enter(inside, maxInside)
                        secondEntered.countDown()
                        inside.decrementAndGet()
                    }
                } catch (error: Throwable) {
                    errors.add(error)
                }
            }

            assertTrue(conflict.await(5, TimeUnit.SECONDS))
            assertEquals(1, inside.get())
            releaseFirst.countDown()
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, maxInside.get())
            assertTrue(errors.isEmpty(), errors.joinToString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ownerlessLegacyLockAndGuardAreRecovered() {
        val root = Files.createTempDirectory("cint-user-annotations-ownerless")
        try {
            val directory = root.resolve(UserAnnotationsFile.DIRECTORY)
            val lockPath = lockPath(root)
            val guardPath = directory.resolve("${UserAnnotationsFile.FILE_NAME}.lock.guard")
            val tombstone = directory.resolve("${UserAnnotationsFile.FILE_NAME}.lock.stale-old")
            Files.createDirectories(lockPath)
            Files.createDirectories(guardPath)
            Files.createDirectories(tombstone)
            setOld(lockPath)
            setOld(guardPath)
            setOld(tombstone)

            var entered = false
            UserAnnotationsFile.withFileLock(root) { entered = true }

            assertTrue(entered)
            assertFalse(Files.exists(lockPath))
            assertFalse(Files.exists(guardPath))
            assertFalse(Files.exists(tombstone))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deadHolderLockIsRecoveredInOneAttempt() {
        val root = Files.createTempDirectory("cint-user-annotations-dead-holder")
        val helper = ProcessBuilder("sleep", "60").start()
        try {
            val pid = helper.pid()
            val start = waitForStartMarker(pid)
            helper.destroyForcibly()
            assertTrue(helper.waitFor(5, TimeUnit.SECONDS))

            val lockPath = lockPath(root)
            writeLock(lockPath, "a".repeat(64), pid, start, old = false)

            var entered = false
            UserAnnotationsFile.withFileLock(root, acquireTimeoutMillis = 2_000) { entered = true }

            assertTrue(entered)
            assertFalse(Files.exists(lockPath))
        } finally {
            helper.destroyForcibly()
            helper.waitFor(5, TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun liveAndSuspendedHoldersAreNotStolen() {
        val root = Files.createTempDirectory("cint-user-annotations-live-holder")
        val helper = ProcessBuilder("sleep", "60").start()
        try {
            val currentPid = ProcessHandle.current().pid()
            val currentStart = UserAnnotationsFile.processStartMarkerForTest(currentPid)
            val lockPath = lockPath(root)
            writeLock(lockPath, "b".repeat(64), currentPid, currentStart, old = true)
            assertFalse(UserAnnotationsFile.isStaleForTest(root))

            var entered = false
            val timeout =
                assertFailsWith<IllegalStateException> {
                    UserAnnotationsFile.withFileLock(root, acquireTimeoutMillis = 250) { entered = true }
                }
            assertTrue(timeout.message.orEmpty().contains("timed out"))
            assertFalse(entered)
            assertTrue(Files.exists(lockPath))
            assertEquals("b".repeat(64), readToken(lockPath))

            val helperPid = helper.pid()
            val helperStart = waitForStartMarker(helperPid)
            signal("-STOP", helperPid)
            writeLock(lockPath, "c".repeat(64), helperPid, helperStart, old = true)
            assertFalse(UserAnnotationsFile.isStaleForTest(root))
        } finally {
            runCatching { signal("-CONT", helper.pid()) }
            helper.destroyForcibly()
            helper.waitFor(5, TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun staleLockStealLeavesSuccessorOwnedAndOldReleaseCannotDeleteIt() {
        val root = Files.createTempDirectory("cint-user-annotations-stale")
        try {
            val lockPath = lockPath(root)
            writeLock(lockPath, "d".repeat(64), Long.MAX_VALUE, "", old = false)

            val successorEntered = CountDownLatch(1)
            val releaseSuccessor = CountDownLatch(1)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                try {
                    UserAnnotationsFile.withFileLock(root) {
                        successorEntered.countDown()
                        releaseSuccessor.await(5, TimeUnit.SECONDS)
                    }
                } catch (error: Throwable) {
                    errors.add(error)
                }
            }

            assertTrue(successorEntered.await(5, TimeUnit.SECONDS))
            val successorToken = readToken(lockPath)
            assertTrue(successorToken != null && successorToken != "d".repeat(64))

            assertFalse(UserAnnotationsFile.releaseIfOwned(root, "d".repeat(64)))
            assertTrue(Files.exists(lockPath))
            assertEquals(successorToken, readToken(lockPath))

            releaseSuccessor.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(errors.isEmpty(), errors.joinToString())
            assertFalse(Files.exists(lockPath))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun releaseRestoresLockReplacedAfterTokenRead() {
        val root = Files.createTempDirectory("cint-user-annotations-release-race")
        try {
            val currentPid = ProcessHandle.current().pid()
            val currentStart = UserAnnotationsFile.processStartMarkerForTest(currentPid)
            val lockPath = lockPath(root)
            val successorToken = "f".repeat(64)

            val hookEntered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                try {
                    UserAnnotationsFile.withFileLock(
                        root,
                        onBeforeRelease = {
                            hookEntered.countDown()
                            resume.await(5, TimeUnit.SECONDS)
                        },
                    ) {}
                } catch (error: Throwable) {
                    errors.add(error)
                }
            }

            assertTrue(hookEntered.await(5, TimeUnit.SECONDS))
            deleteTree(lockPath)
            writeLock(lockPath, successorToken, currentPid, currentStart, old = false)
            resume.countDown()

            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(errors.isEmpty(), errors.joinToString())
            assertTrue(Files.exists(lockPath))
            assertEquals(successorToken, readToken(lockPath))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
