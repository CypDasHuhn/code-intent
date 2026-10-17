package com.semanticpainter.core.oracle

import com.semanticpainter.core.model.SourceFile

data class TypeFact(
    val fqName: String,
    val source: String = "lsp",
    val confidence: Double = 1.0,
)

data class SymbolFact(
    val fqName: String,
    val kind: String,
    val source: String = "lsp",
    val confidence: Double = 1.0,
)

interface TypeOracle {
    fun typeAt(file: SourceFile, byteOffset: Int): TypeFact?

    fun definitionAt(file: SourceFile, byteOffset: Int): SymbolFact?
}

object NullOracle : TypeOracle {
    override fun typeAt(file: SourceFile, byteOffset: Int): TypeFact? = null

    override fun definitionAt(file: SourceFile, byteOffset: Int): SymbolFact? = null
}

class MapOracle(
    private val types: Map<Int, TypeFact> = emptyMap(),
    private val symbols: Map<Int, SymbolFact> = emptyMap(),
) : TypeOracle {
    override fun typeAt(file: SourceFile, byteOffset: Int): TypeFact? = types[byteOffset]

    override fun definitionAt(file: SourceFile, byteOffset: Int): SymbolFact? = symbols[byteOffset]
}
