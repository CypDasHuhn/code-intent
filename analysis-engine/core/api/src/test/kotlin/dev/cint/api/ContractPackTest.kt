package dev.cint.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractPackTest {
    private val bookkeeping = TestRule("demo:bookkeeping", "BOOKKEEPING")
    private val validation = TestRule("demo:validation", "VALIDATION", setOf("BOOKKEEPING"))
    private val pack = TestPack("demo", entries = listOf(bookkeeping, validation))

    @Test
    fun rulePackApiVersionMustBeDeclaredByThePack() {
        assertFalse(RulePack::class.java.getMethod("getApiVersion").isDefault)
        assertEquals(CINT_API_VERSION, pack.apiVersion)
    }

    @Test
    fun optionalReadsDefaultsToEmptyAndRemainsABinaryCompatibleAccessor() {
        val rule = TestRule("demo:optional", "OPTIONAL")

        assertTrue(rule.optionalReads.isEmpty())
        assertTrue(Rule::class.java.getMethod("getOptionalReads").isDefault)
        assertEquals(5, CINT_API_VERSION)
    }

    @Test
    fun configurableRulePackIsAdditiveAndVersionPinned() {
        assertEquals(5, CINT_API_VERSION)

        val original = TestConfigurablePack()
        val configured = original.configure(mapOf("extra" to true))

        assertEquals(original.id, configured.id)
        assertEquals(CINT_API_VERSION, configured.apiVersion)
        assertEquals(1, original.rules().size)
        assertEquals(2, configured.rules().size)
    }

    @Test
    fun rulePackApiMetadataIsReadableFromTheClass() {
        assertEquals(CINT_API_VERSION + 1, TestAnnotatedPack::class.java.getAnnotation(RulePackApi::class.java)?.version)
    }

    @Test
    fun ruleIdsAreNamespacedByPack() {
        assertEquals("demo:validation", validation.id)
        assertTrue(pack.rules().all { it.id.startsWith("${pack.id}:") })
        assertEquals(1, pack.rules().count { it.owns == "VALIDATION" })
    }

    @Test
    fun untaggedIsReservedAndUnowned() {
        assertEquals("UNTAGGED", UNTAGGED_INTENT)
        assertTrue(pack.rules().none { it.owns == UNTAGGED_INTENT })
        assertTrue(pack.rules().none { UNTAGGED_INTENT in it.reads })
    }

    @Test
    fun relationResolvesReadIntentToOwningRule() {
        val relation = resolve(listOf(pack)).single()

        assertTrue(relation.intent in relation.reader.reads)
        assertEquals(relation.intent, relation.owner.owns)
        assertEquals(validation, relation.reader)
        assertEquals(bookkeeping, relation.owner)
        assertEquals(1, pack.rules().count { it.owns == relation.intent })
        assertEquals(listOf(Relation(validation, "BOOKKEEPING", bookkeeping)), resolve(listOf(pack)))
    }

    @Test
    fun relationRejectsUnknownReadIntent() {
        val broken = TestRule("demo:broken", "BROKEN", setOf("MISSING"))
        val malformed = TestPack("demo", entries = listOf(bookkeeping, broken))

        val error = assertFailsWith<IllegalArgumentException> { resolve(listOf(malformed)) }

        assertTrue(error.message.orEmpty().contains("MISSING"))
    }
}
