package dev.cint.rulepacks.tests.intents.dataaccess

import dev.cint.api.Intent
import dev.cint.api.RulePack
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack

internal fun dataAccess(
    language: String,
    text: String,
    pack: RulePack = DataAccessRulePack(),
): PackAnalysis = analyzePack(language, text, listOf(pack))

internal fun PackAnalysis.candidateTexts(intent: Intent): List<String> =
    candidates(intent).map { input.source.text.substring(it.startByte, it.endByte) }
