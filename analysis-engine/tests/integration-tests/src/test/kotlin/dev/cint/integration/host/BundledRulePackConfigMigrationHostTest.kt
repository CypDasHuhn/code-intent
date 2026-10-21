package dev.cint.integration.host

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.RulePackConfigResolver
import dev.cint.api.View
import dev.cint.core.config.ProjectConfig
import dev.cint.core.runtime.rulepack.RulePackLoadFailureKind
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.host.HostPipeline
import dev.cint.host.HostRuntime
import dev.cint.rulepacks.BundledRulePackConfigResolver
import dev.cint.rulepacks.errorhandling.ERROR_HANDLING_INTENT
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ThirdPartyRule(
    override val id: String,
    override val owns: Intent,
) : Rule {
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> = emptyList()
}

class ThirdPartyErrorHandlingLanguagePack(
    private val calls: Set<String> = setOf("default"),
) : ConfigurableRulePack {
    companion object {
        var lastCalls: Set<String>? = null
            private set

        fun reset() {
            lastCalls = null
        }
    }

    override val id: String = "error-handling-kotlin"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack {
        val unknown = values.keys - "calls"
        if (unknown.isNotEmpty()) {
            throw RulePackConfigException("error-handling-kotlin.${unknown.minOf { it }}: unknown key")
        }
        val configured =
            when (val raw = values["calls"]) {
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
                        throw RulePackConfigException("error-handling-kotlin.calls: expected strings")
                    }
                }

                else -> {
                    throw RulePackConfigException("error-handling-kotlin.calls: expected a string or list")
                }
            }
        lastCalls = configured
        return ThirdPartyErrorHandlingLanguagePack(configured)
    }

    override fun rules(): List<Rule> = listOf(ThirdPartyRule("error-handling-kotlin:third-party", "THIRD_PARTY"))
}

class ThirdPartyErrorHandlingOwnerPack : RulePack {
    override val id: String = "third-party-error-handling"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(ThirdPartyRule("third-party-error-handling:error-handling", ERROR_HANDLING_INTENT))
}

class BundledRulePackConfigMigrationHostTest {
    @Test
    fun legacyLanguageConfigStaysWithAThirdPartyPackThatOwnsItsId() {
        ThirdPartyErrorHandlingLanguagePack.reset()
        val project =
            ProjectConfig(
                packs = mapOf("error-handling-kotlin" to mapOf("calls" to listOf("third"))),
            )

        withService(ThirdPartyErrorHandlingLanguagePack::class.java, project) { pipeline ->
            assertEquals(setOf("third"), ThirdPartyErrorHandlingLanguagePack.lastCalls)
            val ids = pipeline.packs("kotlin").map { it.id }.toSet()
            assertTrue("error-handling-kotlin" in ids)
            assertTrue("error-handling" in ids)
            assertTrue(pipeline.failures.isEmpty(), pipeline.failures.joinToString { it.message })
        }
    }

    @Test
    fun explicitHostResolverAppliesMigrationAndReportsInvalidLegacySections() {
        val project =
            ProjectConfig(
                packs =
                    mapOf(
                        "error-handling-kotlin" to mapOf("calls" to listOf("fail")),
                        "error-handling-javascript" to mapOf("throws" to "not-a-boolean"),
                    ),
            )

        val pipeline = HostRuntime.pipeline(project, BundledRulePackConfigResolver)

        assertTrue(pipeline.packs("kotlin").any { it.id == "error-handling" })
        assertTrue(pipeline.packs("javascript").any { it.id == "error-handling" })
        val invalid = pipeline.failures.single { it.kind == RulePackLoadFailureKind.INVALID_PACK_CONFIG }
        assertEquals(setOf("error-handling"), invalid.packIds)
        assertTrue(invalid.message.contains("error-handling-javascript"))
    }

    @Test
    fun configResolversAreNotDiscoveredGlobally() {
        assertTrue(ServiceLoader.load(RulePackConfigResolver::class.java).toList().isEmpty())
    }

    @Test
    fun hostRuntimeWithoutAnExplicitResolverDoesNotRewriteLegacyConfig() {
        val project =
            ProjectConfig(
                packs = mapOf("error-handling-kotlin" to mapOf("calls" to listOf("fail"))),
            )

        val pipeline = HostRuntime.pipeline(project)

        assertTrue(
            pipeline.failures.any {
                it.kind == RulePackLoadFailureKind.UNKNOWN_PACK_CONFIG &&
                    it.packId == "error-handling-kotlin"
            },
        )
    }

    @Test
    fun thirdPartyErrorHandlingOwnerFailsAsADuplicateOwner() {
        withService(ThirdPartyErrorHandlingOwnerPack::class.java) { pipeline ->
            val duplicateOwners =
                pipeline.failures.filter { it.kind == RulePackLoadFailureKind.DUPLICATE_INTENT_OWNER }

            assertEquals(
                setOf("error-handling", "third-party-error-handling"),
                duplicateOwners.flatMap { it.packIds }.toSet(),
            )
            assertTrue(pipeline.packs("kotlin").none { it.id == "error-handling" })
            assertTrue(pipeline.packs("kotlin").none { it.id == "third-party-error-handling" })
            assertTrue(ERROR_HANDLING_INTENT !in pipeline.plan("kotlin").terminal.sourceIntents)
        }
    }

    private fun withService(
        provider: Class<out RulePack>,
        project: ProjectConfig = ProjectConfig(),
        block: (HostPipeline) -> Unit,
    ) {
        val directory = Files.createTempDirectory("cint-bundled-config")
        val service = directory.resolve("META-INF/services/${RulePack::class.java.name}")
        Files.createDirectories(service.parent)
        Files.writeString(service, provider.name)
        try {
            val classLoader =
                URLClassLoader(
                    arrayOf(directory.toUri().toURL()),
                    BundledRulePackConfigMigrationHostTest::class.java.classLoader,
                )
            val pipeline =
                HostPipeline(
                    RulePackLoader(classLoader, onFailure = {}),
                    project,
                    BundledRulePackConfigResolver,
                )
            block(pipeline)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
