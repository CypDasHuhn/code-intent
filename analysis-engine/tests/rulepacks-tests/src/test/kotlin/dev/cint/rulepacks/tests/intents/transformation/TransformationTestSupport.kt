package dev.cint.rulepacks.tests.intents.transformation

import dev.cint.api.RulePack
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.transformation.DATA_TRANSFORMATION_INTENT
import dev.cint.rulepacks.intents.transformation.TransformationRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack

internal fun analyze(
    language: String,
    text: String,
    packs: List<RulePack> = listOf(DataAccessRulePack(), TransformationRulePack()),
): PackAnalysis = analyzePack(language, text, packs)

internal class FixedOracle(
    private val fact: TypeFact,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact = fact
}

internal fun PackAnalysis.candidateText(
    text: String,
    index: Int = 0,
): String {
    val candidate = candidates(DATA_TRANSFORMATION_INTENT)[index]
    return text.substring(candidate.startByte, candidate.endByte)
}
