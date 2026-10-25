package v2.graphguard

import com.google.gson.Gson

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
