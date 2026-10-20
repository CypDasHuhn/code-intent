package dev.cint.rulepacks

import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack
import dev.cint.rulepacks.support.BundledRulePackSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledRulePackSelectionTest {
    @Test
    fun constructsOneErrorHandlingPackForEachLanguage() {
        for (language in ErrorHandlingRulePack.languages) {
            assertEquals(
                listOf("error-handling-$language"),
                BundledRulePackSelection.constructed(language).map { it.id },
            )
        }
    }

    @Test
    fun constructsNothingForUnsupportedLanguages() {
        assertTrue(BundledRulePackSelection.constructed("vue").isEmpty())
    }
}
