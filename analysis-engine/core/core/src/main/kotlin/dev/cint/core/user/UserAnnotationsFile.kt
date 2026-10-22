package dev.cint.core.user

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Serializes user-annotation writes through a directory lock shared with the
 * Neovim client. The lock carries an owner record (token, pid and process-start
 * marker); a lock is stale only when that process is dead, or when the owner is
 * missing/legacy and the directory itself is old. A live holder, including a
 * suspended one, is never stolen. Stale transitions are serialized by a
 * fixed steal-claim path, and release tombstones are owned before deletion.
 */
object UserAnnotationsFile {
    const val DIRECTORY: String = ".cint"
    const val FILE_NAME: String = "user-annotations.json"

    private const val OWNER_FILE: String = "owner"
    private const val STALE_MILLIS: Long = 10_000
    private const val ACQUIRE_TIMEOUT_MILLIS: Long = 15_000
    private const val RETRY_MILLIS: Long = 10
    private const val LOCK_NAME: String = "$FILE_NAME.lock"
    private const val CLAIM_NAME: String = "$LOCK_NAME.steal"
    private const val GUARD_NAME: String = "$LOCK_NAME.guard"
    private const val TOMBSTONE_MARKER: String = ".stale-"
    private val TOKEN_PATTERN = Regex("^[0-9a-f]{64}$")

    private val locks = ConcurrentHashMap<Path, Any>()
    private val random = SecureRandom()

    fun path(root: Path): Path = root.resolve(DIRECTORY).resolve(FILE_NAME)

    fun load(root: Path): List<UserAnnotation> {
        val path = path(root)
        if (!Files.isRegularFile(path)) return emptyList()
        return UserAnnotationsJson.parse(Files.readString(path))
    }

    fun loadTolerant(
        root: Path,
        onError: (Throwable) -> Unit = {},
    ): List<UserAnnotation> =
        runCatching { load(root) }.getOrElse { error ->
            onError(error)
            emptyList()
        }

    fun add(
        root: Path,
        annotation: UserAnnotation,
    ): List<UserAnnotation> =
        synchronized(lockFor(root)) {
            withFileLockToken(root) { token ->
                val existing = loadTolerant(root)
                val kept =
                    existing.filterNot {
                        it.path == annotation.path &&
                            it.span == annotation.span &&
                            it.intents == annotation.intents
                    }
                val updated = kept + annotation
                write(root, updated, token)
                updated
            }
        }

    fun save(
        root: Path,
        annotations: List<UserAnnotation>,
    ) {
        synchronized(lockFor(root)) {
            withFileLockToken(root) { token ->
                write(root, annotations, token)
            }
        }
    }

    internal fun releaseIfOwned(
        root: Path,
        token: String,
    ): Boolean {
        val directory = path(root).parent
        Files.createDirectories(directory)
        val lockPath = directory.resolve(LOCK_NAME)
        return release(lockPath, token, null, null) == ReleaseResult.RELEASED
    }

    internal fun isStaleForTest(root: Path): Boolean = isStale(path(root).parent.resolve(LOCK_NAME))

    internal fun processStartMarkerForTest(pid: Long): String = processStartMarker(pid)

    internal fun <T> withFileLock(
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

    internal fun <T> withFileLockToken(
        root: Path,
        onAcquireConflict: ((Path) -> Unit)? = null,
        onBeforeOwnerWrite: ((Path) -> Unit)? = null,
        onAfterClaimAcquired: ((Path) -> Unit)? = null,
        onBeforeRelease: (() -> Unit)? = null,
        onBeforeReleaseRestore: (() -> Unit)? = null,
        acquireTimeoutMillis: Long = ACQUIRE_TIMEOUT_MILLIS,
        block: (String) -> T,
    ): T {
        val directory = path(root).parent
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
        if (readOwner(lockPath)?.token != token) {
            runCatching { release(lockPath, token, null, null) }
            throw IllegalStateException("lost annotation lock at $lockPath")
        }
        var failure: Throwable? = null
        try {
            return block(token)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
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

    private fun lockFor(root: Path): Any = locks.computeIfAbsent(root.toAbsolutePath().normalize()) { Any() }

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
                    removeIfOwnerless(lockPath)
                    Thread.sleep(RETRY_MILLIS)
                    continue
                }
                onBeforeOwnerWrite?.invoke(lockPath)
                if (publishOwner(lockPath, token)) return
                Thread.sleep(RETRY_MILLIS)
                continue
            }
            onAcquireConflict?.invoke(lockPath)
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
                    if (!publishOwner(claimPath, token)) return false
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
        val observed = readOwnerText(path)
        val tombstone = path.resolveSibling("${path.fileName}$TOMBSTONE_MARKER$token")
        try {
            atomicMove(path, tombstone)
        } catch (_: NoSuchFileException) {
            return
        } catch (_: FileAlreadyExistsException) {
            return
        }
        val moved = readOwnerText(tombstone)
        if (moved == observed && isStale(tombstone)) {
            deleteTree(tombstone)
            return
        }
        if (!runCatching { atomicMove(tombstone, path) }.isSuccess) {
            throw IllegalStateException("failed to restore $path")
        }
    }

    private fun stealCanonical(
        path: Path,
        token: String,
    ) {
        val tombstone = path.resolveSibling("${path.fileName}$TOMBSTONE_MARKER$token")
        try {
            atomicMove(path, tombstone)
        } catch (_: NoSuchFileException) {
            return
        } catch (error: FileAlreadyExistsException) {
            throw IllegalStateException("stale tombstone already exists for $path", error)
        }
        if (isStale(tombstone)) {
            deleteTree(tombstone)
            return
        }
        if (!runCatching { atomicMove(tombstone, path) }.isSuccess) {
            throw IllegalStateException("failed to restore lock at $path")
        }
        throw IllegalStateException("lock became live during stale steal at $path")
    }

    private fun isStale(path: Path): Boolean {
        val owner = readOwner(path)
        if (owner != null) return !isProcessAlive(owner)
        val directory = directoryModified(path) ?: return false
        val ownerFile = fileModified(path.resolve(OWNER_FILE))
        val modified = if (ownerFile == null) directory else maxOf(directory, ownerFile)
        return System.currentTimeMillis() - modified > STALE_MILLIS
    }

    private fun isProcessAlive(owner: Owner): Boolean {
        val handle = ProcessHandle.of(owner.pid).orElse(null) ?: return false
        if (!handle.isAlive) return false
        if (owner.start.isNotEmpty()) {
            val current = processStartMarker(owner.pid)
            if (current.isNotEmpty() && current != owner.start) return false
        }
        return true
    }

    private fun processStartMarker(pid: Long): String {
        val text = runCatching { Files.readString(Path.of("/proc/$pid/stat")) }.getOrNull() ?: return ""
        var close = -1
        var index = 0
        while (true) {
            val found = text.indexOf(')', index)
            if (found < 0) break
            close = found
            index = found + 1
        }
        if (close < 0) return ""
        val fields = text.substring(close + 1).trim().split(Regex("\\s+"))
        return fields.getOrNull(19) ?: ""
    }

    private fun release(
        path: Path,
        token: String,
        onBeforeRelease: (() -> Unit)?,
        onBeforeReleaseRestore: (() -> Unit)?,
    ): ReleaseResult {
        val tombstone = path.resolveSibling("${path.fileName}$TOMBSTONE_MARKER$token")
        val observed = readOwnerText(path)
        val owner = parseOwner(observed)
        if (owner?.token != token) {
            if (ownsTombstone(tombstone, token)) {
                deleteTree(tombstone)
                return ReleaseResult.RELEASED
            }
            return ReleaseResult.NOT_OWNER
        }
        onBeforeRelease?.invoke()
        try {
            atomicMove(path, tombstone)
        } catch (_: NoSuchFileException) {
            if (ownsTombstone(tombstone, token)) {
                deleteTree(tombstone)
                return ReleaseResult.RELEASED
            }
            return ReleaseResult.NOT_OWNER
        } catch (_: FileAlreadyExistsException) {
            if (!ownsTombstone(tombstone, token)) {
                throw IllegalStateException("tombstone already exists for $path")
            }
            deleteTree(tombstone)
            try {
                atomicMove(path, tombstone)
            } catch (error: Throwable) {
                throw IllegalStateException("failed to release $path", error)
            }
        }
        val moved = readOwner(tombstone)
        if (moved?.token == token) {
            deleteTree(tombstone)
            return ReleaseResult.RELEASED
        }
        onBeforeReleaseRestore?.invoke()
        if (!runCatching { atomicMove(tombstone, path) }.isSuccess) {
            throw IllegalStateException("failed to restore lock at $path")
        }
        return ReleaseResult.NOT_OWNER
    }

    private fun ownsTombstone(
        path: Path,
        token: String,
    ): Boolean = readOwner(path)?.token == token

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
            if (auxiliary && isStale(entry)) deleteTree(entry)
        }
    }

    private fun readOwner(path: Path): Owner? = parseOwner(readOwnerText(path))

    private fun readOwnerText(path: Path): String? =
        try {
            Files.readString(path.resolve(OWNER_FILE))
        } catch (_: NoSuchFileException) {
            null
        } catch (_: java.io.IOException) {
            null
        }

    private fun parseOwner(text: String?): Owner? {
        if (text == null) return null
        val fields =
            text
                .lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) {
                        null
                    } else {
                        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                    }
                }.toMap()
        val token = fields["token"] ?: return null
        if (!TOKEN_PATTERN.matches(token)) return null
        val pid = fields["pid"]?.toLongOrNull() ?: return null
        return Owner(token, pid, fields["start"] ?: "")
    }

    private fun publishOwner(
        path: Path,
        token: String,
    ): Boolean {
        val content = ownerContent(token)
        try {
            Files
                .newOutputStream(
                    path.resolve(OWNER_FILE),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { it.write(content.toByteArray()) }
            return true
        } catch (_: FileAlreadyExistsException) {
            return false
        } catch (_: NoSuchFileException) {
            return false
        } catch (error: Throwable) {
            cleanupFailedOwnerPublish(path, token)
            throw error
        }
    }

    private fun cleanupFailedOwnerPublish(
        path: Path,
        token: String,
    ) {
        val ownerPath = path.resolve(OWNER_FILE)
        val raw =
            try {
                if (!Files.exists(ownerPath)) null else Files.readString(ownerPath)
            } catch (_: Throwable) {
                return
            }
        val safe = raw == null || raw.isBlank() || parseOwner(raw)?.token == token
        if (!safe) return
        runCatching { Files.deleteIfExists(ownerPath) }
        runCatching { Files.deleteIfExists(path) }
    }

    private fun ownerContent(token: String): String {
        val pid = ProcessHandle.current().pid()
        return "token=$token\npid=$pid\nstart=${processStartMarker(pid)}\n"
    }

    private fun removeIfOwnerless(path: Path): Boolean {
        if (Files.exists(path.resolve(OWNER_FILE))) return false
        return runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
    }

    private fun directoryModified(path: Path): Long? = fileModified(path)

    private fun fileModified(path: Path): Long? =
        try {
            Files.getLastModifiedTime(path).toMillis()
        } catch (_: NoSuchFileException) {
            null
        }

    private fun atomicMove(
        from: Path,
        to: Path,
    ) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    private fun deleteTree(path: Path) {
        runCatching {
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun write(
        root: Path,
        annotations: List<UserAnnotation>,
        token: String,
    ) {
        val path = path(root)
        Files.createDirectories(path.parent)
        val lockPath = path.parent.resolve(LOCK_NAME)
        if (readOwner(lockPath)?.token != token) {
            throw IllegalStateException("lost annotation lock at $lockPath")
        }
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, UserAnnotationsJson.encode(annotations))
        if (readOwner(lockPath)?.token != token) {
            runCatching { Files.deleteIfExists(temporary) }
            throw IllegalStateException("lost annotation lock at $lockPath")
        }
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class Owner(
        val token: String,
        val pid: Long,
        val start: String,
    )

    private enum class ReleaseResult {
        RELEASED,
        NOT_OWNER,
    }
}
