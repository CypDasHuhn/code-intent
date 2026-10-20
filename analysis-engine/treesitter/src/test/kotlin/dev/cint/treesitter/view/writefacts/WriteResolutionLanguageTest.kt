package dev.cint.treesitter.view.writefacts

import dev.cint.api.DeclarationKind
import dev.cint.core.facts.model.SourceFile
import dev.cint.treesitter.view.support.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteResolutionLanguageTest {
    @Test
    fun qualifiedAndShadowedWritesResolveToTheActualTarget() {
        val sources =
            mapOf(
                "kotlin" to
                    """
                    class Box {
                        var count = 0
                        var config = 0

                        fun qualified() {
                            var count = 1
                            this.count = 2
                            this.count++
                            other.count = 3
                            other.count = this.count
                            config.value = 4
                            count = 5
                        }

                        fun parameter(count: Int) {
                            count = 6
                        }
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    class Box {
                        count = 0;
                        config = 0;

                        qualified() {
                            let count = 1;
                            this.count = 2;
                            this.count++;
                            other.count = 3;
                            other.count = this.count;
                            config.value = 4;
                            count = 5;
                        }

                        parameter(count) {
                            count = 6;
                        }
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    class Box {
                        count: number = 0;
                        config: number = 0;

                        qualified(): void {
                            let count: number = 1;
                            this.count = 2;
                            this.count++;
                            other.count = 3;
                            other.count = this.count;
                            config.value = 4;
                            count = 5;
                        }

                        parameter(count: number): void {
                            count = 6;
                        }
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class Box {
                        int count = 0;
                        int config = 0;

                        void Qualified() {
                            int count = 1;
                            this.count = 2;
                            this.count++;
                            other.count = 3;
                            other.count = this.count;
                            config.value = 4;
                            count = 5;
                        }

                        void Parameter(int count) {
                            count = 6;
                        }
                    }
                    """.trimIndent(),
            )
        for ((language, text) in sources) {
            val view = view(language, SourceFile("Sample", text))
            val field = view.declarations().single { it.name == "count" && it.kind == DeclarationKind.PROPERTY }
            val local = view.declarations().single { it.name == "count" && it.kind == DeclarationKind.VARIABLE }
            val config = view.declarations().single { it.name == "config" && it.kind == DeclarationKind.PROPERTY }

            assertEquals(
                setOf("this.count = 2", "this.count++"),
                field.writes().map { it.node.text }.toSet(),
                "$language qualified writes",
            )
            assertEquals(setOf("count = 5"), local.writes().map { it.node.text }.toSet(), "$language local writes")
            assertTrue(config.writes().isEmpty(), "$language receiver write leaked to config")
        }
    }

    @Test
    fun whileLoopWithoutBindingKeepsFieldWrites() {
        val sources =
            listOf(
                Triple(
                    "kotlin",
                    """
                    class Box {
                        var count = 0
                        fun run() {
                            while (count > 0) {
                                count = 1
                            }
                        }
                    }
                    """.trimIndent(),
                    "count = 1",
                ),
                Triple(
                    "javascript",
                    """
                    class Box {
                        count = 0;
                        run() {
                            while (this.count > 0) {
                                this.count = 1;
                            }
                        }
                    }
                    """.trimIndent(),
                    "this.count = 1",
                ),
                Triple(
                    "typescript",
                    """
                    class Box {
                        count: number = 0;
                        run(): void {
                            while (this.count > 0) {
                                this.count = 1;
                            }
                        }
                    }
                    """.trimIndent(),
                    "this.count = 1",
                ),
                Triple(
                    "csharp",
                    """
                    class Box {
                        int count = 0;
                        void Run() {
                            while (count > 0) {
                                count = 1;
                            }
                        }
                    }
                    """.trimIndent(),
                    "count = 1",
                ),
            )
        for ((language, text, expectedWrite) in sources) {
            val field =
                view(language, SourceFile("Sample", text))
                    .declarations()
                    .single { it.name == "count" && it.kind == DeclarationKind.PROPERTY }
            assertEquals(setOf(expectedWrite), field.writes().map { it.node.text }.toSet(), "$language while write")
        }
    }

    @Test
    fun loopAndCatchBindingsShadowFields() {
        val sources =
            mapOf(
                "kotlin" to
                    """
                    class Box {
                        var count = 0
                        fun run(list: List<Int>) {
                            for (count in list) {
                                count = 1
                            }
                            try {
                            } catch (count: Exception) {
                                count = 2
                            }
                        }
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    class Box {
                        count = 0;
                        run(list) {
                            for (const count of list) {
                                count = 1;
                            }
                            try {
                            } catch (count) {
                                count = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    class Box {
                        count: number = 0;
                        run(list: number[]): void {
                            for (const count of list) {
                                count = 1;
                            }
                            try {
                            } catch (count) {
                                count = 2;
                            }
                        }
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class Box {
                        int count = 0;
                        void Run(int[] list) {
                            foreach (var count in list) {
                                count = 1;
                            }
                            try {
                            } catch (Exception count) {
                                count = 2;
                            }
                        }
                    }
                    """.trimIndent(),
            )
        for ((language, text) in sources) {
            val field =
                view(language, SourceFile("Sample", text))
                    .declarations()
                    .single { it.name == "count" && it.kind == DeclarationKind.PROPERTY }
            assertTrue(field.writes().isEmpty(), "$language binding write leaked to field")
        }
    }
}
