package dev.cint.rulepacks.tests.intents.transformation

import dev.cint.api.Role
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.normalization.NORMALIZATION_INTENT
import dev.cint.rulepacks.intents.normalization.NormalizationRulePack
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
import dev.cint.rulepacks.intents.transformation.DATA_TRANSFORMATION_INTENT
import dev.cint.rulepacks.intents.transformation.TransformationRulePack
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.ValidationRulePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertTrue

class DataTransformationOverlapTest {
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
                    LocalCopyRulePack(),
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
    fun transformationAndLocalMutationOverlapOnMutatingCollectionCalls() {
        val text = "function f(values) { values.sort(); }"

        val analysis =
            analyze(
                "javascript",
                text,
                listOf(BookkeepingRulePack(), DataAccessRulePack(), MutationRulePack(), LocalCopyRulePack(), TransformationRulePack()),
            )
        val call =
            analysis.annotations.single {
                it.role == Role.SOURCE_INTENT && it.text(text) == "values.sort()"
            }

        assertTrue(DATA_TRANSFORMATION_INTENT in call.intents)
        assertTrue(LOCAL_VALUE_MODIFY_INTENT in call.intents)
    }
}
