package dev.cint.frontend

import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.treesitter.CsharpParser
import dev.cint.treesitter.SyntaxProfiles
import java.nio.file.Path

object CsharpFrontend : LanguageFrontend {
    override val language: String = "csharp"
    override val extensions: List<String> = listOf(".cs")

    private val model: SyntaxModel = ProfileSyntaxModel(SyntaxProfiles.load(language))

    override fun parse(file: SourceFile): SyntaxTree = CsharpParser().parse(file)

    override fun syntax(tree: SyntaxTree): SyntaxModel = model

    override fun oracle(
        root: Path,
        options: OracleOptions,
    ): TypeOracle = NullOracle
}
