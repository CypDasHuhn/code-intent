package dev.cint.contracts.ports

import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import kotlinx.serialization.Serializable

@Serializable
data class KnowledgeRepositoryLocation(
    val rootPath: String? = null,
    val path: String? = null,
)

interface KnowledgeRepository {
    fun load(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<KnowledgeSnapshot?>

    fun save(
        snapshot: KnowledgeSnapshot,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>
}

fun interface KnowledgeRepositoryFactory {
    fun open(location: KnowledgeRepositoryLocation): ContractResult<KnowledgeRepository>
}

fun interface KnowledgeStoreFactory {
    fun persistent(
        repository: KnowledgeRepository,
        delegate: KnowledgeStore,
    ): KnowledgeStore
}
