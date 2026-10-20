package dev.cint.treesitter.view

import dev.cint.api.DeclarationKind
import dev.cint.api.Emit
import dev.cint.api.GuardKind
import dev.cint.api.ScopeKind
import dev.cint.api.WriteKind
import dev.cint.core.facts.flow.ControlFlowBuilder
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.runtime.engine.AnalysisContext
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxProfiles
import dev.cint.core.syntax.SyntaxTree
import dev.cint.treesitter.CsharpParser
import dev.cint.treesitter.JavascriptParser
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import dev.cint.treesitter.TypescriptParser
import dev.cint.treesitter.support.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private data class LanguageFixture(
    val language: String,
    val fileName: String,
    val text: String,
    val callName: String,
    val fieldWrites: Set<String>,
    val localWrite: String,
)

class SemanticFactsLanguageTest {
    private val fixtures =
        listOf(
            LanguageFixture(
                language = "kotlin",
                fileName = "Sample.kt",
                text =
                    """
                    class Box {
                        // cint:bookkeeping
                        @field:Bookkeeping
                        var count = 0

                        fun run() {
                            count++
                            count = 2
                            var count = 1
                            count = 3
                            if (count > 0) {
                                return
                            }
                            while (count > 0) {
                                return
                            }
                            foo(bar { })
                            logger.info("x") {
                                "detail"
                            }
                        }
                    }
                    """.trimIndent(),
                callName = "info",
                fieldWrites = setOf("count++", "count = 2"),
                localWrite = "count = 3",
            ),
            LanguageFixture(
                language = "javascript",
                fileName = "Sample.js",
                text =
                    """
                    class Box {
                        // cint:bookkeeping
                        @Bookkeeping
                        count = 0;

                        run() {
                            this.count++;
                            this.count = 2;
                            let count = 1;
                            count = 3;
                            if (this.count > 0) {
                                return;
                            }
                            while (this.count > 0) {
                                return;
                            }
                            setTimeout(() => {}, 100);
                            logger.info("x", () => "detail");
                        }
                    }
                    """.trimIndent(),
                callName = "info",
                fieldWrites = setOf("this.count++", "this.count = 2"),
                localWrite = "count = 3",
            ),
            LanguageFixture(
                language = "typescript",
                fileName = "Sample.ts",
                text =
                    """
                    class Box {
                        // cint:bookkeeping
                        @Bookkeeping
                        count: number = 0;

                        run(): void {
                            this.count++;
                            this.count = 2;
                            let count: number = 1;
                            count = 3;
                            if (this.count > 0) {
                                return;
                            }
                            while (this.count > 0) {
                                return;
                            }
                            setTimeout(() => {}, 100);
                            logger.info("x", () => "detail");
                        }
                    }
                    """.trimIndent(),
                callName = "info",
                fieldWrites = setOf("this.count++", "this.count = 2"),
                localWrite = "count = 3",
            ),
            LanguageFixture(
                language = "csharp",
                fileName = "Sample.cs",
                text =
                    """
                    class Box {
                        // cint:bookkeeping
                        [Bookkeeping]
                        private int count = 0;

                        void Run() {
                            count++;
                            count = 2;
                            int count = 1;
                            count = 3;
                            if (count > 0) {
                                return;
                            }
                            while (count > 0) {
                                return;
                            }
                            setTimeout(() => {}, 100);
                            logger.Info("x", () => "detail");
                        }
                    }
                    """.trimIndent(),
                callName = "Info",
                fieldWrites = setOf("count++", "count = 2"),
                localWrite = "count = 3",
            ),
        )

    @Test
    fun profilesExposeDeclarationWriteGuardAndClassScopeFacts() {
        for (fixture in fixtures) {
            val source = SourceFile(fixture.fileName, fixture.text)
            val tree = parse(fixture.language, source)
            val syntax = syntax(fixture.language)
            val flow = ControlFlowBuilder(tree, syntax).build()
            val context =
                AnalysisContext(
                    source,
                    tree,
                    syntax,
                    flow,
                    NullOracle,
                    testConfig(fixture.language),
                    AnnotationStore(),
                )
            val view = context.view("BOOKKEEPING", emptySet())

            val field = view.declarations().single { it.name == "count" && it.kind == DeclarationKind.PROPERTY }
            val local = view.declarations().single { it.name == "count" && it.kind == DeclarationKind.VARIABLE }

            assertTrue(field.hasAnnotation("Bookkeeping"), "${fixture.language} annotation")
            assertEquals(listOf("Bookkeeping"), field.annotations, "${fixture.language} normalized annotation")
            assertTrue(field.hasMagicComment("bookkeeping"), "${fixture.language} magic comment")
            assertTrue(field.isProperty, "${fixture.language} field is property")
            assertTrue(local.isVariable, "${fixture.language} local is variable")
            assertEquals(ScopeKind.CLASS, field.scope?.kind, "${fixture.language} field scope")
            assertEquals("class_declaration", field.scope?.owner?.kind, "${fixture.language} class owner")
            assertTrue(view.scopes().any { it.kind == ScopeKind.CLASS }, "${fixture.language} class scope")
            assertEquals(fixture.fieldWrites, field.writes().map { it.node.text }.toSet(), "${fixture.language} field writes")
            assertEquals(setOf(fixture.localWrite), local.writes().map { it.node.text }.toSet(), "${fixture.language} local writes")
            assertTrue(field.writes().any { it.kind == WriteKind.INCREMENT }, "${fixture.language} increment write")
            assertTrue(field.writes().any { it.kind == WriteKind.ASSIGNMENT }, "${fixture.language} assignment write")

            val conditional = view.guards().single { it.kind == GuardKind.CONDITIONAL }
            val loop = view.guards().single { it.kind == GuardKind.LOOP }
            assertNotNull(conditional.condition, "${fixture.language} if condition")
            assertTrue(conditional.exits.isNotEmpty(), "${fixture.language} if exits")
            assertNotNull(loop.condition, "${fixture.language} loop condition")
            assertTrue(loop.exits.isNotEmpty(), "${fixture.language} loop exits")

            val call = view.calls().single { it.callee == fixture.callName }
            assertNotNull(call.path, "${fixture.language} call path")
            assertNotNull(call.trailingLambda, "${fixture.language} trailing lambda")
            assertTrue(call.trailingLambda!!.text.contains("detail"), "${fixture.language} trailing lambda text")
        }
    }

    @Test
    fun nonTrailingLambdaArgumentsAreNotReported() {
        val cases =
            listOf(
                "kotlin" to
                    """
                    fun f() {
                        foo(bar { })
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    function f() {
                        setTimeout(() => {}, 100);
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f(): void {
                        setTimeout(() => {}, 100);
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F() {
                            setTimeout(() => {}, 100);
                        }
                    }
                    """.trimIndent(),
            )
        for ((language, text) in cases) {
            val source = SourceFile("Sample", text)
            val tree = parse(language, source)
            val syntax = syntax(language)
            val flow = ControlFlowBuilder(tree, syntax).build()
            val context =
                AnalysisContext(
                    source,
                    tree,
                    syntax,
                    flow,
                    NullOracle,
                    testConfig(language),
                    AnnotationStore(),
                )
            val view = context.view("BOOKKEEPING", emptySet())
            val name = if (language == "kotlin") "foo" else "setTimeout"
            val outer = view.calls().single { it.callee == name }
            assertTrue(outer.trailingLambda == null, "$language incorrectly reported a trailing lambda")
        }
    }

    @Test
    fun objectLiteralsAndCreationsAreNotClassScopes() {
        val cases =
            listOf(
                "javascript" to
                    """
                    function f() {
                        const value = {};
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f(): void {
                        const value = {};
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F() {
                            var value = new Box();
                        }
                    }
                    """.trimIndent(),
            )
        for ((language, text) in cases) {
            val source = SourceFile("Sample", text)
            val tree = parse(language, source)
            val syntax = syntax(language)
            val flow = ControlFlowBuilder(tree, syntax).build()
            val context =
                AnalysisContext(
                    source,
                    tree,
                    syntax,
                    flow,
                    NullOracle,
                    testConfig(language),
                    AnnotationStore(),
                )
            val view = context.view("BOOKKEEPING", emptySet())
            assertTrue(
                view.scopes().none {
                    it.kind == ScopeKind.CLASS &&
                        (it.node.kind == "object" || it.node.kind == "object_creation_expression")
                },
                "$language classified an object expression as a class scope",
            )
        }
    }

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
    fun typeLiteralsAndNamespaceBodiesAreNotClassScopes() {
        val typescript = SourceFile("Sample.ts", "type T = { value: string }")
        val tsTree = parse("typescript", typescript)
        val tsScopes = view("typescript", typescript).scopes()
        val typeLiteral = tsTree.root.descendants("object_type").single()
        assertTrue(
            tsScopes.none {
                it.kind == ScopeKind.CLASS &&
                    it.node.startByte == typeLiteral.startByte &&
                    it.node.endByte == typeLiteral.endByte
            },
            "TypeScript object type was classified as a class scope",
        )

        val csharp = SourceFile("Sample.cs", "namespace N { class C { int value = 0; } }")
        val csTree = parse("csharp", csharp)
        val csView = view("csharp", csharp)
        val namespaceBody =
            csTree.root
                .descendants("namespace_declaration")
                .single()
                .children
                .single { it.kind == "declaration_list" }
        assertTrue(
            csView.scopes().none {
                it.kind == ScopeKind.CLASS &&
                    it.node.startByte == namespaceBody.startByte &&
                    it.node.endByte == namespaceBody.endByte
            },
            "C# namespace body was classified as a class scope",
        )
        assertTrue(csView.scopes().any { it.kind == ScopeKind.CLASS }, "C# class scope missing")
    }

    @Test
    fun programRootIsFileScopeForJavascriptAndTypescript() {
        val sources =
            mapOf(
                "javascript" to "let top = 0;\ntop = 1;",
                "typescript" to "let top: number = 0;\ntop = 1;",
            )
        for ((language, text) in sources) {
            val view = view(language, SourceFile("Sample", text))
            val declaration = view.declarations().single { it.name == "top" }
            assertEquals(ScopeKind.FILE, declaration.scope?.kind, "$language declaration scope")
            assertEquals(DeclarationKind.PROPERTY, declaration.kind, "$language top-level kind")
            assertEquals(ScopeKind.FILE, view.enclosingScope(declaration.node)?.kind, "$language enclosing scope")
            assertTrue(
                view.scopes().any {
                    it.kind == ScopeKind.FILE &&
                        it.node.startByte == declaration.scope?.node?.startByte &&
                        it.node.endByte == declaration.scope?.node?.endByte
                },
                "$language root FILE scope is not exposed",
            )
        }
    }

    @Test
    fun nestedNonTrailingLambdaDoesNotPromoteTheCallSite() {
        val source = SourceFile("Sample.js", "function f() { foo()(() => {}, 2); }")
        val view = view("javascript", source)
        val call = view.calls().single { it.node.text == "foo()" }

        assertEquals("foo()", call.node.text)
        assertEquals("foo()", call.site.text)
        val emit = Emit.call("LOGGING", call)
        assertEquals(call.node.span(), emit.span)
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

    @Test
    fun kotlinForLoopIsAGuard() {
        val source = SourceFile("Sample.kt", "fun f() { for (item in list) { return } }")
        val view = view("kotlin", source)
        val guard = view.guards().single { it.kind == GuardKind.LOOP }

        assertEquals("for_statement", guard.node.kind)
        assertTrue(guard.exits.isNotEmpty())
    }

    private fun view(
        language: String,
        source: SourceFile,
    ): dev.cint.api.View {
        val tree = parse(language, source)
        val syntax = syntax(language)
        val flow = ControlFlowBuilder(tree, syntax).build()
        val context =
            AnalysisContext(
                source,
                tree,
                syntax,
                flow,
                NullOracle,
                testConfig(language),
                AnnotationStore(),
            )
        return context.view("BOOKKEEPING", emptySet())
    }

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
        if (language == "kotlin") KotlinSyntaxModel else ProfileSyntaxModel(SyntaxProfiles.load(language))
}
