package com.semanticpainter.frontend

import com.semanticpainter.core.frontend.LanguageFrontend
import com.semanticpainter.core.frontend.OracleOptions
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.syntax.ProfileSyntaxModel
import com.semanticpainter.core.syntax.SyntaxModel
import com.semanticpainter.core.syntax.SyntaxProfiles
import com.semanticpainter.core.syntax.SyntaxTree
import com.semanticpainter.treesitter.JavascriptParser
import com.semanticpainter.treesitter.TypescriptParser
import java.nio.file.Path

object JavascriptFrontend : LanguageFrontend {
    override val language: String = "javascript"
    override val extensions: List<String> = listOf(".js", ".jsx", ".mjs", ".cjs")

    private val model: SyntaxModel = ProfileSyntaxModel(SyntaxProfiles.load(language))

    override fun parse(file: SourceFile): SyntaxTree = JavascriptParser().parse(file)

    override fun syntax(tree: SyntaxTree): SyntaxModel = model

    override fun oracle(
        root: Path,
        options: OracleOptions,
    ): TypeOracle = NullOracle
}

object TypescriptFrontend : LanguageFrontend {
    override val language: String = "typescript"
    override val extensions: List<String> = listOf(".ts", ".mts", ".cts")

    private val model: SyntaxModel = ProfileSyntaxModel(SyntaxProfiles.load(language))

    override fun parse(file: SourceFile): SyntaxTree = TypescriptParser().parse(file)

    override fun syntax(tree: SyntaxTree): SyntaxModel = model

    override fun oracle(
        root: Path,
        options: OracleOptions,
    ): TypeOracle = NullOracle
}
