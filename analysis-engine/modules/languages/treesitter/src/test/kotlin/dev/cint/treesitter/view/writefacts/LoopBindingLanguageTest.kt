package dev.cint.treesitter.view.writefacts

import dev.cint.api.DeclarationKind
import dev.cint.core.facts.model.SourceFile
import dev.cint.treesitter.view.support.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopBindingLanguageTest {
    @Test
    fun loopBindingsShadowFieldsAcrossPatternsAndTypedHeaders() {
        val fixtures =
            listOf(
                "kotlin" to
                    """
                    class Box {
                        var key = 0
                        var value = 0
                        fun run(map: Map<String, Int>) {
                            for ((key, value) in map) {
                                key = 1
                                value = 2
                            }
                        }
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    class Box {
                        a = 0;
                        b = 0;
                        run(list) {
                            for (const [a, b] of list) {
                                a = 1;
                                b = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    class Box {
                        a = 0;
                        b = 0;
                        run(list: number[]): void {
                            for (const [a, b] of list) {
                                a = 1;
                                b = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                "kotlin" to
                    """
                    class Box {
                        var key = 0
                        var a = 0
                        var b = 0
                        fun run(map: Map<String, Pair<Int, Int>>) {
                            for ((key, (a, b)) in map) {
                                key = 1
                                a = 2
                                b = 3
                            }
                        }
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class Box {
                        int pair = 0;
                        int list = 0;
                        void Run(Dictionary<string, int> map, List<int> list) {
                            foreach (KeyValuePair<string, int> pair in map) {
                                pair = default;
                            }
                            list = new List<int>();
                        }
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class Box {
                        int key = 0;
                        int value = 0;
                        void Run(Dictionary<string, int> map) {
                            foreach (var (key, value) in map) {
                                key = 1;
                                value = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    class Box {
                        x = 0;
                        y = 0;
                        run(list) {
                            for (const {a: x, b: y} of list) {
                                x = 1;
                                y = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    class Box {
                        a = 0;
                        b = 0;
                        run(list) {
                            for (const [[a], b] of list) {
                                a = 1;
                                b = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    class Box {
                        a = 0;
                        b = 0;
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
            )
        for ((language, text) in fixtures) {
            val view = view(language, SourceFile("Sample", text))
            val fields = view.declarations().filter { it.kind == DeclarationKind.PROPERTY }
            assertTrue(fields.isNotEmpty(), "$language missing field declarations")
            for (field in fields) {
                assertTrue(
                    field.writes().isEmpty(),
                    "$language loop or parameter binding write leaked to field ${field.name}: ${field.writes().map { it.node.text }}",
                )
            }
        }
    }

    @Test
    fun defaultPatternExpressionsAreNotLoopBindingsAndRestStillShadows() {
        val fixtures =
            listOf(
                Triple(
                    "javascript",
                    """
                    class Box {
                        a = 0;
                        fallback = 0;
                        rest = 0;
                        run(list) {
                            for (const [a = fallback, ...rest] of list) {
                                a = 1;
                                fallback = 2;
                                rest = 3;
                            }
                        }
                    }
                    """.trimIndent(),
                    setOf("a", "rest"),
                ),
                Triple(
                    "javascript",
                    """
                    class Box {
                        a = 0;
                        fallback = 0;
                        rest = 0;
                        run(list) {
                            for (const {a = fallback, ...rest} of list) {
                                a = 1;
                                fallback = 2;
                                rest = 3;
                            }
                        }
                    }
                    """.trimIndent(),
                    setOf("a", "rest"),
                ),
                Triple(
                    "typescript",
                    """
                    class Box {
                        c = 0;
                        fallback = 0;
                        run(list: unknown[]): void {
                            for (const {b: c = fallback} of list) {
                                c = 1;
                                fallback = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                    setOf("c"),
                ),
            )
        for ((language, text, bindingNames) in fixtures) {
            val fields = view(language, SourceFile("Sample", text)).declarations().filter { it.kind == DeclarationKind.PROPERTY }
            val bindings = fields.filter { it.name in bindingNames }
            assertEquals(bindingNames, bindings.mapNotNull { it.name }.toSet(), "$language binding fields")
            val leaked = bindings.filter { it.writes().isNotEmpty() }.map { it.name }
            assertTrue(leaked.isEmpty(), "$language pattern binding write leaked to field: $leaked")
            val fallback = fields.single { it.name == "fallback" }
            assertEquals(
                setOf("fallback = 2"),
                fallback.writes().map { it.node.text }.toSet(),
                "$language default initializer name must not shadow the field",
            )
        }
    }

    @Test
    fun computedPatternKeysAreNotLoopBindings() {
        val fixtures =
            listOf(
                "javascript" to
                    """
                    class Box {
                        key = 0;
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
                    class Box {
                        key = 0;
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
        for ((language, text) in fixtures) {
            val fields = view(language, SourceFile("Sample", text)).declarations().filter { it.kind == DeclarationKind.PROPERTY }
            val key = fields.single { it.name == "key" }
            val value = fields.single { it.name == "value" }

            assertEquals(setOf("key = 1"), key.writes().map { it.node.text }.toSet(), "$language computed key field write")
            assertTrue(value.writes().isEmpty(), "$language pair value should shadow the field")
        }
    }
}
