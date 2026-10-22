package dev.cint.core.runtime.engine.validation

import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Span
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.engine.support.*
import dev.cint.core.runtime.scheduler.RuleEngine
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleEngineEmitValidationTest {
    private val config = IntentConfig("kotlin", emptyList())

    @Test
    fun emptyIntentEmitFails() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = fakeTree(source.text)
        val rule =
            TestRule("test:empty", "EMPTY") {
                listOf(emitWithIntents(emptySet(), 0))
            }

        val error =
            assertFailsWith<IllegalArgumentException> {
                RuleEngine()
                    .analyze(
                        source,
                        tree,
                        fakeSyntax(),
                        NullOracle,
                        config,
                        listOf(TestPack("test", listOf(rule))),
                    ).annotations
            }

        assertTrue(error.message.orEmpty().contains("test:empty"))
        assertTrue(error.message.orEmpty().contains("emitted intents"))
    }

    @Test
    fun foreignIntentEmitFails() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = fakeTree(source.text)
        val rule =
            TestRule("test:foreign", "FOREIGN") {
                listOf(emitWithIntents(setOf("OTHER"), 0))
            }

        val error =
            assertFailsWith<IllegalArgumentException> {
                RuleEngine()
                    .analyze(
                        source,
                        tree,
                        fakeSyntax(),
                        NullOracle,
                        config,
                        listOf(TestPack("test", listOf(rule))),
                    ).annotations
            }

        assertTrue(error.message.orEmpty().contains("test:foreign"))
        assertTrue(error.message.orEmpty().contains("OTHER"))
    }

    @Test
    fun foreignCandidateIntentFails() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = fakeTree(source.text)
        val rule =
            TestRule("test:candidate", "CANDIDATE") {
                listOf(
                    CandidateEmit(
                        intents = setOf("OTHER"),
                        span = Span(0, 0, 0, 1),
                        startByte = 0,
                        endByte = 1,
                        source = "rule:test",
                        confidence = 0.4,
                        reason = CandidateReason.GAP,
                    ),
                )
            }

        val error =
            assertFailsWith<IllegalArgumentException> {
                RuleEngine().analyze(
                    source,
                    tree,
                    fakeSyntax(),
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(rule))),
                )
            }

        assertTrue(error.message.orEmpty().contains("test:candidate"))
        assertTrue(error.message.orEmpty().contains("OTHER"))
    }
}
