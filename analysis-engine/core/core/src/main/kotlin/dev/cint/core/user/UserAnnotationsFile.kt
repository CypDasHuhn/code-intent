package dev.cint.core.user

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object UserAnnotationsFile {
    const val DIRECTORY: String = ".cint"
    const val FILE_NAME: String = "user-annotations.json"

    private val locks = ConcurrentHashMap<Path, Any>()

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
            withFileLock(root) {
                val existing = loadTolerant(root)
                val kept =
                    existing.filterNot {
                        it.path == annotation.path &&
                            it.span == annotation.span &&
                            it.intents == annotation.intents
                    }
                val updated = kept + annotation
                write(root, updated)
                updated
            }
        }

    fun save(
        root: Path,
        annotations: List<UserAnnotation>,
    ) {
        synchronized(lockFor(root)) {
            withFileLock(root) {
                write(root, annotations)
            }
        }
    }

    private fun lockFor(root: Path): Any = locks.computeIfAbsent(root.toAbsolutePath().normalize()) { Any() }

    private fun <T> withFileLock(
        root: Path,
        block: () -> T,
    ): T {
        val directory = path(root).parent
        Files.createDirectories(directory)
        val lockPath = directory.resolve("$FILE_NAME.lock")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            try {
                Files.createDirectory(lockPath)
                break
            } catch (_: FileAlreadyExistsException) {
                val age = System.currentTimeMillis() - Files.getLastModifiedTime(lockPath).toMillis()
                if (age > TimeUnit.SECONDS.toMillis(10)) {
                    runCatching { Files.delete(lockPath) }
                }
                if (System.nanoTime() > deadline) {
                    throw IllegalStateException("timed out waiting for $lockPath")
                }
                Thread.sleep(10)
            }
        }
        return try {
            block()
        } finally {
            runCatching { Files.deleteIfExists(lockPath) }
        }
    }

    private fun write(
        root: Path,
        annotations: List<UserAnnotation>,
    ) {
        val path = path(root)
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, UserAnnotationsJson.encode(annotations))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
