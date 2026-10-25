package dev.cint.v2.testing

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.TypeRef
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.util.ResultFactory
import dev.cint.v2.testing.fakes.FakeClock
import dev.cint.v2.testing.fakes.FakeIndex
import dev.cint.v2.testing.fakes.FakeIntentPipeline
import dev.cint.v2.testing.fakes.FakeKnowledgeStore
import dev.cint.v2.testing.fakes.FakeLanguageSupport
import dev.cint.v2.testing.fakes.FakeRecorder
import dev.cint.v2.testing.fakes.FakeRuntime
import dev.cint.v2.testing.fakes.FakeSemanticProvider
import dev.cint.v2.testing.fakes.FakeSyntaxEngine
import dev.cint.v2.testing.fakes.fakeDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FakeEndToEndTest {
    private val sourceText =
        """
        fun findUser() {
            call repository.findUser
        }

        fun saveUser() {
            call repository.save
        }
        """.trimIndent()

    @Test
    fun fakePathExercisesMergeCacheAndSemanticsAndIsDeterministic() {
        val harness = buildHarness()
        val request = AnalysisRequest(targetPaths = listOf("one.fake", "two.fake"), language = "fake")

        val first = harness.runtime.analyze(request).getOrThrow()
        val hitsBefore = harness.recorder.count("index.cacheHit")
        val second = harness.runtime.analyze(request).getOrThrow()

        assertEquals(first, second)
        assertEquals(2, harness.recorder.count("index.cacheHit") - hitsBefore)
        assertEquals(first, buildHarness().runtime.analyze(request).getOrThrow())

        assertEquals(4, first.annotations.size)
        assertTrue(first.annotations.all { it.confidence == Confidence.LOW })
        assertEquals(2, first.annotations.count { Intents.LOOKUP in it.intents })
        assertEquals(2, first.annotations.count { Intents.DATA_MODIFY in it.intents })

        val tree = assertNotNull(first.facts.syntaxTree)
        val functionIds = first.facts.functions.mapNotNull { it.nodeId }
        assertEquals(4, functionIds.size)
        assertEquals(4, functionIds.toSet().size)
        assertTrue(functionIds.all { it in tree.nodes })
        assertTrue(functionIds.all { it.substringBefore('|') in setOf("one.fake", "two.fake") })
        assertTrue(first.facts.calls.all { it.nodeId in tree.nodes })
        assertEquals(4, tree.nodes.values.count { it.kind == "function_declaration" })

        assertTrue(
            harness.recorder.names().containsAll(
                listOf("syntax.parse", "syntax.facts", "semantic.request", "pipeline.analyze", "index.indexed"),
            ),
        )
        assertTrue(first.errors.isEmpty())
    }

    private fun buildHarness(): Harness {
        val recorder = FakeRecorder()
        val clock = FakeClock()
        val syntax = FakeSyntaxEngine("fake", recorder)
        val provider =
            FakeSemanticProvider(
                "fake",
                recorder,
                { ResultFactory.success(SemanticResult.TypeAt(TypeRef("fake.Type", "semantic-provider", Confidence.LOW))) },
                clock,
            )
        val support = FakeLanguageSupport(fakeDescriptor(), syntax, provider, recorder)
        val index =
            FakeIndex(
                mutableMapOf("one.fake" to sourceText, "two.fake" to sourceText),
                clock,
                recorder = recorder,
            )
        val knowledge = FakeKnowledgeStore(clock = clock, recorder = recorder)
        knowledge.setFunctionAnnotation(annotation("findUser", Intents.LOOKUP, clock))
        knowledge.setFunctionAnnotation(annotation("saveUser", Intents.DATA_MODIFY, clock))
        val runtime =
            FakeRuntime(
                supports = mapOf("fake" to support),
                sources =
                    mutableMapOf(
                        "one.fake" to SourceInput.InMemory(sourceText, "fake", 1, "one.fake"),
                        "two.fake" to SourceInput.InMemory(sourceText, "fake", 1, "two.fake"),
                    ),
                index = index,
                pipeline = FakeIntentPipeline(recorder),
                knowledgeStore = knowledge,
                clock = clock,
                recorder = recorder,
            )
        return Harness(runtime, recorder)
    }

    private fun annotation(
        name: String,
        intent: String,
        clock: FakeClock,
    ): FunctionAnnotation =
        FunctionAnnotation(
            selector = FunctionSelector(qualifiedName = name),
            intents = setOf(intent),
            scope = AnnotationScope.GLOBAL,
            provenance = Provenance.USER,
            confidence = Confidence.HIGH,
            status = Status.ACCEPTED,
            createdAt = clock.now,
            updatedAt = clock.now,
        )

    private data class Harness(
        val runtime: FakeRuntime,
        val recorder: FakeRecorder,
    )
}
