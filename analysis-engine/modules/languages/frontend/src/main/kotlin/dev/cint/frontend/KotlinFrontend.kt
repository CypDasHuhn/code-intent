package dev.cint.frontend

import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.lsp.KotlinLspOracle
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import java.nio.file.Path

object KotlinFrontend : LanguageFrontend {
    override val language: String = "kotlin"
    override val extensions: List<String> = listOf(".kt", ".kts")

    override fun parse(file: SourceFile): SyntaxTree = KotlinParser().parse(file)

    override fun syntax(tree: SyntaxTree): SyntaxModel = KotlinSyntaxModel

    override fun oracle(
        root: Path,
        options: OracleOptions,
    ): TypeOracle =
        if (options.enabled) {
            KotlinLspOracle.connect(root, options.timeoutMs)
        } else {
            NullOracle
        }
}

object Frontends {
    private val all: List<LanguageFrontend> = listOf(KotlinFrontend, JavascriptFrontend, TypescriptFrontend, VueFrontend, CsharpFrontend)

    fun languages(): List<String> = all.map { it.language }

    fun forLanguage(language: String): LanguageFrontend? = all.firstOrNull { it.language.equals(language, ignoreCase = true) }

    fun require(language: String): LanguageFrontend = forLanguage(language) ?: error("unsupported language: $language")
}
