package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Span
import kotlinx.serialization.Serializable

@Serializable
sealed interface Selector {
    val identityKey: String
}

@Serializable
data class FunctionSelector(
    val qualifiedName: String = "",
    val importPath: String? = null,
    val receiverType: String? = null,
    val declaringType: String? = null,
    val signature: String? = null,
    val structuralPattern: String? = null,
) : Selector {
    init {
        require(
            listOf(qualifiedName, importPath, receiverType, declaringType, signature, structuralPattern)
                .any { !it.isNullOrBlank() },
        ) {
            "FunctionSelector must define at least one selector field"
        }
    }

    override val identityKey: String
        get() =
            canonicalSelectorKey(
                "FUNCTION",
                qualifiedName,
                importPath,
                receiverType,
                declaringType,
                signature,
                structuralPattern,
            )

    fun matches(other: FunctionSelector): Boolean =
        fieldMatches(qualifiedName, other.qualifiedName) &&
            fieldMatches(importPath, other.importPath) &&
            fieldMatches(receiverType, other.receiverType) &&
            fieldMatches(declaringType, other.declaringType) &&
            fieldMatches(signature, other.signature) &&
            fieldMatches(structuralPattern, other.structuralPattern)

    override fun toString(): String = identityKey

    private fun fieldMatches(
        expected: String?,
        actual: String?,
    ): Boolean = expected.isNullOrBlank() || expected == actual
}

@Serializable
data class SpanSelector(
    val path: String,
    val span: Span,
    val contentHash: String,
) : Selector {
    init {
        require(path.isNotBlank()) { "SpanSelector.path must not be blank" }
        require(contentHash.isNotBlank()) { "SpanSelector.contentHash must not be blank" }
    }

    override val identityKey: String
        get() =
            canonicalSelectorKey(
                "SPAN",
                path,
                span.startLine.toString(),
                span.startCol.toString(),
                span.endLine.toString(),
                span.endCol.toString(),
                contentHash,
            )

    override fun toString(): String = identityKey
}

@Serializable
data class PatternSelector(
    val pattern: String,
    val path: String? = null,
) : Selector {
    init {
        require(pattern.isNotBlank()) { "PatternSelector.pattern must not be blank" }
    }

    override val identityKey: String
        get() = canonicalSelectorKey("PATTERN", pattern, path)

    override fun toString(): String = identityKey
}

private fun canonicalSelectorKey(
    kind: String,
    vararg fields: String?,
): String =
    buildString {
        append(kind)
        fields.forEach { raw ->
            val value = raw?.takeIf { it.isNotBlank() }
            if (value == null) {
                append("|null")
            } else {
                append('|')
                append(value.length)
                append(':')
                append(value)
            }
        }
    }
