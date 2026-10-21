package dev.cint.rulepacks.tests.intents.structural

import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.structural.LOCAL_COPY_INTENT
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
import dev.cint.rulepacks.intents.structural.RETURN_VALUE_INTENT
import dev.cint.rulepacks.intents.structural.RETURN_VALUE_RULE_ID
import dev.cint.rulepacks.intents.structural.ReturnValueRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.ValidationRulePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun analyze(
    language: String,
    text: String,
    packs: List<dev.cint.api.RulePack> = listOf(ReturnValueRulePack()),
): PackAnalysis = analyzePack(language, text, packs)

class ReturnValueRulePackTest {
    @Test
    fun packDeclaresOneOwnedRuleAcrossBundledLanguages() {
        val pack = ReturnValueRulePack()

        assertEquals("return-value", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        val rule = pack.rules().single()
        assertEquals(RETURN_VALUE_RULE_ID, rule.id)
        assertEquals(RETURN_VALUE_INTENT, rule.owns)
        assertTrue(rule.reads.isEmpty())
    }

    @Test
    fun returnsWithAnExpressionEstablishWithoutBareReturns() {
        val text =
            """
            fun find(id: String): String? {
                if (id.isEmpty()) return
                return id.trim()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(listOf("id.trim()"), analysis.texts(RETURN_VALUE_INTENT))
        assertTrue(analysis.source(RETURN_VALUE_INTENT).single().rule == RETURN_VALUE_RULE_ID)
    }

    @Test
    fun literalOnlyReturnsAreExcludedUnlessConfigured() {
        val text = "fun f(): String { return \"missing\" }"
        assertTrue(analyze("kotlin", text).source(RETURN_VALUE_INTENT).isEmpty())

        val configured = ReturnValueRulePack().configure(mapOf("kotlin" to mapOf("includeLiterals" to true)))
        val analysis = analyze("kotlin", text, listOf(configured))

        assertEquals(listOf("\"missing\""), analysis.texts(RETURN_VALUE_INTENT))
    }

    @Test
    fun labeledAndScalarLiteralReturnsDoNotEmit() {
        val text =
            """
            fun bare(values: List<Int>) {
                values.forEach { return@forEach }
                if (values.isEmpty()) return
                return
            }
            fun nullValue(): Any? { return null }
            fun flag(): Boolean { return true }
            fun number(): Int { return 42 }
            fun negative(): Int {
                if (System.currentTimeMillis() > 0) return 0
                return -1
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(RETURN_VALUE_INTENT).isEmpty())
        assertTrue(analysis.candidates(RETURN_VALUE_INTENT).isEmpty())
    }

    @Test
    fun returnValueCoexistsWithSearchAndValidation() {
        val text =
            """
            fun find(repository: UserRepository, name: String): List<String> {
                if (name.isEmpty()) {
                    return emptyList()
                }
                return repository.findAll()
            }
            """.trimIndent()

        val analysis =
            analyze(
                "kotlin",
                text,
                listOf(
                    BookkeepingRulePack(),
                    MutationRulePack(),
                    LocalCopyRulePack(),
                    DataAccessRulePack(),
                    ValidationRulePack(),
                    ReturnValueRulePack(),
                ),
            )
        val returned = analysis.source(RETURN_VALUE_INTENT).first { it.text(text) == "repository.findAll()" }
        val searched = analysis.source(SEARCH_INTENT).single { it.text(text) == "repository.findAll()" }

        assertEquals(returned.startByte, searched.startByte)
        assertEquals(returned.endByte, searched.endByte)
        assertEquals(setOf("repository.findAll()"), analysis.texts(SEARCH_INTENT).toSet())
        assertTrue(analysis.texts(VALIDATION_INTENT).any { "name.isEmpty()" in it })
    }

    @Test
    fun returnsWithExpressionsEstablishAcrossLanguages() {
        val cases =
            listOf(
                "javascript" to "function f(value) { return value.trim(); }",
                "typescript" to "function f(value: string): string { return value.trim(); }",
                "csharp" to "class C { string F(string value) { return value.Trim(); } }",
            )

        for ((language, text) in cases) {
            val texts = analyze(language, text).texts(RETURN_VALUE_INTENT)
            assertTrue(texts.any { it.contains("value") && it.contains("trim", ignoreCase = true) }, "$language: $texts")
        }
    }
}
