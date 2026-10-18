package dev.cint.core.frontend

import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.TypeOracle
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
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
