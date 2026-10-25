package dev.cint.contracts.util

import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.PatternSelector
import dev.cint.contracts.knowledge.Selector
import dev.cint.contracts.knowledge.SpanSelector
import kotlinx.serialization.Serializable

@Serializable
data class ContentHash(val value: String) {
    companion object {
        fun compute(data: ByteArray): ContentHash = ContentHash(sha256(data).toHex())

        fun compute(data: String): ContentHash = compute(data.encodeToByteArray())

        val EMPTY: ContentHash = compute(ByteArray(0))
    }

    override fun toString(): String = value
}

@Serializable
data class NodeHash(val value: String) {
    companion object {
        fun compute(nodeType: String, children: List<NodeHash>, content: ContentHash): NodeHash {
            val combined = buildString {
                append(nodeType.length)
                append(':')
                append(nodeType)
                children.forEach {
                    append(it.value.length)
                    append(':')
                    append(it.value)
                }
                append(content.value.length)
                append(':')
                append(content.value)
            }
            return compute(combined.encodeToByteArray())
        }

        fun compute(data: ByteArray): NodeHash = NodeHash(sha256(data).toHex())

        fun compute(data: String): NodeHash = compute(data.encodeToByteArray())

        val EMPTY: NodeHash = compute(ByteArray(0))
    }

    override fun toString(): String = value
}

@Serializable
data class SelectorIdentity(
    val kind: SelectorKind,
    val key: String,
    val contentHash: ContentHash,
) {
    override fun toString(): String = "$kind:$key:${contentHash.value.take(16)}"

    companion object {
        fun function(selector: FunctionSelector, contentHash: ContentHash): SelectorIdentity =
            SelectorIdentity(SelectorKind.FUNCTION, selector.identityKey, contentHash)

        fun span(selector: SpanSelector, contentHash: ContentHash): SelectorIdentity =
            SelectorIdentity(SelectorKind.SPAN, selector.identityKey, contentHash)
    }
}

@Serializable
enum class SelectorKind {
    FUNCTION,
    SPAN,
    PATTERN,
}

fun computeSelectorHash(selector: Selector, contentHash: ContentHash): ContentHash {
    val kind = when (selector) {
        is FunctionSelector -> SelectorKind.FUNCTION
        is SpanSelector -> SelectorKind.SPAN
        is PatternSelector -> SelectorKind.PATTERN
    }
    return computeSelectorHash(SelectorIdentity(kind, selector.identityKey, contentHash))
}

fun computeSelectorHash(identity: SelectorIdentity): ContentHash {
    val combined = buildString {
        appendField(identity.kind.name)
        appendField(identity.key)
        appendField(identity.contentHash.value)
    }
    return ContentHash.compute(combined)
}

private fun StringBuilder.appendField(value: String) {
    append(value.length)
    append(':')
    append(value)
}

private fun ByteArray.toHex(): String {
    val hex = StringBuilder(size * 2)
    for (b in this) {
        val bInt = b.toInt() and 0xFF
        val hi = (bInt ushr 4) and 0xF
        val lo = bInt and 0xF
        hex.append(HEX_DIGITS[hi])
        hex.append(HEX_DIGITS[lo])
    }
    return hex.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"

expect fun sha256(data: ByteArray): ByteArray
