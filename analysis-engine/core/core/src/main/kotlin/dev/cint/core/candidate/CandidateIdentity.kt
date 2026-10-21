package dev.cint.core.candidate

import dev.cint.core.support.hashing.contentHash as canonicalContentHash

object CandidateIdentity {
    fun of(
        path: String,
        contentHash: String,
        startByte: Int,
        endByte: Int,
        intents: Collection<String>,
        reason: String,
    ): String {
        val canonical =
            listOf(
                path,
                contentHash,
                startByte.toString(),
                endByte.toString(),
                intents.sorted().joinToString(","),
                reason,
            ).joinToString("\n")
        return canonicalContentHash(canonical)
    }

    fun contentHash(text: String): String = canonicalContentHash(text)
}
