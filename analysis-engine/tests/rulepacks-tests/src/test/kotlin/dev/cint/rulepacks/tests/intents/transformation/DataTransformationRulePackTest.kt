package dev.cint.rulepacks.tests.intents.transformation

import dev.cint.api.CandidateReason
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.runtime.scheduler.RuleScheduler
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.transformation.DATA_TRANSFORMATION_INTENT
import dev.cint.rulepacks.intents.transformation.DATA_TRANSFORMATION_RULE_ID
import dev.cint.rulepacks.intents.transformation.TransformationRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataTransformationRulePackTest {
    @Test
    fun packDeclaresOneOwnedRuleAcrossBundledLanguages() {
        val pack = TransformationRulePack()

        assertEquals("data-transformation", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        val rule = pack.rules().single()
        assertEquals("data-transformation:data-transformation", rule.id)
        assertEquals("DATA_TRANSFORMATION", rule.owns)
        assertEquals(setOf(SEARCH_INTENT, DATA_TRANSFORMATION_INTENT), rule.reads)
    }

    @Test
    fun schedulesAfterSearchAndCanConsumeItsOwnEmissions() {
        val plan = RuleScheduler().plan(listOf(DataAccessRulePack(), TransformationRulePack()))
        val transformation = plan.strata.flatten().single { it.id == DATA_TRANSFORMATION_RULE_ID }
        val searchIndex = plan.strata.indexOfFirst { stratum -> stratum.any { it.owns == SEARCH_INTENT } }
        val transformationIndex = plan.strata.indexOfFirst { stratum -> stratum.any { it.id == DATA_TRANSFORMATION_RULE_ID } }

        assertEquals(setOf(SEARCH_INTENT, DATA_TRANSFORMATION_INTENT), transformation.reads)
        assertTrue(searchIndex < transformationIndex)
    }

    @Test
    fun chainedTransformationsEstablishFromPriorTransformationEmissions() {
        val text = "fun f(values: List<String>) = values.map { it.trim() }.filter { it.isNotEmpty() }"
        val input = parsePackInput("kotlin", text)
        val valuesOffset =
            input.syntax
                .calls(input.tree)
                .first { it.callee == "map" }
                .receiver!!
                .startByte
        val innerOnlyOracle =
            object : TypeOracle {
                override fun typeAt(
                    file: dev.cint.core.facts.model.SourceFile,
                    byteOffset: Int,
                ): TypeFact? =
                    if (byteOffset == valuesOffset) {
                        TypeFact("kotlin.collections.List", "lsp", 0.9)
                    } else {
                        null
                    }
            }
        val pack =
            TransformationRulePack().configure(
                mapOf("kotlin" to mapOf("qualifiers" to emptyList<String>())),
            )

        val analysis = analyzePack(input, listOf(DataAccessRulePack(), pack), innerOnlyOracle)
        val outer = "values.map { it.trim() }.filter { it.isNotEmpty() }"
        assertTrue(analysis.source(DATA_TRANSFORMATION_INTENT).any { it.text(text) == outer })
        assertTrue(analysis.candidates(DATA_TRANSFORMATION_INTENT).isEmpty())
    }

    @Test
    fun configuredCollectionTransformationsEstablishPerLanguage() {
        val cases =
            listOf(
                Triple(
                    "kotlin",
                    """
                    fun f(values: List<String>) {
                        val mapped = values.map { it.trim() }
                        val filtered = values.filter { it.isNotEmpty() }
                        val flat = values.flatMap { it.split(",") }
                        val folded = values.fold("") { acc, value -> acc + value }
                        val reduced = values.reduce { acc, value -> acc + value }
                        val distinct = values.distinct()
                        val sorted = values.sorted()
                        val grouped = values.groupBy { it.first() }
                        val associated = values.associate { it to it.length }
                        val partitioned = values.partition { it.isNotEmpty() }
                        val taken = values.take(2)
                        val dropped = values.drop(2)
                        val all = values.all { it.isNotEmpty() }
                        val any = values.any { it.isEmpty() }
                        val none = values.none { it.isEmpty() }
                    }
                    """.trimIndent(),
                    listOf(
                        ".map",
                        ".filter",
                        ".flatMap",
                        ".fold",
                        ".reduce",
                        ".distinct",
                        ".sorted",
                        ".groupBy",
                        ".associate",
                        ".partition",
                        ".take",
                        ".drop",
                        ".all",
                        ".any",
                        ".none",
                    ),
                ),
                Triple(
                    "javascript",
                    "function f(values) { values.map(v => v).filter(v => v).reduce((a, b) => a, ''); }",
                    listOf(".map", ".filter", ".reduce"),
                ),
                Triple(
                    "typescript",
                    "function f(values: string[]): void { values.map(v => v).filter(v => v).sort(); }",
                    listOf(".map", ".filter", ".sort"),
                ),
                Triple(
                    "csharp",
                    "class C { void F(string[] values) { values.Where(v => v != null).Select(v => v).OrderBy(v => v).Distinct().ToList(); } }",
                    listOf(".Where", ".Select", ".OrderBy", ".Distinct", ".ToList"),
                ),
            )

        for ((language, text, expected) in cases) {
            val texts = analyze(language, text).texts(DATA_TRANSFORMATION_INTENT)
            for (fragment in expected) {
                assertTrue(texts.any { fragment in it }, "$language missing '$fragment' in $texts")
            }
        }
    }

    @Test
    fun ambiguousHelpersStayCandidates() {
        val text = "fun f(values: List<String>) = values.transform { it.length }"

        val analysis = analyze("kotlin", text)
        val candidate = analysis.candidates(DATA_TRANSFORMATION_INTENT).single()

        assertTrue(analysis.source(DATA_TRANSFORMATION_INTENT).isEmpty())
        assertEquals(CandidateReason.LOW_CONFIDENCE, candidate.reason)
        assertEquals("rule:$DATA_TRANSFORMATION_RULE_ID", candidate.source)
        assertEquals(0.4, candidate.confidence)
        assertTrue(candidate.aiEligible)
        assertEquals("values.transform { it.length }", analysis.candidateText(text))
    }

    @Test
    fun configuredPackUsesOnlyTheConfiguredCalls() {
        val pack =
            TransformationRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "calls" to listOf("shape"),
                            "candidateCalls" to emptyList<String>(),
                            "candidatePrefixes" to emptyList<String>(),
                        ),
                ),
            )
        val text =
            """
            fun f(values: List<String>) {
                values.shape { it.length }
                values.map { it.length }
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, listOf(DataAccessRulePack(), pack))

        assertEquals(listOf("values.shape { it.length }"), analysis.texts(DATA_TRANSFORMATION_INTENT))
        assertTrue(analysis.candidates(DATA_TRANSFORMATION_INTENT).isEmpty())
    }

    @Test
    fun additiveConfigPreservesDefaultsAndAddsValues() {
        val pack = TransformationRulePack().configureAdditive(mapOf("kotlin" to mapOf("calls" to listOf("shape"))))
        val text =
            """
            fun f(values: List<String>) {
                values.shape { it.length }
                values.map { it.length }
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, listOf(DataAccessRulePack(), pack))

        assertEquals(
            setOf("values.shape { it.length }", "values.map { it.length }"),
            analysis.texts(DATA_TRANSFORMATION_INTENT).toSet(),
        )
    }

    @Test
    fun bareConfiguredCalleesStayCandidates() {
        val text = "fun f(values: List<String>) { map(values) { it } }"

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(DATA_TRANSFORMATION_INTENT).isEmpty())
        assertEquals(1, analysis.candidates(DATA_TRANSFORMATION_INTENT).size)
        assertTrue("map(values)" in analysis.candidateText(text))
    }

    @Test
    fun knownNonCollectionReceiversStayCandidatesUnlessQualified() {
        val text = "fun f(entity: Entity) = entity.map { it.id }"
        val input = parsePackInput("kotlin", text)
        val oracle = FixedOracle(TypeFact("com.example.Entity", "lsp", 0.9))

        val unqualified = analyzePack(input, listOf(DataAccessRulePack(), TransformationRulePack()), oracle)
        assertTrue(unqualified.source(DATA_TRANSFORMATION_INTENT).isEmpty())
        assertTrue("entity.map" in unqualified.candidateText(text))

        val qualifiedPack =
            TransformationRulePack().configure(
                mapOf("kotlin" to mapOf("qualifiers" to listOf("entity"))),
            )
        val qualified = analyzePack(input, listOf(DataAccessRulePack(), qualifiedPack), oracle)
        assertEquals(listOf("entity.map { it.id }"), qualified.texts(DATA_TRANSFORMATION_INTENT))
        assertTrue(qualified.candidates(DATA_TRANSFORMATION_INTENT).isEmpty())
    }

    @Test
    fun unclassifiedCallReceiversStayCandidates() {
        val text = "fun f(service: Service) = service.loadEntity().map { it.id }"

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(DATA_TRANSFORMATION_INTENT).isEmpty())
        assertEquals(1, analysis.candidates(DATA_TRANSFORMATION_INTENT).size)
        assertTrue("service.loadEntity().map" in analysis.candidateText(text))
    }

    @Test
    fun searchClassifiedCallReceiversEstablish() {
        val text = "fun f(repository: Repository) = repository.findAll().map { it.name }"

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(SEARCH_INTENT).any { it.text(text) == "repository.findAll()" })
        assertTrue(analysis.source(DATA_TRANSFORMATION_INTENT).any { ".map" in it.text(text) })
        assertTrue(analysis.candidates(DATA_TRANSFORMATION_INTENT).isEmpty())
    }

    @Test
    fun unknownReceiverTypesWithoutQualifiersStayCandidates() {
        val text = "fun f(entity: Entity) = entity.map { it.id }"

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(DATA_TRANSFORMATION_INTENT).isEmpty())
        assertEquals(1, analysis.candidates(DATA_TRANSFORMATION_INTENT).size)
        assertTrue("entity.map" in analysis.candidateText(text))
    }

    @Test
    fun unrelatedCallsRemainUntagged() {
        val text =
            """
            fun f(values: List<String>) {
                values.forEach { consume(it) }
                values.size
                values.isEmpty()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(DATA_TRANSFORMATION_INTENT).isEmpty())
        assertTrue(analysis.candidates(DATA_TRANSFORMATION_INTENT).isEmpty())
    }
}
