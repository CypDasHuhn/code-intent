package dev.cint.rulepacks.tests.user

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.View
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.user.USER_CONTRIBUTOR_ID
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationsJson
import dev.cint.host.HostPipeline
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class HostLoggingRule : Rule {
    override val id: String = "user-test:merge"
    override val owns: Intent = "USER_MERGE_INTENT"
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<Emit> =
        view
            .functions()
            .map { function ->
                Emit.of("USER_MERGE_INTENT", function.node, source = "test")
            }.toList()
}

class HostLoggingPack : RulePack {
    override val id: String = "user-test"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(HostLoggingRule())
}

class HostUserAnnotationsTest {
    @Test
    fun userAnnotationsMergeWithRulePackContributionsAndDisappearWhenDeleted() {
        val root = Files.createTempDirectory("cint-user-annotations")
        try {
            val text =
                """
                fun make(): String = "x"
                fun keep(): String = "y"
                """.trimIndent()
            val source = SourceFile("Sample.kt", text)
            val tree = KotlinParser().parse(source)
            val syntax = KotlinSyntaxModel
            val function = syntax.functions(tree).first { it.name == "make" }
            val config = IntentConfig("kotlin", emptyList())
            withLoggingPipeline(root) { pipeline ->
                write(
                    root,
                    UserAnnotation(
                        path = "Sample.kt",
                        span = function.node.span(),
                        intents = setOf("PURE", "TEAM_CUSTOM_INTENT"),
                        symbol = "make",
                        contentHash = contentHash(function.node.text),
                    ),
                )

                val merged = pipeline.analyze(source, tree, syntax, NullOracle, config)
                val annotation =
                    merged.first {
                        it.role == Role.SOURCE_INTENT && "TEAM_CUSTOM_INTENT" in it.intents
                    }

                assertEquals(setOf("USER_MERGE_INTENT", "PURE", "TEAM_CUSTOM_INTENT"), annotation.intents)
                assertEquals(null, annotation.nodeAnchor)
                assertTrue(
                    pipeline.intents(listOf("kotlin")).containsAll(
                        listOf("USER_MERGE_INTENT", "PURE", "TEAM_CUSTOM_INTENT", "UNTAGGED"),
                    ),
                )
                assertEquals(
                    setOf("user-test:merge", USER_CONTRIBUTOR_ID),
                    annotation.provenance.map { it.rule }.toSet(),
                )
                assertTrue(annotation.provenance.any { it.rule == USER_CONTRIBUTOR_ID && it.intents.contains("PURE") })

                write(root)
                val removed = pipeline.analyze(source, tree, syntax, NullOracle, config)

                assertFalse(removed.any { "TEAM_CUSTOM_INTENT" in it.intents })
                assertFalse(removed.any { annotation -> annotation.provenance.any { it.rule == USER_CONTRIBUTOR_ID } })
                assertTrue(removed.any { "USER_MERGE_INTENT" in it.intents })
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun withLoggingPipeline(
        root: Path,
        block: (HostPipeline) -> Unit,
    ) {
        val directory = Files.createTempDirectory("cint-user-annotations-pack")
        val service = directory.resolve("META-INF/services/${RulePack::class.java.name}")
        Files.createDirectories(service.parent)
        Files.writeString(service, HostLoggingPack::class.java.name)
        try {
            val classLoader =
                URLClassLoader(
                    arrayOf(directory.toUri().toURL()),
                    HostUserAnnotationsTest::class.java.classLoader,
                )
            block(
                HostPipeline(
                    loader = RulePackLoader(classLoader, onFailure = {}),
                    root = root,
                ),
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun write(
        root: Path,
        vararg annotations: UserAnnotation,
    ) {
        val file = root.resolve(".cint/user-annotations.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, UserAnnotationsJson.encode(annotations.toList()))
    }
}
