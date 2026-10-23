package dev.cint.suggest.cache

import dev.cint.core.support.hashing.contentHash
import dev.cint.suggest.session.SessionUnit

data class CachedVerdictOperation(
    val intent: String,
    val startOffset: Int,
    val endOffset: Int,
    val confidence: Double,
    val rationale: String? = null,
    val startToken: Int? = null,
    val endToken: Int? = null,
)

data class CachedVerdict(
    val key: String,
    val aiEnv: String,
    val path: String,
    val language: String,
    val nodeKind: String,
    val textDigest: String,
    val rawTextDigest: String,
    val importsDigest: String,
    val symbolDigest: String,
    val signatureDigest: String,
    val referencesDigest: String,
    val existingIntentDigest: String,
    val adds: List<CachedVerdictOperation>,
    val removes: List<CachedVerdictOperation>,
    val none: Boolean,
    val anchorFingerprint: String,
    val startByte: Int,
    val endByte: Int,
    val createdAt: Long,
    val lastUsed: Long,
    val normalizedText: String? = null,
    val symbol: String? = null,
) {
    fun touched(now: Long): CachedVerdict = copy(lastUsed = now)
}

class VerdictCache(
    private val maxEntries: Int = 5_000,
    private val ttlMillis: Long = 720L * 60L * 60L * 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = LinkedHashMap<String, CachedVerdict>()

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun snapshot(): List<CachedVerdict> = entries.values.toList()

    @Synchronized
    fun restore(entries: Collection<CachedVerdict>) {
        this.entries.clear()
        for (entry in entries) {
            if (maxEntries <= 0) break
            this.entries[entry.key] = entry
        }
        prune()
    }

    @Synchronized
    fun lookup(
        key: UnitCacheKey,
        unit: SessionUnit,
        invalidation: CacheInvalidation = CacheInvalidation.NONE,
    ): CachedVerdict? {
        val now = clock()
        val entry = entries[key.hash] ?: return null
        if (expired(entry, now)) {
            entries.remove(key.hash)
            return null
        }
        if (!invalidation.allows(unit)) return null
        val touched = entry.touched(now)
        entries[key.hash] = touched
        return touched
    }

    @Synchronized
    fun store(
        key: UnitCacheKey,
        unit: SessionUnit,
        adds: List<CachedVerdictOperation>,
        removes: List<CachedVerdictOperation>,
        none: Boolean,
        anchorFingerprint: String,
    ): CachedVerdict? {
        if (maxEntries <= 0) return null
        val now = clock()
        val entry =
            CachedVerdict(
                key = key.hash,
                aiEnv = key.aiEnv,
                path = unit.path,
                language = key.language,
                nodeKind = key.nodeKind,
                textDigest = key.textDigest,
                rawTextDigest = contentHash(unit.text),
                importsDigest = key.importsDigest,
                symbolDigest = key.symbolDigest,
                signatureDigest = key.signatureDigest,
                referencesDigest = key.referencesDigest,
                existingIntentDigest = key.existingIntentDigest,
                adds = adds,
                removes = removes,
                none = none,
                anchorFingerprint = anchorFingerprint,
                startByte = unit.startByte,
                endByte = unit.endByte,
                createdAt = now,
                lastUsed = now,
                normalizedText = unit.normalizedText,
                symbol = unit.enclosingSymbol,
            )
        entries[key.hash] = entry
        prune()
        return entry
    }

    @Synchronized
    fun remove(key: String): CachedVerdict? = entries.remove(key)

    @Synchronized
    fun similar(
        unit: SessionUnit,
        aiEnv: String,
        threshold: Double,
    ): CachedVerdict? {
        if (threshold <= 0.0) return null
        val now = clock()
        var best: CachedVerdict? = null
        var bestScore = threshold
        val candidates = entries.values.toList()
        for (entry in candidates) {
            if (expired(entry, now)) {
                entries.remove(entry.key)
                continue
            }
            if (entry.aiEnv != aiEnv || entry.language != unit.language) continue
            val prior = entry.normalizedText ?: continue
            if (entry.symbol != unit.enclosingSymbol) continue
            val score = similarity(prior, unit.normalizedText)
            if (score >= bestScore) {
                best = entry
                bestScore = score
            }
        }
        return best
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun prune() {
        val now = clock()
        entries.values.removeIf { expired(it, now) }
        if (maxEntries > 0) {
            while (entries.size > maxEntries) {
                val oldest = entries.minByOrNull { it.value.lastUsed }?.key ?: break
                entries.remove(oldest)
            }
        }
    }

    private fun expired(
        entry: CachedVerdict,
        now: Long,
    ): Boolean = ttlMillis > 0 && now - entry.lastUsed > ttlMillis

    private fun similarity(
        left: String,
        right: String,
    ): Double {
        if (left == right) return 1.0
        val leftTokens = left.split(' ').filter { it.isNotEmpty() }
        val rightTokens = right.split(' ').filter { it.isNotEmpty() }
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val counts = mutableMapOf<String, Int>()
        for (token in leftTokens) counts[token] = (counts[token] ?: 0) + 1
        var intersection = 0
        for (token in rightTokens) {
            val count = counts[token] ?: 0
            if (count > 0) {
                intersection += 1
                counts[token] = count - 1
            }
        }
        return 2.0 * intersection / (leftTokens.size + rightTokens.size)
    }
}
