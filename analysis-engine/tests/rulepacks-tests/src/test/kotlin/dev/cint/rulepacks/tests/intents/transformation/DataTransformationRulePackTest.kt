package dev.cint.rulepacks.tests.intents.transformation

import dev.cint.api.CandidateReason
import dev.cint.api.Role
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.normalization.NORMALIZATION_INTENT
import dev.cint.rulepacks.intents.normalization.NormalizationRulePack
import dev.cint.rulepacks.intents.transformation.DATA_TRANSFORMATION_INTENT
import dev.cint.rulepacks.intents.transformation.DATA_TRANSFORMATION_RULE_ID
import dev.cint.rulepacks.intents.transformation.TransformationRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.ValidationRulePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun analyze(
    language: String,
    text: String,
    packs: List<dev.cint.api.RulePack> = listOf(DataAccessRulePack(), TransformationRulePack()),
): PackAnalysis = analyzePack(language, text, packs)

private class FixedOracle(
    private val fact: TypeFact,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact = fact
}

private fun PackAnalysis.candidateText(
    text: String,
    index: Int = 0,
): String {
    val candidate = candidates(DATA_TRANSFORMATION_INTENT)[index]
    return text.substring(candidate.startByte, candidate.endByte)
}

class DataTransformationRulePackTest {
    @Test
    fun packDeclaresOneOwnedRuleAcrossBundledLanguages() {
        val pack = TransformationRulePack()

        assertEquals("data-transformation", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        val rule = pack.rules().single()
        assertEquals("data-transformation:data-transformation", rule.id)
        assertEquals("DATA_TRANSFORMATION", rule.owns)
        assertEquals(setOf(SEARCH_INTENT), rule.reads)
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
    fun overlapsWithSearchNormalizationValidationAndBookkeepingAreKept() {
        val searchText =
            """
            fun f(repository: Repository) {
                repository.filter { it.isNotBlank() }.map { it.trim() }
            }
            """.trimIndent()
        val dedicatedTransformation =
            TransformationRulePack().configure(
                mapOf("kotlin" to mapOf("qualifiers" to listOf("repository"))),
            )
        val searchAnalysis =
            analyze(
                "kotlin",
                searchText,
                listOf(DataAccessRulePack(), NormalizationRulePack(), dedicatedTransformation),
            )
        val filter =
            searchAnalysis.annotations.first {
                it.role == Role.SOURCE_INTENT &&
                    SEARCH_INTENT in it.intents &&
                    DATA_TRANSFORMATION_INTENT in it.intents &&
                    ".map" !in it.text(searchText)
            }
        assertTrue(SEARCH_INTENT in filter.intents)
        assertTrue(DATA_TRANSFORMATION_INTENT in filter.intents)

        val normalizationText = "fun f(values: String) = values.trim().map { it.length }"
        val normalizationAnalysis =
            analyze(
                "kotlin",
                normalizationText,
                listOf(DataAccessRulePack(), NormalizationRulePack(), TransformationRulePack()),
            )
        assertTrue(
            normalizationAnalysis.annotations.any {
                it.role == Role.SOURCE_INTENT &&
                    NORMALIZATION_INTENT in it.intents &&
                    it.text(normalizationText) == "values.trim()"
            },
        )
        assertTrue(
            normalizationAnalysis.annotations.any {
                it.role == Role.SOURCE_INTENT &&
                    DATA_TRANSFORMATION_INTENT in it.intents &&
                    ".map" in it.text(normalizationText)
            },
        )

        val validationText =
            """
            fun f(values: List<String>): Boolean {
                if (values.map { it.trim() }.isEmpty()) return false
                return true
            }
            """.trimIndent()
        val validationAnalysis =
            analyze(
                "kotlin",
                validationText,
                listOf(
                    BookkeepingRulePack(),
                    DataAccessRulePack(),
                    MutationRulePack(),
                    ValidationRulePack(),
                    NormalizationRulePack(),
                    TransformationRulePack(),
                ),
            )
        assertTrue(validationAnalysis.source(VALIDATION_INTENT).any { "values.map" in it.text(validationText) })
        assertTrue(validationAnalysis.source(DATA_TRANSFORMATION_INTENT).any { ".map" in it.text(validationText) })
        assertTrue(validationAnalysis.source(NORMALIZATION_INTENT).any { it.text(validationText) == "it.trim()" })

        val bookkeepingText =
            """
            class C {
                @Bookkeeping var total = 0
                fun f(values: List<Int>) {
                    total = values.map { it + 1 }.size
                }
            }
            """.trimIndent()
        val bookkeepingAnalysis =
            analyze(
                "kotlin",
                bookkeepingText,
                listOf(BookkeepingRulePack(), DataAccessRulePack(), TransformationRulePack()),
            )
        assertTrue(bookkeepingAnalysis.source(BOOKKEEPING_INTENT).any { "total = values.map" in it.text(bookkeepingText) })
        assertTrue(bookkeepingAnalysis.source(DATA_TRANSFORMATION_INTENT).any { "values.map" in it.text(bookkeepingText) })
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
    fun transformationAndLocalMutationOverlapOnMutatingCollectionCalls() {
        val text = "function f(values) { values.sort(); }"

        val analysis =
            analyze(
                "javascript",
                text,
                listOf(BookkeepingRulePack(), DataAccessRulePack(), MutationRulePack(), TransformationRulePack()),
            )
        val call =
            analysis.annotations.single {
                it.role == Role.SOURCE_INTENT && it.text(text) == "values.sort()"
            }

        assertTrue(DATA_TRANSFORMATION_INTENT in call.intents)
        assertTrue(LOCAL_VALUE_MODIFY_INTENT in call.intents)
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
