package dev.cint.core.facts.oracle

import dev.cint.core.facts.model.SourceFile

data class TypeFact(
    val fqName: String,
    val source: String = "lsp",
    val confidence: Double = 1.0,
)

interface TypeOracle {
    fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact?
}

object NullOracle : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = null
}

class MapOracle(
    private val types: Map<Int, TypeFact> = emptyMap(),
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = types[byteOffset]
}
