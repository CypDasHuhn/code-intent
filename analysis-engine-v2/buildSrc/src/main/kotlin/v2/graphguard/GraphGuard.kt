package v2.graphguard

import com.google.gson.Gson

data class GraphGuardConfig(
    val allowedEdges: List<List<String>>
)

data class ProjectGraph(
    val modules: Set<String>,
    val dependencies: Map<String, Set<String>>,
    val rootDependencies: Set<String>,
    val missingBuildFiles: Set<String>
)

data class GraphGuardResult(
    val errors: List<String>
) {
    val passed: Boolean
        get() = errors.isEmpty()
}

object GraphGuardValidator {
    fun loadConfig(content: String): GraphGuardConfig =
        Gson().fromJson(content, GraphGuardConfig::class.java)

    fun validate(config: GraphGuardConfig, graph: ProjectGraph): GraphGuardResult {
        val errors = mutableListOf<String>()
        val modules = graph.modules
        val allowedEdges = mutableSetOf<String>()

        for (edge in config.allowedEdges) {
            if (edge.size != 2) {
                errors.add("Malformed allowed edge: $edge")
                continue
            }
            allowedEdges.add("${edge[0]}->${edge[1]}")
        }

        for (edge in allowedEdges) {
            val from = edge.substringBefore("->")
            val to = edge.substringAfter("->")
            if (from !in modules) {
                errors.add("Allowed edge references unknown module '$from'")
            }
            if (to !in modules) {
                errors.add("Allowed edge references unknown module '$to'")
            }
        }

        for (dependency in graph.rootDependencies) {
            errors.add("Root project must not declare a dependency on module '$dependency'")
        }

        for (module in graph.missingBuildFiles) {
            errors.add("Module '$module' has no build.gradle.kts (declared project must have a build file)")
        }

        for (module in graph.dependencies.keys) {
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

            for (dependency in dependencies) {
                val edge = "$module->$dependency"
                when {
                    dependency !in modules ->
                        errors.add("Module '$module' depends on unknown module '$dependency'")
                    edge !in allowedEdges ->
                        errors.add(
                            "Module '$module' has undeclared dependency on '$dependency' " +
                                "(edge '$edge' not in allowedEdges)"
                        )
                }
            }
        }

        for (cycle in findCycles(allowedEdges)) {
            errors.add("Reverse/cyclic edge detected among allowedEdges: ${cycle.joinToString(" -> ")}")
        }

        return GraphGuardResult(errors.distinct())
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
