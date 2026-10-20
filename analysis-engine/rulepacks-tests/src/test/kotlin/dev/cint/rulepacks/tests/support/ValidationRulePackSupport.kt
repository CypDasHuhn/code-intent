package dev.cint.rulepacks.tests.support

import dev.cint.api.Annotation
import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.View
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxProfiles
import dev.cint.core.syntax.SyntaxTree
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.VALIDATION_RULE_ID
import dev.cint.rulepacks.validation.ValidationRulePack
import dev.cint.treesitter.CsharpParser
import dev.cint.treesitter.JavascriptParser
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import dev.cint.treesitter.TypescriptParser
import java.nio.file.Files
import java.nio.file.Path

internal data class ValidationInput(
    val source: SourceFile,
    val tree: SyntaxTree,
    val syntax: SyntaxModel,
    val config: IntentConfig,
)

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
    RuleEngine().analyze(
        input.source,
        input.tree,
        input.syntax,
        NullOracle,
        input.config,
        listOf(BookkeepingRulePack(), ValidationRulePack()),
    )

internal fun validationSources(input: ValidationInput): List<Annotation> =
    validationPipeline(input).filter { it.role == Role.SOURCE_INTENT && it.rule == VALIDATION_RULE_ID }

internal fun parseValidationInput(
    language: String,
    name: String,
    text: String,
): ValidationInput {
    val source = SourceFile(name, text)
    val config = testConfig(language)
    return when (language) {
        "kotlin" -> {
            ValidationInput(source, KotlinParser().parse(source), KotlinSyntaxModel, config)
        }

        "javascript" -> {
            ValidationInput(
                source,
                JavascriptParser().parse(source),
                ProfileSyntaxModel(SyntaxProfiles.load(language)),
                config,
            )
        }

        "typescript" -> {
            ValidationInput(
                source,
                TypescriptParser().parse(source),
                ProfileSyntaxModel(SyntaxProfiles.load(language)),
                config,
            )
        }

        "csharp" -> {
            ValidationInput(
                source,
                CsharpParser().parse(source),
                ProfileSyntaxModel(SyntaxProfiles.load(language)),
                config,
            )
        }

        else -> {
            error("unsupported language: $language")
        }
    }
}

internal fun validationFixture(relative: String): String {
    val path = Path.of("..", "..", "testing-grounds", relative)
    check(Files.exists(path)) { "missing fixture $path" }
    return Files.readString(path)
}
