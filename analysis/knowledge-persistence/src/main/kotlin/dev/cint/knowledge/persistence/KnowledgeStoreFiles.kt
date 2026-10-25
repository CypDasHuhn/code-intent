package dev.cint.knowledge.persistence

import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.knowledge.KnowledgeStoreJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object KnowledgeStoreFiles {
    fun load(
        path: Path,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<KnowledgeSnapshot> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        if (!Files.isRegularFile(path)) {
            return ResultFactory.failure(ContractError.NotFound("knowledge store", path.toString()))
        }
        val text =
            try {
                Files.readString(path, StandardCharsets.UTF_8)
            } catch (error: Exception) {
                return ResultFactory.failure(
                    ContractError.SerializationError("Unable to read knowledge store", error.message),
                )
            }
        return KnowledgeStoreJson.decode(text)
    }

    fun loadIfPresent(
        path: Path,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<KnowledgeSnapshot?> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return if (Files.isRegularFile(path)) {
            load(path, cancellation).map { it }
        } else {
            ResultFactory.success(null)
        }
    }

    fun save(
        path: Path,
        snapshot: KnowledgeSnapshot,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val encoded = KnowledgeStoreJson.encode(snapshot)
        if (encoded.isFailure) {
            return ResultFactory.failure(
                encoded.errorOrNull ?: ContractError.InternalError("Unable to encode knowledge store"),
            )
        }
        val text = encoded.getOrThrow()
        return try {
            AtomicFileWrites.writeText(path, text, ".knowledge-")
            ResultFactory.success(Unit)
        } catch (error: Exception) {
            ResultFactory.failure(
                ContractError.SerializationError("Unable to write knowledge store", error.message),
            )
        }
    }

    fun exists(path: Path): Boolean = Files.isRegularFile(path)

    private fun cancellationError(cancellation: CancellationToken): ContractError? = cancellation.throwIfCancelled().errorOrNull
}
