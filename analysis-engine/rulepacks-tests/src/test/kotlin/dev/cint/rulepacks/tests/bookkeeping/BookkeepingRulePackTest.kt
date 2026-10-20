package dev.cint.rulepacks.tests.bookkeeping

import dev.cint.api.Annotation
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.tests.support.PackInput
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookkeepingRulePackTest {
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

    @Test
    fun loopAndParameterBindingsDoNotInheritBookkeeping() {
        val kotlin =
            """
            class Box {
                @Bookkeeping
                var key = 0
                @Bookkeeping
                var value = 0

                fun run(map: Map<String, Int>) {
                    for ((key, value) in map) {
                        key = 1
                        value = 2
                    }
                }
            }
            """.trimIndent()
        val csharp =
            """
            class C {
                [Bookkeeping]
                private int pair = 0;
                [Bookkeeping]
                private int list = 0;

                void F(Dictionary<string, int> map, List<int> list) {
                    foreach (KeyValuePair<string, int> pair in map) {
                        pair = default;
                    }
                    list = new List<int>();
                }
            }
            """.trimIndent()

        val kotlinAnnotations = bookkeeping("kotlin", parse("kotlin", "Sample.kt", kotlin))
        assertEquals(2, kotlinAnnotations.size, "kotlin declarations only")
        assertTrue(kotlinAnnotations.none { it.evidence == "key = 1" || it.evidence == "value = 2" })

        val csharpAnnotations = bookkeeping("csharp", parse("csharp", "Sample.cs", csharp))
        assertEquals(2, csharpAnnotations.size, "csharp declarations only")
        assertTrue(csharpAnnotations.none { it.evidence == "pair = default" || it.evidence == "list = new List<int>()" })
    }

    @Test
    fun destructuredLoopBindingsDoNotInheritBookkeeping() {
        val cases =
            listOf(
                Triple(
                    "csharp",
                    """
                    class C {
                        [Bookkeeping]
                        private int key = 0;
                        [Bookkeeping]
                        private int value = 0;

                        void F(Dictionary<string, int> map) {
                            foreach (var (key, value) in map) {
                                key = 1;
                                value = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                    setOf("key = 1", "value = 2"),
                ),
                Triple(
                    "javascript",
                    """
                    class C {
                        // cint:bookkeeping
                        x = 0;
                        // cint:bookkeeping
                        y = 0;

                        run(list) {
                            for (const {a: x, b: y} of list) {
                                x = 1;
                                y = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                    setOf("x = 1", "y = 2"),
                ),
                Triple(
                    "typescript",
                    """
                    class C {
                        @Bookkeeping
                        a = 0;
                        @Bookkeeping
                        b = 0;
                        @Bookkeeping
                        c = 0;

                        run(list: unknown[]): void {
                            for (const {nested: {a}, b, c: c} of list) {
                                a = 1;
                                b = 2;
                                c = 3;
                            }
                        }
                    }
                    """.trimIndent(),
                    setOf("a = 1", "b = 2", "c = 3"),
                ),
            )

        for ((language, text, writes) in cases) {
            val annotations = bookkeeping(language, parse(language, "Sample", text))
            assertEquals(writes.size, annotations.size, "$language declarations only")
            assertTrue(
                annotations.none { it.evidence in writes },
                "$language destructured write tagged: ${annotations.map { it.evidence }}",
            )
        }
    }

    @Test
    fun defaultPatternExpressionsDoNotCreateBindings() {
        val cases =
            listOf(
                Triple(
                    "javascript",
                    """
                    class C {
                        // cint:bookkeeping
                        fallback = 0;
                        // cint:bookkeeping
                        a = 0;

                        run(list) {
                            for (const [a = fallback, ...rest] of list) {
                                a = 1;
                                fallback = 2;
                                rest = 3;
                            }
                        }
                    }
                    """.trimIndent(),
                    setOf("a = 1", "rest = 3"),
                ),
                Triple(
                    "typescript",
                    """
                    class C {
                        @Bookkeeping
                        fallback = 0;
                        @Bookkeeping
                        c = 0;

                        run(list: unknown[]): void {
                            for (const {b: c = fallback} of list) {
                                c = 1;
                                fallback = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                    setOf("c = 1"),
                ),
            )

        for ((language, text, bindingWrites) in cases) {
            val annotations = bookkeeping(language, parse(language, "Sample", text))
            assertEquals(3, annotations.size, "$language declarations plus the fallback field write")
            assertTrue(annotations.any { it.evidence == "fallback = 2" }, "$language fallback field write")
            assertTrue(annotations.none { it.evidence in bindingWrites }, "$language default/rest binding write tagged")
        }
    }

    @Test
    fun computedPatternKeysRemainOuterFields() {
        val cases =
            listOf(
                "javascript" to
                    """
                    class C {
                        // cint:bookkeeping
                        key = 0;
                        // cint:bookkeeping
                        value = 0;

                        run(list) {
                            for (const {[key]: value} of list) {
                                key = 1;
                                value = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    class C {
                        @Bookkeeping
                        key = 0;
                        @Bookkeeping
                        value = 0;

                        run(list: unknown[]): void {
                            for (const {[key]: value} of list) {
                                key = 1;
                                value = 2;
                            }
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val annotations = bookkeeping(language, parse(language, "Sample", text))
            assertEquals(3, annotations.size, "$language declarations plus the computed-key field write")
            assertTrue(annotations.any { it.evidence == "key = 1" }, "$language computed-key field write")
            assertTrue(annotations.none { it.evidence == "value = 2" }, "$language pair value write tagged")
        }
    }

    @Test
    fun companionAndObjectDeclarationsKeepTheirOwnWrites() {
        val text =
            """
            class Box {
                var total = 0

                companion object {
                    @Bookkeeping
                    var total = 0

                    fun bump() {
                        total++
                    }
                }

                object Registry {
                    // cint:bookkeeping
                    var size = 0

                    fun reset() {
                        size = 0
                    }
                }
            }
            """.trimIndent()

        val annotations = bookkeeping("kotlin", parse("kotlin", "Sample.kt", text))
        assertEquals(1, annotations.count { it.evidence == "total++" })
        assertEquals(1, annotations.count { it.evidence == "size = 0" })
    }

    private fun bookkeeping(
        language: String,
        input: PackInput,
    ): List<Annotation> = analyzePack(input, listOf(BookkeepingRulePack())).source("BOOKKEEPING")

    private fun parse(
        language: String,
        name: String,
        text: String,
    ): PackInput = parsePackInput(language, text, name)
}
