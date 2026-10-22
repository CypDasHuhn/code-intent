package dev.cint.host

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.RulePackConfigResolver
import dev.cint.api.View
import dev.cint.core.config.ProjectConfig
import dev.cint.core.runtime.rulepack.RulePackLoader
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

private class ServiceClassLoaderAnchor

private class TestRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
) : Rule {
    override fun run(view: View): List<RuleEmit> = emptyList()
}

class AlphaPack : RulePack {
    override val id: String = "alpha"
    override val languages: Set<String> = setOf("alpha")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(TestRule("alpha:alpha", "ALPHA"))
}

class BetaPack : RulePack {
    override val id: String = "beta"
    override val languages: Set<String> = setOf("beta")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(TestRule("beta:beta", "BETA"))
}

class ConfigurableTestPack(
    private val calls: Set<String> = setOf("default"),
) : ConfigurableRulePack {
    companion object {
        var lastCalls: Set<String>? = null
            private set

        fun reset() {
            lastCalls = null
        }
    }

    override val id: String = "constructed-configurable"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack {
        val calls = decodeCalls(values)
        lastCalls = calls
        return ConfigurableTestPack(calls)
    }

    override fun configureAdditive(values: Map<String, Any?>): RulePack {
        val calls = this.calls + decodeCalls(values)
        lastCalls = calls
        return ConfigurableTestPack(calls)
    }

    private fun decodeCalls(values: Map<String, Any?>): Set<String> {
        val unknown = values.keys - "calls"
        if (unknown.isNotEmpty()) {
            throw RulePackConfigException("constructed-configurable.${unknown.minOf { it }}: unknown key")
        }
        return when (val raw = values["calls"]) {
            null -> {
                calls
            }

            is String -> {
                setOf(raw)
            }

            is List<*> -> {
                if (raw.all { it is String }) {
                    raw.map { it as String }.toSet()
                } else {
                    throw RulePackConfigException("constructed-configurable.calls: expected strings")
                }
            }

            else -> {
                throw RulePackConfigException("constructed-configurable.calls: expected a string or list")
            }
        }
    }

    override fun rules(): List<Rule> = listOf(TestRule("constructed-configurable:configurable", "CONFIGURABLE"))
}

class ConfigurableMismatchTestPack : ConfigurableRulePack {
    companion object {
        var configureCalls: Int = 0
            private set

        fun reset() {
            configureCalls = 0
        }
    }

    override val id: String = "constructed-configurable-mismatch"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION + 1

    override fun configure(values: Map<String, Any?>): RulePack {
        configureCalls++
        return this
    }

    override fun rules(): List<Rule> = listOf(TestRule("constructed-configurable-mismatch:configurable", "MISMATCH"))
}

class ServiceValidationPack : RulePack {
    override val id: String = "service-validation"
    override val languages: Set<String> = setOf("servicelang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(TestRule("service-validation:validation", "VALIDATION"))
}

private data class SchemaPackConfig(
    val calls: Set<String> = setOf("default"),
)

private val schemaPackSchema =
    ConfigSchema.of(
        ConfigField.strings<SchemaPackConfig>(
            "calls",
            { it.calls },
            { config, value -> config.copy(calls = value) },
        ),
    )

private class PlainConfiguredRulePack : RulePack {
    override val id: String = "schema-captured"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(TestRule("schema-captured:rule", "SCHEMA_CAPTURED"))
}

class SchemaCapturedPack : ConfigurableRulePack {
    override val id: String = "schema-captured"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack = PlainConfiguredRulePack()

    override fun configSchema(): ConfigSchema<*> = schemaPackSchema

    override fun rules(): List<Rule> = listOf(TestRule("schema-captured:rule", "SCHEMA_CAPTURED"))
}

class LegacyAdditivePack(
    private val calls: Set<String> = setOf("default"),
) : ConfigurableRulePack {
    companion object {
        var lastCalls: Set<String>? = null
            private set

        fun reset() {
            lastCalls = null
        }
    }

    override val id: String = "legacy-additive"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack {
        val decoded =
            when (val raw = values["calls"]) {
                null -> calls
                is String -> setOf(raw)
                is List<*> -> raw.map { it as String }.toSet()
                else -> throw RulePackConfigException("legacy-additive.calls: expected strings")
            }
        lastCalls = decoded
        return LegacyAdditivePack(decoded)
    }

    override fun configSchema(): ConfigSchema<*> = schemaPackSchema

    override fun rules(): List<Rule> = listOf(TestRule("legacy-additive:rule", "LEGACY_ADDITIVE"))
}

internal fun writeOverlay(
    root: Path,
    text: String,
) {
    val path = root.resolve(".cint").resolve("config.overlay.yml")
    Files.createDirectories(path.parent)
    Files.writeString(path, text)
}

internal fun withService(
    vararg providers: Class<out RulePack>,
    project: ProjectConfig = ProjectConfig(),
    configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
    root: Path? = null,
    block: (HostPipeline) -> Unit,
) {
    val directory = Files.createTempDirectory("cint-host-pipeline")
    val service = directory.resolve("META-INF/services/${RulePack::class.java.name}")
    Files.createDirectories(service.parent)
    Files.writeString(service, providers.joinToString("\n") { it.name })
    try {
        val classLoader =
            URLClassLoader(
                arrayOf(directory.toUri().toURL()),
                ServiceClassLoaderAnchor::class.java.classLoader,
            )
        block(HostPipeline(RulePackLoader(classLoader, onFailure = {}), project, configResolver, root))
    } finally {
        directory.toFile().deleteRecursively()
    }
}
