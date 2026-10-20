package dev.cint.integration.view

import dev.cint.api.Annotation
import dev.cint.api.CallFact
import dev.cint.api.CommentFact
import dev.cint.api.Contribution
import dev.cint.api.Coverage
import dev.cint.api.DeclarationFact
import dev.cint.api.FunctionFact
import dev.cint.api.GuardFact
import dev.cint.api.IfFact
import dev.cint.api.PropertyFact
import dev.cint.api.ReturnFact
import dev.cint.api.Role
import dev.cint.api.ScopeFact
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.api.TypeRef
import dev.cint.api.WriteFact
import dev.cint.core.facts.flow.ControlFlow
import dev.cint.core.facts.flow.ControlFlowBuilder
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.facts.view.CapabilityView
import dev.cint.core.runtime.context.RuleContext
import dev.cint.core.runtime.engine.AnalysisContext
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.core.test.testConfig
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapabilityViewTest {
    private val config = testConfig("kotlin")
    private val text =
        """
        import java.util.List

        class Box {
            @Pure
            fun pureValue(): String = "x"

            var count = 0

            fun run(input: String?): String {
                val name = input ?: return "none"
                count = count + 1
                if (input == null) {
                    return "null"
                }
                try {
                    println(name)
                    throw IllegalStateException("bad")
                } catch (failure: Exception) {
                    println(failure.message)
                }
                return name
            }
        }
        """.trimIndent()
    private val source = SourceFile("Sample.kt", text)
    private val tree = KotlinParser().parse(source)
    private val syntax = KotlinSyntaxModel
    private val flow = ControlFlowBuilder(tree, syntax).build()

    private fun context(
        seed: Boolean = true,
        configure: (AnalysisContext, AnnotationStore) -> Unit = { _, _ -> },
    ): AnalysisContext {
        val store = AnnotationStore()
        val context = AnalysisContext(source, tree, syntax, flow, NullOracle, config, store)
        if (seed) {
            val ifNode = context.ifs.first().node
            store.replaceRule("logging", listOf(contribution("logging", "LOGGING", ifNode)))
            store.replaceRule("validation", listOf(contribution("validation", "VALIDATION", ifNode)))
            store.replaceRule("bookkeeping", listOf(contribution("bookkeeping", "BOOKKEEPING", ifNode)))
            store.replaceRule("mixed", listOf(contribution("mixed", setOf("LOGGING", "BOOKKEEPING"), ifNode)))
        }
        configure(context, store)
        return context
    }

    private fun assertSameNode(
        expected: SyntaxNode?,
        actual: SyntaxNode?,
    ) {
        assertNotNull(expected)
        assertNotNull(actual)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.startByte, actual.startByte)
        assertEquals(expected.endByte, actual.endByte)
    }

    private fun contribution(
        rule: String,
        intent: String,
        node: SyntaxNode,
        role: Role = Role.SOURCE_INTENT,
    ): Contribution = contribution(rule, setOf(intent), node, role)

    private fun contribution(
        rule: String,
        intents: Set<String>,
        node: SyntaxNode,
        role: Role = Role.SOURCE_INTENT,
    ): Contribution =
        Contribution(
            rule = rule,
            role = role,
            intents = intents,
            evidence = rule,
            span = node.span(),
            source = "treesitter",
            confidence = 1.0,
            startByte = node.startByte,
            endByte = node.endByte,
        )

    @Test
    fun coreFactsAreAvailable() {
        val view = context(seed = false).view("VALIDATION", setOf("BOOKKEEPING"))

        assertTrue(view.calls().any { it.callee == "println" })
        assertTrue(view.properties().any { it.name == "count" })
        assertTrue(view.functions().any { it.name == "run" })
        assertTrue(view.ifs().isNotEmpty())
        assertTrue(view.tries().isNotEmpty())
        assertTrue(view.catches().isNotEmpty())
        assertTrue(view.imports().contains("java.util.List"))
        assertTrue(view.references().containsKey("count"))
        assertTrue(view.throws().isNotEmpty())
    }

    @Test
    fun derivedFactsAreAvailable() {
        val view = context(seed = false).view("VALIDATION", setOf("BOOKKEEPING"))
        val ifNode = view.ifs().single().node
        val run = view.functions().first { it.name == "run" }.node
        val pure = view.functions().first { it.name == "pureValue" }.node
        val property = view.properties().first { it.name == "count" }

        assertSameNode(run, view.enclosingFunction(ifNode))
        assertSameNode(run, view.enclosingDeclaration(ifNode))
        assertSameNode(property.node, view.enclosingDeclaration(property.nameNode!!))

        val countReferences = view.references().getValue("count")
        assertTrue(countReferences.any { view.writes(it) != null })
        assertTrue(countReferences.any { view.writes(it) == null })

        assertTrue(view.isAnnotatedPure(pure))
        assertFalse(view.isAnnotatedPure(run))

        assertTrue(view.coverage(pure).hasUntagged)
        assertTrue(view.intentsAt(pure).isEmpty())
    }

    @Test
    fun annotationsAndIntentsAtHideUndeclaredIntents() {
        val view = context().view("VALIDATION", setOf("BOOKKEEPING"))
        val ifNode = view.ifs().single().node

        assertEquals(setOf("VALIDATION", "BOOKKEEPING"), view.intentsAt(ifNode))
        assertTrue(view.annotations("LOGGING").isEmpty())

        val annotation = view.annotations("VALIDATION").single()
        assertEquals(setOf("VALIDATION", "BOOKKEEPING"), annotation.intents)
        assertEquals(setOf("VALIDATION", "BOOKKEEPING"), annotation.provenance.flatMap { it.intents }.toSet())
        assertTrue(annotation.provenance.none { "LOGGING" in it.intents })

        assertEquals(setOf("VALIDATION", "BOOKKEEPING"), view.annotations("BOOKKEEPING").single().intents)
        assertEquals(setOf("LOGGING", "VALIDATION", "BOOKKEEPING"), view.coverage(ifNode).intents)
    }

    @Test
    fun ownedIntentIsAlwaysVisible() {
        val logging = context().view("LOGGING", emptySet())
        val ifNode = logging.ifs().single().node

        assertEquals(setOf("LOGGING"), logging.intentsAt(ifNode))
        assertEquals(setOf("LOGGING"), logging.annotations("LOGGING").single().intents)
        assertTrue(logging.annotations("VALIDATION").isEmpty())
        assertTrue(logging.annotations("BOOKKEEPING").isEmpty())
    }

    @Test
    fun undeclaredReadCanFailLoudly() {
        val strict = context().view("VALIDATION", setOf("BOOKKEEPING"), failOnUndeclaredRead = true)

        val error = assertFailsWith<IllegalStateException> { strict.annotations("LOGGING") }

        assertTrue(error.message.orEmpty().contains("LOGGING"))
        assertNotNull(strict.annotations("VALIDATION").singleOrNull())
    }

    @Test
    fun intentsAtSelectsVisibleAnnotationBeforeInnermost() {
        val context =
            context(seed = false) { current, store ->
                val ifFact = current.ifs.single()
                store.replaceRule("bookkeeping", listOf(contribution("bookkeeping", "BOOKKEEPING", ifFact.node)))
                store.replaceRule("logging", listOf(contribution("logging", "LOGGING", ifFact.condition!!)))
            }
        val view = context.view("VALIDATION", setOf("BOOKKEEPING"))
        val condition = view.ifs().single().condition!!

        assertEquals(setOf("BOOKKEEPING"), view.intentsAt(condition))
        assertTrue("LOGGING" !in view.intentsAt(condition))
    }

    @Test
    fun strictModeFailsOnlyForUndeclaredSourceCoverage() {
        val context =
            context(seed = false) { current, store ->
                val pure = current.functions.first { it.name == "pureValue" }.node
                store.replaceRule("logging", listOf(contribution("logging", "LOGGING", pure)))
            }
        val node = context.functions.first { it.name == "pureValue" }.node
        val loose = context.view("VALIDATION", setOf("BOOKKEEPING"))
        val strict = context.view("VALIDATION", setOf("BOOKKEEPING"), failOnUndeclaredRead = true)

        assertTrue(loose.intentsAt(node).isEmpty())
        assertFailsWith<IllegalStateException> { strict.intentsAt(node) }
    }

    @Test
    fun pluginFacingReadsIgnoreCoreOnlyRoles() {
        val context =
            context(seed = false) { current, store ->
                val ifNode = current.ifs.single().node
                store.replaceRule("scaffold", listOf(contribution("scaffold", "BOOKKEEPING", ifNode, Role.SCAFFOLD)))
                store.replaceRule(
                    "derivative",
                    listOf(contribution("derivative", "BOOKKEEPING", ifNode, Role.DERIVATIVE_USE)),
                )
            }
        val view = context.view("VALIDATION", setOf("BOOKKEEPING"))
        val strict = context.view("VALIDATION", setOf("BOOKKEEPING"), failOnUndeclaredRead = true)
        val ifNode = view.ifs().single().node

        assertTrue(view.annotations("BOOKKEEPING").isEmpty())
        assertTrue(view.intentsAt(ifNode).isEmpty())
        assertTrue(strict.intentsAt(ifNode).isEmpty())
    }

    @Test
    fun annotationsRebuildIntentsFromSurvivingProvenance() {
        val contribution =
            Contribution(
                rule = "demo:bookkeeping",
                role = Role.SOURCE_INTENT,
                intents = setOf("BOOKKEEPING"),
                evidence = "bookkeeping",
                span = Span(0, 0, 0, 1),
                source = "treesitter",
                confidence = 1.0,
                startByte = 0,
                endByte = 1,
            )
        val annotation =
            Annotation(
                role = Role.SOURCE_INTENT,
                intents = setOf("BOOKKEEPING", "VALIDATION"),
                evidence = "advertised",
                span = Span(0, 0, 0, 1),
                source = "treesitter",
                confidence = 1.0,
                startByte = 0,
                endByte = 1,
                provenance = listOf(contribution),
            )
        val view =
            CapabilityView(
                context = AnnotationContext(listOf(annotation)),
                owns = "VALIDATION",
                reads = setOf("BOOKKEEPING"),
                pureAnnotations = emptyList(),
            )

        assertEquals(setOf("BOOKKEEPING"), view.annotations("BOOKKEEPING").single().intents)
        assertTrue(view.annotations("VALIDATION").isEmpty())
    }
}

private class AnnotationContext(
    private val entries: List<Annotation>,
) : RuleContext {
    override val file: SourceFile get() = error("unused")
    override val language: String get() = "kotlin"
    override val tree: SyntaxTree get() = error("unused")
    override val syntax: SyntaxModel get() = error("unused")
    override val flow: ControlFlow get() = error("unused")
    override val oracle: TypeOracle get() = error("unused")
    override val calls: List<CallFact> get() = emptyList()
    override val properties: List<PropertyFact> get() = emptyList()
    override val functions: List<FunctionFact> get() = emptyList()
    override val declarations: List<DeclarationFact> get() = emptyList()
    override val writes: List<WriteFact> get() = emptyList()
    override val comments: List<CommentFact> get() = emptyList()
    override val scopes: List<ScopeFact> get() = emptyList()
    override val guards: List<GuardFact> get() = emptyList()
    override val returnSites: List<ReturnFact> get() = emptyList()
    override val ifs: List<IfFact> get() = emptyList()
    override val tries: List<TryFact> get() = emptyList()
    override val catches: List<SyntaxNode> get() = emptyList()
    override val guardConditions: List<SyntaxNode> get() = emptyList()
    override val imports: List<String> get() = emptyList()
    override val references: Map<String, List<SyntaxNode>> get() = emptyMap()
    override val throws: List<SyntaxNode> get() = emptyList()

    override fun receiverType(call: CallFact): TypeRef? = null

    override fun commentBefore(node: SyntaxNode): CommentFact? = null

    override fun annotations(): List<Annotation> = entries

    override fun covering(node: SyntaxNode): List<Annotation> = emptyList()

    override fun containingAnnotations(node: SyntaxNode): List<Annotation> = entries

    override fun intentsAt(node: SyntaxNode): Set<String> = emptySet()

    override fun coverage(
        node: SyntaxNode,
        effect: Boolean,
    ): Coverage = Coverage(emptySet())
}
