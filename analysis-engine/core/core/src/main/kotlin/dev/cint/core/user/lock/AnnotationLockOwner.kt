package dev.cint.core.user

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

internal object AnnotationLockOwner {
    const val OWNER_FILE: String = "owner"
    private const val STALE_MILLIS: Long = 10_000
    private val TOKEN_PATTERN = Regex("^[0-9a-f]{64}$")

    data class Owner(
        val token: String,
        val pid: Long,
        val start: String,
    )

    fun isStale(path: Path): Boolean {
        val owner = read(path)
        if (owner != null) return !isProcessAlive(owner)
        val directory = directoryModified(path) ?: return false
        val ownerFile = fileModified(path.resolve(OWNER_FILE))
        val modified = if (ownerFile == null) directory else maxOf(directory, ownerFile)
        return System.currentTimeMillis() - modified > STALE_MILLIS
    }

    fun read(path: Path): Owner? = parse(readText(path))

    fun readText(path: Path): String? =
        try {
            Files.readString(path.resolve(OWNER_FILE))
        } catch (_: NoSuchFileException) {
            null
        } catch (_: java.io.IOException) {
            null
        }

    fun publish(
        path: Path,
        token: String,
    ): Boolean {
        val content = content(token)
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
            cleanupFailedPublish(path, token)
            throw error
        }
    }

    fun content(token: String): String {
        val pid = ProcessHandle.current().pid()
        return "token=$token\npid=$pid\nstart=${processStartMarker(pid)}\n"
    }

    fun removeIfOwnerless(path: Path): Boolean {
        if (Files.exists(path.resolve(OWNER_FILE))) return false
        return runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
    }

    fun processStartMarker(pid: Long): String {
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

    private fun isProcessAlive(owner: Owner): Boolean {
        val handle = ProcessHandle.of(owner.pid).orElse(null) ?: return false
        if (!handle.isAlive) return false
        if (owner.start.isNotEmpty()) {
            val current = processStartMarker(owner.pid)
            if (current.isNotEmpty() && current != owner.start) return false
        }
        return true
    }

    private fun parse(text: String?): Owner? {
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

    private fun cleanupFailedPublish(
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
        val safe = raw.isNullOrBlank() || parse(raw)?.token == token
        if (!safe) return
        runCatching { Files.deleteIfExists(ownerPath) }
        runCatching { Files.deleteIfExists(path) }
    }

    fun atomicMove(
        from: Path,
        to: Path,
    ) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    fun deleteTree(path: Path) {
        runCatching {
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun directoryModified(path: Path): Long? = fileModified(path)

    private fun fileModified(path: Path): Long? =
        try {
            Files.getLastModifiedTime(path).toMillis()
        } catch (_: NoSuchFileException) {
            null
        }
}
