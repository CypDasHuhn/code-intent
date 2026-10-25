package dev.cint.contracts.util

import kotlinx.serialization.Serializable

@Serializable
data class ContentHash(val value: String) {
    companion object {
        fun compute(data: ByteArray): ContentHash =
            ContentHash(bytesToHex(data))

        fun compute(data: String): ContentHash = compute(stringToByteArray(data))

        val EMPTY: ContentHash = ContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    override fun toString(): String = value
}

@Serializable
data class NodeHash(val value: String) {
    companion object {
        fun compute(nodeType: String, children: List<NodeHash>, content: ContentHash): NodeHash {
            val combined = buildString {
                append(nodeType)
                append(":")
                children.forEach { append(it.value) }
                append(":")
                append(content.value)
            }
            return compute(stringToByteArray(combined))
        }

        fun compute(data: ByteArray): NodeHash =
            NodeHash(bytesToHex(data))

        fun compute(data: String): NodeHash = compute(stringToByteArray(data))

        val EMPTY: NodeHash = NodeHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
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
        fun function(selector: dev.cint.contracts.knowledge.FunctionSelector, contentHash: ContentHash): SelectorIdentity =
            SelectorIdentity(SelectorKind.FUNCTION, selector.identityKey, contentHash)

        fun span(selector: dev.cint.contracts.knowledge.SpanSelector, contentHash: ContentHash): SelectorIdentity =
            SelectorIdentity(SelectorKind.SPAN, selector.identityKey, contentHash)
    }
}

@Serializable
enum class SelectorKind {
    FUNCTION,
    SPAN,
    PATTERN,
}

fun computeSelectorHash(selector: Any, contentHash: ContentHash): ContentHash =
    ContentHash.compute("${selector.hashCode()}:${contentHash.value}")

private fun bytesToHex(bytes: ByteArray): String {
    val hex = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val bInt = b.toInt()
        val hi = (bInt ushr 4) and 0xF
        val lo = bInt and 0xF
        hex.append(if (hi < 10) ('0' + hi) else ('a' + hi - 10))
        hex.append(if (lo < 10) ('0' + lo) else ('a' + lo - 10))
    }
    return hex.toString()
}

private fun stringToByteArray(str: String): ByteArray {
    val result = ByteArray(str.length)
    for (i in str.indices) {
        result[i] = str[i].code.toByte()
    }
    return result
}

expect fun sha256(data: ByteArray): ByteArray