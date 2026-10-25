package dev.cint.contracts.facts

import dev.cint.contracts.data.Span
import dev.cint.contracts.data.Completeness
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface SourceInput {
    @Serializable
    @SerialName("FilePath")
    data class FilePath(
        val path: String,
        val languageHint: String? = null,
        val version: Long = 0,
    ) : SourceInput

    @Serializable
    @SerialName("InMemory")
    data class InMemory(
        val text: String,
        val language: String,
        val version: Long = 0,
        val path: String? = null,
    ) : SourceInput
}

@Serializable
data class IndexedSource(
    val input: SourceInput,
    val facts: FactBatch,
    val sourceEpoch: Long,
    val completeness: Completeness,
    val contentHash: String,
)