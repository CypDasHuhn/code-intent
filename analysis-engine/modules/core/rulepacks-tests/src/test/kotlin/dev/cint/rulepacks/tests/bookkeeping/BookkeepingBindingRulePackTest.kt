package dev.cint.rulepacks.tests.bookkeeping

import dev.cint.api.Annotation
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.tests.support.PackInput
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookkeepingBindingRulePackTest {
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
        assertTrue(kotlinAnnotations.none { it.text(kotlin) == "key = 1" || it.text(kotlin) == "value = 2" })

        val csharpAnnotations = bookkeeping("csharp", parse("csharp", "Sample.cs", csharp))
        assertEquals(2, csharpAnnotations.size, "csharp declarations only")
        assertTrue(csharpAnnotations.none { it.text(csharp) == "pair = default" || it.text(csharp) == "list = new List<int>()" })
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
                annotations.none { it.text(text) in writes },
                "$language destructured write tagged: ${annotations.map { it.text(text) }}",
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
            assertTrue(annotations.any { it.text(text) == "fallback = 2" }, "$language fallback field write")
            assertTrue(annotations.none { it.text(text) in bindingWrites }, "$language default/rest binding write tagged")
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
            assertTrue(annotations.any { it.text(text) == "key = 1" }, "$language computed-key field write")
            assertTrue(annotations.none { it.text(text) == "value = 2" }, "$language pair value write tagged")
        }
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
