package dev.cint.rulepacks.tests.support

import dev.cint.api.Annotation
import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.View
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.VALIDATION_RULE_ID
import dev.cint.rulepacks.validation.ValidationRulePack

internal typealias ValidationInput = PackInput

internal class NoOpValidationRule : Rule {
    override val id: String = "validation-noop:validation"
    override val owns: Intent = VALIDATION_INTENT
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<Emit> = emptyList()
}

internal class NoOpValidationPack : RulePack {
    override val id: String = "validation-noop"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(NoOpValidationRule())
}

internal fun validationPipeline(input: ValidationInput): List<Annotation> =
    analyzePack(input, listOf(BookkeepingRulePack(), ValidationRulePack())).annotations

internal fun validationSources(input: ValidationInput): List<Annotation> =
    validationPipeline(input).filter { it.role == Role.SOURCE_INTENT && it.rule == VALIDATION_RULE_ID }

internal fun parseValidationInput(
    language: String,
    name: String,
    text: String,
): ValidationInput = parsePackInput(language, text, name)
