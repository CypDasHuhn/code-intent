package dev.cint.frontend

import dev.cint.api.SyntaxNode
import dev.cint.core.frontend.LanguageFrontend
import dev.cint.core.frontend.OracleOptions
import dev.cint.core.frontend.SplitHandler
import dev.cint.core.frontend.SplitRegion
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.NullOracle
import dev.cint.core.oracle.TypeOracle
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxProfiles
import dev.cint.core.syntax.SyntaxTree
import dev.cint.treesitter.VueParser
import java.nio.file.Path

object VueFrontend : LanguageFrontend, SplitHandler {
    override val language: String = "vue"
    override val extensions: List<String> = listOf(".vue")

    private val model: SyntaxModel = ProfileSyntaxModel(SyntaxProfiles.load(language))

    override fun parse(file: SourceFile): SyntaxTree = VueParser().parse(file)

    override fun syntax(tree: SyntaxTree): SyntaxModel = model

    override fun oracle(
        root: Path,
        options: OracleOptions,
    ): TypeOracle = NullOracle

    override fun regions(file: SourceFile): List<SplitRegion> {
        val tree = VueParser().parse(file)
        return tree.root
            .descendants("script_element")
            .mapNotNull { element ->
                val startTag = element.child("start_tag") ?: return@mapNotNull null
                if (hasAttribute(startTag, "src")) return@mapNotNull null
                val raw = element.child("raw_text") ?: return@mapNotNull null
                if (raw.text.isBlank()) return@mapNotNull null
                SplitRegion(
                    language = scriptLanguage(startTag),
                    text = raw.text,
                    startByte = raw.startByte,
                    startLine = raw.startLine,
                    startCol = raw.startCol,
                )
            }.toList()
    }

    private fun scriptLanguage(startTag: SyntaxNode): String =
        when (attributeValue(startTag, "lang")?.lowercase()) {
            "ts", "tsx" -> "typescript"
            else -> "javascript"
        }

    private fun hasAttribute(
        startTag: SyntaxNode,
        name: String,
    ): Boolean = startTag.descendants("attribute").any { attributeName(it) == name }

    private fun attributeValue(
        startTag: SyntaxNode,
        name: String,
    ): String? {
        val attribute =
            startTag
                .descendants("attribute")
                .firstOrNull { attributeName(it) == name }
                ?: return null
        val value =
            attribute.children.firstOrNull {
                it.kind == "quoted_attribute_value" || it.kind == "attribute_value"
            } ?: return null
        return value.text.trim('"', '\'', ' ')
    }

    private fun attributeName(attribute: SyntaxNode): String? = attribute.children.firstOrNull { it.kind == "attribute_name" }?.text
}
