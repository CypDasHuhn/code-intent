package com.semanticpainter.frontend

import com.semanticpainter.core.frontend.LanguageFrontend
import com.semanticpainter.core.frontend.OracleOptions
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.syntax.SyntaxModel
import com.semanticpainter.core.syntax.SyntaxTree
import com.semanticpainter.lsp.KotlinLspOracle
import com.semanticpainter.treesitter.KotlinParser
import com.semanticpainter.treesitter.KotlinSyntaxModel
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
    private val all: List<LanguageFrontend> = listOf(KotlinFrontend, JavascriptFrontend, TypescriptFrontend)

    fun forLanguage(language: String): LanguageFrontend? = all.firstOrNull { it.language.equals(language, ignoreCase = true) }

    fun require(language: String): LanguageFrontend = forLanguage(language) ?: error("unsupported language: $language")
}
