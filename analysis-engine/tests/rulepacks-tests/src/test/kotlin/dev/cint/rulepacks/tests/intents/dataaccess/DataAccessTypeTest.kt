package dev.cint.rulepacks.tests.intents.dataaccess

import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import kotlin.test.Test
import kotlin.test.assertTrue

class DataAccessTypeTest {
    @Test
    fun commonJpaAndMapTypesMatchByTokenSuffix() {
        val text =
            """
            fun f(source: Any, entries: Any) {
                source.findAll()
                entries.getValue("x")
            }
            """.trimIndent()

        val analysis =
            typedDataAccess(
                "kotlin",
                text,
                types =
                    mapOf(
                        "source" to "org.springframework.data.jpa.repository.JpaRepository",
                        "entries" to "kotlin.collections.MutableMap",
                    ),
            )

        assertTrue(analysis.texts(SEARCH_INTENT).any { it.startsWith("source.findAll") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("entries.getValue") })
    }

    @Test
    fun fullyQualifiedTypesDoNotMatchOtherPackagesWithSameSimpleName() {
        val pack =
            DataAccessRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "searchTypes" to listOf("com.acme.Dao"),
                            "lookupTypes" to listOf("com.acme.Table"),
                        ),
                ),
            )
        val text =
            """
            fun f(source: Any, entries: Any) {
                source.findAll()
                entries.getValue("x")
            }
            """.trimIndent()

        val other =
            typedDataAccess(
                "kotlin",
                text,
                pack,
                mapOf("source" to "other.Dao", "entries" to "other.Table"),
            )
        other.assertNoSource(SEARCH_INTENT)
        other.assertNoSource(LOOKUP_INTENT)
        assertTrue(other.candidateTexts(SEARCH_INTENT).any { it.startsWith("source.findAll") })
        assertTrue(other.candidateTexts(LOOKUP_INTENT).any { it.startsWith("entries.getValue") })

        val exact =
            typedDataAccess(
                "kotlin",
                text,
                pack,
                mapOf("source" to "com.acme.Dao", "entries" to "com.acme.Table"),
            )
        assertTrue(exact.texts(SEARCH_INTENT).any { it.startsWith("source.findAll") })
        assertTrue(exact.texts(LOOKUP_INTENT).any { it.startsWith("entries.getValue") })
    }

    @Test
    fun simpleConfiguredTypeStillUsesSimpleNameMatching() {
        val pack =
            DataAccessRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "searchTypes" to listOf("Dao"),
                            "lookupTypes" to listOf("Table"),
                        ),
                ),
            )
        val text =
            """
            fun f(source: Any, entries: Any) {
                source.findAll()
                entries.getValue("x")
            }
            """.trimIndent()

        val analysis =
            typedDataAccess(
                "kotlin",
                text,
                pack,
                mapOf("source" to "other.Dao", "entries" to "other.Table"),
            )

        assertTrue(analysis.texts(SEARCH_INTENT).any { it.startsWith("source.findAll") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("entries.getValue") })
    }
}
