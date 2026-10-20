package dev.cint.treesitter

import dev.cint.api.Annotation
import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.api.View
import dev.cint.core.CoreContributors
import dev.cint.core.config.IntentConfig
import dev.cint.core.engine.AnalysisContext
import dev.cint.core.flow.ControlFlowBuilder
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.NullOracle
import dev.cint.core.scheduler.RuleEngine
import dev.cint.core.scheduler.RuleScheduler
import dev.cint.core.store.AnnotationStore
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxProfiles
import dev.cint.core.syntax.SyntaxTree
import dev.cint.rulepacks.BOOKKEEPING_INTENT
import dev.cint.rulepacks.BookkeepingRulePack
import dev.cint.rulepacks.LoggingRulePack
import dev.cint.rulepacks.VALIDATION_INTENT
import dev.cint.rulepacks.VALIDATION_RULE_ID
import dev.cint.rulepacks.ValidationRulePack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class NoOpValidationRule : Rule {
    override val id: String = "validation-noop:validation"
    override val owns: Intent = VALIDATION_INTENT
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<Emit> = emptyList()
}

private class NoOpValidationPack : RulePack {
    override val id: String = "validation-noop"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(NoOpValidationRule())
}

class ValidationRulePackTest {
    private data class Input(
        val source: SourceFile,
        val tree: SyntaxTree,
        val syntax: SyntaxModel,
        val config: IntentConfig,
    )

    @Test
    fun validationReturnAndBareReturnAreNotUntagged() {
        val text =
            """
            fun f(value: Int): Boolean {
                if (value < 0) {
                    return false
                }
                return true
            }
            fun g(value: String?) {
                if (value != null) {
                    return
                }
            }
            """.trimIndent()
        val input = parse("kotlin", "Sample.kt", text)
        val annotations = newPipeline(input)
        val returns = annotations.filter { it.rule == CoreContributors.RETURN_CATEGORY }

        val falseReturn = returns.first { it.evidence.contains("return false") }
        val bareReturn = returns.first { it.evidence.trim() == "return" }
        assertEquals(setOf(VALIDATION_INTENT), falseReturn.intents)
        assertEquals(setOf(VALIDATION_INTENT), bareReturn.intents)
        assertTrue(UNTAGGED_INTENT !in falseReturn.intents)
        assertTrue(UNTAGGED_INTENT !in bareReturn.intents)

        for (guard in input.syntax.ifs(input.tree)) {
            val condition = guard.condition
            assertNotNull(condition)
            val source =
                annotations.single {
                    it.role == Role.SOURCE_INTENT &&
                        it.rule == VALIDATION_RULE_ID &&
                        it.startByte == condition.startByte &&
                        it.endByte == condition.endByte
                }
            assertEquals(setOf(VALIDATION_INTENT), source.intents)

            val scaffold =
                annotations.firstOrNull {
                    it.role == Role.SCAFFOLD &&
                        it.rule == CoreContributors.SCAFFOLD &&
                        it.startByte == guard.node.startByte &&
                        it.endByte == guard.node.endByte
                }
            assertNotNull(scaffold, "missing guard scaffold for ${guard.node.text}")
            assertEquals(setOf(VALIDATION_INTENT), scaffold.intents)
        }
    }

    @Test
    fun returnsRequireEmittedValidationAnnotations() {
        val text =
            """
            fun f(value: Int): Boolean {
                if (value < 0) {
                    return false
                }
                return true
            }
            """.trimIndent()
        val input = parse("kotlin", "Sample.kt", text)

        fun returns(
            source: Input,
            packs: List<RulePack>,
        ) = RuleEngine()
            .analyze(
                source.source,
                source.tree,
                source.syntax,
                NullOracle,
                source.config,
                packs,
            ).first {
                it.rule == CoreContributors.RETURN_CATEGORY && it.evidence.contains("return false")
            }

        val withoutValidation = returns(input, listOf(BookkeepingRulePack()))
        val withNoOpValidation = returns(input, listOf(BookkeepingRulePack(), NoOpValidationPack()))
        val withValidation = returns(input, listOf(BookkeepingRulePack(), ValidationRulePack()))
        val withoutValidationIntent =
            returns(
                input.copy(config = IntentConfig("kotlin", emptyMap(), emptyList())),
                emptyList(),
            )

        assertEquals(setOf(UNTAGGED_INTENT), withoutValidation.intents)
        assertEquals(setOf(UNTAGGED_INTENT), withNoOpValidation.intents)
        assertEquals(setOf(VALIDATION_INTENT), withValidation.intents)
        assertEquals(setOf(UNTAGGED_INTENT), withoutValidationIntent.intents)
    }

    @Test
    fun nestedFunctionAndLambdaReturnsDoNotCertifyGuards() {
        val cases =
            listOf(
                """
                fun f(values: List<String>) {
                    if (values.isNotEmpty()) {
                        fun positive() {
                            return
                        }
                        positive()
                    }
                }
                """.trimIndent(),
                """
                fun f(values: List<String>) {
                    if (values.isNotEmpty()) {
                        values.forEach { return@forEach }
                    }
                }
                """.trimIndent(),
            )

        for (text in cases) {
            val input = parse("kotlin", "Sample.kt", text)
            val annotations = newPipeline(input)
            assertTrue(validation(input).isEmpty())
            assertTrue(
                annotations.none {
                    VALIDATION_INTENT in it.intents &&
                        (it.rule == VALIDATION_RULE_ID || it.rule == CoreContributors.SCAFFOLD)
                },
            )
        }
    }

    @Test
    fun stringContentIsNotAValidationComment() {
        val cases =
            listOf(
                "kotlin" to
                    """
                    fun consume(value: Int): Boolean = value > 0
                    fun f(value: Int) {
                        val label = "// cint:validation"
                        if (consume(value)) {
                            return
                        }
                    }
                    """.trimIndent(),
                "kotlin" to
                    """
                    fun consume(value: Int): Boolean = value > 0
                    fun f(value: Int) {
                        val label = "/* cint:validation */"
                        if (consume(value)) {
                            return
                        }
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    function consume(value) { return value > 0; }
                    function f(value) {
                        const label = "// cint:validation";
                        if (consume(value)) {
                            return;
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val input = parse(language, "Sample", text)
            assertTrue(validation(input).isEmpty(), "$language string content tagged validation")
        }
    }

    @Test
    fun validationGuardClassifiesPlumbingLocals() {
        val text =
            """
            fun f(value: String?): String {
                val current = value
                if (current != null) {
                    return current
                }
                return ""
            }
            """.trimIndent()
        val input = parse("kotlin", "Sample.kt", text)
        val annotations = newPipeline(input)
        val derivative =
            annotations.first {
                it.role == Role.DERIVATIVE_USE && it.evidence.contains("val current")
            }
        val guard = input.syntax.ifs(input.tree).single()
        val condition = guard.condition
        assertNotNull(condition)
        val source =
            annotations.single {
                it.role == Role.SOURCE_INTENT &&
                    it.rule == VALIDATION_RULE_ID &&
                    it.startByte == condition.startByte &&
                    it.endByte == condition.endByte
            }
        val scaffold =
            annotations.firstOrNull {
                it.role == Role.SCAFFOLD &&
                    it.rule == CoreContributors.SCAFFOLD &&
                    it.startByte == guard.node.startByte &&
                    it.endByte == guard.node.endByte
            }

        assertEquals(setOf(VALIDATION_INTENT), source.intents)
        assertNotNull(scaffold, "missing ambient validation scaffold")
        assertEquals(setOf(VALIDATION_INTENT), scaffold.intents)
        assertEquals(setOf(VALIDATION_INTENT), derivative.intents)
    }

    @Test
    fun bookkeepingOnlyGuardDoesNotMakeItsScaffoldUntagged() {
        val text =
            """
            class C {
                @Bookkeeping var skipped = 0
                fun f(value: Int): Boolean {
                    if (consume(value)) {
                        skipped++
                        return false
                    }
                    return true
                }
                fun consume(value: Int): Boolean = value > 0
            }
            """.trimIndent()
        val input = parse("kotlin", "Sample.kt", text)
        val annotations = newPipeline(input)
        val guard = input.syntax.ifs(input.tree).single()
        val condition = guard.condition
        assertNotNull(condition)
        val source =
            annotations.single {
                it.role == Role.SOURCE_INTENT &&
                    it.rule == VALIDATION_RULE_ID &&
                    it.startByte == condition.startByte &&
                    it.endByte == condition.endByte
            }
        val scaffold =
            annotations.firstOrNull {
                it.role == Role.SCAFFOLD &&
                    it.rule == CoreContributors.SCAFFOLD &&
                    it.startByte == guard.node.startByte &&
                    it.endByte == guard.node.endByte
            }
        val returnFalse =
            annotations.first {
                it.rule == CoreContributors.RETURN_CATEGORY && it.evidence.contains("return false")
            }

        assertEquals(setOf(VALIDATION_INTENT), source.intents)
        assertEquals(setOf(UNTAGGED_INTENT), returnFalse.intents)
        assertNotNull(scaffold, "missing bookkeeping guard scaffold")
        assertEquals(setOf(VALIDATION_INTENT, BOOKKEEPING_INTENT), scaffold.intents)
        assertTrue(UNTAGGED_INTENT !in scaffold.intents)
    }

    @Test
    fun onlyFalseAndBareReturnsAreNeutralForScaffolds() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun loggingEnabled(): Boolean = true
            fun nullCase(): String? {
                if (loggingEnabled()) {
                    log.info("x")
                } else {
                    return null
                }
            }
            fun zeroCase(): Int {
                if (loggingEnabled()) {
                    log.info("x")
                } else {
                    return 0
                }
            }
            fun falseCase(): Boolean {
                if (loggingEnabled()) {
                    log.info("x")
                } else {
                    return false
                }
            }
            fun bareCase() {
                if (loggingEnabled()) {
                    log.info("x")
                } else {
                    return
                }
            }
            """.trimIndent()
        val input = parse("kotlin", "Sample.kt", text)
        val annotations =
            RuleEngine().analyze(
                input.source,
                input.tree,
                input.syntax,
                NullOracle,
                input.config,
                listOf(LoggingRulePack(), BookkeepingRulePack(), ValidationRulePack()),
            )

        fun scaffoldCovers(
            text: String,
            intent: String,
        ): Boolean {
            val node =
                input.tree.root
                    .descendants()
                    .first { it.text.trim() == text }
            return annotations.any {
                it.role == Role.SCAFFOLD &&
                    it.rule == CoreContributors.SCAFFOLD &&
                    it.startByte <= node.startByte &&
                    it.endByte >= node.endByte &&
                    intent in it.intents
            }
        }

        assertTrue(scaffoldCovers("return null", UNTAGGED_INTENT))
        assertTrue(scaffoldCovers("return 0", UNTAGGED_INTENT))
        assertTrue(!scaffoldCovers("return false", UNTAGGED_INTENT))
        assertTrue(
            annotations.none {
                it.role == Role.SCAFFOLD &&
                    it.rule == CoreContributors.SCAFFOLD &&
                    it.intents.isEmpty()
            },
        )
    }

    @Test
    fun validationSchedulesAfterBookkeeping() {
        val plan = RuleScheduler().plan(listOf(ValidationRulePack(), BookkeepingRulePack()))

        assertEquals(
            listOf(listOf("bookkeeping:bookkeeping"), listOf(VALIDATION_RULE_ID)),
            plan.strata.map { stratum -> stratum.map { it.id } },
        )
        assertEquals(setOf(BOOKKEEPING_INTENT), plan.strata[1].single().reads)
    }

    @Test
    fun ruleIsPureAndDoesNotMutateTheStore() {
        val input =
            parse(
                "kotlin",
                "Sample.kt",
                """
                fun f(values: List<String>) {
                    if (values.isEmpty()) {
                        return
                    }
                }
                """.trimIndent(),
            )
        val store = AnnotationStore()
        val flow = ControlFlowBuilder(input.tree, input.syntax).build()
        val context =
            AnalysisContext(
                input.source,
                input.tree,
                input.syntax,
                flow,
                NullOracle,
                input.config,
                store,
            )
        val rule = ValidationRulePack().rules().single()
        val view = context.view(rule.owns, rule.reads)

        val first = rule.run(view)
        val second = rule.run(view)

        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
        assertTrue(store.annotations().isEmpty())
    }

    private fun validation(input: Input): List<Annotation> =
        newPipeline(input).filter { it.role == Role.SOURCE_INTENT && it.rule == VALIDATION_RULE_ID }

    private fun newPipeline(input: Input): List<Annotation> =
        RuleEngine().analyze(
            input.source,
            input.tree,
            input.syntax,
            NullOracle,
            input.config,
            listOf(BookkeepingRulePack(), ValidationRulePack()),
        )

    private fun parse(
        language: String,
        name: String,
        text: String,
    ): Input {
        val source = SourceFile(name, text)
        val config = testConfig(language)
        return when (language) {
            "kotlin" -> {
                Input(source, KotlinParser().parse(source), KotlinSyntaxModel, config)
            }

            "javascript" -> {
                Input(
                    source,
                    JavascriptParser().parse(source),
                    ProfileSyntaxModel(SyntaxProfiles.load(language)),
                    config,
                )
            }

            "typescript" -> {
                Input(
                    source,
                    TypescriptParser().parse(source),
                    ProfileSyntaxModel(SyntaxProfiles.load(language)),
                    config,
                )
            }

            "csharp" -> {
                Input(
                    source,
                    CsharpParser().parse(source),
                    ProfileSyntaxModel(SyntaxProfiles.load(language)),
                    config,
                )
            }

            else -> {
                error("unsupported language: $language")
            }
        }
    }

    private fun fixture(relative: String): String {
        val path = Path.of("..", "..", "testing-grounds", relative)
        check(Files.exists(path)) { "missing fixture $path" }
        return Files.readString(path)
    }
}
