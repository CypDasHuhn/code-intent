package dev.cint.rulepacks.tests.intents.logic

import dev.cint.api.CandidateReason
import dev.cint.rulepacks.intents.logic.BOOL_COMBINATORICS_INTENT
import dev.cint.rulepacks.intents.logic.BOOL_COMBINATORICS_RULE_ID
import dev.cint.rulepacks.intents.logic.BoolCombinatoricsRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun analyze(
    language: String,
    text: String,
    pack: dev.cint.api.RulePack = BoolCombinatoricsRulePack(),
): PackAnalysis = analyzePack(language, text, listOf(pack))

private fun PackAnalysis.candidateText(
    text: String,
    index: Int = 0,
): String {
    val candidate = candidates(BOOL_COMBINATORICS_INTENT)[index]
    return text.substring(candidate.startByte, candidate.endByte)
}

class BoolCombinatoricsRulePackTest {
    @Test
    fun packDeclaresOneOwnedRuleAcrossBundledLanguages() {
        val pack = BoolCombinatoricsRulePack()

        assertEquals("bool-combinatorics", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        val rule = pack.rules().single()
        assertEquals("bool-combinatorics:bool-combinatorics", rule.id)
        assertEquals("BOOL_COMBINATORICS", rule.owns)
        assertTrue(rule.reads.isEmpty())
    }

    @Test
    fun complexConditionsEstablishWithoutPaintingSimpleComparisons() {
        val text =
            """
            fun f(a: Boolean, b: Boolean, c: Boolean, node: String?, uses: List<String>): Boolean {
                if (a && b || c) return false
                if (node != null) return false
                if (a == b) return false
                if (a && b) return false
                if (uses.isEmpty()) return false
                return a
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)
        val established = analysis.texts(BOOL_COMBINATORICS_INTENT)

        assertEquals(listOf("a && b || c"), established)
        assertTrue(analysis.candidates(BOOL_COMBINATORICS_INTENT).any { "a && b" in analysis.candidateText(text) })
        assertTrue(analysis.texts(BOOL_COMBINATORICS_INTENT).none { "node != null" in it })
        assertTrue(analysis.texts(BOOL_COMBINATORICS_INTENT).none { "a == b" in it })
        assertTrue(analysis.texts(BOOL_COMBINATORICS_INTENT).none { "uses.isEmpty" in it })
    }

    @Test
    fun initializersReturnsAndQuantifiersAreSources() {
        val text =
            """
            fun f(values: List<String>, a: Boolean, b: Boolean): Boolean {
                val selected = values.all { isConstant(it) || isIdentifier(it) }
                val pure = isConstant(selected) || isIdentifier(selected) || isAnnotated(selected)
                return a && (b || isConstant(selected))
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)
        val established = analysis.texts(BOOL_COMBINATORICS_INTENT)

        assertTrue(established.any { "values.all" in it && "isConstant" in it })
        assertTrue(established.any { "isConstant(selected) ||" in it })
        assertTrue(established.any { "a && (b || isConstant(selected))" in it })
        assertTrue(analysis.candidates(BOOL_COMBINATORICS_INTENT).isEmpty())
    }

    @Test
    fun severalDistinctClassifiedPredicatesEstablish() {
        val text = "fun f(value: String): Boolean { return value.isEmpty() || value.isBlank() }"

        val analysis = analyze("kotlin", text)

        assertEquals(listOf("value.isEmpty() || value.isBlank()"), analysis.texts(BOOL_COMBINATORICS_INTENT))
        assertTrue(analysis.candidates(BOOL_COMBINATORICS_INTENT).isEmpty())
    }

    @Test
    fun simpleBooleanOperatorsStayLowConfidenceCandidates() {
        val text = "fun f(a: Boolean, b: Boolean): Boolean { return a && b }"

        val analysis = analyze("kotlin", text)
        val candidate = analysis.candidates(BOOL_COMBINATORICS_INTENT).single()

        assertTrue(analysis.source(BOOL_COMBINATORICS_INTENT).isEmpty())
        assertEquals(CandidateReason.LOW_CONFIDENCE, candidate.reason)
        assertEquals("rule:$BOOL_COMBINATORICS_RULE_ID", candidate.source)
        assertEquals(0.4, candidate.confidence)
        assertTrue(candidate.aiEligible)
        assertEquals("a && b", analysis.candidateText(text))
    }

    @Test
    fun configuredThresholdsAndHelpersDriveDetection() {
        val permissive =
            BoolCombinatoricsRulePack().configure(
                mapOf("kotlin" to mapOf("minimumOperators" to 1)),
            )
        val strict =
            BoolCombinatoricsRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "quantifierCalls" to emptyList<String>(),
                            "predicateCalls" to emptyList<String>(),
                            "predicatePrefixes" to emptyList<String>(),
                        ),
                ),
            )
        val permissiveText = "fun f(a: Boolean, b: Boolean): Boolean { return a && b }"
        assertEquals(listOf("a && b"), analyze("kotlin", permissiveText, permissive).texts(BOOL_COMBINATORICS_INTENT))

        val strictText = "fun f(values: List<String>): Boolean { return values.all { it.isNotEmpty() } }"
        val strictAnalysis = analyze("kotlin", strictText, strict)
        assertTrue(strictAnalysis.source(BOOL_COMBINATORICS_INTENT).none { "values.all" in it.text(strictText) })
    }

    @Test
    fun configuredHelpersEstablishAcrossLanguages() {
        val cases =
            listOf(
                "javascript" to "function f(a, b) { if (a && b || a) return false; }",
                "typescript" to "function f(a: boolean, b: boolean): boolean { if (a && b || a) return false; return true; }",
                "csharp" to "class C { bool F(bool a, bool b) { if (a && b || a) return false; return true; } }",
            )

        for ((language, text) in cases) {
            val texts = analyze(language, text).texts(BOOL_COMBINATORICS_INTENT)
            assertTrue(texts.any { "a && b || a" in it }, "$language: $texts")
        }

        val kotlinQuantifier = analyze("kotlin", "fun f(values: List<String>): Boolean { return values.none { it.isEmpty() } }")
        assertTrue(kotlinQuantifier.texts(BOOL_COMBINATORICS_INTENT).single().contains("values.none"))

        val javascriptQuantifier = analyze("javascript", "function f(values) { if (values.every(v => v)) return false; return true; }")
        assertTrue(javascriptQuantifier.texts(BOOL_COMBINATORICS_INTENT).single().contains("values.every"))

        val csharpQuantifier =
            analyze(
                "csharp",
                "class C { bool F(System.Collections.Generic.List<string> v) { if (v.All(x => x != null)) return false; return true; } }",
            )
        assertTrue(csharpQuantifier.texts(BOOL_COMBINATORICS_INTENT).single().contains("v.All"))
    }

    @Test
    fun predicatePrefixesRequireAWordBoundary() {
        val text =
            """
            fun f(): Boolean {
                if (issue() && hashCode() > 0) return false
                return true
            }
            fun issue(): Boolean = true
            fun hashCode(): Int = 1
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(BOOL_COMBINATORICS_INTENT).isEmpty())
        assertEquals(1, analysis.candidates(BOOL_COMBINATORICS_INTENT).size)
        assertTrue("issue" in analysis.candidateText(text))
    }

    @Test
    fun predicatePrefixHelpersStillEstablish() {
        val text = "fun f(value: String): Boolean { return value.isValid() || value.hasValue() }"

        val analysis = analyze("kotlin", text)

        assertEquals(listOf("value.isValid() || value.hasValue()"), analysis.texts(BOOL_COMBINATORICS_INTENT))
        assertTrue(analysis.candidates(BOOL_COMBINATORICS_INTENT).isEmpty())
    }

    @Test
    fun negationOnlyGuardsDoNotEstablishOrBecomeCandidates() {
        val text = "fun f(flag: Boolean): Boolean { if (!flag) return false; return true }"

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(BOOL_COMBINATORICS_INTENT).isEmpty())
        assertTrue(analysis.candidates(BOOL_COMBINATORICS_INTENT).isEmpty())
    }

    @Test
    fun nestedBooleanInsideACallIsNotTheRootExpression() {
        val text = "fun f(values: List<String>) = values.filter { it.isNotEmpty() && it.isNotBlank() }"

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(BOOL_COMBINATORICS_INTENT).isEmpty())
        assertTrue(analysis.candidates(BOOL_COMBINATORICS_INTENT).isEmpty())
    }
}
