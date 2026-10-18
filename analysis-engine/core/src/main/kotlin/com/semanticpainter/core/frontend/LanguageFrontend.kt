package com.semanticpainter.core.frontend

import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.syntax.SyntaxModel
import com.semanticpainter.core.syntax.SyntaxTree
import java.nio.file.Path

data class OracleOptions(
    val enabled: Boolean,
    val timeoutMs: Long,
)

interface LanguageFrontend {
    val language: String
    val extensions: List<String>

    fun parse(file: SourceFile): SyntaxTree

    fun syntax(tree: SyntaxTree): SyntaxModel

    fun oracle(
        root: Path,
        options: OracleOptions,
    ): TypeOracle
}
