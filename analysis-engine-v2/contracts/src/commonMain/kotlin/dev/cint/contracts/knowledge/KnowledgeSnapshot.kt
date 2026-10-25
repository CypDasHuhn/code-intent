package dev.cint.contracts.knowledge

import dev.cint.contracts.util.currentTimeMillis
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
    @Required
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = 1,
    @Required
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val timestamp: Long = currentTimeMillis(),
) {
    init {
        require(version == SUPPORTED_VERSION) {
            "Unsupported KnowledgeSnapshot version: $version"
        }
        require(timestamp > 0) { "KnowledgeSnapshot.timestamp must be positive" }
    }

    companion object {
        const val SUPPORTED_VERSION = 1
    }
}
