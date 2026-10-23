package dev.cint.core.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EnvironmentIdentityTest {
    private val deterministic =
        DeterministicEnvironment(
            engineVersion = "engine-v1",
            packFingerprint = "packs",
            configFingerprint = "config",
            userAnnotationsHash = "user",
            aiRestrictionsHash = "restrictions",
            languageProfileVersion = "profile-v1",
            oracleMode = "true:750",
        )

    @Test
    fun everyDeterministicComponentChangesTheIdentity() {
        val hashes =
            listOf(
                deterministic.copy(engineVersion = "engine-v2").hash,
                deterministic.copy(packFingerprint = "packs-2").hash,
                deterministic.copy(configFingerprint = "config-2").hash,
                deterministic.copy(userAnnotationsHash = "user-2").hash,
                deterministic.copy(aiRestrictionsHash = "restrictions-2").hash,
                deterministic.copy(languageProfileVersion = "profile-v2").hash,
                deterministic.copy(oracleMode = "false:750").hash,
            )

        assertEquals(hashes.size, hashes.toSet().size)
        hashes.forEach { assertNotEquals(deterministic.hash, it) }
    }

    @Test
    fun everyAiComponentChangesTheIdentity() {
        val ai =
            AiEnvironment(
                deterministic = deterministic,
                provider = "deepseek",
                model = "deepseek-chat",
                promptVersion = "prompt-v1",
                vocabularyVersion = "vocab",
            )
        val hashes =
            listOf(
                ai.copy(deterministic = deterministic.copy(configFingerprint = "config-2")).hash,
                ai.copy(provider = "fake").hash,
                ai.copy(model = "other-model").hash,
                ai.copy(promptVersion = "prompt-v2").hash,
                ai.copy(vocabularyVersion = "vocab-2").hash,
            )

        assertEquals(hashes.size, hashes.toSet().size)
        hashes.forEach { assertNotEquals(ai.hash, it) }
    }
}
