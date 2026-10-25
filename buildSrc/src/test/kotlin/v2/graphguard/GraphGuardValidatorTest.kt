package v2.graphguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphGuardValidatorTest {
    private fun graph(
        modules: Set<String>,
        dependencies: Map<String, Set<String>> = modules.associateWith { emptySet() },
        rootDependencies: Set<String> = emptySet(),
        missingBuildFiles: Set<String> = emptySet(),
        testDependencies: Map<String, Set<String>> = emptyMap(),
    ) = ProjectGraph(modules, dependencies, rootDependencies, missingBuildFiles, testDependencies)

    private fun config(vararg edges: Pair<String, String>) = GraphGuardConfig(edges.map { listOf(it.first, it.second) })

    @Test
    fun declaredGraphPasses() {
        val result =
            GraphGuardValidator.validate(
                config("core" to "contracts", "index" to "contracts"),
                graph(
                    setOf("contracts", "core", "index"),
                    mapOf("contracts" to emptySet(), "core" to setOf("contracts"), "index" to setOf("contracts")),
                ),
            )

        assertTrue(result.errors.toString(), result.passed)
    }

    @Test
    fun illegalEdgeFails() {
        val result =
            GraphGuardValidator.validate(
                config("core" to "contracts", "index" to "contracts"),
                graph(
                    setOf("contracts", "core", "index"),
                    mapOf("contracts" to emptySet(), "core" to setOf("index"), "index" to setOf("contracts")),
                ),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("core->index") })
    }

    @Test
    fun actualReverseEdgeFails() {
        val result =
            GraphGuardValidator.validate(
                config("core" to "contracts"),
                graph(
                    setOf("contracts", "core"),
                    mapOf("contracts" to setOf("core"), "core" to emptySet()),
                ),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("contracts") && it.contains("core") })
    }

    @Test
    fun allowedReverseEdgeIsRejectedAsCycle() {
        val result =
            GraphGuardValidator.validate(
                config("core" to "contracts", "contracts" to "core"),
                graph(setOf("contracts", "core")),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("cyclic", ignoreCase = true) })
    }

    @Test
    fun rootProjectDependencyFails() {
        val result =
            GraphGuardValidator.validate(
                config(),
                graph(setOf("ai"), rootDependencies = setOf("ai")),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("Root project") })
    }

    @Test
    fun missingBuildFileFails() {
        val result =
            GraphGuardValidator.validate(
                config(),
                graph(setOf("core"), missingBuildFiles = setOf("core")),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("no build.gradle.kts") })
    }

    @Test
    fun unknownDependencyFails() {
        val result =
            GraphGuardValidator.validate(
                config(),
                graph(setOf("core"), mapOf("core" to setOf("ghost"))),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("unknown module 'ghost'") })
    }

    @Test
    fun allowedEdgeToUnknownModuleFails() {
        val result =
            GraphGuardValidator.validate(
                config("core" to "ghost"),
                graph(setOf("core")),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("unknown module 'ghost'") })
    }

    @Test
    fun moduleWithoutDependencyDataFails() {
        val result =
            GraphGuardValidator.validate(
                config(),
                graph(setOf("core"), emptyMap()),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("no dependency data") })
    }

    private fun testConfig(
        testOnly: List<String>,
        production: List<Pair<String, String>> = emptyList(),
        test: List<Pair<String, String>> = emptyList(),
    ) = GraphGuardConfig(
        allowedEdges = production.map { listOf(it.first, it.second) },
        allowedTestEdges = test.map { listOf(it.first, it.second) },
        testEdgePolicy = TestEdgePolicy(description = "test modules may exercise modules", testOnlyModules = testOnly),
    )

    @Test
    fun testOnlyModulesMayDependOnExercisedModules() {
        val result =
            GraphGuardValidator.validate(
                testConfig(
                    testOnly = listOf("integration-tests", "test-fixtures"),
                    production = listOf("core" to "contracts"),
                    test =
                        listOf(
                            "integration-tests" to "core",
                            "integration-tests" to "test-fixtures",
                            "test-fixtures" to "contracts",
                        ),
                ),
                graph(
                    setOf("contracts", "core", "integration-tests", "test-fixtures"),
                    mapOf(
                        "contracts" to emptySet(),
                        "core" to setOf("contracts"),
                        "integration-tests" to setOf("core", "test-fixtures"),
                        "test-fixtures" to setOf("contracts"),
                    ),
                ),
            )

        assertTrue(result.errors.toString(), result.passed)
    }

    @Test
    fun productionModuleMayNotDependOnTestOnlyModule() {
        val result =
            GraphGuardValidator.validate(
                testConfig(
                    testOnly = listOf("test-fixtures"),
                    production = listOf("core" to "contracts"),
                    test = listOf("test-fixtures" to "contracts"),
                ),
                graph(
                    setOf("contracts", "core", "test-fixtures"),
                    mapOf(
                        "contracts" to emptySet(),
                        "core" to setOf("contracts", "test-fixtures"),
                        "test-fixtures" to setOf("contracts"),
                    ),
                ),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("must not depend on test-only module 'test-fixtures'") })
    }

    @Test
    fun productionTestSourceSetMayDependOnTestFixtures() {
        val result =
            GraphGuardValidator.validate(
                testConfig(
                    testOnly = listOf("test-fixtures"),
                    production = listOf("core" to "contracts"),
                    test = listOf("test-fixtures" to "contracts", "core" to "test-fixtures"),
                ),
                graph(
                    setOf("contracts", "core", "test-fixtures"),
                    mapOf(
                        "contracts" to emptySet(),
                        "core" to setOf("contracts"),
                        "test-fixtures" to setOf("contracts"),
                    ),
                    testDependencies = mapOf("core" to setOf("test-fixtures")),
                ),
            )

        assertTrue(result.errors.toString(), result.passed)
    }

    @Test
    fun productionTestDependencyMustBeDeclaredAsTestEdge() {
        val result =
            GraphGuardValidator.validate(
                testConfig(
                    testOnly = listOf("test-fixtures"),
                    production = listOf("core" to "contracts"),
                    test = listOf("test-fixtures" to "contracts"),
                ),
                graph(
                    setOf("contracts", "core", "test-fixtures"),
                    mapOf(
                        "contracts" to emptySet(),
                        "core" to setOf("contracts"),
                        "test-fixtures" to setOf("contracts"),
                    ),
                    testDependencies = mapOf("core" to setOf("test-fixtures")),
                ),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("test source set") && it.contains("not in allowedTestEdges") })
    }

    @Test
    fun testEdgeAllowanceDoesNotOpenProductionMainEdge() {
        val result =
            GraphGuardValidator.validate(
                testConfig(
                    testOnly = listOf("test-fixtures"),
                    production = listOf("core" to "contracts"),
                    test = listOf("test-fixtures" to "contracts", "core" to "test-fixtures"),
                ),
                graph(
                    setOf("contracts", "core", "test-fixtures"),
                    mapOf(
                        "contracts" to emptySet(),
                        "core" to setOf("contracts", "test-fixtures"),
                        "test-fixtures" to setOf("contracts"),
                    ),
                ),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("must not depend on test-only module 'test-fixtures'") })
    }

    @Test
    fun testOnlyDependencyMustUseAllowedTestEdges() {
        val result =
            GraphGuardValidator.validate(
                testConfig(
                    testOnly = listOf("integration-tests"),
                    production = listOf("core" to "contracts"),
                ),
                graph(
                    setOf("contracts", "core", "integration-tests"),
                    mapOf(
                        "contracts" to emptySet(),
                        "core" to setOf("contracts"),
                        "integration-tests" to setOf("core"),
                    ),
                ),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("not in allowedTestEdges") })
    }

    @Test
    fun testEdgePolicyRejectsUnknownTestOnlyModule() {
        val result =
            GraphGuardValidator.validate(
                testConfig(testOnly = listOf("ghost")),
                graph(setOf("core")),
            )

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("unknown test-only module 'ghost'") })
    }
}
