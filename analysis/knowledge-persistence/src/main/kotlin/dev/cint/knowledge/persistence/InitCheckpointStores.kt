package dev.cint.knowledge.persistence

import dev.cint.contracts.init.InitCheckpoint
import dev.cint.contracts.ports.InitCheckpointStore
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

class InMemoryInitCheckpointStore : InitCheckpointStore {
    private val lock = Any()
    private val checkpoints = linkedMapOf<String, InitCheckpoint>()

    override fun load(
        workflowId: String,
        cancellation: CancellationToken,
    ): ContractResult<InitCheckpoint?> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            ResultFactory.success(checkpoints[workflowId])
        }
    }

    override fun save(
        checkpoint: InitCheckpoint,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            val existing = checkpoints[checkpoint.workflowId]
            if (existing != null && checkpoint.revision < existing.revision) {
                return@synchronized ResultFactory.failure(staleRevision())
            }
            checkpoints[checkpoint.workflowId] = checkpoint
            ResultFactory.success(Unit)
        }
    }

    override fun update(
        workflowId: String,
        cancellation: CancellationToken,
        transform: (InitCheckpoint?) -> ContractResult<InitCheckpoint?>,
    ): ContractResult<InitCheckpoint?> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            val existing = checkpoints[workflowId]
            val transformed = transform(existing)
            if (transformed.isFailure) return@synchronized transformed
            val next = transformed.getOrThrow() ?: return@synchronized ResultFactory.success(null)
            if (next.workflowId != workflowId) {
                return@synchronized ResultFactory.failure(
                    ContractError.ValidationError(
                        "Checkpoint workflow id '${next.workflowId}' does not match '$workflowId'",
                        "workflowId",
                    ),
                )
            }
            val stored =
                next.copy(
                    revision = (existing?.revision ?: 0L) + 1L,
                    createdAt = existing?.createdAt ?: next.createdAt,
                )
            checkpoints[workflowId] = stored
            ResultFactory.success(stored)
        }
    }
}

class FileInitCheckpointStore private constructor(
    private val path: Path,
) : InitCheckpointStore {
    override fun load(
        workflowId: String,
        cancellation: CancellationToken,
    ): ContractResult<InitCheckpoint?> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return withLock {
            ResultFactory.success(readDocument().checkpoints[workflowId])
        }
    }

    override fun save(
        checkpoint: InitCheckpoint,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return withLock {
            val document = readDocument()
            val existing = document.checkpoints[checkpoint.workflowId]
            if (existing != null && checkpoint.revision < existing.revision) {
                return@withLock ResultFactory.failure(staleRevision())
            }
            writeDocument(
                document.copy(
                    checkpoints = document.checkpoints + (checkpoint.workflowId to checkpoint),
                ),
            )
            ResultFactory.success(Unit)
        }
    }

    override fun update(
        workflowId: String,
        cancellation: CancellationToken,
        transform: (InitCheckpoint?) -> ContractResult<InitCheckpoint?>,
    ): ContractResult<InitCheckpoint?> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return withLock {
            val document = readDocument()
            val existing = document.checkpoints[workflowId]
            val transformed = transform(existing)
            if (transformed.isFailure) return@withLock transformed
            val next = transformed.getOrThrow() ?: return@withLock ResultFactory.success(null)
            if (next.workflowId != workflowId) {
                return@withLock ResultFactory.failure(
                    ContractError.ValidationError(
                        "Checkpoint workflow id '${next.workflowId}' does not match '$workflowId'",
                        "workflowId",
                    ),
                )
            }
            val stored =
                next.copy(
                    revision = (existing?.revision ?: 0L) + 1L,
                    createdAt = existing?.createdAt ?: next.createdAt,
                )
            writeDocument(
                document.copy(
                    checkpoints = document.checkpoints + (workflowId to stored),
                ),
            )
            ResultFactory.success(stored)
        }
    }

    fun path(): Path = path

    private fun <T> withLock(block: () -> ContractResult<T>): ContractResult<T> {
        val target = path.toAbsolutePath()
        val parent = target.parent ?: Path.of(".").toAbsolutePath()
        val lockPath = target.resolveSibling("${target.fileName}.lock")
        val processLock = PROCESS_LOCKS.computeIfAbsent(target.toString()) { ReentrantLock() }
        processLock.lock()
        return try {
            Files.createDirectories(parent)
            FileChannel
                .open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    acquireFileLock(channel).use { block() }
                }
        } catch (error: Exception) {
            ResultFactory.failure(
                ContractError.SerializationError(
                    "Unable to lock init checkpoint",
                    error.message,
                ),
            )
        } finally {
            processLock.unlock()
        }
    }

    private fun acquireFileLock(channel: FileChannel): java.nio.channels.FileLock {
        var attempt = 0
        while (true) {
            try {
                return channel.lock()
            } catch (_: OverlappingFileLockException) {
                attempt += 1
                if (attempt >= MAX_LOCK_RETRIES) throw OverlappingFileLockException()
                Thread.sleep(LOCK_RETRY_MILLIS)
            }
        }
    }

    private fun readDocument(): InitCheckpointDocument {
        if (!Files.isRegularFile(path)) return InitCheckpointDocument()
        val text = Files.readString(path, StandardCharsets.UTF_8)
        return checkpointJson.decodeFromString(text)
    }

    private fun writeDocument(document: InitCheckpointDocument) {
        AtomicFileWrites.writeText(
            path,
            checkpointJson.encodeToString(document),
            ".init-checkpoint-",
        )
    }

    companion object {
        fun open(
            rootPath: String?,
            checkpointPath: String?,
        ): ContractResult<FileInitCheckpointStore> {
            val root =
                if (rootPath.isNullOrBlank()) {
                    null
                } else {
                    parseRoot(rootPath) ?: return ResultFactory.failure(rootError(rootPath))
                }
            val path =
                if (checkpointPath.isNullOrBlank()) {
                    null
                } else {
                    parsePath(checkpointPath) ?: return ResultFactory.failure(pathError(checkpointPath))
                }
            val resolved =
                when {
                    path == null -> {
                        val rootPathValue =
                            root
                                ?: return ResultFactory.failure(
                                    ContractError.InvalidInput(
                                        "Init checkpoint requires a root or path",
                                        "checkpointPath",
                                    ),
                                )
                        rootPathValue.resolve(DEFAULT_CHECKPOINT_PATH)
                    }

                    path.isAbsolute -> {
                        path
                    }

                    root != null -> {
                        root.resolve(path)
                    }

                    else -> {
                        path
                    }
                }
            return ResultFactory.success(
                FileInitCheckpointStore(resolved.toAbsolutePath().normalize()),
            )
        }

        private fun parseRoot(raw: String): Path? =
            try {
                Path.of(raw).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                null
            }

        private fun parsePath(raw: String): Path? =
            try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                null
            }

        private fun rootError(raw: String): ContractError = ContractError.InvalidInput("Invalid project root path '$raw'", "rootPath")

        private fun pathError(raw: String): ContractError = ContractError.InvalidInput("Invalid checkpoint path '$raw'", "checkpointPath")
    }
}

object InitCheckpointStores {
    fun open(
        rootPath: String?,
        checkpointPath: String?,
    ): ContractResult<InitCheckpointStore> =
        if (rootPath.isNullOrBlank() && checkpointPath.isNullOrBlank()) {
            ResultFactory.success(InMemoryInitCheckpointStore())
        } else {
            FileInitCheckpointStore.open(rootPath, checkpointPath).map { it }
        }

    fun inMemory(): InitCheckpointStore = InMemoryInitCheckpointStore()
}

@Serializable
private data class InitCheckpointDocument(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val checkpoints: Map<String, InitCheckpoint> = emptyMap(),
)

private fun staleRevision(): ContractError = ContractError.PreconditionFailed("Refusing to replace a newer init checkpoint revision")

private fun cancellationError(cancellation: CancellationToken): ContractError? = cancellation.throwIfCancelled().errorOrNull

private val checkpointJson =
    Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

private const val FORMAT = "cint-init-checkpoints"
private const val FORMAT_VERSION = 1
private val DEFAULT_CHECKPOINT_PATH: Path = Path.of(".cint", "init-checkpoint.json")
private val PROCESS_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
private const val MAX_LOCK_RETRIES = 100
private const val LOCK_RETRY_MILLIS = 5L
