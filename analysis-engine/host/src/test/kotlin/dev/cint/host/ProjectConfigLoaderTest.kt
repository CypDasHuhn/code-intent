package dev.cint.host

import dev.cint.core.runtime.rulepack.RulePackLoadFailureKind
import dev.cint.core.runtime.rulepack.RulePackLoader
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Enumeration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SERVICE_RESOURCE = "META-INF/services/dev.cint.api.RulePack"

private class IsolatedConfigClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    override fun getResources(name: String): Enumeration<URL> =
        if (name == SERVICE_RESOURCE) findResources(name) else super.getResources(name)
}

class ProjectConfigLoaderTest {
    @Test
    fun packOverridesAndCorePolicyLoadTogether() {
        FixtureConfigurablePack.reset()

        withConfigFile(
            """
            core:
              purity:
                annotations: [CoreOnly]
            fixture-configurable:
              calls: [audit]
            """.trimIndent(),
        ) { path ->
            val loaded = loader().load(path.toString())

            assertTrue(loaded.issues.isEmpty())
            assertTrue(loaded.failures.isEmpty())
            assertEquals(setOf("CoreOnly"), loaded.core.purityAnnotations.toSet())
            assertEquals(setOf("audit"), FixtureConfigurablePack.lastCalls)
            assertEquals(
                listOf("fixture-configurable", "fixture-good"),
                loaded.packs.map { it.id },
            )
        }
    }

    @Test
    fun noFileLoadsPackDefaults() {
        FixtureConfigurablePack.reset()

        withConfigFile("{}") { _ ->
            val loaded = loader().load(null)

            assertTrue(loaded.issues.isEmpty())
            assertTrue(loaded.failures.isEmpty())
            assertNull(FixtureConfigurablePack.lastCalls)
            assertEquals(
                listOf("fixture-configurable", "fixture-good"),
                loaded.packs.map { it.id },
            )
        }
    }

    @Test
    fun unknownPackAndUnknownKeyAreReported() {
        withConfigFile(
            """
            missing-pack:
              calls: [audit]
            fixture-configurable:
              unknown: true
            """.trimIndent(),
        ) { path ->
            val loaded = loader().load(path.toString())

            assertEquals(
                setOf(
                    RulePackLoadFailureKind.UNKNOWN_PACK_CONFIG,
                    RulePackLoadFailureKind.INVALID_PACK_CONFIG,
                ),
                loaded.failures.map { it.kind }.toSet(),
            )
            assertEquals(
                setOf("fixture-good"),
                loaded.packs.map { it.id }.toSet(),
            )
        }
    }

    private fun loader(): ProjectConfigLoader =
        ProjectConfigLoader(
            RulePackLoader(
                IsolatedConfigClassLoader(
                    arrayOf(serviceDirectory.toUri().toURL()),
                    ProjectConfigLoaderTest::class.java.classLoader,
                ),
                onFailure = {},
            ),
        )

    private lateinit var serviceDirectory: Path

    private fun withConfigFile(
        text: String,
        block: (Path) -> Unit,
    ) {
        val directory = Files.createTempDirectory("cint-project-config-test")
        serviceDirectory = directory.resolve("classes")
        val service = serviceDirectory.resolve(SERVICE_RESOURCE)
        Files.createDirectories(service.parent)
        Files.writeString(
            service,
            listOf(
                FixtureConfigurablePack::class.java.name,
                HostFixtureGoodPack::class.java.name,
            ).joinToString("\n"),
        )
        val config = directory.resolve("project.yaml")
        Files.writeString(config, text)
        try {
            block(config)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
