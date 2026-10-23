package dev.cint.core.cache

import dev.cint.core.support.hashing.contentHash
import dev.cint.core.support.hashing.normalizedSourceText

data class DeterministicEnvironment(
    val engineVersion: String,
    val packFingerprint: String,
    val configFingerprint: String,
    val userAnnotationsHash: String,
    val aiRestrictionsHash: String,
    val languageProfileVersion: String,
    val oracleMode: String,
) {
    val hash: String =
        contentHash(
            listOf(
                engineVersion,
                packFingerprint,
                configFingerprint,
                userAnnotationsHash,
                aiRestrictionsHash,
                languageProfileVersion,
                oracleMode,
            ).joinToString("\n"),
        )
}

data class AiEnvironment(
    val deterministic: DeterministicEnvironment,
    val provider: String?,
    val model: String?,
    val promptVersion: String,
    val vocabularyVersion: String,
) {
    val hash: String =
        contentHash(
            listOf(
                deterministic.hash,
                provider.orEmpty(),
                model.orEmpty(),
                promptVersion,
                vocabularyVersion,
            ).joinToString("\n"),
        )
}

object AnchorFingerprint {
    fun of(
        path: String,
        text: String,
        intents: Collection<String> = emptyList(),
        reason: String = "",
        context: String = "",
    ): String =
        contentHash(
            listOf(
                path,
                normalizedSourceText(text),
                intents.sorted().joinToString(","),
                reason,
                context,
            ).joinToString("\n"),
        )
}
