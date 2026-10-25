package dev.cint.v2.server

import dev.cint.ipc.DocumentParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

internal data class OpenDocument(
    val uri: String,
    val text: String,
    val version: Int,
    val languageId: String,
    val generation: Long,
)

internal class DocumentStore(
    private val defaultLanguage: String?,
) {
    private val documents = linkedMapOf<String, OpenDocument>()
    private val revisions = mutableMapOf<String, AtomicLong>()
    private val configRevisions = mutableMapOf<String, AtomicLong>()
    private val publishLocks = ConcurrentHashMap<String, ReentrantLock>()
    private var generations = 0L

    fun open(params: DocumentParams): OpenDocument? =
        withPublishLock(params.uri) {
            synchronized(this) {
                val current = documents[params.uri]
                if (current != null && params.version <= current.version) return@synchronized null
                replace(params.uri, params.text, params.version, params.languageId)
            }
        }

    fun change(params: DocumentParams): OpenDocument? =
        withPublishLock(params.uri) {
            synchronized(this) {
                val current = documents[params.uri] ?: return@synchronized null
                if (params.version <= current.version) return@synchronized null
                replace(params.uri, params.text, params.version, params.languageId)
            }
        }

    fun close(uri: String): Boolean =
        withPublishLock(uri) {
            synchronized(this) { documents.remove(uri) != null }
        }

    @Synchronized
    fun nextRevision(uri: String): Long = revisions.computeIfAbsent(uri) { AtomicLong(0) }.incrementAndGet()

    @Synchronized
    fun bumpRevisions() {
        documents.keys.forEach { uri ->
            nextRevision(uri)
            configRevisions.computeIfAbsent(uri) { AtomicLong(0) }.incrementAndGet()
        }
    }

    @Synchronized
    fun configRevision(uri: String): Long = configRevisions[uri]?.get() ?: 0

    @Synchronized
    fun current(uri: String): OpenDocument? = documents[uri]

    @Synchronized
    fun isCurrent(
        uri: String,
        generation: Long,
    ): Boolean = documents[uri]?.generation == generation

    @Synchronized
    fun activeCount(): Int = documents.size

    fun publishIfCurrent(
        uri: String,
        generation: Long,
        publish: (Long) -> Unit,
    ): Boolean {
        val lock = publishLocks.computeIfAbsent(uri) { ReentrantLock() }
        lock.lock()
        try {
            val revision =
                synchronized(this) {
                    if (documents[uri]?.generation != generation) {
                        null
                    } else {
                        revisions.computeIfAbsent(uri) { AtomicLong(0) }.incrementAndGet()
                    }
                } ?: return false
            publish(revision)
            return true
        } finally {
            lock.unlock()
        }
    }

    private fun <T> withPublishLock(
        uri: String,
        block: () -> T,
    ): T {
        val lock = publishLocks.computeIfAbsent(uri) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun replace(
        uri: String,
        text: String,
        version: Int,
        languageId: String,
    ): OpenDocument {
        generations += 1
        val document =
            OpenDocument(
                uri = uri,
                text = text,
                version = version,
                languageId = languageId.ifBlank { defaultLanguage ?: defaultLanguageFor(uri) },
                generation = generations,
            )
        documents[uri] = document
        return document
    }

    private fun defaultLanguageFor(uri: String): String =
        when (uri.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "vue" -> "vue"
            "cs" -> "csharp"
            else -> defaultLanguage ?: "kotlin"
        }
}
