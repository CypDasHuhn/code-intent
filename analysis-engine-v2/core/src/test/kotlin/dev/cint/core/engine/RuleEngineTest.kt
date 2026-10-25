package dev.cint.core.engine

import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.FactBatch
import dev.cint.core.rule.CandidateEmit
import dev.cint.core.rule.Emit
import dev.cint.core.support.TestRule
import dev.cint.core.support.candidate
import dev.cint.core.support.emit
import dev.cint.core.support.emitAt
import dev.cint.core.support.emitRange
import dev.cint.core.support.facts
import dev.cint.core.support.node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleEngineTest {
    private fun singleNodeFacts(text: String = "x"): FactBatch =
        facts("root", listOf(node("root", "program", 0, text.length, text)))

    @Test
    fun selfReadingRuleConvergesUnderInternalFixpoint() {
        val tree = singleNodeFacts("fun f() {}")
        val rule =
            TestRule("test:chain", "CHAIN", setOf("CHAIN")) { view ->
                val count = (view.annotations("CHAIN").size + 1).coerceAtMost(3)
                (0 until count).map { emitRange("CHAIN", it) }
            }

        val output = RuleEngine().analyze(tree, listOf(rule))

        assertEquals(4, rule.runs)
        assertEquals(3, output.annotations.count { "CHAIN" in it.intents })
    }

    @Test
    fun metadataOnlyChangesConvergeAtStableIdentity() {
        val tree = singleNodeFacts("x")
        val rule =
            TestRule("test:metadata", "METADATA", setOf("METADATA")) { view ->
                val prior = view.annotations("METADATA").singleOrNull()?.source.orEmpty()
                val reported = if (prior.length < 3) "$prior|" else prior
                listOf(emitAt("METADATA", 0, reported))
            }

        val output = RuleEngine().analyze(tree, listOf(rule))

        assertEquals(4, rule.runs)
        assertEquals("|||", output.annotations.single { "METADATA" in it.intents }.source)
    }

    @Test
    fun shrinkingFixpointFailsWithNamedNonMonotoneError() {
        val tree = singleNodeFacts()
        val rule =
            TestRule("test:shrink", "SHRINK", setOf("SHRINK")) { view ->
                if (view.annotations("SHRINK").isEmpty()) {
                    listOf(emitAt("SHRINK", 0), emitAt("SHRINK", 1))
                } else {
                    listOf(emitAt("SHRINK", 0))
                }
            }

        val error =
            assertFailsWith<NonMonotoneRule> {
                RuleEngine().analyze(tree, listOf(rule))
            }

        assertEquals("test:shrink", error.ruleId)
        assertEquals(2L, error.iterations)
    }

    @Test
    fun configuredIterationLimitFailsWithNamedError() {
        val tree = singleNodeFacts("fun f() {}")
        val rule =
            TestRule("test:grow", "GROW", setOf("GROW")) { view ->
                val count = (view.annotations("GROW").size + 1).coerceAtMost(4)
                (0 until count).map { emitRange("GROW", it) }
            }

        val error =
            assertFailsWith<NonConvergentRule> {
                RuleEngine(maxIterationsPerRule = 2).analyze(tree, listOf(rule))
            }

        assertEquals("test:grow", error.ruleId)
        assertTrue(error.message.orEmpty().contains("configured iteration limit 2"))
    }


    @Test
    fun optionalSelfReadRunsOnceWithoutEnteringFixpoint() {
        val facts = singleNodeFacts("fun f() {}")
        val rule =
            TestRule("test:optional-self", "SELF", optionalReads = setOf("SELF")) { view ->
                val count = view.annotations("SELF").size + 1
                (0 until count).map { emitRange("SELF", it) }
            }

        val output = RuleEngine().analyze(facts, listOf(rule))

        assertEquals(1, rule.runs)
        assertEquals(1, output.annotations.count { "SELF" in it.intents })
    }

    @Test
    fun rulesInTheSameStratumDoNotSeeLiveOutput() {
        val facts = singleNodeFacts("fun f() {}")
        val node = facts.syntaxTree!!.getNode("root")!!
        val seen = mutableSetOf<String>()
        val first =
            TestRule("test:a", "A") { listOf(emit("A", node)) }
        val second =
            TestRule("test:b", "B") { view ->
                seen += view.coverage(node)
                emptyList<Emit>()
            }

        RuleEngine().analyze(facts, listOf(first, second))

        assertTrue("A" !in seen)
        assertTrue(Intents.UNTAGGED in seen)
    }

    @Test
    fun candidateProvenanceIsPreserved() {
        val facts = singleNodeFacts("fun f() {}")
        val node = facts.syntaxTree!!.getNode("root")!!
        val proposed =
            TestRule("test:candidate", "CANDIDATE") {
                listOf(
                    CandidateEmit(
                        intents = setOf("CANDIDATE"),
                        span = node.span,
                        startByte = node.startByte,
                        endByte = node.endByte,
                        source = "rule:candidate",
                        confidence = Confidence.LOW,
                        reason = CandidateReason.LOW_CONFIDENCE,
                        rationale = "needs review",
                        provider = "fake-provider",
                        model = "fake-model",
                        promptVersion = "v1",
                    ),
                )
            }

        val candidate = RuleEngine().analyze(facts, listOf(proposed)).candidates.single()

        assertEquals("rule:candidate", candidate.source)
        assertEquals(CandidateReason.LOW_CONFIDENCE, candidate.reason)
        assertEquals("needs review", candidate.rationale)
        assertEquals("fake-provider", candidate.provider)
        assertEquals("fake-model", candidate.model)
        assertEquals("v1", candidate.promptVersion)
    }

    @Test
    fun establishedAndCandidateOutputsStaySeparate() {
        val facts = singleNodeFacts("fun f() {}")
        val node = facts.syntaxTree!!.getNode("root")!!
        val established = TestRule("test:established", "ESTABLISHED") { listOf(emit("ESTABLISHED", node)) }
        val proposed =
            TestRule("test:candidate", "CANDIDATE") {
                listOf(candidate("CANDIDATE", node, CandidateReason.PACK_DOMAIN))
            }

        val output = RuleEngine().analyze(facts, listOf(established, proposed))

        assertEquals(listOf("ESTABLISHED"), output.annotations.flatMap { it.intents }.distinct())
        assertTrue(output.annotations.none { "CANDIDATE" in it.intents })
        assertEquals(1, output.candidates.size)
        assertEquals(setOf("CANDIDATE"), output.candidates.single().intents)
        assertEquals(CandidateReason.PACK_DOMAIN, output.candidates.single().reason)
    }

    @Test
    fun candidatesAreInvisibleToDownstreamRules() {
        val facts = singleNodeFacts("fun f() {}")
        val node = facts.syntaxTree!!.getNode("root")!!
        val proposed =
            TestRule("test:candidate", "CANDIDATE") {
                listOf(candidate("CANDIDATE", node))
            }
        val reader =
            TestRule("test:reader", "READ", setOf("CANDIDATE")) { view ->
                assertTrue(view.annotations("CANDIDATE").isEmpty())
                assertTrue(view.intentsAt(node).isEmpty())
                listOf(emit("READ", node))
            }

        val output = RuleEngine().analyze(facts, listOf(proposed, reader))

        assertEquals(1, reader.runs)
        assertEquals(setOf("READ"), output.annotations.flatMap { it.intents }.toSet())
    }

    @Test
    fun downstreamRuleRunsOnceAfterSelfReadingUpstreamSettles() {
        val facts = singleNodeFacts("fun f() {}")
        val chain =
            TestRule("test:chain", "CHAIN", setOf("CHAIN")) { view ->
                val count = (view.annotations("CHAIN").size + 1).coerceAtMost(3)
                (0 until count).map { emitRange("CHAIN", it) }
            }
        val reader =
            TestRule("test:reader", "READ", setOf("CHAIN")) { view ->
                assertEquals(3, view.annotations("CHAIN").size)
                emptyList<Emit>()
            }

        RuleEngine().analyze(facts, listOf(chain, reader))

        assertEquals(4, chain.runs)
        assertEquals(1, reader.runs)
    }
}
