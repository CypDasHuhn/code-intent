package dev.cint.server

import dev.cint.core.support.hashing.CodeFingerprint
import dev.cint.server.live.ENGINE_PACKAGE_PREFIXES
import dev.cint.server.live.LANGUAGE_PACKAGE_PREFIXES
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineCodeCoverageGuardTest {
    @Test
    fun moduleListMatchesTheFingerprintedEngineSurface() {
        val settings = settingsFile()
        val declared =
            Regex("""project\(":[^"]+"\)\.projectDir = file\("([^"]+)"\)""")
                .findAll(Files.readString(settings))
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(
            setOf(
                "core/api",
                "core/rulepacks",
                "core/core",
                "core/suggest",
                "core/host",
                "languages/treesitter",
                "languages/lsp",
                "languages/frontend",
                "hosts/cli",
                "hosts/server",
                "tests/rulepacks-tests",
                "tests/test-fixtures",
                "tests/integration-tests",
            ),
            declared,
        )

        val coveredPrefixes = ENGINE_PACKAGE_PREFIXES + LANGUAGE_PACKAGE_PREFIXES
        val coveredPackages =
            coveredPrefixes.map { it.removePrefix("dev/cint/").removeSuffix("/") }.toSet()
        assertEquals(
            setOf("api", "core", "suggest", "host", "server", "lsp", "rulepacks", "frontend", "treesitter"),
            coveredPackages,
        )

        val anchors =
            listOf(
                dev.cint.api.Annotation::class.java,
                dev.cint.core.runtime.scheduler.RuleEngine::class.java,
                dev.cint.suggest.session.AnnotationSession::class.java,
                dev.cint.host.HostPipeline::class.java,
                LiveServer::class.java,
                Class.forName("dev.cint.lsp.KotlinLspOracle"),
                Class.forName("dev.cint.rulepacks.BundledRulePackConfigResolver"),
                dev.cint.frontend.KotlinFrontend::class.java,
                Class.forName("dev.cint.treesitter.KotlinParser"),
            )
        for (prefix in coveredPrefixes) {
            assertTrue(
                CodeFingerprint
                    .ofPackages(
                        label = "coverage:$prefix",
                        prefixes = listOf(prefix),
                        anchors = anchors,
                    ).isNotBlank(),
                "no fingerprint entries for $prefix",
            )
        }
    }

    private fun settingsFile(): Path {
        val start = Path.of("").toAbsolutePath()
        return generateSequence(start) { it.parent }
            .map { it.resolve("analysis-engine").resolve("settings.gradle.kts") }
            .firstOrNull(Files::isRegularFile)
            ?: error("could not locate analysis-engine/settings.gradle.kts from $start")
    }
}
