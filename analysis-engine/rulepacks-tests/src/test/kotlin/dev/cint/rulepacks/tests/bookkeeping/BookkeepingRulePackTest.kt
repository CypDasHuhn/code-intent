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
