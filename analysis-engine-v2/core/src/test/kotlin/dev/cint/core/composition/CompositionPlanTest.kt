package dev.cint.core.composition

import dev.cint.contracts.ports.CompositionSpec
import dev.cint.contracts.ports.DerivativeUseSpec
import dev.cint.contracts.ports.ScaffoldSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CompositionPlanTest {
    @Test
    fun emptySpecsUseCoreDefaults() {
        assertEquals(CompositionConfig(), CompositionPlan.config(emptyList()))
    }

    @Test
    fun mergesSpecsAndPreservesPureAnnotations() {
        val config = CompositionPlan.config(
            listOf(
                CompositionSpec(
                    derivativeUse = DerivativeUseSpec(enabled = true, minConfidence = 0.6),
                    scaffolds = ScaffoldSpec(enabled = true),
                    pureAnnotations = listOf("Pure"),
                ),
                CompositionSpec(
                    derivativeUse = DerivativeUseSpec(enabled = false, minConfidence = 0.4),
                    scaffolds = ScaffoldSpec(enabled = true),
                    pureAnnotations = listOf("Pure", "Immutable"),
                ),
            ),
        )

        assertFalse(config.derivativeUseEnabled)
        assertEquals(0.6, config.derivativeMinConfidence)
        assertEquals(listOf("Immutable", "Pure"), config.pureAnnotations)
    }
}
