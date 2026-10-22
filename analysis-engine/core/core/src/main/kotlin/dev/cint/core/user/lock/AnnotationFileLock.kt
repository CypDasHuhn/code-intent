package dev.cint.core.user

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal interface AnnotationLockObserver {
    fun acquired(
        root: Path,
        lockPath: Path,
        token: String,
    )

    fun beforeRelease(
        root: Path,
        lockPath: Path,
        token: String,
    )

    fun conflict(lockPath: Path)
}

/**
 * Serializes annotation writes through a directory lock shared with the
 * Neovim client; stale holders are recovered without stealing live locks.
 */
internal object AnnotationFileLock {
    private const val ACQUIRE_TIMEOUT_MILLIS: Long = 15_000
    private const val RETRY_MILLIS: Long = 10
    private const val LOCK_NAME: String = "${UserAnnotationsFile.FILE_NAME}.lock"
    private const val CLAIM_NAME: String = "$LOCK_NAME.steal"
    private const val GUARD_NAME: String = "$LOCK_NAME.guard"
    private const val TOMBSTONE_MARKER: String = ".stale-"

    private val locks = ConcurrentHashMap<Path, Any>()
    private val random = SecureRandom()

    @Volatile
    internal var observer: AnnotationLockObserver? = null

    fun lockFor(root: Path): Any = locks.computeIfAbsent(root.toAbsolutePath().normalize()) { Any() }

    fun releaseIfOwned(
        root: Path,
        token: String,
    ): Boolean {
        val directory = UserAnnotationsFile.path(root).parent
        Files.createDirectories(directory)
        val lockPath = directory.resolve(LOCK_NAME)
        return release(lockPath, token, null, null) == ReleaseResult.RELEASED
    }

    fun isStaleForTest(root: Path): Boolean = AnnotationLockOwner.isStale(lockPath(root))

    fun processStartMarkerForTest(pid: Long): String = AnnotationLockOwner.processStartMarker(pid)

    fun <T> withFileLock(
        root: Path,
        onAcquireConflict: ((Path) -> Unit)? = null,
        onBeforeOwnerWrite: ((Path) -> Unit)? = null,
        onAfterClaimAcquired: ((Path) -> Unit)? = null,
        onBeforeRelease: (() -> Unit)? = null,
        onBeforeReleaseRestore: (() -> Unit)? = null,
        acquireTimeoutMillis: Long = ACQUIRE_TIMEOUT_MILLIS,
        block: () -> T,
    ): T =
        withFileLockToken(
            root,
            onAcquireConflict,
            onBeforeOwnerWrite,
            onAfterClaimAcquired,
            onBeforeRelease,
            onBeforeReleaseRestore,
            acquireTimeoutMillis,
        ) { block() }

    fun <T> withFileLockToken(
        root: Path,
        onAcquireConflict: ((Path) -> Unit)? = null,
        onBeforeOwnerWrite: ((Path) -> Unit)? = null,
        onAfterClaimAcquired: ((Path) -> Unit)? = null,
        onBeforeRelease: (() -> Unit)? = null,
        onBeforeReleaseRestore: (() -> Unit)? = null,
        acquireTimeoutMillis: Long = ACQUIRE_TIMEOUT_MILLIS,
        block: (String) -> T,
    ): T {
        val directory = UserAnnotationsFile.path(root).parent
        Files.createDirectories(directory)
        val lockPath = directory.resolve(LOCK_NAME)
        val claimPath = directory.resolve(CLAIM_NAME)
        reapInert(directory)
        val token = newToken()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMillis)
        acquireLock(
            lockPath,
            claimPath,
            token,
            deadline,
            onAcquireConflict,
            onBeforeOwnerWrite,
            onAfterClaimAcquired,
        )
        reapInert(directory)
        if (AnnotationLockOwner.read(lockPath)?.token != token) {
            runCatching { release(lockPath, token, null, null) }
            throw IllegalStateException("lost annotation lock at $lockPath")
        }
        var failure: Throwable? = null
        try {
            runCatching { observer?.acquired(root, lockPath, token) }
            return block(token)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            runCatching { observer?.beforeRelease(root, lockPath, token) }
            try {
                release(lockPath, token, onBeforeRelease, onBeforeReleaseRestore)
            } catch (releaseError: Throwable) {
                if (failure != null) {
                    failure.addSuppressed(releaseError)
                } else {
                    throw releaseError
                }
            }
        }
    }

    fun write(
        root: Path,
        annotations: List<UserAnnotation>,
        token: String,
    ) {
        val path = UserAnnotationsFile.path(root)
        Files.createDirectories(path.parent)
        val lockPath = path.parent.resolve(LOCK_NAME)
        if (AnnotationLockOwner.read(lockPath)?.token != token) {
            throw IllegalStateException("lost annotation lock at $lockPath")
        }
        AnnotationFileStore.write(path, annotations) {
            if (AnnotationLockOwner.read(lockPath)?.token != token) {
                throw IllegalStateException("lost annotation lock at $lockPath")
            }
        }
    }

    private fun lockPath(root: Path): Path = UserAnnotationsFile.path(root).parent.resolve(LOCK_NAME)

    private fun acquireLock(
        lockPath: Path,
        claimPath: Path,
        token: String,
        deadline: Long,
        onAcquireConflict: ((Path) -> Unit)?,
        onBeforeOwnerWrite: ((Path) -> Unit)?,
        onAfterClaimAcquired: ((Path) -> Unit)?,
    ) {
        while (true) {
            if (System.nanoTime() > deadline) {
                throw IllegalStateException("timed out waiting for $lockPath")
            }
            if (Files.exists(claimPath)) {
                if (isStale(claimPath)) reapStaleAux(claimPath, token)
                Thread.sleep(RETRY_MILLIS)
                continue
            }
            val created =
                try {
                    Files.createDirectory(lockPath)
                    true
                } catch (_: FileAlreadyExistsException) {
                    false
                }
            if (created) {
                if (Files.exists(claimPath)) {
                    AnnotationLockOwner.removeIfOwnerless(lockPath)
                    Thread.sleep(RETRY_MILLIS)
                    continue
                }
                onBeforeOwnerWrite?.invoke(lockPath)
                if (AnnotationLockOwner.publish(lockPath, token)) return
                Thread.sleep(RETRY_MILLIS)
                continue
            }
            onAcquireConflict?.invoke(lockPath)
            runCatching { observer?.conflict(lockPath) }
            if (isStale(lockPath)) {
                val claimed = tryAcquireClaim(claimPath, token, deadline, onAfterClaimAcquired)
                if (claimed) {
                    var failure: Throwable? = null
                    try {
                        if (Files.exists(lockPath) && isStale(lockPath)) {
                            stealCanonical(lockPath, token)
                        }
                    } catch (error: Throwable) {
                        failure = error
                        throw error
                    } finally {
                        try {
                            release(claimPath, token, null, null)
                        } catch (releaseError: Throwable) {
                            if (failure != null) {
                                failure.addSuppressed(releaseError)
                            } else {
                                throw releaseError
                            }
                        }
                    }
                }
            }
            Thread.sleep(RETRY_MILLIS)
        }
    }

    private fun tryAcquireClaim(
        claimPath: Path,
        token: String,
        deadline: Long,
        onAfterClaimAcquired: ((Path) -> Unit)?,
    ): Boolean {
        while (true) {
            if (System.nanoTime() > deadline) return false
            if (Files.exists(claimPath)) {
                if (!isStale(claimPath)) return false
                reapStaleAux(claimPath, token)
            } else {
                val created =
                    try {
                        Files.createDirectory(claimPath)
                        true
                    } catch (_: FileAlreadyExistsException) {
                        false
                    }
                if (created) {
                    if (!AnnotationLockOwner.publish(claimPath, token)) return false
                    onAfterClaimAcquired?.invoke(claimPath)
                    return true
                }
            }
            Thread.sleep(RETRY_MILLIS)
        }
    }

    private fun reapStaleAux(
        path: Path,
        token: String,
    ) {
        val observed = AnnotationLockOwner.readText(path)
        val tombstone = path.resolveSibling("${path.fileName}$TOMBSTONE_MARKER$token")
        try {
            AnnotationLockOwner.atomicMove(path, tombstone)
        } catch (_: NoSuchFileException) {
            return
        } catch (_: FileAlreadyExistsException) {
            return
        }
        val moved = AnnotationLockOwner.readText(tombstone)
        if (moved == observed && isStale(tombstone)) {
            AnnotationLockOwner.deleteTree(tombstone)
            return
        }
        if (!runCatching { AnnotationLockOwner.atomicMove(tombstone, path) }.isSuccess) {
            throw IllegalStateException("failed to restore $path")
        }
    }

    private fun stealCanonical(
        path: Path,
        token: String,
    ) {
        val tombstone = path.resolveSibling("${path.fileName}$TOMBSTONE_MARKER$token")
        try {
            AnnotationLockOwner.atomicMove(path, tombstone)
        } catch (_: NoSuchFileException) {
            return
        } catch (error: FileAlreadyExistsException) {
            throw IllegalStateException("stale tombstone already exists for $path", error)
        }
        if (isStale(tombstone)) {
            AnnotationLockOwner.deleteTree(tombstone)
            return
        }
        if (!runCatching { AnnotationLockOwner.atomicMove(tombstone, path) }.isSuccess) {
            throw IllegalStateException("failed to restore lock at $path")
        }
        throw IllegalStateException("lock became live during stale steal at $path")
    }

    private fun isStale(path: Path): Boolean = AnnotationLockOwner.isStale(path)

    private fun release(
        path: Path,
        token: String,
        onBeforeRelease: (() -> Unit)?,
        onBeforeReleaseRestore: (() -> Unit)?,
    ): ReleaseResult {
        val tombstone = path.resolveSibling("${path.fileName}$TOMBSTONE_MARKER$token")
        val owner = AnnotationLockOwner.read(path)
        if (owner?.token != token) {
            if (ownsTombstone(tombstone, token)) {
                AnnotationLockOwner.deleteTree(tombstone)
                return ReleaseResult.RELEASED
            }
            return ReleaseResult.NOT_OWNER
        }
        onBeforeRelease?.invoke()
        try {
            AnnotationLockOwner.atomicMove(path, tombstone)
        } catch (_: NoSuchFileException) {
            if (ownsTombstone(tombstone, token)) {
                AnnotationLockOwner.deleteTree(tombstone)
                return ReleaseResult.RELEASED
            }
            return ReleaseResult.NOT_OWNER
        } catch (_: FileAlreadyExistsException) {
            if (!ownsTombstone(tombstone, token)) {
                throw IllegalStateException("tombstone already exists for $path")
            }
            AnnotationLockOwner.deleteTree(tombstone)
            try {
                AnnotationLockOwner.atomicMove(path, tombstone)
            } catch (error: Throwable) {
                throw IllegalStateException("failed to release $path", error)
            }
        }
        val moved = AnnotationLockOwner.read(tombstone)
        if (moved?.token == token) {
            AnnotationLockOwner.deleteTree(tombstone)
            return ReleaseResult.RELEASED
        }
        onBeforeReleaseRestore?.invoke()
        if (!runCatching { AnnotationLockOwner.atomicMove(tombstone, path) }.isSuccess) {
            throw IllegalStateException("failed to restore lock at $path")
        }
        return ReleaseResult.NOT_OWNER
    }

    private fun ownsTombstone(
        path: Path,
        token: String,
    ): Boolean = AnnotationLockOwner.read(path)?.token == token

    private fun reapInert(directory: Path) {
        val entries =
            runCatching {
                Files.newDirectoryStream(directory).use { stream -> stream.map { it }.toList() }
            }.getOrDefault(emptyList())
        for (entry in entries) {
            val name = entry.fileName.toString()
            val auxiliary =
                name != LOCK_NAME &&
                    name != CLAIM_NAME &&
                    (
                        name.startsWith("$LOCK_NAME$TOMBSTONE_MARKER") ||
                            name.startsWith("$CLAIM_NAME$TOMBSTONE_MARKER") ||
                            name == GUARD_NAME ||
                            name.startsWith("$GUARD_NAME$TOMBSTONE_MARKER")
                    )
            if (auxiliary && isStale(entry)) AnnotationLockOwner.deleteTree(entry)
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private enum class ReleaseResult {
        RELEASED,
        NOT_OWNER,
    }
}
