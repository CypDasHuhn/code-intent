package dev.cint.frontend

import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.treesitter.JavascriptParser
import dev.cint.treesitter.SyntaxProfiles
import dev.cint.treesitter.TypescriptParser
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
