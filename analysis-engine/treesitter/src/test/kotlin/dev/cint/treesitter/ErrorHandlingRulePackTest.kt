package dev.cint.treesitter

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.api.RulePack
import dev.cint.api.Span
import dev.cint.core.config.loadIntentConfig
import dev.cint.core.engine.AnalysisEngine
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.NullOracle
import dev.cint.core.rules.IntentBlocks
import dev.cint.core.scheduler.DuplicateIntentOwner
import dev.cint.core.scheduler.RuleEngine
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxProfiles
import dev.cint.core.syntax.SyntaxTree
import dev.cint.rulepacks.errorhandling.ERROR_HANDLING_INTENT
import dev.cint.rulepacks.errorhandling.ErrorHandlingConfig
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class ErrorHandlingHit(
    val intents: Set<String>,
    val span: Span,
    val evidence: String,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
)

private val errorHandlingHitOrder =
    compareBy<ErrorHandlingHit>(
        { it.startByte },
        { it.endByte },
        { it.evidence },
        { it.intents.joinToString("\u0000") },
    )

class ErrorHandlingRulePackTest {
    @Test
    fun packMetadataMatchesContract() {
        val pack = ErrorHandlingRulePack()
        val rule = pack.rules().single()

        assertEquals("error-handling-kotlin", pack.id)
        assertEquals("kotlin", pack.language)
        assertEquals(setOf("kotlin"), pack.languages)
        assertEquals("error-handling-kotlin:error-handling", rule.id)
        assertEquals(ERROR_HANDLING_INTENT, rule.owns)
        assertTrue(rule.reads.isEmpty())
    }

    @Test
    fun packProvidesOneInstancePerSupportedLanguageWithUniqueIds() {
        val packs = ErrorHandlingRulePack.all()

        assertEquals(
            listOf("kotlin", "javascript", "typescript", "csharp"),
            packs.map { it.language },
        )
        assertEquals(packs.size, packs.map { it.id }.distinct().size)
        assertEquals(packs.size, packs.map { it.rules().single().id }.distinct().size)
    }

    @Test
    fun languageSelectionSchedulesAndExecutesSuccessfully() {
        for (language in ErrorHandlingRulePack.languages) {
            val pack = ErrorHandlingRulePack.forLanguage(language)
            val plan = RuleEngine().plan(ErrorHandlingRulePack.selection(language))

            assertEquals(1, plan.strata.size)
            assertEquals(listOf(pack.rules().single().id), plan.strata.single().map { it.id })
            assertParity(language, fixtureFor(language))
        }
    }

    @Test
    fun mixedLanguageCoLoadFailsAsDuplicateIntentOwner() {
        val error = assertFailsWith<DuplicateIntentOwner> { RuleEngine().plan(ErrorHandlingRulePack.all()) }

        assertTrue(error.message.orEmpty().contains(ERROR_HANDLING_INTENT))
    }

    @Test
    fun kotlinFixtureMatchesLegacy() {
        assertParity("kotlin", fixture("kotlin/ErrorHandling.kt"))
    }

    @Test
    fun javascriptFixtureMatchesLegacy() {
        assertParity("javascript", fixture("javascript/Sample.js"))
    }

    @Test
    fun typescriptFixtureMatchesLegacy() {
        assertParity("typescript", fixture("typescript/Sample.ts"))
    }

    @Test
    fun csharpFixtureMatchesLegacy() {
        assertParity("csharp", fixture("csharp/Sample.cs"))
    }

    @Test
    fun kotlinCatchesFixtureMatchesLegacy() {
        assertParity("kotlin", fixture("kotlin/Catches.kt"))
    }

    @Test
    fun vueScriptThrowMatchesLegacy() {
        val script =
            Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(fixture("vue/Sample.vue"))
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?: error("vue fixture has no script block")

        assertParity("typescript", script)
    }

    @Test
    fun csharpQualifiedCallsMatchLegacyAndArbitraryReceiverStaysUntagged() {
        val text =
            """
            class C {
                void F(Checker c) {
                    Debug.Assert(true);
                    Trace.Assert(true);
                    Contract.Assert(true);
                    System.Diagnostics.Debug.Assert(true);
                    c.Assert(true);
                    throw new Exception("x");
                }
            }
            """.trimIndent()

        val legacy = legacyHits("csharp", text)
        val current = newHits("csharp", text)

        assertEquals(legacy, current)
        assertTrue(current.any { it.evidence == "Debug.Assert(true)" })
        assertTrue(current.any { it.evidence == "Trace.Assert(true)" })
        assertTrue(current.any { it.evidence == "Contract.Assert(true)" })
        assertTrue(current.any { it.evidence == "System.Diagnostics.Debug.Assert(true)" })
        assertTrue(current.none { it.evidence == "c.Assert(true)" })
    }

    @Test
    fun kotlinReceiverOnConfiguredCheckIsNotErrorHandling() {
        val text =
            """
            fun f(c: Checker) {
                c.require(true)
                require(true) { "required" }
                throw Exception("x")
            }
            """.trimIndent()

        val legacy = legacyHits("kotlin", text)
        val current = newHits("kotlin", text)

        assertEquals(legacy, current)
        assertTrue(current.none { it.evidence.startsWith("c.require") })
        assertTrue(current.any { it.evidence.startsWith("require(true)") })
        assertTrue(current.any { it.evidence == "throw Exception(\"x\")" })
    }

    @Test
    fun javascriptUnqualifiedChecksMatchLegacyAndReceiverStaysUntagged() {
        val text =
            """
            function f(obj, id) {
                assert(id, "id required");
                invariant(id);
                obj.assert(id);
            }
            """.trimIndent()

        val legacy = legacyHits("javascript", text)
        val current = newHits("javascript", text)

        assertEquals(legacy, current)
        assertTrue(current.any { it.evidence == "assert(id, \"id required\")" })
        assertTrue(current.any { it.evidence == "invariant(id)" })
        assertTrue(current.none { it.evidence == "obj.assert(id)" })
    }

    @Test
    fun trailingLambdaSpanIsIncluded() {
        val text =
            """
            fun f() {
                require(true) {
                    "message"
                }
            }
            """.trimIndent()

        val current = newHits("kotlin", text)
        val check = current.single { it.evidence.contains("\"message\"") }

        assertEquals(legacyHits("kotlin", text), current)
        assertEquals(2, check.span.endLine - check.span.startLine)
        assertTrue(check.evidence.startsWith("require(true)"))
    }

    @Test
    fun kotlinAssertMatchesLegacy() {
        val text = """fun f(x: Int) { assert(x > 0) }"""

        val legacy = legacyHits("kotlin", text)
        val current = newHits("kotlin", text)

        assertEquals(legacy, current)
        assertTrue(current.any { it.evidence == "assert(x > 0)" })
    }

    @Test
    fun disabledThrowsConfigSkipsThrows() {
        val text = """fun f() { throw Exception("x") }"""
        val pack = ErrorHandlingRulePack("kotlin", ErrorHandlingConfig(detectThrows = false))

        assertEquals(1, legacyHits("kotlin", text).size)
        assertTrue(newHits("kotlin", text, pack).isEmpty())
    }

    private fun assertParity(
        language: String,
        text: String,
    ) {
        assertEquals(legacyHits(language, text), newHits(language, text))
    }

    private fun legacyHits(
        language: String,
        text: String,
    ): List<ErrorHandlingHit> {
        val source = SourceFile(fileName(language), text)
        val tree = parse(language, source)
        val config = loadIntentConfig(null, language)
        val annotations =
            AnalysisEngine(IntentBlocks.build(config))
                .analyze(source, tree, syntax(language), NullOracle, config)
        return hits(annotations)
    }

    private fun newHits(
        language: String,
        text: String,
    ): List<ErrorHandlingHit> = newHits(language, text, ErrorHandlingRulePack.forLanguage(language))

    private fun newHits(
        language: String,
        text: String,
        pack: RulePack,
    ): List<ErrorHandlingHit> {
        val source = SourceFile(fileName(language), text)
        val tree = parse(language, source)
        val config = loadIntentConfig(null, language)
        val annotations =
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    syntax(language),
                    NullOracle,
                    config,
                    listOf(pack),
                )
        return hits(annotations)
    }

    private fun hits(annotations: List<Annotation>): List<ErrorHandlingHit> =
        annotations
            .asSequence()
            .filter { it.role == Role.SOURCE_INTENT }
            .flatMap { it.provenance.asSequence() }
            .filter { ERROR_HANDLING_INTENT in it.intents }
            .map {
                ErrorHandlingHit(
                    intents = it.intents,
                    span = it.span,
                    evidence = it.evidence,
                    source = it.source,
                    confidence = it.confidence,
                    startByte = it.startByte,
                    endByte = it.endByte,
                )
            }.sortedWith(errorHandlingHitOrder)
            .toList()

    private fun parse(
        language: String,
        source: SourceFile,
    ): SyntaxTree =
        when (language) {
            "kotlin" -> KotlinParser().parse(source)
            "javascript" -> JavascriptParser().parse(source)
            "typescript" -> TypescriptParser().parse(source)
            "csharp" -> CsharpParser().parse(source)
            else -> error("unsupported fixture language: $language")
        }

    private fun syntax(language: String): SyntaxModel =
        if (language == "kotlin") {
            KotlinSyntaxModel
        } else {
            ProfileSyntaxModel(SyntaxProfiles.load(language))
        }

    private fun fileName(language: String): String =
        when (language) {
            "kotlin" -> "Sample.kt"
            "javascript" -> "Sample.js"
            "typescript" -> "Sample.ts"
            "csharp" -> "Sample.cs"
            else -> error("unsupported fixture language: $language")
        }

    private fun fixtureFor(language: String): String =
        when (language) {
            "kotlin" -> fixture("kotlin/ErrorHandling.kt")
            "javascript" -> fixture("javascript/Sample.js")
            "typescript" -> fixture("typescript/Sample.ts")
            "csharp" -> fixture("csharp/Sample.cs")
            else -> error("unsupported fixture language: $language")
        }

    private fun fixture(relative: String): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "testing-grounds/$relative")
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("fixture not found: testing-grounds/$relative")
    }
}
