package dev.cint.treesitter.language

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.syntax.ExpressionUnit
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.expressionUnits
import dev.cint.treesitter.CsharpParser
import dev.cint.treesitter.JavascriptParser
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.SyntaxProfiles
import dev.cint.treesitter.TreeSitterParser
import dev.cint.treesitter.TypescriptParser
import dev.cint.treesitter.VueParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpressionUnitsTest {
    private val kotlinSource =
        """
        class Foo {
            fun scan(text: String): Boolean {
                val match = Regex("abc").find(text)
                val thing = object : Runnable {
                    override fun run() {}
                }
                return match != null && text.length > thing.hashCode()
            }
        }
        """.trimIndent()

    @Test
    fun kotlinEnumeratesRealExpressionsAndLeavesWithoutDeclarationsOrTypes() {
        val units = units("kotlin", KotlinParser(), kotlinSource)

        assertTrue(units.any { it.text.contains("find(text)") })
        assertTrue(units.any { it.text == "Regex" })
        assertTrue(units.any { it.text == "text" })
        assertTrue(units.any { it.node.kind == "object_literal" })
        assertTrue(units.filter { it.parent != null }.isNotEmpty())
        assertNoNodes(units, "function_body", "statements", "parameter", "function_value_parameters", "user_type", "variable_declaration")
        assertNoDeclarationNames(units, "variable_declaration", "function_declaration", "class_declaration")
        val call = units.first { it.text.contains("find(text)") }
        assertTrue(units.any { it.parent != null })
        assertTrue(call.startByte >= 0)
        assertEquals(units.map { it.unitId }, units.map { it.unitId }.distinct())
    }

    @Test
    fun javascriptEnumeratesCallsAndLeavesWithoutFunctionsOrParameters() {
        val source =
            """
            function scan(text) {
                const match = /abc/.exec(text);
                return match != null;
            }
            """.trimIndent()
        val units = units("javascript", JavascriptParser(), source)

        assertTrue(units.any { it.text.contains(".exec(text)") })
        assertTrue(units.any { it.text == "text" })
        assertNoNodes(units, "function_declaration", "formal_parameters", "statement_block", "variable_declarator", "return_statement")
        assertNoDeclarationNames(units, "variable_declarator", "function_declaration")
    }

    @Test
    fun typescriptEnumeratesNewAndCallExpressionsWithoutTypeNodes() {
        val source =
            """
            function scan(text: string): boolean {
                const match = new RegExp("abc").exec(text);
                return match != null;
            }
            """.trimIndent()
        val units = units("typescript", TypescriptParser(), source)

        assertTrue(units.any { it.node.kind == "new_expression" })
        assertTrue(units.any { it.text.contains(".exec(text)") })
        assertNoNodes(units, "required_parameter", "predefined_type", "type_annotation", "formal_parameters", "statement_block")
        assertNoDeclarationNames(units, "variable_declarator", "function_declaration")
    }

    @Test
    fun csharpEnumeratesCallsWithoutParametersTypesOrBlocks() {
        val source =
            """
            class Foo {
                bool Scan(string text) {
                    var match = new Regex("abc").Match(text);
                    return match != null;
                }
            }
            """.trimIndent()
        val units = units("csharp", CsharpParser(), source)

        assertTrue(units.any { it.node.kind == "invocation_expression" })
        assertTrue(units.any { it.node.kind == "object_creation_expression" })
        assertTrue(units.any { it.text == "text" })
        assertNoNodes(units, "parameter", "parameter_list", "predefined_type", "block", "variable_declaration")
        assertNoDeclarationNames(units, "variable_declarator", "method_declaration", "class_declaration")
    }

    @Test
    fun vueEnumeratesTemplateExpressions() {
        val source =
            """
            <template>
                <div>{{ item.name }}</div>
            </template>
            <script>
                const value = item.name
            </script>
            """.trimIndent()
        val units = units("vue", VueParser(), source)

        assertTrue(units.isNotEmpty())
        assertTrue(units.any { it.node.kind == "interpolation" })
        assertNoNodes(units, "script_element", "style_element", "start_tag", "end_tag")
    }

    @Test
    fun kotlinDefaultParameterExpressionsAreUnitsWithoutNamesOrTypes() {
        val source =
            """
            class Foo {
                fun scan(text: String, pattern: String = Regex("abc").find(text)?.value ?: ""): Boolean {
                    return pattern.isNotEmpty()
                }
            }
            """.trimIndent()
        val units = units("kotlin", KotlinParser(), source)

        assertTrue(units.any { it.text.contains("Regex(\"abc\")") })
        assertTrue(units.any { it.text.contains("find(text)") })
        assertNoNodes(
            units,
            "parameter",
            "function_value_parameters",
            "user_type",
            "type_annotation",
            "function_body",
            "statements",
        )
        assertNoDeclarationNames(units, "parameter", "function_value_parameters")
    }

    @Test
    fun typescriptDefaultParameterExpressionsAreUnitsWithoutNamesOrTypes() {
        val source =
            """
            function scan(text: string, pattern: string = "abc".trim()): boolean {
                return pattern.length > 0;
            }
            """.trimIndent()
        val units = units("typescript", TypescriptParser(), source)

        assertTrue(units.any { it.text.contains("\"abc\".trim()") })
        assertNoNodes(
            units,
            "required_parameter",
            "optional_parameter",
            "formal_parameters",
            "type_annotation",
            "predefined_type",
            "statement_block",
        )
        assertNoDeclarationNames(units, "required_parameter", "optional_parameter", "formal_parameters")
    }

    @Test
    fun expressionsApiUsesTheSameSkipRulesAsTheWorklist() {
        val tree = KotlinParser().parse(SourceFile("Sample.kt", kotlinSource))
        val syntax = ProfileSyntaxModel(SyntaxProfiles.load("kotlin"))
        val units = syntax.expressionUnits(tree)
        val expressions = syntax.expressions(tree).toList()

        assertEquals(
            units.map { Triple(it.node.kind, it.startByte, it.endByte) },
            expressions.map { Triple(it.kind, it.startByte, it.endByte) },
        )
        assertTrue(expressions.none { it.kind in setOf("parameter", "function_body", "user_type", "statements") })
        assertTrue(
            expressions.none {
                it.kind in setOf("simple_identifier", "identifier", "type_identifier") &&
                    it.parent?.kind in setOf("parameter", "function_declaration", "class_declaration")
            },
        )
    }

    private fun units(
        language: String,
        parser: TreeSitterParser,
        text: String,
    ): List<ExpressionUnit> {
        val tree = parser.parse(SourceFile("Sample.$language", text))
        val syntax = ProfileSyntaxModel(SyntaxProfiles.load(language))
        return syntax.expressionUnits(tree)
    }

    private fun assertNoNodes(
        units: List<ExpressionUnit>,
        vararg kinds: String,
    ) {
        val excluded = kinds.toSet()
        assertTrue(
            units.none {
                it.node.kind in excluded
            },
            "unexpected node kinds: ${units.map { it.node.kind }.filter { it in excluded }.distinct()}",
        )
    }

    private fun assertNoDeclarationNames(
        units: List<ExpressionUnit>,
        vararg parentKinds: String,
    ) {
        val parents = parentKinds.toSet()
        val names =
            units.filter { unit ->
                unit.node.kind in setOf("identifier", "simple_identifier", "type_identifier") &&
                    unit.node.parent?.kind in parents
            }
        assertTrue(names.isEmpty(), "declaration names leaked into worklist: ${names.map { it.text }}")
    }
}
