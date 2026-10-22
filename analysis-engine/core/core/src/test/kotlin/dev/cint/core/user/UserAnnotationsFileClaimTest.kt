package dev.cint.core.user

import dev.cint.core.user.support.*
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserAnnotationsFileClaimTest {
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
}
