package dev.cint.knowledge

import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object KnowledgeStoreJson {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = false
            classDiscriminator = "type"
        }

    fun encode(snapshot: KnowledgeSnapshot): ContractResult<String> {
        validateSnapshot(snapshot)?.let { return ResultFactory.failure(it) }
        return try {
            val document =
                KnowledgeDocument(
                    format = KnowledgeStoreFormat.FORMAT,
                    formatVersion = KnowledgeStoreFormat.VERSION,
                    snapshot = snapshot,
                )
            ResultFactory.success(json.encodeToString(document))
        } catch (error: Exception) {
            ResultFactory.failure(
                ContractError.SerializationError("Unable to encode knowledge store", error.message),
            )
        }
    }

    fun decode(text: String): ContractResult<KnowledgeSnapshot> {
        val document =
            try {
                json.decodeFromString<KnowledgeDocument>(text)
            } catch (error: Exception) {
                return ResultFactory.failure(
                    ContractError.SerializationError("Unable to decode knowledge store", error.message),
                )
            }

        if (document.format != KnowledgeStoreFormat.FORMAT) {
            return ResultFactory.failure(
                ContractError.ValidationError(
                    "Unsupported knowledge store format: ${document.format}",
                    "format",
                ),
            )
        }
        if (document.formatVersion != KnowledgeStoreFormat.VERSION) {
            return ResultFactory.failure(
                ContractError.ValidationError(
                    "Unsupported knowledge store format version: ${document.formatVersion}; " +
                        "expected ${KnowledgeStoreFormat.VERSION}",
                    "formatVersion",
                ),
            )
        }
        validateSnapshot(document.snapshot)?.let { return ResultFactory.failure(it) }
        return ResultFactory.success(document.snapshot)
    }
}

@Serializable
private data class KnowledgeDocument(
    val format: String = KnowledgeStoreFormat.FORMAT,
    val formatVersion: Int,
    val snapshot: KnowledgeSnapshot,
)
