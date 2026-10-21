package dev.cint.rulepacks.tests.intents.normalization

import dev.cint.api.CandidateReason
import dev.cint.api.Role
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.normalization.NORMALIZATION_INTENT
import dev.cint.rulepacks.intents.normalization.NORMALIZATION_RULE_ID
import dev.cint.rulepacks.intents.normalization.NormalizationRulePack
import dev.cint.rulepacks.logging.LOGGING_INTENT
import dev.cint.rulepacks.logging.LoggingRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.ValidationRulePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FixedOracle(
    private val fact: TypeFact,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact = fact
}

private fun analyze(
    language: String,
    text: String,
    packs: List<dev.cint.api.RulePack> = listOf(NormalizationRulePack()),
    oracle: TypeOracle = NullOracle,
): PackAnalysis = analyzePack(language, text, packs, oracle)

private fun PackAnalysis.candidateText(
    text: String,
    index: Int = 0,
): String {
    val candidate = candidates(NORMALIZATION_INTENT)[index]
    return text.substring(candidate.startByte, candidate.endByte)
}

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

    @Test
    fun validationAndNormalizationCoexistOnOneExpression() {
        val text =
            """
            fun f(value: String): String {
                if (value.trim().isBlank()) {
                    return ""
                }
                return value.trim()
            }
            """.trimIndent()

        val analysis =
            analyze(
                "kotlin",
                text,
                listOf(BookkeepingRulePack(), ValidationRulePack(), NormalizationRulePack()),
            )

        val validation = analysis.source(VALIDATION_INTENT).single { "isBlank" in it.text(text) }
        val normalization = analysis.source(NORMALIZATION_INTENT)
        val guardedTrim = normalization.first { it.text(text) == "value.trim()" }

        assertEquals(2, normalization.count { it.text(text) == "value.trim()" })
        assertTrue(normalization.none { "isBlank" in it.text(text) })
        assertTrue(guardedTrim.startByte >= validation.startByte)
        assertTrue(guardedTrim.endByte <= validation.endByte)
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())

        val guard =
            analysis.annotations.first {
                it.role == Role.SCAFFOLD && "if (value.trim().isBlank())" in it.text(text)
            }
        assertTrue(VALIDATION_INTENT in guard.intents)
    }

    @Test
    fun normalizationDoesNotBecomeLoggingOrBookkeeping() {
        val text =
            """
            class C {
                @Bookkeeping
                var total = 0

                fun f(value: String) {
                    println(value.trim())
                    total++
                }
            }
            """.trimIndent()

        val analysis =
            analyze(
                "kotlin",
                text,
                listOf(BookkeepingRulePack(), LoggingRulePack(), NormalizationRulePack()),
            )

        val normalization = analysis.texts(NORMALIZATION_INTENT)
        val logging = analysis.texts(LOGGING_INTENT)
        val bookkeeping = analysis.texts(BOOKKEEPING_INTENT)

        assertEquals(listOf("value.trim()"), normalization)
        assertTrue(logging.any { "println(value.trim())" in it })
        assertTrue(bookkeeping.any { it == "total++" })
        assertTrue(normalization.none { "println" in it })
        assertTrue(logging.none { it == "value.trim()" })
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun multiStepTransformYieldsCandidateForTheUnconfiguredStep() {
        val text =
            """
            fun f(value: String): String {
                return value.trim().replace(" ", "")
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)
        val candidate = analysis.candidates(NORMALIZATION_INTENT).single()

        assertEquals(listOf("value.trim()"), analysis.texts(NORMALIZATION_INTENT))
        assertTrue(analysis.texts(NORMALIZATION_INTENT).none { "replace" in it })
        assertEquals(CandidateReason.PACK_DOMAIN, candidate.reason)
        assertEquals("rule:$NORMALIZATION_RULE_ID", candidate.source)
        assertEquals(0.4, candidate.confidence)
        assertTrue(candidate.aiEligible)
        assertTrue("replace(" in analysis.candidateText(text))
    }

    @Test
    fun formattingHelpersAreCandidatesWithoutAConfiguredType() {
        val text = "fun f(value: String) = value.format(value)"

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(NORMALIZATION_INTENT).isEmpty())
        val candidate = analysis.candidates(NORMALIZATION_INTENT).single()
        assertEquals(CandidateReason.PACK_DOMAIN, candidate.reason)
        assertTrue(candidate.aiEligible)
        assertEquals("value.format(value)", analysis.candidateText(text))
    }

    @Test
    fun formattingHelperOnAConfiguredTypeRemainsCandidate() {
        val text = "fun f(date: LocalDate) = date.format(null)"
        val input = parsePackInput("kotlin", text)

        val analysis =
            analyzePack(
                input,
                listOf(NormalizationRulePack()),
                FixedOracle(TypeFact("java.time.LocalDate", "lsp", 0.9)),
            )

        assertTrue(analysis.source(NORMALIZATION_INTENT).isEmpty())
        assertEquals("date.format(null)", analysis.candidateText(text))
    }

    @Test
    fun configuredTypeEstablishesPrefixCalls() {
        val text = "fun f(date: LocalDate) = date.parseCanonical()"
        val input = parsePackInput("kotlin", text)

        val analysis =
            analyzePack(
                input,
                listOf(NormalizationRulePack()),
                FixedOracle(TypeFact("java.time.LocalDate", "lsp", 0.9)),
            )

        assertEquals(listOf("date.parseCanonical()"), analysis.texts(NORMALIZATION_INTENT))
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun fullyEstablishedChainsStayCandidateFree() {
        val text = "fun f(value: String) = value.trim().uppercase()"

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("value.trim()", "value.trim().uppercase()"), analysis.texts(NORMALIZATION_INTENT).toSet())
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun nonTransformChainTailsStayCandidateFree() {
        val text =
            """
            fun f(value: String) {
                value.trim().isBlank()
                value.trim().length
                value.trim().substring(1)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(3, analysis.texts(NORMALIZATION_INTENT).count { it == "value.trim()" })
        assertTrue(analysis.candidates(NORMALIZATION_INTENT).isEmpty())
    }

    @Test
    fun displayFormattingStaysCandidateByDefault() {
        val kotlin = analyze("kotlin", "fun f(value: String) = String.format(value)")
        assertTrue(kotlin.source(NORMALIZATION_INTENT).isEmpty())
        assertEquals("String.format(value)", kotlin.candidateText("fun f(value: String) = String.format(value)"))

        val javascript = analyze("javascript", "function f(value) { return JSON.stringify(value); }")
        assertTrue(javascript.source(NORMALIZATION_INTENT).isEmpty())
        assertTrue(javascript.candidates(NORMALIZATION_INTENT).single().aiEligible)
    }

    @Test
    fun broadPrefixesStayConservativeAcrossLanguages() {
        val kotlin =
            analyze(
                "kotlin",
                """
                fun f(value: String) {
                    value.parseDomainEvent()
                    value.normalizeLayout()
                    value.canonicalizeForDisplay()
                }
                """.trimIndent(),
            )
        assertTrue(kotlin.source(NORMALIZATION_INTENT).isEmpty())
        assertEquals(3, kotlin.candidates(NORMALIZATION_INTENT).size)

        val csharp =
            analyze(
                "csharp",
                """
                class C {
                    void F(string value) {
                        if (int.TryParse(value, out _)) {
                            work();
                        }
                    }
                }
                """.trimIndent(),
            )
        assertTrue(csharp.source(NORMALIZATION_INTENT).isEmpty())
        assertTrue(csharp.candidates(NORMALIZATION_INTENT).single().aiEligible)
    }

    @Test
    fun unrelatedCallsRemainUntagged() {
        val text =
            """
            fun f(value: String) {
                value.substring(1)
                value.replace("a", "b")
                value.isEmpty()
                logger.info(value.trim())
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(listOf("value.trim()"), analysis.texts(NORMALIZATION_INTENT))
        assertFalse(analysis.texts(NORMALIZATION_INTENT).any { "substring" in it || "replace" in it })
    }
}
