package dev.cint.core.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuralBoundariesTest {
    @Test
    fun coreOwnsNoSyntaxResourcesOrForeignRuntimeTypes() {
        val core = moduleSourceRoot("core")
        val sources = kotlinSources(core.resolve("src/main/kotlin"))

        assertFalse(Files.exists(core.resolve("src/main/resources/syntax")))
        assertTrue(sources.none { "syntax/" in it.readText() })
        assertNoText(sources, "dev.cint.treesitter")
        assertNoText(sources, "dev.cint.host")
    }

    @Test
    fun moduleDependencySetsAreExplicit() {
        assertEquals(setOf(":api"), projectDependencies("core"))
        assertEquals(setOf(":api", ":core"), projectDependencies("host"))
    }

    @Test
    fun testModulesDoNotDependOnHigherLevelModules() {
        assertEquals(emptySet(), testProjectDependencies("frontend") intersect setOf(":host", ":rulepacks"))
        assertEquals(emptySet(), testProjectDependencies("rulepacks") intersect setOf(":host"))
        assertEquals(emptySet(), testProjectDependencies("treesitter") intersect setOf(":rulepacks"))
    }

    @Test
    fun testOnlyModulesAreLeavesAndNotUsedInProduction() {
        val modules = includedModules()
        assertTrue(
            setOf("core", "frontend", "rulepacks", "treesitter", "test-fixtures", "integration-tests")
                .all { it in modules },
            "missing modules from settings: $modules",
        )
        assertEquals(setOf(":api", ":core"), productionProjectDependencies("host"))

        val testOnly = setOf(":test-fixtures", ":integration-tests", ":rulepacks-tests")
        for (module in modules) {
            if (":$module" in testOnly) continue
            if (!Files.isDirectory(moduleSourceRoot(module).resolve("src/main"))) continue
            val dependencies = productionProjectDependencies(module)
            assertTrue(
                dependencies.none { it in testOnly },
                "$module production dependencies include test-only modules: ${dependencies intersect testOnly}",
            )
        }
        assertEquals(
            setOf(":api", ":core", ":frontend", ":rulepacks", ":treesitter"),
            projectDependencies("test-fixtures"),
        )
    }

    @Test
    fun configPackageRemainsDataOnly() {
        val config = moduleSource("core").resolve("dev/cint/core/config")
        assertNoImport(kotlinSources(config), "dev.cint.core.runtime")
    }

    @Test
    fun viewDoesNotDependOnTheEnginePackage() {
        val view = moduleSource("core").resolve("dev/cint/core/facts/view")
        assertNoImport(kotlinSources(view), "dev.cint.core.runtime.engine")
    }

    @Test
    fun compositionDoesNotDependOnTheSchedulerPackage() {
        val composition = moduleSource("core").resolve("dev/cint/core/runtime/composition")
        assertNoImport(kotlinSources(composition), "dev.cint.core.runtime.scheduler")
    }

    @Test
    fun corePackageGraphIsAcyclic() {
        val packages = kotlinSources(moduleSource("core")).mapNotNull(::packageSource)
        val knownPackages = packages.mapTo(mutableSetOf()) { it.name }
        val edges = packages.associate { it.name to it.edges(knownPackages) }
        val cycle = findCycle(edges)
        assertTrue(cycle == null, "core package cycle: ${cycle?.joinToString(" -> ")}")
    }

    private fun assertNoText(
        sources: List<Path>,
        forbidden: String,
    ) {
        val offenders = sources.filter { forbidden in it.readText() }
        assertTrue(offenders.isEmpty(), "forbidden reference '$forbidden' in ${offenders.joinToString { it.toString() }}")
    }

    private fun assertNoImport(
        sources: List<Path>,
        forbidden: String,
    ) {
        val offenders =
            sources.filter { source ->
                importPattern
                    .findAll(source.readText())
                    .any { match -> match.groupValues[1] == forbidden || match.groupValues[1].startsWith("$forbidden.") }
            }
        assertTrue(offenders.isEmpty(), "forbidden import '$forbidden' in ${offenders.joinToString { it.toString() }}")
    }

    private fun packageSource(source: Path): PackageSource? {
        val text = source.readText()
        val name = packagePattern.find(text)?.groupValues?.get(1) ?: return null
        return PackageSource(name, text)
    }

    private fun PackageSource.edges(knownPackages: Set<String>): Set<String> =
        importPattern
            .findAll(text)
            .mapNotNull { match -> longestKnownPackage(match.groupValues[1], knownPackages) }
            .filter { it != name }
            .toSet()

    private fun longestKnownPackage(
        imported: String,
        knownPackages: Set<String>,
    ): String? =
        knownPackages
            .filter { imported == it || imported.startsWith("$it.") }
            .maxByOrNull { it.length }

    private fun findCycle(edges: Map<String, Set<String>>): List<String>? {
        val state = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()

        fun visit(packageName: String): List<String>? {
            when (state[packageName]) {
                1 -> {
                    val start = stack.indexOf(packageName)
                    return stack.subList(start, stack.size) + packageName
                }

                2 -> {
                    return null
                }
            }
            state[packageName] = 1
            stack += packageName
            for (dependency in edges[packageName].orEmpty().sorted()) {
                val cycle = visit(dependency) ?: continue
                return cycle
            }
            stack.removeAt(stack.lastIndex)
            state[packageName] = 2
            return null
        }

        for (packageName in edges.keys.sorted()) {
            val cycle = visit(packageName) ?: continue
            return cycle
        }
        return null
    }

    private fun projectDependencies(module: String): Set<String> =
        projectDependencyPattern
            .findAll(buildFile(module).readText())
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun productionProjectDependencies(module: String): Set<String> =
        productionDependencyPattern
            .findAll(buildFile(module).readText())
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun testProjectDependencies(module: String): Set<String> =
        testDependencyPattern
            .findAll(buildFile(module).readText())
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun includedModules(): List<String> =
        settingsModulePattern
            .findAll(moduleSourceRoot("core").parent.resolve("settings.gradle.kts").readText())
            .map { it.groupValues[1].removePrefix(":") }
            .toList()

    private fun buildFile(module: String): Path =
        moduleSourceRoot(module)
            .resolve("build.gradle.kts")

    private fun kotlinSources(directory: Path): List<Path> =
        Files.walk(directory).use { paths ->
            paths.filter { it.toString().endsWith(".kt") && Files.isRegularFile(it) }.toList()
        }

    private fun moduleSource(module: String): Path = moduleSourceRoot(module).resolve("src/main/kotlin")

    private fun moduleSourceRoot(module: String): Path {
        val cwd = Path.of("").toAbsolutePath()
        return generateSequence(cwd) { it.parent }
            .mapNotNull { directory ->
                listOf(directory.resolve(module), directory.resolve("analysis-engine/$module"))
                    .firstOrNull { candidate -> candidate.resolve("src").isDirectory() }
            }.firstOrNull()
            ?: error("could not locate module '$module' from $cwd")
    }

    private data class PackageSource(
        val name: String,
        val text: String,
    )

    private companion object {
        val importPattern = Regex("^\\s*import\\s+([\\w.]+?)(?:\\.\\*)?\\s*$", RegexOption.MULTILINE)
        val packagePattern = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE)
        val projectDependencyPattern = Regex("project\\(\"(:[^\"]+)\"\\)")
        val productionDependencyPattern = Regex("^\\s*(?:implementation|api)\\(.*project\\(\"(:[^\"]+)\"\\)", RegexOption.MULTILINE)
        val testDependencyPattern = Regex("^\\s*test\\w*\\(.*project\\(\"(:[^\"]+)\"\\)", RegexOption.MULTILINE)
        val settingsModulePattern = Regex("\"(:[^\"]+)\"")
    }
}
