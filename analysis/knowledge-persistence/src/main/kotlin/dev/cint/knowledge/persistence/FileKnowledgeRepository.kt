package dev.cint.knowledge.persistence

import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.ports.KnowledgeRepository
import dev.cint.contracts.ports.KnowledgeRepositoryFactory
import dev.cint.contracts.ports.KnowledgeRepositoryLocation
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import java.nio.file.InvalidPathException
import java.nio.file.Path

class FileKnowledgeRepository private constructor(
    private val path: Path,
) : KnowledgeRepository {
    override fun load(cancellation: CancellationToken): ContractResult<KnowledgeSnapshot?> =
        KnowledgeStoreFiles.loadIfPresent(path, cancellation)

    override fun save(
        snapshot: KnowledgeSnapshot,
        cancellation: CancellationToken,
    ): ContractResult<Unit> = KnowledgeStoreFiles.save(path, snapshot, cancellation)

    fun path(): Path = path

    companion object {
        fun open(location: KnowledgeRepositoryLocation): ContractResult<FileKnowledgeRepository> {
            val rootRaw = location.rootPath
            val root =
                if (rootRaw.isNullOrBlank()) {
                    null
                } else {
                    parseRoot(rootRaw) ?: return ResultFactory.failure(rootError(rootRaw))
                }
            val pathRaw = location.path
            val path =
                if (pathRaw.isNullOrBlank()) {
                    null
                } else {
                    parsePath(pathRaw) ?: return ResultFactory.failure(pathError(pathRaw))
                }
            val resolved =
                when {
                    path == null -> {
                        val rootPath =
                            root
                                ?: return ResultFactory.failure(
                                    ContractError.InvalidInput(
                                        "Knowledge location requires a root or path",
                                        "knowledgePath",
                                    ),
                                )
                        rootPath.resolve(DEFAULT_KNOWLEDGE_PATH)
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
            return ResultFactory.success(FileKnowledgeRepository(resolved.toAbsolutePath().normalize()))
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

        private fun rootError(raw: String?): ContractError = ContractError.InvalidInput("Invalid project root path '$raw'", "rootPath")

        private fun pathError(raw: String?): ContractError =
            ContractError.InvalidInput("Invalid knowledge store path '$raw'", "knowledgePath")
    }
}

object FileKnowledgeRepositoryFactory : KnowledgeRepositoryFactory {
    override fun open(location: KnowledgeRepositoryLocation): ContractResult<KnowledgeRepository> = FileKnowledgeRepository.open(location)
}

private val DEFAULT_KNOWLEDGE_PATH: Path = Path.of(".cint", "knowledge.json")
