package dev.cint.contracts.knowledge

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class KnowledgeSnapshot(
    val functionAnnotations: List<FunctionAnnotation>,
    val spanOverrides: List<SpanOverride>,
    val patternHypotheses: List<PatternHypothesis>,
    val packOrigins: Map<String, PackOrigin> = emptyMap(),
    val materialized: Boolean = false,
    @Required
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = SUPPORTED_VERSION,
    @Required
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val timestamp: Long,
) {
    init {
        require(version == SUPPORTED_VERSION) {
            "Unsupported KnowledgeSnapshot version: $version"
        }
        require(timestamp > 0) { "KnowledgeSnapshot.timestamp must be positive" }
        require(packOrigins.keys.all { it.isNotBlank() }) {
            "KnowledgeSnapshot.packOrigins keys must not be blank"
        }
    }

    companion object {
        const val SUPPORTED_VERSION = 1
    }
}
