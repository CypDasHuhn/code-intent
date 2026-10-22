package dev.cint.rulepacks.tests.intents.normalization

import dev.cint.api.RulePack
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.intents.normalization.NORMALIZATION_INTENT
import dev.cint.rulepacks.intents.normalization.NormalizationRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack

internal class FixedOracle(
    private val fact: TypeFact,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact = fact
}

internal fun analyze(
    language: String,
    text: String,
    packs: List<RulePack> = listOf(NormalizationRulePack()),
    oracle: TypeOracle = NullOracle,
): PackAnalysis = analyzePack(language, text, packs, oracle)

internal fun PackAnalysis.candidateText(
    text: String,
    index: Int = 0,
): String {
    val candidate = candidates(NORMALIZATION_INTENT)[index]
    return text.substring(candidate.startByte, candidate.endByte)
}
