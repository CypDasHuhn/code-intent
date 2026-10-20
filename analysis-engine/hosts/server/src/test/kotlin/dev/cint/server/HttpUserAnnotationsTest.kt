package dev.cint.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.Span
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationsJson
import dev.cint.host.HostConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class HttpUserAnnotationsTest {
    @Test
    fun httpAnalysisLoadsAnnotationsFromTheRequestRoot() {
        val requestRoot = Files.createTempDirectory("cint-http-user-annotations")
        try {
            val file = requestRoot.resolve(".cint/user-annotations.json")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                UserAnnotationsJson.encode(
                    listOf(
                        UserAnnotation(
                            path = "Sample.kt",
                            span = Span(0, 4, 0, 8),
                            intents = setOf("TEAM_CUSTOM_INTENT"),
                            symbol = "make",
                            contentHash = contentHash("make"),
                        ),
                    ),
                ),
            )
            val text = "fun make(): String = \"x\""

            val result =
                analyze(
                    AnalyzeRequest(
                        path = "Sample.kt",
                        root = requestRoot.toString(),
                        text = text,
                        language = "kotlin",
                    ),
                    useLsp = false,
                    defaultLanguage = "kotlin",
                    defaultSettings = HostConfig.load(null),
                )

            val artifact = jacksonObjectMapper().readTree(result)
            val spans = artifact["files"][0]["spans"]
            assertTrue(artifact["intents"].any { it.asText() == "TEAM_CUSTOM_INTENT" })
            assertTrue(
                spans.any { span ->
                    span["intents"].any { it.asText() == "TEAM_CUSTOM_INTENT" } &&
                        span["provenance"].any { it["source"].asText() == "user" }
                },
            )
        } finally {
            requestRoot.toFile().deleteRecursively()
        }
    }
}
