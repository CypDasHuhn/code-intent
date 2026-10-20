package dev.cint.core.host

import dev.cint.core.config.loadIntentConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostConfigTest {
    @Test
    fun validCustomConfigWarnsAndFallsBackToDefaults() {
        val file = Files.createTempFile("cint-config", ".yaml")
        Files.writeString(
            file,
            """
            intents:
              LOGGING:
                blocks: [print]
            """.trimIndent(),
        )

        try {
            assertEquals(loadIntentConfig(null, "kotlin"), HostConfig.load(file.toString(), "kotlin"))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun missingCustomConfigIsAHardFailure() {
        val file = Files.createTempDirectory("cint-config-missing").resolve("absent.yaml")

        assertFailsWith<IllegalArgumentException> {
            HostConfig.load(file.toString(), "kotlin")
        }
    }

    @Test
    fun invalidCustomConfigIsAHardFailure() {
        val file = Files.createTempFile("cint-config-invalid", ".yaml")
        try {
            assertFailsWith<IllegalStateException> {
                HostConfig.load(file.toString(), "kotlin")
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
