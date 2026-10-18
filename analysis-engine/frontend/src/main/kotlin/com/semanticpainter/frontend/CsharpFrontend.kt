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
import com.semanticpainter.treesitter.CsharpParser
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
