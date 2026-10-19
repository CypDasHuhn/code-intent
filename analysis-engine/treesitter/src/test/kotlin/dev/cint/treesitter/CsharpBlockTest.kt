package dev.cint.treesitter

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.core.model.SourceFile
import dev.cint.core.config.loadIntentConfig
import dev.cint.core.engine.AnalysisEngine
import dev.cint.core.oracle.NullOracle
import dev.cint.core.rules.IntentBlocks
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsharpBlockTest {
    private val config = loadIntentConfig(null, "csharp")

    private fun analyze(text: String): List<Annotation> {
        val source = SourceFile("Sample.cs", text)
        val tree = CsharpParser().parse(source)
        val model = ProfileSyntaxModel(SyntaxProfiles.load("csharp"))
        return AnalysisEngine(IntentBlocks.build(config)).analyze(source, tree, model, NullOracle, config)
    }

    @Test
    fun consoleWriteLineIsLogging() {
        val annotations = analyze("""class C { void F() { Console.WriteLine("x"); } }""")
        val source = annotations.first { it.role == Role.SOURCE_INTENT }
        assertEquals("print", source.rule)
        assertEquals(setOf("LOGGING"), source.intents)
    }

    @Test
    fun loggerCallsUseReceiverName() {
        val annotations = analyze("""class C { void F() { _logger.LogInformation("x"); } }""")
        assertTrue(annotations.any { it.rule == "receiver-name" && it.intents == setOf("LOGGING") })
    }

    @Test
    fun throwIsErrorHandling() {
        val annotations = analyze("""class C { void F() { throw new Exception("x"); } }""")
        assertTrue(
            annotations.any {
                it.rule == "error-handling-throw" && it.intents == setOf("ERROR_HANDLING")
            },
        )
    }

    @Test
    fun debugAssertIsErrorHandling() {
        val annotations = analyze("""class C { void F(string id) { Debug.Assert(!string.IsNullOrEmpty(id)); } }""")
        assertTrue(
            annotations.any {
                it.rule == "error-handling-call" && it.intents == setOf("ERROR_HANDLING")
            },
        )
    }

    @Test
    fun assertOnUnqualifiedReceiverIsNotErrorHandling() {
        val annotations = analyze("""class C { void F(Checker c) { c.Assert(true); } }""")
        assertTrue(annotations.none { it.rule == "error-handling-call" })
    }

    @Test
    fun validationGuardWithEarlyReturn() {
        val text =
            """
            class C {
                int F(string value) {
                    if (string.IsNullOrEmpty(value)) {
                        return 0;
                    }
                    return 1;
                }
            }
            """.trimIndent()
        val annotations = analyze(text)
        val guard = annotations.first { it.rule == "validation-guard" }
        assertEquals(setOf("VALIDATION"), guard.intents)
        assertTrue(annotations.any { it.role == Role.SCAFFOLD && it.intents == setOf("VALIDATION") })
    }

    @Test
    fun bookkeepingCommentTagsWrites() {
        val text =
            """
            class C {
                // cint:bookkeeping
                private int skipped = 0;

                void F() {
                    skipped++;
                }
            }
            """.trimIndent()
        val annotations = analyze(text)
        assertTrue(
            annotations.any {
                it.rule == "bookkeeping-declaration" && it.intents == setOf("BOOKKEEPING")
            },
        )
        val write = annotations.first { it.rule == "bookkeeping-write" }
        assertEquals("skipped++", write.evidence)
    }

    @Test
    fun bookkeepingAttributeTagsWrites() {
        val text =
            """
            class C {
                [Bookkeeping]
                private int skipped = 0;

                void F() {
                    this.skipped++;
                }
            }
            """.trimIndent()
        val annotations = analyze(text)
        assertTrue(
            annotations.any {
                it.rule == "bookkeeping-declaration" && it.source == "annotation"
            },
        )
        val write = annotations.first { it.rule == "bookkeeping-write" }
        assertEquals("this.skipped++", write.evidence)
    }
}
