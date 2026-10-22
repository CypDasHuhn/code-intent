package dev.cint.lsp

import dev.cint.core.facts.oracle.TypeFact

internal data class DocumentTypeKey(
    val uri: String,
    val version: Long,
    val offset: Int,
)

internal data class DocumentTypeEntry(
    val fact: TypeFact?,
    val cachedAtNanos: Long,
)

internal data class DocumentTypeLookup(
    val present: Boolean,
    val entry: DocumentTypeEntry?,
)

internal class DocumentTypeCache(
    private val maxEntries: Int,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries =
        object : LinkedHashMap<DocumentTypeKey, DocumentTypeEntry>(maxEntries + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DocumentTypeKey, DocumentTypeEntry>?): Boolean =
                size > maxEntries
        }

    @Synchronized
    fun lookup(key: DocumentTypeKey): DocumentTypeLookup {
        val entry = entries[key]
        return DocumentTypeLookup(entry != null, entry)
    }

    @Synchronized
    fun put(
        key: DocumentTypeKey,
        value: DocumentTypeEntry,
    ) {
        entries[key] = value
    }

    @Synchronized
    fun remove(key: DocumentTypeKey) {
        entries.remove(key)
    }

    @Synchronized
    fun invalidate(uri: String) {
        entries.keys.removeIf { it.uri == uri }
    }

    @Synchronized
    fun size(): Int = entries.size
}
