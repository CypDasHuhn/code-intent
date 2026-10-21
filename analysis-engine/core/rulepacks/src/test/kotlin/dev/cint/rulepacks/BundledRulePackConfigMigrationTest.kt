package dev.cint.rulepacks

import dev.cint.api.RulePackConfigIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val bundledIds = setOf("logging", "error-handling", "bookkeeping", "mutation", "validation")

class BundledRulePackConfigMigrationTest {
    @Test
    fun legacySectionsMigratePerLanguageAndKeepTheOriginalKeysOutOfTheLoader() {
        val config =
            mapOf(
                "error-handling-kotlin" to mapOf("calls" to listOf("fail")),
                "error-handling-javascript" to mapOf("throws" to false),
                "error-handling-typescript" to mapOf("qualifiers" to listOf("Check")),
                "error-handling-csharp" to mapOf("detectThrows" to false),
            )

        val resolved = BundledRulePackConfigMigration.resolve(config, bundledIds)
        val errorHandling = resolved.getValue("error-handling")

        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), errorHandling.keys)
        assertTrue(config.keys.none { it in resolved })
    }

    @Test
    fun invalidLegacyLanguageIsDroppedWithoutDroppingOtherLanguages() {
        val config =
            mapOf(
                "error-handling-kotlin" to mapOf("calls" to listOf("fail")),
                "error-handling-javascript" to mapOf("throws" to "not-a-boolean"),
                "error-handling-typescript" to mapOf("qualifiers" to listOf("Check")),
            )

        val issues = mutableListOf<RulePackConfigIssue>()
        val resolved = BundledRulePackConfigMigration.resolve(config, bundledIds, issues)

        assertEquals(setOf("kotlin", "typescript"), resolved.getValue("error-handling").keys)
        assertFalse("error-handling-javascript" in resolved)
        assertEquals(1, issues.size)
        assertEquals("error-handling", issues.single().packId)
        assertTrue(issues.single().message.contains("error-handling-javascript"))
    }

    @Test
    fun aThirdPartyPackWithALegacyIdKeepsItsOwnConfig() {
        val config = mapOf("error-handling-kotlin" to mapOf("calls" to listOf("third")))
        val discovered = bundledIds + "error-handling-kotlin"

        assertEquals(config, BundledRulePackConfigMigration.resolve(config, discovered))
    }

    @Test
    fun nothingIsMigratedWhenTheBundledPackIsNotRegistered() {
        val config = mapOf("error-handling-kotlin" to mapOf("calls" to listOf("fail")))

        assertEquals(config, BundledRulePackConfigMigration.resolve(config, setOf("logging")))
    }
}
