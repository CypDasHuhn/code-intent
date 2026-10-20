package dev.cint.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeNode(
    override val kind: String,
    override val startByte: Int,
    override val endByte: Int,
    override val text: String,
    override val parent: SyntaxNode? = null,
    override val children: List<SyntaxNode> = emptyList(),
) : SyntaxNode {
    override val startLine: Int get() = 0
    override val startCol: Int get() = startByte
    override val endLine: Int get() = 0
    override val endCol: Int get() = endByte
}

private class FakeView(
    private val callFacts: List<CallFact> = emptyList(),
    private val importNames: List<String> = emptyList(),
) : View {
    override fun language(): String = "kotlin"

    override fun typeAt(node: SyntaxNode): TypeRef? = null

    override fun calls(): List<CallFact> = callFacts

    override fun properties(): List<PropertyFact> = emptyList()

    override fun functions(): List<FunctionFact> = emptyList()

    override fun ifs(): List<IfFact> = emptyList()

    override fun guardConditions(): List<SyntaxNode> = emptyList()

    override fun tries(): List<TryFact> = emptyList()

    override fun catches(): List<SyntaxNode> = emptyList()

    override fun imports(): List<String> = importNames

    override fun references(): Map<String, List<SyntaxNode>> = emptyMap()

    override fun throws(): List<SyntaxNode> = emptyList()

    override fun coverage(node: SyntaxNode): Coverage = Coverage(emptySet())

    override fun enclosingFunction(node: SyntaxNode): SyntaxNode? = null

    override fun enclosingDeclaration(node: SyntaxNode): SyntaxNode? = null

    override fun writes(reference: SyntaxNode): SyntaxNode? = null

    override fun isAnnotatedPure(node: SyntaxNode): Boolean = false

    override fun isConstant(node: SyntaxNode): Boolean = false

    override fun isSideEffectFree(node: SyntaxNode): Boolean = false

    override fun statements(container: SyntaxNode): List<SyntaxNode>? = null

    override fun isExit(node: SyntaxNode): Boolean = false

    override fun annotations(intent: Intent): List<Annotation> = emptyList()

    override fun intentsAt(node: SyntaxNode): Set<Intent> = emptySet()
}

private fun call(
    callee: String?,
    receiver: String?,
    startByte: Int,
    endByte: Int,
): CallFact {
    val node = FakeNode("call_expression", startByte, endByte, "$receiver.$callee(...)")
    val receiverNode = receiver?.let { FakeNode("identifier", startByte, startByte + it.length, it, node) }
    return CallFact(node = node, site = node, callee = callee, receiver = receiverNode)
}

private class ConsoleLoggingRule(
    private val maxOffset: Int = 100,
) : Rule {
    override val id: String = "demo:console-logging"
    override val owns: Intent = "LOGGING"
    override val reads: Set<Intent> = emptySet()

    private val consoleContext: Predicate =
        Predicate { view -> view.imports().any { name -> (DslValue(name) eq "console").test() } } or
            Predicate { view -> view.calls().any { fact -> (DslValue(fact.receiver?.text) eq "console").test() } }

    override fun run(view: View): List<Emit> {
        if (!consoleContext.test(view)) return emptyList()
        return view
            .calls()
            .filter { call ->
                (
                    (DslValue(call.callee) eq "log") or
                        (
                            (DslValue(call.callee) eq "warn") and
                                (DslValue(call.receiver?.text) ne null) and
                                (DslValue(call.node.startByte) gt 0) and
                                (DslValue(call.node.endByte) lt maxOffset)
                        )
                ).test()
            }.map { call ->
                Emit(
                    intents = setOf(owns),
                    evidence = call.node.text,
                    span = call.node.span(),
                    source = "treesitter",
                    confidence = 1.0,
                    startByte = call.node.startByte,
                    endByte = call.node.endByte,
                )
            }
    }
}

class DslTest {
    @Test
    fun infixComparisonsEvaluate() {
        val missing: String? = null

        assertTrue((DslValue("a") eq "a").test())
        assertFalse((DslValue("a") eq "b").test())
        assertTrue((DslValue("a") ne "b").test())
        assertFalse((DslValue("a") ne "a").test())
        assertTrue((DslValue(missing) eq null).test())
        assertFalse((DslValue(missing) eq "a").test())
        assertTrue((DslValue(missing) ne "a").test())
        assertFalse((DslValue(missing) ne null).test())
        assertTrue((DslValue(3) eq 3).test())
        assertFalse((DslValue(3) eq 2).test())
        assertTrue((DslValue(3) gt 2).test())
        assertFalse((DslValue(2) gt 3).test())
        assertTrue((DslValue(2) lt 3).test())
        assertFalse((DslValue(3) lt 2).test())
    }

    @Test
    fun infixLogicCombinesConditions() {
        val truth = DslValue("x") eq "x"
        val lie = DslValue("x") eq "y"

        assertTrue((truth and truth).test())
        assertFalse((truth and lie).test())
        assertTrue((truth or lie).test())
        assertFalse((lie or lie).test())
    }

    @Test
    fun conditionsWorkInsideFilterLambdas() {
        val letters = listOf("a", "b", "c")

        assertEquals(listOf("b", "c"), letters.filter { (DslValue(it) gt "a").test() })
        assertEquals(listOf("a", "b"), letters.filter { (DslValue(it) lt "c").test() })
    }

    @Test
    fun viewPredicatesAcceptLambdasAndCombine() {
        val imported = Predicate { view -> view.imports().any { name -> (DslValue(name) eq "console").test() } }
        val logged = Predicate { view -> view.calls().any { fact -> (DslValue(fact.callee) eq "log").test() } }

        assertTrue((imported or logged).test(FakeView(importNames = listOf("console"))))
        assertTrue((imported or logged).test(FakeView(callFacts = listOf(call("log", null, 1, 2)))))
        assertFalse((imported or logged).test(FakeView()))
        assertTrue((imported and logged).test(FakeView(listOf(call("log", null, 1, 2)), listOf("console"))))
        assertFalse((imported and logged).test(FakeView(importNames = listOf("console"))))
    }

    @Test
    fun composedConditionsEvaluateByBehavior() {
        val accepted = (DslValue(3) gt 2) and ((DslValue("a") eq "a") or (DslValue("b") ne "c"))
        val rejected = (DslValue(3) gt 2) and ((DslValue("a") eq "b") or (DslValue("b") ne "b"))

        assertTrue(accepted.test())
        assertFalse(rejected.test())
    }

    @Test
    fun representativeRuleUsesDslAgainstFakeView() {
        val view =
            FakeView(
                callFacts =
                    listOf(
                        call("log", "console", 10, 20),
                        call("warn", "logger", 30, 40),
                        call("warn", null, 35, 45),
                        call("debug", "console", 50, 60),
                        call("warn", "console", 0, 40),
                        call("warn", "console", 60, 120),
                    ),
            )

        val emits = ConsoleLoggingRule(maxOffset = 100).run(view)

        assertEquals(listOf("console.log(...)", "logger.warn(...)"), emits.map { it.evidence })
        assertEquals(listOf(Span(0, 10, 0, 20), Span(0, 30, 0, 40)), emits.map { it.span })
        assertTrue(emits.all { it.intents == setOf("LOGGING") })
        assertTrue(emits.all { it.source == "treesitter" })
    }

    @Test
    fun representativeRuleRejectsNullReceiverOnWarnBranch() {
        val view = FakeView(callFacts = listOf(call("warn", null, 10, 20)), importNames = listOf("console"))

        assertTrue(ConsoleLoggingRule().run(view).isEmpty())
    }

    @Test
    fun representativeRuleGatesOnViewPredicate() {
        val rule = ConsoleLoggingRule()
        val imported = FakeView(callFacts = listOf(call("log", "custom", 10, 20)), importNames = listOf("console"))
        val unimported = FakeView(callFacts = listOf(call("log", "custom", 10, 20)))

        assertEquals(1, rule.run(imported).size)
        assertTrue(rule.run(unimported).isEmpty())
    }
}
