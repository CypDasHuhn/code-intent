package dev.cint.rulepacks.tests.intents.normalization

import dev.cint.core.facts.oracle.TypeFact
import dev.cint.rulepacks.intents.normalization.NORMALIZATION_INTENT
import dev.cint.rulepacks.intents.normalization.NormalizationRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizationRulePackTest {
    @Test
    fun packDeclaresOneNormalizationRule() {
        val pack = NormalizationRulePack()

        assertEquals("normalization", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        val rule = pack.rules().single()
        assertEquals("normalization:normalization", rule.id)
        assertEquals("NORMALIZATION", rule.owns)
        assertTrue(rule.reads.isEmpty())
    }

    @Test
    fun configuredNormalizersProduceSpansPerLanguage() {
        val cases =
            listOf(
                Triple(
                    "kotlin",
                    """
                    fun f(value: String) {
                        val trimmed = value.trim()
                        val upper = value.uppercase()
                        val date = java.time.LocalDate.parse("2020-01-01")
                        val decoded = Json.decodeFromString(value)
                    }
                    """.trimIndent(),
                    listOf(
                        "value.trim()",
                        "value.uppercase()",
                        "java.time.LocalDate.parse(\"2020-01-01\")",
                        "Json.decodeFromString(value)",
                    ),
                ),
                Triple(
                    "javascript",
                    """
                    function f(value) {
                        const trimmed = value.trim();
                        const lower = value.toLowerCase();
                        const parsed = JSON.parse("{}");
                        const encoded = encodeURIComponent(value);
                    }
                    """.trimIndent(),
                    listOf("value.trim()", "value.toLowerCase()", "JSON.parse(\"{}\")", "encodeURIComponent(value)"),
                ),
                Triple(
                    "typescript",
                    """
                    function f(value: string): void {
                        const trimmed = value.trim();
                        const lower = value.toLowerCase();
                        const parsed = JSON.parse("{}");
                    }
                    """.trimIndent(),
                    listOf("value.trim()", "value.toLowerCase()", "JSON.parse(\"{}\")"),
                ),
                Triple(
                    "csharp",
                    """
                    class C {
                        void F(string value) {
                            var trimmed = value.Trim();
                            var lower = value.ToLowerInvariant();
                            var parsed = int.Parse(value);
                            var full = System.IO.Path.GetFullPath(value);
                        }
                    }
                    """.trimIndent(),
                    listOf("value.Trim()", "value.ToLowerInvariant()", "int.Parse(value)", "System.IO.Path.GetFullPath(value)"),
                ),
            )

        for ((language, text, expected) in cases) {
            val texts = analyze(language, text).texts(NORMALIZATION_INTENT)
            for (fragment in expected) {
                assertTrue(texts.any { fragment in it }, "$language missing '$fragment' in $texts")
            }
        }
    }

    @Test
    fun configuredPackUsesOnlyTheConfiguredCalls() {
        val pack =
            NormalizationRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "calls" to listOf("audit"),
                            "prefixes" to emptyList<String>(),
                            "candidateCalls" to emptyList<String>(),
                            "candidatePrefixes" to emptyList<String>(),
                            "chainCalls" to emptyList<String>(),
                        ),
                ),
            )
        val text =
            """
            fun f(value: String) {
                audit(value)
                value.trim()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, listOf(pack))

        assertEquals(listOf("audit(value)"), analysis.texts(NORMALIZATION_INTENT))
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun additiveConfigPreservesDefaultsAndAddsValues() {
        val pack = NormalizationRulePack().configureAdditive(mapOf("kotlin" to mapOf("calls" to listOf("audit"))))
        val text =
            """
            fun f(value: String) {
                audit(value)
                value.trim()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, listOf(pack))

        assertEquals(setOf("audit(value)", "value.trim()"), analysis.texts(NORMALIZATION_INTENT).toSet())
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun qualifiersSeparateConfiguredHelpersFromAmbiguousOnes() {
        val pack =
            NormalizationRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "calls" to listOf("fromString"),
                            "qualifiedCalls" to listOf("fromString"),
                            "prefixes" to emptyList<String>(),
                            "qualifiers" to listOf("Normalizers"),
                        ),
                ),
            )
        val text =
            """
            fun f(value: String) {
                Normalizers.fromString(value)
                other.fromString(value)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, listOf(pack))

        assertEquals(listOf("Normalizers.fromString(value)"), analysis.texts(NORMALIZATION_INTENT))
        assertEquals("other.fromString(value)", analysis.candidateText(text))
    }

    @Test
    fun configuredInstanceCallsEstablishWithoutAnExtraList() {
        val pack =
            NormalizationRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "calls" to listOf("slugify"),
                            "prefixes" to emptyList<String>(),
                            "candidateCalls" to emptyList<String>(),
                            "candidatePrefixes" to emptyList<String>(),
                            "chainCalls" to emptyList<String>(),
                        ),
                ),
            )
        val text = "fun f(value: String) = value.slugify()"

        val analysis = analyze("kotlin", text, listOf(pack))

        assertEquals(listOf("value.slugify()"), analysis.texts(NORMALIZATION_INTENT))
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun standardInstanceNormalizersEstablishAcrossLanguages() {
        val cases =
            mapOf(
                "kotlin" to "fun f(value: String) = value.normalize()",
                "javascript" to "function f(value) { return value.normalize(); }",
                "typescript" to "function f(value: string): string { return value.normalize(); }",
                "csharp" to "class C { string F(string value) { return value.Normalize(); } }",
            )

        for ((language, text) in cases) {
            val analysis = analyze(language, text)
            assertTrue(analysis.texts(NORMALIZATION_INTENT).single().contains("normalize", ignoreCase = true), language)
            assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty(), "$language candidates")
        }

        val kotlinPath = "fun f(path: LocalPath) = path.normalize()"
        val withoutOracle = analyze("kotlin", kotlinPath)
        assertEquals(listOf("path.normalize()"), withoutOracle.texts(NORMALIZATION_INTENT))
        assertTrue(withoutOracle.candidates(NORMALIZATION_INTENT).isEmpty())

        val withOracle =
            analyzePack(
                parsePackInput("kotlin", kotlinPath),
                listOf(NormalizationRulePack()),
                FixedOracle(TypeFact("java.nio.file.Path", "lsp", 0.9)),
            )
        assertEquals(listOf("path.normalize()"), withOracle.texts(NORMALIZATION_INTENT))
        assertTrue(withOracle.candidates(NORMALIZATION_INTENT).isEmpty())
    }
}
