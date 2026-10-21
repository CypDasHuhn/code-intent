package dev.cint.test.fixtures

import dev.cint.api.RulePack
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.logic.BoolCombinatoricsRulePack
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.normalization.NormalizationRulePack
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.intents.structural.ChainingRulePack
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
import dev.cint.rulepacks.intents.structural.ReturnValueRulePack
import dev.cint.rulepacks.intents.transformation.TransformationRulePack
import dev.cint.rulepacks.logging.LoggingRulePack
import dev.cint.rulepacks.validation.ValidationRulePack

fun testPacks(): List<RulePack> =
    listOf(
        LoggingRulePack(),
        ErrorHandlingRulePack(),
        BookkeepingRulePack(),
        ValidationRulePack(),
        MutationRulePack(),
        NormalizationRulePack(),
        DataAccessRulePack(),
        ServiceRulePack(),
        BoolCombinatoricsRulePack(),
        TransformationRulePack(),
        ReturnValueRulePack(),
        ChainingRulePack(),
        LocalCopyRulePack(),
    )
