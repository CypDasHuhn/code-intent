package dev.cint.rulepacks.tests.intents.dataaccess

import dev.cint.api.Intent
import dev.cint.api.RulePack
import dev.cint.core.facts.oracle.MapOracle
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput

internal fun dataAccess(
    language: String,
    text: String,
    pack: RulePack = DataAccessRulePack(),
): PackAnalysis = analyzePack(language, text, listOf(pack))

internal fun PackAnalysis.candidateTexts(intent: Intent): List<String> =
    candidates(intent).map { input.source.text.substring(it.startByte, it.endByte) }

internal fun typedDataAccess(
    language: String,
    text: String,
    pack: RulePack = DataAccessRulePack(),
    types: Map<String, String>,
): PackAnalysis {
    val input = parsePackInput(language, text)
    val facts =
        input.syntax
            .calls(input.tree)
            .mapNotNull { call ->
                val receiver = call.receiver ?: return@mapNotNull null
                val fqName = types[receiver.text] ?: return@mapNotNull null
                input.syntax.receiverOffset(receiver) to TypeFact(fqName)
            }.toMap()
    return analyzePack(input, listOf(pack), MapOracle(facts))
}
