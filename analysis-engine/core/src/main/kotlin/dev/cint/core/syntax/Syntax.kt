package dev.cint.core.syntax

import dev.cint.api.SyntaxNode
import dev.cint.core.model.SourceFile

interface SyntaxTree {
    val source: SourceFile
    val root: SyntaxNode
}
