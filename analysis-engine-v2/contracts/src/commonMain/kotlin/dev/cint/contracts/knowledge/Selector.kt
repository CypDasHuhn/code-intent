package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Role
import dev.cint.contracts.data.Span
import dev.cint.contracts.data.Status
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.datetime.Instant

@Serializable
data class FunctionSelector(
    val qualifiedName: String,
    val importPath: String? = null,
    val receiverType: String? = null,
    val declaringType: String? = null,
    val signature: String? = null,
    val structuralPattern: String? = null,
) {
    @Transient
    val identityKey: String
        get() = "fn:$qualifiedName${receiverType?.let { ":$it" } ?: ""}${signature?.let { "#$it" } ?: ""}"

    fun matches(other: FunctionSelector): Boolean {
        if (qualifiedName != other.qualifiedName) return false
        if (importPath != null && importPath != other.importPath) return false
        if (receiverType != null && receiverType != other.receiverType) return false
        if (declaringType != null && declaringType != other.declaringType) return false
        if (signature != null && signature != other.signature) return false
        return true
    }

    override fun toString(): String = identityKey
}

@Serializable
data class SpanSelector(
    val path: String,
    val span: Span,
    val contentHash: String,
) {
    @Transient
    val identityKey: String
        get() = "span:$path:${span.startOffset}-${span.endOffset}:$contentHash"

    override fun toString(): String = identityKey
}