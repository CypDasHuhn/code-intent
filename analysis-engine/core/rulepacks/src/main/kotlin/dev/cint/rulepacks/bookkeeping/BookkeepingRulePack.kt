package dev.cint.rulepacks.bookkeeping

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.DeclarationKind
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.SingleIntentPack
import dev.cint.api.View
import dev.cint.api.emit

const val BOOKKEEPING_INTENT: Intent = "BOOKKEEPING"
const val BOOKKEEPING_PACK_ID: String = "bookkeeping"
const val BOOKKEEPING_RULE_ID: String = "$BOOKKEEPING_PACK_ID:bookkeeping"

data class BookkeepingConfig(
    val annotation: String = "Bookkeeping",
    val magicComment: String = "bookkeeping",
) {
    fun withOverrides(values: Map<String, Any?>): BookkeepingConfig = SCHEMA.decode(this, values, BOOKKEEPING_PACK_ID)

    companion object {
        internal val SCHEMA =
            ConfigSchema.of(
                ConfigField.string<BookkeepingConfig>(
                    "annotation",
                    { it.annotation },
                    { config, value -> config.copy(annotation = value) },
                ),
                ConfigField.string<BookkeepingConfig>(
                    "magicComment",
                    { it.magicComment },
                    { config, value -> config.copy(magicComment = value) },
                ),
            )

        fun from(values: Map<String, Any?>): BookkeepingConfig = BookkeepingConfig().withOverrides(values)
    }
}

class BookkeepingRulePack(
    config: BookkeepingConfig = BookkeepingConfig(),
) : SingleIntentPack<BookkeepingConfig>(
        id = BOOKKEEPING_PACK_ID,
        intent = BOOKKEEPING_INTENT,
        languages = setOf("csharp", "javascript", "kotlin", "typescript"),
        config = config,
        schema = BookkeepingConfig.SCHEMA,
        ruleId = BOOKKEEPING_RULE_ID,
    ) {
    override val matchers: List<Matcher> = listOf(Matcher { view -> bookkeeping(view, config) })

    override fun withConfig(config: BookkeepingConfig): SingleIntentPack<BookkeepingConfig> = BookkeepingRulePack(config)
}

private fun bookkeeping(
    view: View,
    config: BookkeepingConfig,
): List<Emit> =
    view
        .declarations()
        .filter { it.kind != DeclarationKind.FUNCTION && it.name != null }
        .flatMap { declaration ->
            val source =
                when {
                    declaration.hasAnnotation(config.annotation) -> "annotation"
                    declaration.hasMagicComment(config.magicComment) -> "comment"
                    else -> return@flatMap emptyList()
                }
            val magicComment = config.magicComment.takeIf { source == "comment" }
            listOf(declaration.emit(BOOKKEEPING_INTENT, source, magicComment = magicComment)) +
                declaration.writes().map { it.emit(BOOKKEEPING_INTENT, source) }
        }
