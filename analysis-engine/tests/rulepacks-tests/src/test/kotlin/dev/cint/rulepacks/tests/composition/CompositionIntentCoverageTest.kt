package dev.cint.rulepacks.tests.composition

import dev.cint.api.Role
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.logic.BOOL_COMBINATORICS_INTENT
import dev.cint.rulepacks.intents.logic.BoolCombinatoricsRulePack
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.normalization.NormalizationRulePack
import dev.cint.rulepacks.intents.structural.localcopy.LocalCopyRulePack
import dev.cint.rulepacks.intents.transformation.DATA_TRANSFORMATION_INTENT
import dev.cint.rulepacks.intents.transformation.TransformationRulePack
import dev.cint.rulepacks.logging.LoggingRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.fixture
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.ValidationRulePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositionIntentCoverageTest {
    @Test
    fun motivatingCompositionFixtureCoversAssignmentsGuardsAndBooleanComposition() {
        val source = fixture("kotlin/Composition.kt")
        val analysis =
            analyzePack(
                "kotlin",
                source,
                listOf(
                    BookkeepingRulePack(),
                    LoggingRulePack(),
                    MutationRulePack(),
                    LocalCopyRulePack(),
                    ValidationRulePack(),
                    NormalizationRulePack(),
                    DataAccessRulePack(),
                    BoolCombinatoricsRulePack(),
                    TransformationRulePack(),
                ),
            )
        val derivatives = analysis.annotations.filter { it.role == Role.DERIVATIVE_USE }
        val validation = analysis.source(VALIDATION_INTENT).map { it.text(source) }
        val booleans = analysis.source(BOOL_COMBINATORICS_INTENT).map { it.text(source) }
        val transformations = analysis.source(DATA_TRANSFORMATION_INTENT).map { it.text(source) }

        val usesAssignment = derivatives.single { it.text(source).startsWith("val uses =") }
        assertTrue(SEARCH_INTENT in usesAssignment.intents)
        assertTrue(DATA_TRANSFORMATION_INTENT in usesAssignment.intents)
        assertEquals(0.9, usesAssignment.confidence)

        val currentAssignment = derivatives.single { it.text(source).startsWith("val current =") }
        assertTrue(VALIDATION_INTENT in currentAssignment.intents)
        assertEquals(0.55, currentAssignment.confidence)

        assertTrue(validation.any { "node ?: continue" in it })
        assertTrue(validation.any { "uses.isEmpty()" in it })
        assertTrue(validation.any { "receiverNode(node) != null" in it })

        assertTrue(transformations.any { ".map" in it })
        assertTrue(transformations.any { ".all" in it })

        assertTrue(booleans.any { "isConstant(selected) ||" in it })
        assertTrue(booleans.any { "uses.all" in it })
        assertTrue(booleans.none { "uses.isEmpty()" in it })
        assertTrue(booleans.none { "receiverNode(node) != null" in it })
    }

    @Test
    fun receiverGuardValidationKeepsLowerConfidenceThanConfiguredPredicates() {
        val source = fixture("kotlin/Composition.kt")
        val analysis =
            analyzePack(
                "kotlin",
                source,
                listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ValidationRulePack()),
            )
        val values = analysis.source(VALIDATION_INTENT)
        val predicate = values.first { it.text(source) == "uses.isEmpty()" }
        val readOnly = values.first { it.text(source) == "receiverNode(node) != null" }

        assertTrue(readOnly.confidence < predicate.confidence)
    }
}
