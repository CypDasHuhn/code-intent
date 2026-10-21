package dev.cint.rulepacks.tests.intents.structural

import dev.cint.api.CandidateReason
import dev.cint.rulepacks.intents.structural.CHAINING_INTENT
import dev.cint.rulepacks.intents.structural.CHAINING_RULE_ID
import dev.cint.rulepacks.intents.structural.ChainingRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun analyze(
    language: String,
    text: String,
    pack: dev.cint.api.RulePack = ChainingRulePack(),
): PackAnalysis = analyzePack(language, text, listOf(pack))

class ChainingRulePackTest {
    @Test
    fun packDeclaresOneOwnedRuleAcrossBundledLanguages() {
        val pack = ChainingRulePack()

        assertEquals("chaining", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        val rule = pack.rules().single()
        assertEquals(CHAINING_RULE_ID, rule.id)
        assertEquals(CHAINING_INTENT, rule.owns)
        assertTrue(rule.reads.isEmpty())
    }

    @Test
    fun fluentChainsTagTheOuterCallOnly() {
        val text = "fun build(): Service { return Factory.For().Use().Build() }"

        val analysis = analyze("kotlin", text)

        assertEquals(listOf("Factory.For().Use().Build()"), analysis.texts(CHAINING_INTENT))
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun fluentChainsEstablishAcrossLanguages() {
        val cases =
            listOf(
                "javascript" to "function build() { return Factory.For().Use().Build(); }",
                "typescript" to "function build(): Service { return Factory.For().Use().Build(); }",
                "csharp" to "class C { Service Build() { return Factory.For().Use().Build(); } }",
            )

        for ((language, text) in cases) {
            val texts = analyze(language, text).texts(CHAINING_INTENT)
            assertEquals(listOf("Factory.For().Use().Build()"), texts, language)
        }
    }

    @Test
    fun booleanOrChainsAreNotChaining() {
        val text =
            """
            fun valid(a: String, b: String): Boolean {
                validate(a)
                return a.isEmpty() || b.isEmpty()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(CHAINING_INTENT).isEmpty())
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun singleCallsAndPropertyAccessAreNotChains() {
        val text =
            """
            fun f(service: Service) {
                Factory.For()
                service.execute()
                service.dependency.execute()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(CHAINING_INTENT).isEmpty())
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun configuredMinimumControlsLinks() {
        val pack = ChainingRulePack().configure(mapOf("kotlin" to mapOf("minimumLinks" to 3)))
        val text =
            """
            fun a(): Service { return Factory.For().Use() }
            fun b(): Service { return Factory.For().Use().Build() }
            """.trimIndent()

        val analysis = analyze("kotlin", text, pack)

        assertEquals(listOf("Factory.For().Use().Build()"), analysis.texts(CHAINING_INTENT))
    }

    @Test
    fun nonDirectReceiverShapesStayCandidates() {
        val text = "function build(flag) { return (flag ? Factory.For() : Other).Use().Build(); }"

        val analysis = analyze("javascript", text)
        val candidate = analysis.candidates(CHAINING_INTENT).single()

        assertTrue(analysis.source(CHAINING_INTENT).isEmpty())
        assertEquals(CandidateReason.LOW_CONFIDENCE, candidate.reason)
        assertEquals("rule:$CHAINING_RULE_ID", candidate.source)
        assertEquals("(flag ? Factory.For() : Other).Use().Build()", text.substring(candidate.startByte, candidate.endByte))
    }
}
