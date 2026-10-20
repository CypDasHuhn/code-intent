package dev.cint.rulepacks.bookkeeping

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.PropertyFact
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.support.configString
import dev.cint.rulepacks.support.configStringSets
import dev.cint.rulepacks.support.configStrings

const val BOOKKEEPING_INTENT: Intent = "BOOKKEEPING"
const val BOOKKEEPING_PACK_ID: String = "bookkeeping"
const val BOOKKEEPING_RULE_ID: String = "$BOOKKEEPING_PACK_ID:bookkeeping"

data class BookkeepingConfig(
    val annotation: String = "Bookkeeping",
    val magicComment: String = "bookkeeping",
    val annotationKinds: Set<String> = setOf("annotation", "decorator", "attribute"),
    val modifierKinds: Set<String> = setOf("modifiers", "attribute_list"),
    val propertyKinds: Set<String> =
        setOf("property_declaration", "variable_declarator", "public_field_definition", "field_definition"),
    val declarationWrappersByRoot: Map<String, Set<String>> =
        mapOf(
            "source_file" to emptySet(),
            "program" to setOf("lexical_declaration", "export_statement"),
            "compilation_unit" to
                setOf("variable_declaration", "local_declaration_statement", "field_declaration"),
        ),
) {
    fun withOverrides(values: Map<String, Any?>): BookkeepingConfig {
        val errors = mutableListOf<String>()
        var updated = this
        for ((rawKey, rawValue) in values) {
            val path = "$BOOKKEEPING_PACK_ID.$rawKey"
            updated =
                when (rawKey) {
                    "annotation" -> {
                        updated.copy(annotation = configString(rawValue, path, errors) ?: updated.annotation)
                    }

                    "magicComment" -> {
                        updated.copy(magicComment = configString(rawValue, path, errors) ?: updated.magicComment)
                    }

                    "annotationKinds" -> {
                        updated.copy(
                            annotationKinds = configStrings(rawValue, path, errors) ?: updated.annotationKinds,
                        )
                    }

                    "modifierKinds" -> {
                        updated.copy(modifierKinds = configStrings(rawValue, path, errors) ?: updated.modifierKinds)
                    }

                    "propertyKinds" -> {
                        updated.copy(propertyKinds = configStrings(rawValue, path, errors) ?: updated.propertyKinds)
                    }

                    "declarationWrappersByRoot" -> {
                        updated.copy(
                            declarationWrappersByRoot =
                                configStringSets(rawValue, path, errors) ?: updated.declarationWrappersByRoot,
                        )
                    }

                    else -> {
                        errors += "$path: unknown key"
                        updated
                    }
                }
        }
        if (errors.isNotEmpty()) throw RulePackConfigException(errors.joinToString("; "))
        return updated
    }

    companion object {
        fun from(values: Map<String, Any?>): BookkeepingConfig = BookkeepingConfig().withOverrides(values)
    }
}

class BookkeepingRulePack(
    private val config: BookkeepingConfig = BookkeepingConfig(),
) : ConfigurableRulePack {
    override val id: String = BOOKKEEPING_PACK_ID
    override val apiVersion: Int = CINT_API_VERSION
    override val languages: Set<String> = setOf("csharp", "javascript", "kotlin", "typescript", "vue")

    override fun configure(values: Map<String, Any?>): RulePack = BookkeepingRulePack(config.withOverrides(values))

    override fun rules(): List<Rule> = listOf(BookkeepingRule(config))
}

private class BookkeepingRule(
    private val config: BookkeepingConfig,
) : Rule {
    override val id: String = BOOKKEEPING_RULE_ID
    override val owns: Intent = BOOKKEEPING_INTENT
    override val reads: Set<Intent> = emptySet()

    private val annotationPattern = Regex("""\.${Regex.escape(config.annotation)}$""")
    private val magicCommentPattern =
        Regex("""cint\s*:\s*${Regex.escape(config.magicComment)}""", RegexOption.IGNORE_CASE)

    override fun run(view: View): List<Emit> {
        val references = view.references()
        val properties = view.properties()
        val wrapperKinds =
            properties.firstOrNull()?.let { config.declarationWrappersByRoot[it.node.rootKind()] }.orEmpty()
        val result = mutableListOf<Emit>()

        for (declaration in properties) {
            val name = declaration.name ?: continue
            val nameNode = declaration.nameNode ?: continue
            val source = declarationSource(declaration.node, wrapperKinds) ?: continue

            result += declarationEmit(declaration, source)

            val scope = declarationHolder(declaration.node, wrapperKinds) ?: continue
            val seen = mutableSetOf<String>()
            for (reference in references[name].orEmpty()) {
                if (reference.startByte < scope.startByte || reference.endByte > scope.endByte) continue
                if (reference.startByte == nameNode.startByte && reference.endByte == nameNode.endByte) continue
                val write = view.writes(reference) ?: continue
                val key = "${write.startByte}:${write.endByte}"
                if (!seen.add(key)) continue
                result += write.rawEmit(source.source)
            }
        }

        return result
    }

    private fun declarationEmit(
        declaration: PropertyFact,
        source: DeclarationSource,
    ): Emit {
        val start = source.startNode ?: return declaration.node.rawEmit(source.source)
        return Emit(
            intents = setOf(BOOKKEEPING_INTENT),
            evidence = "${start.text} ${declaration.node.text}".take(160),
            span = Span(start.startLine, start.startCol, declaration.node.endLine, declaration.node.endCol),
            source = source.source,
            confidence = 1.0,
            startByte = start.startByte,
            endByte = declaration.node.endByte,
        )
    }

    private fun declarationSource(
        declaration: SyntaxNode,
        wrapperKinds: Set<String>,
    ): DeclarationSource? {
        if (hasAnnotation(declaration, wrapperKinds)) return DeclarationSource("annotation", null)
        val comment = precedingMagicComment(declaration, wrapperKinds) ?: return null
        return DeclarationSource("comment", comment)
    }

    private fun hasAnnotation(
        declaration: SyntaxNode,
        wrapperKinds: Set<String>,
    ): Boolean {
        var node: SyntaxNode? = declaration
        while (node != null) {
            val container = node.children.firstOrNull { it.kind in config.modifierKinds }
            val annotations =
                container?.children?.filter { it.kind in config.annotationKinds }
                    ?: node.children.filter { it.kind in config.annotationKinds }
            if (annotations.any { isAnnotation(it.text) }) return true
            val ascend = node.kind in wrapperKinds || node.kind in config.propertyKinds
            node = if (ascend) node.parent else null
        }
        return false
    }

    private fun isAnnotation(text: String): Boolean {
        val normalized = normalizeAnnotation(text.trim())
        return normalized == config.annotation || annotationPattern.containsMatchIn(normalized)
    }

    private fun precedingMagicComment(
        declaration: SyntaxNode,
        wrapperKinds: Set<String>,
    ): SyntaxNode? {
        var anchor = declaration
        var wrapper = anchor.parent
        while (wrapper != null && wrapper.kind in wrapperKinds) {
            anchor = wrapper
            wrapper = anchor.parent
        }
        val parent = anchor.parent ?: return null
        return magicCommentBefore(parent, anchor)
    }

    private fun magicCommentBefore(
        parent: SyntaxNode,
        anchor: SyntaxNode,
    ): SyntaxNode? {
        val index =
            parent.children.indexOfFirst {
                it.startByte == anchor.startByte && it.endByte == anchor.endByte
            }
        if (index <= 0) return null
        var current = index - 1
        while (current >= 0) {
            val sibling = parent.children[current]
            if (!isComment(sibling)) break
            if (magicCommentPattern.containsMatchIn(sibling.text)) return sibling
            current--
        }
        return null
    }

    private fun isComment(node: SyntaxNode): Boolean = node.text.trimStart().let { it.startsWith("//") || it.startsWith("/*") }

    private fun declarationHolder(
        node: SyntaxNode,
        wrapperKinds: Set<String>,
    ): SyntaxNode? {
        var anchor = node
        var holder = node.parent
        while (holder != null && holder.kind in wrapperKinds) {
            anchor = holder
            holder = anchor.parent
        }
        return holder
    }
}

private data class DeclarationSource(
    val source: String,
    val startNode: SyntaxNode?,
)

private fun SyntaxNode.rawEmit(source: String): Emit =
    Emit(
        intents = setOf(BOOKKEEPING_INTENT),
        evidence = text.replace("\n", " ").take(160),
        span = span(),
        source = source,
        confidence = 1.0,
        startByte = startByte,
        endByte = endByte,
    )

private fun SyntaxNode.rootKind(): String {
    var node = this
    while (true) {
        node = node.parent ?: return node.kind
    }
}

private fun normalizeAnnotation(text: String): String =
    text
        .removePrefix("@")
        .replace(Regex("""^(field|property|get|set|receiver|param|setparam|delegate)\s*:\s*"""), "")
        .replace(Regex("""\s+"""), "")
        .removePrefix("[")
        .removeSuffix("]")
