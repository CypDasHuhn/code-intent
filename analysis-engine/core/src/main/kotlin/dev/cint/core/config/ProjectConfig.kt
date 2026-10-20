package dev.cint.core.config

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.math.BigInteger

val DEFAULT_PURITY_ANNOTATIONS: List<String> =
    listOf(
        "Pure",
        "Contract(pure = true)",
        "org.checkerframework.dataflow.qual.Pure",
    )

data class LspPolicy(
    val enabled: Boolean = true,
    val timeoutMs: Long = 5_000L,
)

data class CorePolicy(
    val purityAnnotations: List<String> = DEFAULT_PURITY_ANNOTATIONS,
    val lsp: LspPolicy = LspPolicy(),
)

data class ProjectConfig(
    val core: CorePolicy = CorePolicy(),
    val packs: Map<String, Map<String, Any?>> = emptyMap(),
)

data class ProjectConfigIssue(
    val path: String,
    val message: String,
)

data class ParsedProjectConfig(
    val config: ProjectConfig,
    val issues: List<ProjectConfigIssue>,
    val lspTimeoutConfigured: Boolean = false,
)

fun parseProjectConfig(path: String?): ParsedProjectConfig {
    if (path == null) return ParsedProjectConfig(ProjectConfig(), emptyList())

    val file = File(path)
    if (!file.exists()) {
        return ParsedProjectConfig(
            ProjectConfig(),
            listOf(ProjectConfigIssue(path, "config file not found")),
        )
    }

    val root =
        try {
            Yaml().load<Any?>(file.readText())
        } catch (error: Exception) {
            return ParsedProjectConfig(
                ProjectConfig(),
                listOf(ProjectConfigIssue(path, "could not read config: ${error.message ?: error.javaClass.name}")),
            )
        }

    if (root == null) return ParsedProjectConfig(ProjectConfig(), emptyList())
    if (root !is Map<*, *>) {
        return ParsedProjectConfig(
            ProjectConfig(),
            listOf(ProjectConfigIssue(path, "project config must be a map")),
        )
    }

    val issues = mutableListOf<ProjectConfigIssue>()
    var core = CorePolicy()
    var lspTimeoutConfigured = false
    val packs = linkedMapOf<String, Map<String, Any?>>()

    for ((rawKey, rawValue) in root) {
        if (rawKey !is String || rawKey.isBlank()) {
            issues += ProjectConfigIssue(path, "config keys must be non-blank strings")
            continue
        }
        if (rawKey == CORE_SECTION) {
            lspTimeoutConfigured = overridesLspTimeout(rawValue)
            core = parseCore(rawValue, issues) ?: core
            continue
        }
        val values = stringKeyedMap(rawValue, rawKey, issues)
        if (values == null) {
            issues += ProjectConfigIssue(rawKey, "pack config must be a map")
        } else {
            packs[rawKey] = values
        }
    }

    return ParsedProjectConfig(ProjectConfig(core, packs), issues, lspTimeoutConfigured)
}

fun IntentConfig.withCorePolicy(policy: CorePolicy): IntentConfig = copy(pureAnnotations = policy.purityAnnotations)

private fun overridesLspTimeout(value: Any?): Boolean {
    if (value !is Map<*, *>) return false
    val lsp = value["lsp"] as? Map<*, *> ?: return false
    return lsp.containsKey("timeoutMs")
}

private fun parseCore(
    value: Any?,
    issues: MutableList<ProjectConfigIssue>,
): CorePolicy? {
    if (value == null) return CorePolicy()
    if (value !is Map<*, *>) {
        issues += ProjectConfigIssue(CORE_SECTION, "core config must be a map")
        return null
    }

    var purity = CorePolicy().purityAnnotations
    var lsp = LspPolicy()

    for ((rawKey, rawValue) in value) {
        if (rawKey !is String) {
            issues += ProjectConfigIssue(CORE_SECTION, "core config keys must be strings")
            continue
        }
        when (rawKey) {
            "purity" -> purity = parsePurity(rawValue, issues) ?: purity
            "lsp" -> lsp = parseLsp(rawValue, issues) ?: lsp
            else -> issues += ProjectConfigIssue("$CORE_SECTION.$rawKey", "unknown core config key")
        }
    }

    return CorePolicy(purity, lsp)
}

private fun parsePurity(
    value: Any?,
    issues: MutableList<ProjectConfigIssue>,
): List<String>? {
    if (value !is Map<*, *>) {
        issues += ProjectConfigIssue("$CORE_SECTION.purity", "purity config must be a map")
        return null
    }
    var annotations = CorePolicy().purityAnnotations
    for ((rawKey, rawValue) in value) {
        if (rawKey != "annotations") {
            issues += ProjectConfigIssue("$CORE_SECTION.purity.$rawKey", "unknown core config key")
            continue
        }
        annotations = stringList(rawValue, "$CORE_SECTION.purity.annotations", issues) ?: annotations
    }
    return annotations
}

private fun parseLsp(
    value: Any?,
    issues: MutableList<ProjectConfigIssue>,
): LspPolicy? {
    if (value !is Map<*, *>) {
        issues += ProjectConfigIssue("$CORE_SECTION.lsp", "lsp config must be a map")
        return null
    }
    var enabled = LspPolicy().enabled
    var timeoutMs = LspPolicy().timeoutMs
    for ((rawKey, rawValue) in value) {
        when (rawKey) {
            "enabled" -> {
                if (rawValue is Boolean) {
                    enabled = rawValue
                } else {
                    issues += ProjectConfigIssue("$CORE_SECTION.lsp.enabled", "expected a boolean")
                }
            }

            "timeoutMs" -> {
                val timeout = positiveLong(rawValue)
                if (timeout == null) {
                    issues += ProjectConfigIssue("$CORE_SECTION.lsp.timeoutMs", "expected a positive integer")
                } else {
                    timeoutMs = timeout
                }
            }

            else -> {
                issues += ProjectConfigIssue("$CORE_SECTION.lsp.$rawKey", "unknown core config key")
            }
        }
    }
    return LspPolicy(enabled, timeoutMs)
}

private fun positiveLong(value: Any?): Long? =
    when (value) {
        is Byte -> {
            value.toLong().takeIf { it > 0 }
        }

        is Short -> {
            value.toLong().takeIf { it > 0 }
        }

        is Int -> {
            value.toLong().takeIf { it > 0 }
        }

        is Long -> {
            value.takeIf { it > 0 }
        }

        is BigInteger -> {
            if (value.signum() > 0 && value <= MAX_LONG) {
                value.toLong()
            } else {
                null
            }
        }

        else -> {
            null
        }
    }

private fun stringList(
    value: Any?,
    path: String,
    issues: MutableList<ProjectConfigIssue>,
): List<String>? {
    if (value !is List<*>) {
        issues += ProjectConfigIssue(path, "expected a list of strings")
        return null
    }
    if (value.any { it !is String }) {
        issues += ProjectConfigIssue(path, "expected a list of strings")
        return null
    }
    return value.map { it as String }
}

private fun stringKeyedMap(
    value: Any?,
    path: String,
    issues: MutableList<ProjectConfigIssue>,
): Map<String, Any?>? {
    if (value == null) return emptyMap()
    if (value !is Map<*, *>) return null

    val result = linkedMapOf<String, Any?>()
    for ((rawKey, rawValue) in value) {
        if (rawKey !is String || rawKey.isBlank()) {
            issues += ProjectConfigIssue(path, "config keys must be non-blank strings")
            continue
        }
        val childPath = "$path.$rawKey"
        result[rawKey] =
            when (rawValue) {
                is Map<*, *> -> stringKeyedMap(rawValue, childPath, issues)
                is List<*> -> rawValue.mapIndexed { index, item -> normalize(item, "$childPath[$index]", issues) }
                else -> rawValue
            }
    }
    return result
}

private fun normalize(
    value: Any?,
    path: String,
    issues: MutableList<ProjectConfigIssue>,
): Any? =
    when (value) {
        is Map<*, *> -> stringKeyedMap(value, path, issues)
        is List<*> -> value.mapIndexed { index, item -> normalize(item, "$path[$index]", issues) }
        else -> value
    }

private const val CORE_SECTION = "core"
private val MAX_LONG: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
