package dev.cint.rulepacks.tests.intents.dataaccess.lookup

import dev.cint.core.facts.oracle.MapOracle
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.tests.intents.dataaccess.support.candidateTexts
import dev.cint.rulepacks.tests.intents.dataaccess.support.typedDataAccess
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
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

    @Test
    fun chainedCallReceiversKeepTypedSearchLookupAndEnumLookup() {
        val text =
            """
            fun f(id: String) {
                supplier().findAll()
                source.load().getValue(id)
                getStatusType().valueOf(id)
            }
            """.trimIndent()
        val input = parsePackInput("kotlin", text)
        val facts =
            input.syntax
                .calls(input.tree)
                .mapNotNull { call ->
                    val receiver = call.receiver ?: return@mapNotNull null
                    val type =
                        when {
                            "supplier" in receiver.text -> "org.acme.Repository"
                            "source" in receiver.text -> "kotlin.collections.Map"
                            "getStatusType" in receiver.text -> "com.acme.Status"
                            else -> return@mapNotNull null
                        }
                    input.syntax.receiverOffset(receiver) to TypeFact(type)
                }.toMap()
        val analysis = analyzePack(input, listOf(DataAccessRulePack()), MapOracle(facts))

        assertTrue(analysis.texts(SEARCH_INTENT).any { it.startsWith("supplier().findAll") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("source.load().getValue") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("getStatusType().valueOf") })
    }

    @Test
    fun thisSuperParenthesizedAndCastReceiversKeepTypedDetection() {
        val text =
            """
            class C : Base() {
                fun f(id: String, repository: Repository, entries: Map<String, String>) {
                    val a = this.findAll()
                    val b = super.findAll()
                    val c = (repository).findAll()
                    val d = (repository as Repository).findAll()
                    val e = this.getValue(id)
                    val f = super.getValue(id)
                    val g = (entries).getValue(id)
                    val h = (entries as Map).getValue(id)
                }
            }
            """.trimIndent()
        val input = parsePackInput("kotlin", text)
        val facts =
            input.syntax
                .calls(input.tree)
                .mapNotNull { call ->
                    val receiver = call.receiver ?: return@mapNotNull null
                    val type =
                        when (call.callee) {
                            "findAll" -> "org.acme.Repository"
                            "getValue" -> "kotlin.collections.Map"
                            else -> return@mapNotNull null
                        }
                    input.syntax.receiverOffset(receiver) to TypeFact(type)
                }.toMap()
        val analysis = analyzePack(input, listOf(DataAccessRulePack()), MapOracle(facts))

        val search = analysis.texts(SEARCH_INTENT)
        assertTrue(search.any { it.contains("this.findAll") })
        assertTrue(search.any { it.contains("super.findAll") })
        assertTrue(search.any { it.contains("(repository).findAll") })
        assertTrue(search.any { it.contains("(repository as Repository).findAll") })
        val lookup = analysis.texts(LOOKUP_INTENT)
        assertTrue(lookup.any { it.contains("this.getValue") })
        assertTrue(lookup.any { it.contains("super.getValue") })
        assertTrue(lookup.any { it.contains("(entries).getValue") })
        assertTrue(lookup.any { it.contains("(entries as Map).getValue") })
    }
}
