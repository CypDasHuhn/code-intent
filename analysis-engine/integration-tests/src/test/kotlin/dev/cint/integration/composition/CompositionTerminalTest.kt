package dev.cint.integration.composition

import dev.cint.api.Role
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.config.IntentConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositionTerminalTest {
    @Test
    fun noSourceIntentsStillClassifiesReturnsWithoutScaffoldsOrDerivatives() {
        val text = "fun f(): Int { return 1 }"
        val annotations =
            analyzePipeline(
                text,
                emptyList(),
                config = IntentConfig("kotlin", emptyList()),
            )

        assertEquals(1, annotations.size)
        assertEquals(Role.SCAFFOLD, annotations.single().role)
        assertEquals(setOf(UNTAGGED_INTENT), annotations.single().intents)
    }
}
