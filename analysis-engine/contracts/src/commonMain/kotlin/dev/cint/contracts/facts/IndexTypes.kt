package dev.cint.contracts.facts

import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Span
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface SourceInput {
    @Serializable
    @SerialName("FilePath")
    data class FilePath(
        val path: String,
        val languageHint: String? = null,
        override val version: Long = 0,
    ) : SourceInput

    @Serializable
    @SerialName("InMemory")
    data class InMemory(
        val text: String,
        val language: String,
        override val version: Long = 0,
        val path: String? = null,
        override val documentId: String? = null,
    ) : SourceInput

    val version: Long
        get() =
            when (this) {
                is FilePath -> version
                is InMemory -> version
            }

    val documentId: String?
        get() =
            when (this) {
                is FilePath -> path
                is InMemory -> documentId
            }
}

@Serializable
data class SemanticFact(
    val request: SemanticRequest,
    val result: SemanticResult,
    val providerEpoch: Long,
)

@Serializable
data class IndexedSource(
    val input: SourceInput,
    val facts: FactBatch,
    val sourceEpoch: Long,
    val completeness: Completeness,
    val contentHash: String,
    val semanticFacts: List<SemanticFact> = emptyList(),
)
