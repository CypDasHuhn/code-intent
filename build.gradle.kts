import com.google.gson.Gson
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.google.code.gson:gson:2.11.0")
    }
}

plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    base
}

allprojects {
    group = "dev.cint.v2"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    val multiplatformProjects = setOf("contracts", "syntax-treesitter")
    val isMultiplatform = project.name in multiplatformProjects

    if (!isMultiplatform) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    if (!isMultiplatform) {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            showStandardStreams = true
        }
    }
}

data class TestEdgePolicy(
    val description: String = "",
    val testOnlyModules: List<String> = emptyList(),
)

data class GraphGuardConfig(
    val allowedEdges: List<List<String>> = emptyList(),
    val allowedTestEdges: List<List<String>> = emptyList(),
    val testEdgePolicy: TestEdgePolicy? = null,
)

data class ProjectGraph(
    val modules: Set<String>,
    val dependencies: Map<String, Set<String>>,
    val rootDependencies: Set<String>,
    val missingBuildFiles: Set<String>,
    val testDependencies: Map<String, Set<String>> = emptyMap(),
)

data class GraphGuardResult(
    val errors: List<String>,
) {
    val passed: Boolean
        get() = errors.isEmpty()
}

object GraphGuardValidator {
    fun loadConfig(content: String): GraphGuardConfig = Gson().fromJson(content, GraphGuardConfig::class.java)

    fun validate(
        config: GraphGuardConfig,
        graph: ProjectGraph,
    ): GraphGuardResult {
        val errors = mutableListOf<String>()
        val modules = graph.modules
        val testOnlyModules =
            config.testEdgePolicy
                ?.testOnlyModules
                ?.toSet()
                .orEmpty()
        val allowedEdges = edgeKeys(config.allowedEdges, "allowedEdges", errors)
        val allowedTestEdges = edgeKeys(config.allowedTestEdges, "allowedTestEdges", errors)

        for (module in testOnlyModules) {
            if (module !in modules) {
                errors.add("Test-edge policy references unknown test-only module '$module'")
            }
        }

        validateEdgeEndpoints(allowedEdges, "Allowed edge", modules, errors)
        validateEdgeEndpoints(allowedTestEdges, "Allowed test edge", modules, errors)

        for (edge in allowedEdges) {
            val from = edge.substringBefore("->")
            if (from in testOnlyModules) {
                errors.add(
                    "Test-only module '$from' must declare its edges in allowedTestEdges, not allowedEdges ('$edge')",
                )
            }
        }

        for (dependency in graph.rootDependencies) {
            errors.add("Root project must not declare a dependency on module '$dependency'")
        }

        for (module in graph.missingBuildFiles) {
            errors.add("Module '$module' has no build.gradle.kts (declared project must have a build file)")
        }

        for (module in graph.dependencies.keys + graph.testDependencies.keys) {
            if (module !in modules) {
                errors.add("Dependency data references unregistered module '$module'")
            }
        }

        for (module in modules) {
            val dependencies = graph.dependencies[module]
            if (dependencies == null) {
                errors.add("Module '$module' has no dependency data")
                continue
            }
            val isTestOnly = module in testOnlyModules
            for (dependency in dependencies) {
                val edge = "$module->$dependency"
                when {
                    dependency !in modules -> {
                        errors.add("Module '$module' depends on unknown module '$dependency'")
                    }

                    dependency in testOnlyModules && !isTestOnly -> {
                        errors.add(
                            "Production module '$module' must not depend on test-only module '$dependency' " +
                                "(edge '$edge' is forbidden by the test-edge policy)",
                        )
                    }

                    isTestOnly && edge !in allowedTestEdges -> {
                        errors.add(
                            "Test-only module '$module' has undeclared dependency on '$dependency' " +
                                "(edge '$edge' not in allowedTestEdges)",
                        )
                    }

                    !isTestOnly && edge !in allowedEdges -> {
                        errors.add(
                            "Module '$module' has undeclared dependency on '$dependency' " +
                                "(edge '$edge' not in allowedEdges)",
                        )
                    }
                }
            }

            for (dependency in graph.testDependencies[module].orEmpty()) {
                val edge = "$module->$dependency"
                when {
                    dependency !in modules -> {
                        errors.add("Module '$module' test source set depends on unknown module '$dependency'")
                    }

                    edge !in allowedTestEdges -> {
                        errors.add(
                            "Module '$module' test source set has undeclared dependency on '$dependency' " +
                                "(edge '$edge' not in allowedTestEdges)",
                        )
                    }
                }
            }
        }

        for (cycle in findCycles(allowedEdges + allowedTestEdges)) {
            errors.add("Reverse/cyclic edge detected among declared edges: ${cycle.joinToString(" -> ")}")
        }

        return GraphGuardResult(errors.distinct())
    }

    private fun edgeKeys(
        edges: List<List<String>>,
        source: String,
        errors: MutableList<String>,
    ): Set<String> {
        val result = linkedSetOf<String>()
        for (edge in edges) {
            if (edge.size != 2) {
                errors.add("Malformed edge in $source: $edge")
                continue
            }
            result.add("${edge[0]}->${edge[1]}")
        }
        return result
    }

    private fun validateEdgeEndpoints(
        edges: Set<String>,
        label: String,
        modules: Set<String>,
        errors: MutableList<String>,
    ) {
        for (edge in edges) {
            val from = edge.substringBefore("->")
            val to = edge.substringAfter("->")
            if (from !in modules) {
                errors.add("$label '$edge' references unknown module '$from'")
            }
            if (to !in modules) {
                errors.add("$label '$edge' references unknown module '$to'")
            }
        }
    }

    fun findCycles(edges: Set<String>): List<List<String>> {
        val adjacency = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            val from = edge.substringBefore("->")
            val to = edge.substringAfter("->")
            adjacency.getOrPut(from) { mutableListOf() }.add(to)
        }

        val completed = mutableSetOf<String>()
        val visiting = mutableListOf<String>()
        val cycles = mutableListOf<List<String>>()

        fun visit(node: String) {
            if (node in completed) return
            val index = visiting.indexOf(node)
            if (index >= 0) {
                cycles.add(visiting.subList(index, visiting.size) + node)
                return
            }
            visiting.add(node)
            adjacency[node]?.forEach(::visit)
            visiting.removeAt(visiting.size - 1)
            completed.add(node)
        }

        adjacency.keys.sorted().forEach(::visit)
        return cycles.distinct()
    }
}

object ProjectGraphCollector {
    fun collect(root: Project): ProjectGraph {
        val modules = linkedSetOf<String>()
        val dependencies = linkedMapOf<String, Set<String>>()
        val testDependencies = linkedMapOf<String, Set<String>>()
        val rootDependencies = linkedSetOf<String>()
        val missingBuildFiles = linkedSetOf<String>()

        for (candidate in root.allprojects) {
            val production = linkedSetOf<String>()
            val test = linkedSetOf<String>()
            for (configuration in candidate.configurations) {
                val target = if (configuration.name.contains("test", ignoreCase = true)) test else production
                configuration.dependencies.mapNotNullTo(target) { dependency ->
                    (dependency as? ProjectDependency)?.path
                }
            }
            val productionModules = production.map { it.removePrefix(":") }.toSet()
            val testModules = test.map { it.removePrefix(":") }.toSet()

            if (candidate.path == ":") {
                rootDependencies.addAll(productionModules)
                rootDependencies.addAll(testModules)
                continue
            }

            val module = candidate.path.removePrefix(":")
            modules.add(module)
            dependencies[module] = productionModules
            testDependencies[module] = testModules
            if (!candidate.buildFile.isFile) {
                missingBuildFiles.add(module)
            }
        }

        return ProjectGraph(modules, dependencies, rootDependencies, missingBuildFiles, testDependencies)
    }
}

open class GraphGuardTask : DefaultTask() {
    @TaskAction
    fun validateGraph() {
        val config = GraphGuardValidator.loadConfig(project.file("graph-guard.json").readText())
        val graph = ProjectGraphCollector.collect(project.rootProject)
        val result = GraphGuardValidator.validate(config, graph)

        if (!result.passed) {
            logger.error("GRAPH GUARD FAILED:")
            for (error in result.errors) {
                logger.error("  ERROR: $error")
            }
            throw GradleException("Graph guard validation failed with ${result.errors.size} error(s)")
        }

        logger.lifecycle("GRAPH GUARD PASSED")
    }
}

tasks.register("graphGuard", GraphGuardTask::class) {
    description = "Validates the module dependency graph against declared edges"
    group = "verification"
}

tasks.named("check") {
    dependsOn("graphGuard")
}
