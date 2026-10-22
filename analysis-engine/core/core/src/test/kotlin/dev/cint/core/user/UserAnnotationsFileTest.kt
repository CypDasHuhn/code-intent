package dev.cint.core.user

import dev.cint.api.Span
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun creatorSuspendedBeforeOwnerWriteCannotClobberSuccessor() {
        val root = Files.createTempDirectory("cint-user-annotations-creator-race")
        try {
            val creatorAtWindow = CountDownLatch(1)
            val resumeCreator = CountDownLatch(1)
            val creatorBlock = AtomicBoolean(false)
            val creatorErrors = Collections.synchronizedList(mutableListOf<Throwable>())
            val creator = Executors.newSingleThreadExecutor()
            creator.execute {
                try {
                    UserAnnotationsFile.withFileLock(
                        root,
                        onBeforeOwnerWrite = {
                            creatorAtWindow.countDown()
                            resumeCreator.await(5, TimeUnit.SECONDS)
                        },
                        acquireTimeoutMillis = 2_000,
                    ) {
                        creatorBlock.set(true)
                    }
                } catch (error: Throwable) {
                    creatorErrors.add(error)
                }
            }
            assertTrue(creatorAtWindow.await(5, TimeUnit.SECONDS))
            val lockPath = lockPath(root)
            setOld(lockPath)

            val successorEntered = CountDownLatch(1)
            val releaseSuccessor = CountDownLatch(1)
            val successorErrors = Collections.synchronizedList(mutableListOf<Throwable>())
            val successor = Executors.newSingleThreadExecutor()
            successor.execute {
                try {
                    UserAnnotationsFile.withFileLock(root) {
                        successorEntered.countDown()
                        releaseSuccessor.await(5, TimeUnit.SECONDS)
                    }
                } catch (error: Throwable) {
                    successorErrors.add(error)
                }
            }
            assertTrue(successorEntered.await(5, TimeUnit.SECONDS))
            val successorToken = readToken(lockPath)
            assertTrue(successorToken != null)

            resumeCreator.countDown()
            creator.shutdown()
            assertTrue(creator.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse(creatorBlock.get())
            assertTrue(Files.exists(lockPath))
            assertEquals(successorToken, readToken(lockPath))
            assertEquals(1, creatorErrors.size)
            assertTrue(creatorErrors[0] is IllegalStateException)
            assertTrue(creatorErrors[0].message.orEmpty().contains("timed out"))

            releaseSuccessor.countDown()
            successor.shutdown()
            assertTrue(successor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(successorErrors.isEmpty(), successorErrors.joinToString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun staleStealClaimAllowsOnlyOneTransition() {
        val root = Files.createTempDirectory("cint-user-annotations-claim-contention")
        try {
            val lockPath = lockPath(root)
            val oldToken = "1".repeat(64)
            writeLock(lockPath, oldToken, Long.MAX_VALUE, "", old = false)

            val firstClaim = CountDownLatch(1)
            val resumeFirst = CountDownLatch(1)
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val firstErrors = Collections.synchronizedList(mutableListOf<Throwable>())
            val first = Executors.newSingleThreadExecutor()
            first.execute {
                try {
                    UserAnnotationsFile.withFileLock(
                        root,
                        onAfterClaimAcquired = {
                            firstClaim.countDown()
                            resumeFirst.await(5, TimeUnit.SECONDS)
                        },
                        acquireTimeoutMillis = 5_000,
                    ) {
                        firstEntered.countDown()
                        releaseFirst.await(5, TimeUnit.SECONDS)
                    }
                } catch (error: Throwable) {
                    firstErrors.add(error)
                }
            }
            assertTrue(firstClaim.await(5, TimeUnit.SECONDS))

            var secondEntered = false
            val secondError =
                assertFailsWith<IllegalStateException> {
                    UserAnnotationsFile.withFileLock(root, acquireTimeoutMillis = 400) {
                        secondEntered = true
                    }
                }
            assertTrue(secondError.message.orEmpty().contains("timed out"))
            assertFalse(secondEntered)
            assertTrue(Files.exists(lockPath))
            assertEquals(oldToken, readToken(lockPath))

            resumeFirst.countDown()
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            assertTrue(readToken(lockPath) != oldToken)

            releaseFirst.countDown()
            first.shutdown()
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(firstErrors.isEmpty(), firstErrors.joinToString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun claimRechecksCanonicalAndDoesNotMoveLiveLock() {
        val root = Files.createTempDirectory("cint-user-annotations-claim-live")
        try {
            val currentPid = ProcessHandle.current().pid()
            val currentStart = UserAnnotationsFile.processStartMarkerForTest(currentPid)
            val lockPath = lockPath(root)
            val successorToken = "2".repeat(64)
            writeLock(lockPath, "3".repeat(64), Long.MAX_VALUE, "", old = false)

            val claimEntered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val blockEntered = AtomicBoolean(false)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                try {
                    UserAnnotationsFile.withFileLock(
                        root,
                        onAfterClaimAcquired = {
                            claimEntered.countDown()
                            resume.await(5, TimeUnit.SECONDS)
                        },
                        acquireTimeoutMillis = 1_500,
                    ) {
                        blockEntered.set(true)
                    }
                } catch (error: Throwable) {
                    errors.add(error)
                }
            }

            assertTrue(claimEntered.await(5, TimeUnit.SECONDS))
            deleteTree(lockPath)
            writeLock(lockPath, successorToken, currentPid, currentStart, old = false)
            resume.countDown()

            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse(blockEntered.get())
            assertTrue(Files.exists(lockPath))
            assertEquals(successorToken, readToken(lockPath))
            assertEquals(1, errors.size)
            assertTrue(errors[0] is IllegalStateException)
            assertTrue(errors[0].message.orEmpty().contains("timed out"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun liveClaimBlocksCanonicalLockCreation() {
        val root = Files.createTempDirectory("cint-user-annotations-claim-blocks")
        try {
            val currentPid = ProcessHandle.current().pid()
            val currentStart = UserAnnotationsFile.processStartMarkerForTest(currentPid)
            val claimPath = claimPath(root)
            writeLock(claimPath, "4".repeat(64), currentPid, currentStart, old = false)

            var entered = false
            val error =
                assertFailsWith<IllegalStateException> {
                    UserAnnotationsFile.withFileLock(root, acquireTimeoutMillis = 250) {
                        entered = true
                    }
                }

            assertTrue(error.message.orEmpty().contains("timed out"))
            assertFalse(entered)
            assertFalse(Files.exists(lockPath(root)))
            assertTrue(Files.exists(claimPath))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedReleaseRestoreIsHardFailure() {
        val root = Files.createTempDirectory("cint-user-annotations-release-hard-failure")
        try {
            val currentPid = ProcessHandle.current().pid()
            val currentStart = UserAnnotationsFile.processStartMarkerForTest(currentPid)
            val lockPath = lockPath(root)
            val successorToken = "5".repeat(64)
            val thirdToken = "6".repeat(64)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                try {
                    UserAnnotationsFile.withFileLock(
                        root,
                        onBeforeRelease = {
                            deleteTree(lockPath)
                            writeLock(lockPath, successorToken, currentPid, currentStart, old = false)
                        },
                        onBeforeReleaseRestore = {
                            writeLock(lockPath, thirdToken, currentPid, currentStart, old = false)
                        },
                    ) {}
                } catch (error: Throwable) {
                    errors.add(error)
                }
            }

            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertTrue(errors[0] is IllegalStateException)
            assertTrue(errors[0].message.orEmpty().contains("failed to restore lock"))
            assertEquals(thirdToken, readToken(lockPath))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun releaseFindsItsOwnTombstone() {
        val root = Files.createTempDirectory("cint-user-annotations-release-tombstone")
        try {
            val currentPid = ProcessHandle.current().pid()
            val currentStart = UserAnnotationsFile.processStartMarkerForTest(currentPid)
            val token = "7".repeat(64)
            val tombstone = lockPath(root).resolveSibling("${UserAnnotationsFile.FILE_NAME}.lock.stale-$token")
            writeLock(tombstone, token, currentPid, currentStart, old = false)

            assertTrue(UserAnnotationsFile.releaseIfOwned(root, token))
            assertFalse(Files.exists(tombstone))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun vanishedLockBetweenConflictAndAgeReadIsRetried() {
        val root = Files.createTempDirectory("cint-user-annotations-vanished")
        try {
            val lockPath = lockPath(root)
            writeLock(lockPath, "3".repeat(64), ProcessHandle.current().pid(), "", old = false)

            var entered = false
            UserAnnotationsFile.withFileLock(
                root,
                onAcquireConflict = { path ->
                    Files.deleteIfExists(path.resolve("owner"))
                    Files.deleteIfExists(path)
                },
            ) {
                entered = true
            }

            assertTrue(entered)
            assertFalse(Files.exists(lockPath))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun nonOwnerReleaseLeavesForeignTokenLockAndOwnerReleaseRemovesIt() {
        val root = Files.createTempDirectory("cint-user-annotations-foreign")
        try {
            val lockPath = lockPath(root)
            val currentPid = ProcessHandle.current().pid()
            val currentStart = UserAnnotationsFile.processStartMarkerForTest(currentPid)
            writeLock(lockPath, "a".repeat(64), currentPid, currentStart, old = false)

            assertFalse(UserAnnotationsFile.releaseIfOwned(root, "b".repeat(64)))
            assertTrue(Files.exists(lockPath))
            assertEquals("a".repeat(64), readToken(lockPath))

            assertTrue(UserAnnotationsFile.releaseIfOwned(root, "a".repeat(64)))
            assertFalse(Files.exists(lockPath))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun enter(
        inside: AtomicInteger,
        maxInside: AtomicInteger,
    ) {
        val current = inside.incrementAndGet()
        maxInside.accumulateAndGet(current) { left, right -> maxOf(left, right) }
    }

    private fun waitForStartMarker(pid: Long): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val marker = UserAnnotationsFile.processStartMarkerForTest(pid)
            if (marker.isNotEmpty()) return marker
            Thread.sleep(5)
        }
        return ""
    }

    private fun signal(
        signal: String,
        pid: Long,
    ) {
        val process = ProcessBuilder("/bin/kill", signal, pid.toString()).start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    private fun writeLock(
        lockPath: Path,
        token: String,
        pid: Long,
        start: String,
        old: Boolean,
    ) {
        Files.createDirectories(lockPath)
        Files.writeString(lockPath.resolve("owner"), "token=$token\npid=$pid\nstart=$start\n")
        if (old) setOld(lockPath)
    }

    private fun setOld(path: Path) {
        val old = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(30))
        Files.setLastModifiedTime(path, old)
        val owner = path.resolve("owner")
        if (Files.exists(owner)) Files.setLastModifiedTime(owner, old)
    }

    private fun readToken(lockPath: Path): String? {
        val owner = lockPath.resolve("owner")
        if (!Files.exists(owner)) return null
        return Files
            .readAllLines(owner)
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter("token=")
            ?.trim()
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun lockPath(root: Path): Path =
        root
            .resolve(UserAnnotationsFile.DIRECTORY)
            .resolve("${UserAnnotationsFile.FILE_NAME}.lock")

    private fun claimPath(root: Path): Path =
        root
            .resolve(UserAnnotationsFile.DIRECTORY)
            .resolve("${UserAnnotationsFile.FILE_NAME}.lock.steal")
}
