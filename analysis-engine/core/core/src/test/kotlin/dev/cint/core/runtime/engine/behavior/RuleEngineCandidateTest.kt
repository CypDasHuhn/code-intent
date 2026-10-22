package dev.cint.core.runtime.engine.behavior

import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Intent
import dev.cint.api.Span
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.engine.support.TestPack
import dev.cint.core.runtime.engine.support.TestRule
import dev.cint.core.runtime.engine.support.emitAt
import dev.cint.core.runtime.engine.support.fakeSyntax
import dev.cint.core.runtime.engine.support.fakeTree
import dev.cint.core.runtime.engine.support.indexedEmit
import dev.cint.core.runtime.scheduler.RuleEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleEngineCandidateTest {
    private val config = IntentConfig("kotlin", emptyList())

    @Test
    fun candidatesStayOutOfAnnotationsAndComposition() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = fakeTree(source.text)
        val node = tree.root
        val established =
            TestRule("test:established", "ESTABLISHED") {
                listOf(emitAt("ESTABLISHED", node))
            }
        val secondary =
            TestRule("test:candidate", "CANDIDATE") {
                listOf(candidateAt("CANDIDATE", node, CandidateReason.GAP))
            }

        val output =
            RuleEngine().analyze(
                source,
                tree,
                fakeSyntax(),
                NullOracle,
                config,
                listOf(TestPack("test", listOf(established, secondary))),
            )

        assertEquals(setOf("ESTABLISHED"), output.annotations.flatMap { it.intents }.toSet())
        assertTrue(output.annotations.none { "CANDIDATE" in it.intents })
        assertTrue(output.annotations.flatMap { it.provenance }.none { "CANDIDATE" in it.intents })
        assertEquals(1, output.candidates.size)
        assertEquals(
            "CANDIDATE",
            output.candidates
                .single()
                .intents
                .single(),
        )
    }

    @Test
    fun fixpointRuleKeepsCandidatesFromFinalSettledIterationOnly() {
        val source = SourceFile("Sample.kt", "x")
        val tree = fakeTree(source.text)
        val chain =
            TestRule("test:chain", "CHAIN", setOf("CHAIN")) { view ->
                val seen = view.annotations("CHAIN").size
                val count = (seen + 1).coerceAtMost(3)
                (0 until count).map { indexedEmit("CHAIN", it) } +
                    CandidateEmit(
                        intents = setOf("CHAIN"),
                        span = Span(9, 0, 9, 1),
                        startByte = 90,
                        endByte = 91,
                        source = "candidate:$seen",
                        confidence = 0.3,
                        reason = CandidateReason.LOW_CONFIDENCE,
                    )
            }

        val output =
            RuleEngine().analyze(
                source,
                tree,
                fakeSyntax(),
                NullOracle,
                config,
                listOf(TestPack("test", listOf(chain))),
            )

        assertEquals(4, chain.runs)
        assertEquals(3, output.annotations.count { "CHAIN" in it.intents })
        assertEquals(1, output.candidates.size)
        assertEquals("candidate:3", output.candidates.single().source)
    }

    private fun candidateAt(
        intent: Intent,
        node: dev.cint.api.SyntaxNode,
        reason: CandidateReason,
    ): CandidateEmit =
        CandidateEmit(
            intents = setOf(intent),
            span = node.span(),
            startByte = node.startByte,
            endByte = node.endByte,
            source = "rule:test",
            confidence = 0.4,
            reason = reason,
        )

    @Test
    fun candidatesAreInvisibleToDownstreamReads() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = fakeTree(source.text)
        val node = tree.root
        val candidate =
            TestRule("test:candidate", "CANDIDATE") {
                listOf(candidateAt("CANDIDATE", node, CandidateReason.GAP))
            }
        val reader =
            TestRule("test:reader", "READ", setOf("CANDIDATE")) { view ->
                assertTrue(view.annotations("CANDIDATE").isEmpty())
                assertTrue(view.intentsAt(node).none { it == "CANDIDATE" })
                listOf(
                    dev.cint.api.Emit
                        .of("READ", node),
                )
            }

        val output =
            RuleEngine().analyze(
                source,
                tree,
                fakeSyntax(),
                NullOracle,
                config,
                listOf(TestPack("test", listOf(candidate, reader))),
            )

        assertEquals(1, reader.runs)
        assertTrue(output.annotations.none { "CANDIDATE" in it.intents })
    }
}
