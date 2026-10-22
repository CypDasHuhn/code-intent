package dev.cint.core.runtime.engine

import dev.cint.api.CallFact
import dev.cint.api.SyntaxNode
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.runtime.engine.support.FakeNode
import dev.cint.core.runtime.engine.support.TestPack
import dev.cint.core.runtime.engine.support.TestRule
import dev.cint.core.runtime.engine.support.emitAt
import dev.cint.core.runtime.engine.support.fakeSyntax
import dev.cint.core.runtime.engine.support.fakeTree
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import kotlin.test.Test
import kotlin.test.assertEquals

private class CountingTypeOracle : TypeOracle {
    var calls = 0
        private set

    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? {
        calls++
        return TypeFact("Acme.Type")
    }
}

class RuleEngineOracleSessionTest {
    private val config = IntentConfig("kotlin", emptyList())

    @Test
    fun oneAnalysisReusesAReceiverTypeAcrossRules() {
        val text = "x.get()"
        val source = SourceFile("Sample.kt", text)
        val tree = fakeTree(text)
        val callNode = FakeNode("call_expression", 0, text.length, text)
        val receiver = FakeNode("identifier", 0, 1, "x", parent = callNode)
        val call = CallFact(callNode, callNode, "get", receiver, "x.get")
        val syntax =
            object : SyntaxModel by fakeSyntax() {
                override fun calls(tree: SyntaxTree): Sequence<CallFact> = sequenceOf(call)

                override fun receiverOffset(receiver: SyntaxNode): Int = receiver.startByte
            }
        val oracle = CountingTypeOracle()
        val first =
            TestRule("test:first", "FIRST") { view ->
                view.receiverType(call)
                listOf(emitAt("FIRST", tree.root))
            }
        val second =
            TestRule("test:second", "SECOND") { view ->
                view.receiverType(call)
                listOf(emitAt("SECOND", tree.root))
            }

        val output =
            RuleEngine().analyze(
                source,
                tree,
                syntax,
                oracle,
                config,
                listOf(TestPack("test", listOf(first, second))),
            )

        assertEquals(1, oracle.calls)
        assertEquals(1L, output.oracle.queries)
        assertEquals(1L, output.oracle.cacheHits)
    }
}
