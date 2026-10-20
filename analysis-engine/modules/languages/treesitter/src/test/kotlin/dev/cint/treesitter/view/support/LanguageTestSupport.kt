package dev.cint.treesitter.view.support

import dev.cint.core.facts.flow.ControlFlowBuilder
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.runtime.engine.AnalysisContext
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.core.test.testConfig
import dev.cint.treesitter.CsharpParser
import dev.cint.treesitter.JavascriptParser
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import dev.cint.treesitter.SyntaxProfiles
import dev.cint.treesitter.TypescriptParser

internal data class LanguageFixture(
    val language: String,
    val fileName: String,
    val text: String,
    val callName: String,
    val fieldWrites: Set<String>,
    val localWrite: String,
)

internal val fixtures =
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

internal fun view(
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

internal fun parse(
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

internal fun syntax(language: String): SyntaxModel =
    if (language == "kotlin") KotlinSyntaxModel else ProfileSyntaxModel(SyntaxProfiles.load(language))
