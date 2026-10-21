package dev.cint.treesitter.language

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.View
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.test.testConfig
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import kotlin.test.Test
import kotlin.test.assertTrue

private class UserPureLoggingRule : Rule {
    override val id: String = "user-pure:logging"
    override val owns: Intent = "LOGGING"
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> =
        view
            .calls()
            .filter { it.callee == "warn" }
            .map { Emit.of("LOGGING", it.site) }
            .toList()
}

private class UserPureLoggingPack : RulePack {
    override val id: String = "user-pure"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(UserPureLoggingRule())
}

class UserAnnotationPureTest {
    @Test
    fun userPureAnnotationPromotesDerivativeUse() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            private val π = 1
            fun make(): String = "x"
            fun f() {
                val source = make()
                val message = source
                log.warn(message)
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val syntax = KotlinSyntaxModel
        val function = syntax.functions(tree).first { it.name == "make" }.node
        val users =
            UserAnnotationResolver.resolve(
                listOf(
                    UserAnnotation(
                        path = "Sample.kt",
                        span = function.span(),
                        intents = setOf("PURE"),
                        symbol = "make",
                        contentHash = contentHash(function.text),
                    ),
                ),
                source,
                tree,
                syntax,
                null,
            )

        val annotations =
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    syntax,
                    NullOracle,
                    testConfig("kotlin"),
                    listOf(UserPureLoggingPack()),
                    users,
                ).annotations
        val derivatives = annotations.filter { it.role == Role.DERIVATIVE_USE }

        assertTrue(derivatives.any { slice(text, it.startByte, it.endByte).contains("fun make") })
        assertTrue(derivatives.any { slice(text, it.startByte, it.endByte).contains("val source") })
        assertTrue(derivatives.any { slice(text, it.startByte, it.endByte).contains("val message") })
        assertTrue(derivatives.all { "LOGGING" in it.intents })
    }

    private fun slice(
        text: String,
        start: Int,
        end: Int,
    ): String = String(text.toByteArray(Charsets.UTF_8), start, end - start, Charsets.UTF_8)
}
