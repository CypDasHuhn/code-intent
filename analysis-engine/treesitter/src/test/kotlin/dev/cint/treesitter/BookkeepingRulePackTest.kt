package dev.cint.treesitter

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.core.config.loadIntentConfig
import dev.cint.core.engine.AnalysisEngine
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.NullOracle
import dev.cint.core.rules.IntentBlocks
import dev.cint.core.scheduler.RuleEngine
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxProfiles
import dev.cint.core.syntax.SyntaxTree
import dev.cint.rulepacks.BookkeepingRulePack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookkeepingRulePackTest {
    private data class Input(
        val source: SourceFile,
        val tree: SyntaxTree,
        val syntax: SyntaxModel,
    )

    private data class Shape(
        val role: Role,
        val intents: Set<String>,
        val evidence: String,
        val startLine: Int,
        val startCol: Int,
        val endLine: Int,
        val endCol: Int,
        val sourceTier: String,
        val confidence: Double,
        val startByte: Int,
        val endByte: Int,
    )

    @Test
    fun matchesLegacyBookkeepingOverFixtures() {
        val cases =
            listOf(
                "kotlin" to fixture("kotlin/Bookkeeping.kt"),
                "javascript" to fixture("javascript/Sample.js"),
                "typescript" to fixture("typescript/Sample.ts"),
                "csharp" to fixture("csharp/Sample.cs"),
                "csharp" to
                    """
                    class C {
                        [Bookkeeping]
                        private int skipped = 0;

                        void F() {
                            this.skipped++;
                        }
                    }
                    """.trimIndent(),
                "kotlin" to
                    """
                    fun f() {
                        @Bookkeeping
                        var a = 0
                        var x = 0
                        a++
                        x++
                    }
                    """.trimIndent(),
                "kotlin" to
                    """
                    @Bookkeeping
                    var a = 0
                    var x = 0
                    fun f() {
                        a++
                        x++
                    }
                    """.trimIndent(),
                "kotlin" to
                    """
                    fun f() {
                        // cint:bookkeeping
                        var a = 0
                        var x = 0
                        a++
                        x++
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    // cint:bookkeeping
                    let a = 0;
                    let x = 0;
                    a++;
                    x++;
                    """.trimIndent(),
                "javascript" to
                    """
                    // cint:bookkeeping
                    if (enabled) var skipped = 0;
                    skipped++;
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        [Bookkeeping]
                        private int a = 0, b = 0;

                        void F() {
                            a++;
                            b++;
                        }
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        // cint:bookkeeping
                        private int a = 0, b = 0;

                        void F() {
                            a++;
                            b++;
                        }
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    // cint:bookkeeping
                    let a = 0, b = 0;
                    function f() {
                        a++;
                        b++;
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    // cint:bookkeeping
                    let a = 0, b = 0;
                    function f(): void {
                        a++;
                        b++;
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val input = parse(language, "Sample", text)
            val legacy = legacyBookkeeping(language, input).map(::shape).sortedWith(shapeOrder)
            val actual = bookkeeping(language, input).map(::shape).sortedWith(shapeOrder)
            assertEquals(legacy, actual, "$language bookkeeping parity")
        }
    }

    @Test
    fun writesInheritBookkeepingAndReadsDoNot() {
        val text =
            """
            class C {
                @Bookkeeping
                var skipped = 0

                fun record() {
                    skipped++
                    skipped += 1
                    skipped = 2
                    val read = skipped
                }
            }
            """.trimIndent()

        val annotations = bookkeeping("kotlin", parse("kotlin", "Sample.kt", text))
        val declarations = annotations.filter { it.evidence.startsWith("@Bookkeeping") }
        val writes = annotations - declarations.toSet()

        assertEquals(1, declarations.size)
        assertEquals(setOf("skipped++", "skipped += 1", "skipped = 2"), writes.map { it.evidence }.toSet())
        assertTrue(writes.all { it.intents == setOf("BOOKKEEPING") })
        assertTrue(annotations.none { it.evidence.contains("val read") })
    }

    @Test
    fun unannotatedNeighboursAreNotDeclared() {
        val cases =
            listOf(
                """
                fun f() {
                    @Bookkeeping
                    var a = 0
                    var x = 0
                    a++
                    x++
                }
                """.trimIndent(),
                """
                @Bookkeeping
                var a = 0
                var x = 0
                fun f() {
                    a++
                    x++
                }
                """.trimIndent(),
            )

        for (text in cases) {
            val annotations = bookkeeping("kotlin", parse("kotlin", "Sample.kt", text))
            assertTrue(annotations.any { it.evidence.contains("var a") })
            assertTrue(annotations.any { it.evidence == "a++" })
            assertTrue(annotations.none { it.evidence.contains("var x") })
            assertTrue(annotations.none { it.evidence == "x++" })
        }
    }

    @Test
    fun multiDeclaratorsInheritTheDeclarationSourceAndWrites() {
        val cases =
            listOf(
                "csharp" to
                    """
                    class C {
                        [Bookkeeping]
                        private int a = 0, b = 0;

                        void F() {
                            a++;
                            b++;
                        }
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        // cint:bookkeeping
                        private int a = 0, b = 0;

                        void F() {
                            a++;
                            b++;
                        }
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    // cint:bookkeeping
                    let a = 0, b = 0;
                    function f() {
                        a++;
                        b++;
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    // cint:bookkeeping
                    let a = 0, b = 0;
                    function f(): void {
                        a++;
                        b++;
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val annotations = bookkeeping(language, parse(language, "Sample", text))
            assertEquals(4, annotations.size, "$language multi-declarator annotations")
            assertEquals(
                setOf("a++", "b++"),
                annotations.mapNotNull { it.evidence.takeIf { evidence -> evidence.endsWith("++") } }.toSet(),
            )
        }
    }

    @Test
    fun namesAndInitializersDoNotDeclareBookkeeping() {
        val text =
            """
            var Bookkeeping = 0
            var skipped = [Bookkeeping]
            skipped += 1
            """.trimIndent()

        assertTrue(bookkeeping("javascript", parse("javascript", "Sample.js", text)).isEmpty())
    }

    @Test
    fun magicCommentIsPartOfTheDeclarationSpan() {
        val text =
            """
            class C {
                // cint:bookkeeping
                var rejected = 0
                fun f() {
                    rejected++
                }
            }
            """.trimIndent()

        val annotation =
            bookkeeping("kotlin", parse("kotlin", "Sample.kt", text))
                .first { it.source == "comment" }

        assertEquals(1, annotation.span.startLine)
        assertEquals(2, annotation.span.endLine)
        assertEquals(text.indexOf("// cint:bookkeeping"), annotation.startByte)
        assertEquals(text.indexOf("var rejected = 0") + "var rejected = 0".length, annotation.endByte)
        assertTrue(annotation.evidence.startsWith("// cint:bookkeeping var rejected = 0"))
    }

    private fun bookkeeping(
        language: String,
        input: Input,
    ): List<Annotation> =
        RuleEngine()
            .analyze(
                input.source,
                input.tree,
                input.syntax,
                NullOracle,
                loadIntentConfig(null, language),
                listOf(BookkeepingRulePack()),
            ).filter { it.role == Role.SOURCE_INTENT && "BOOKKEEPING" in it.intents }

    private fun legacyBookkeeping(
        language: String,
        input: Input,
    ): List<Annotation> {
        val config = loadIntentConfig(null, language)
        return AnalysisEngine(IntentBlocks.build(config))
            .analyze(input.source, input.tree, input.syntax, NullOracle, config)
            .filter {
                it.role == Role.SOURCE_INTENT &&
                    (it.rule == "bookkeeping-declaration" || it.rule == "bookkeeping-write")
            }
    }

    private fun parse(
        language: String,
        name: String,
        text: String,
    ): Input {
        val source = SourceFile(name, text)
        return when (language) {
            "kotlin" -> Input(source, KotlinParser().parse(source), KotlinSyntaxModel)
            "javascript" -> Input(source, JavascriptParser().parse(source), ProfileSyntaxModel(SyntaxProfiles.load(language)))
            "typescript" -> Input(source, TypescriptParser().parse(source), ProfileSyntaxModel(SyntaxProfiles.load(language)))
            "csharp" -> Input(source, CsharpParser().parse(source), ProfileSyntaxModel(SyntaxProfiles.load(language)))
            else -> error("unsupported language: $language")
        }
    }

    private fun fixture(relative: String): String {
        val path = Path.of("..", "..", "testing-grounds", relative)
        check(Files.exists(path)) { "missing fixture $path" }
        return Files.readString(path)
    }

    private fun shape(annotation: Annotation): Shape =
        Shape(
            role = annotation.role,
            intents = annotation.intents,
            evidence = annotation.evidence,
            startLine = annotation.span.startLine,
            startCol = annotation.span.startCol,
            endLine = annotation.span.endLine,
            endCol = annotation.span.endCol,
            sourceTier = annotation.source,
            confidence = annotation.confidence,
            startByte = annotation.startByte,
            endByte = annotation.endByte,
        )

    private val shapeOrder =
        compareBy<Shape> { it.startByte }
            .thenBy { it.endByte }
            .thenBy { it.evidence }
            .thenBy { it.sourceTier }
}
