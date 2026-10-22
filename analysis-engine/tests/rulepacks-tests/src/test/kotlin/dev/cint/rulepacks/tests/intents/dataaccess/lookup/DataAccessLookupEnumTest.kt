package dev.cint.rulepacks.tests.intents.dataaccess.lookup

import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.tests.intents.dataaccess.support.candidateTexts
import dev.cint.rulepacks.tests.intents.dataaccess.support.dataAccess
import kotlin.test.Test
import kotlin.test.assertTrue

class DataAccessLookupEnumTest {
    @Test
    fun enumValueAndConstantCallsEmitLookup() {
        val text =
            """
            fun f(code: String) {
                val status = Status.valueOf("ACTIVE")
                val mode = Mode.fromCode(code)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("Status.valueOf") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("Mode.fromCode") })
    }

    @Test
    fun stringValueOfStaysOutOfLookup() {
        val text =
            """
            fun f(value: Int) {
                val rendered = String.valueOf(value)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
        assertTrue(analysis.candidateTexts(LOOKUP_INTENT).isEmpty())
    }

    @Test
    fun enumStyleReceiversWithoutKnownSuffixBecomeCandidates() {
        val text =
            """
            fun f(code: String) {
                Color.valueOf("RED")
                Role.fromCode(code)
                Currency.valueOf(code)
                Priority.fromCode(code)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
        val candidates = analysis.candidateTexts(LOOKUP_INTENT)
        assertTrue(candidates.any { it.startsWith("Color.valueOf") })
        assertTrue(candidates.any { it.startsWith("Role.fromCode") })
        assertTrue(candidates.any { it.startsWith("Currency.valueOf") })
        assertTrue(candidates.any { it.startsWith("Priority.fromCode") })
    }

    @Test
    fun configuredEnumTypesEstablishNonSuffixEnums() {
        val pack =
            DataAccessRulePack().configure(
                mapOf("kotlin" to mapOf("enumTypes" to listOf("Color"))),
            )
        val text =
            """
            fun f(code: String) {
                Color.valueOf("RED")
                Role.fromCode(code)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text, pack)

        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("Color.valueOf") })
        assertTrue(analysis.candidateTexts(LOOKUP_INTENT).any { it.startsWith("Role.fromCode") })
    }
}
