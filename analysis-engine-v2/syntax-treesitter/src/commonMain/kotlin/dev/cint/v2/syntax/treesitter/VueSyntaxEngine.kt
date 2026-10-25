package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

internal class VueSyntaxEngine : SyntaxEngine {
    override val language: String = "vue"

    private val javascript by lazy { createVueScriptEngine("javascript") }
    private val typescript by lazy { createVueScriptEngine("typescript") }

    override fun parse(
        content: ByteArray,
        previous: SyntaxTree?,
        cancellation: CancellationToken,
    ): ContractResult<SyntaxTree> {
        if (cancellation.isCancelled) {
            return ResultFactory.failure(ContractError.Cancelled("SyntaxEngine.parse"))
        }
        val text = content.decodeToString()
        val region = scriptRegion(text)
        val engine = if (region?.typescript == true) typescript else javascript
        val scriptBytes = region?.text?.encodeToByteArray() ?: ByteArray(0)
        val parsed =
            when (val result = engine.parse(scriptBytes, null, cancellation)) {
                is dev.cint.contracts.util.Success -> result.contents
                is dev.cint.contracts.util.Failure -> return result
            }
        val start = region?.start ?: 0
        val baseLine = lineAt(text, start)
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val baseColumn = start - lineStart
        val shifted = parsed.nodes.mapValues { (_, node) -> node.shifted(node.startByte, node.endByte, baseLine, baseColumn, start) }
        return ResultFactory.success(
            SyntaxTree(
                rootId = parsed.rootId,
                nodes = shifted,
                language = "vue",
                version = "vue-script/${parsed.version}",
                contentHash = ContentHash.compute(content).value,
            ),
        )
    }

    override fun diff(
        previous: SyntaxTree,
        next: SyntaxTree,
    ): ChangedRanges =
        if (previous.contentHash == next.contentHash) ChangedRanges.EMPTY else ChangedRanges.WHOLE_FILE

    override fun nodeHash(
        tree: SyntaxTree,
        nodeId: String,
    ): ContractResult<String> =
        if (tree.language == language) {
            TreeAlgorithms.nodeHash(tree, nodeId)
        } else {
            ResultFactory.failure(
                ContractError.InvalidInput(
                    "tree language '${tree.language}' does not match engine '$language'",
                    tree.language,
                ),
            )
        }

    override fun facts(
        tree: SyntaxTree,
        queryIds: List<String>,
        cancellation: CancellationToken,
    ): ContractResult<FactBatch> {
        if (cancellation.isCancelled) {
            return ResultFactory.failure(ContractError.Cancelled("SyntaxEngine.facts"))
        }
        if (tree.language != language) {
            return ResultFactory.failure(
                ContractError.InvalidInput(
                    "tree language '${tree.language}' does not match engine '$language'",
                    tree.language,
                ),
            )
        }
        return SyntaxFactExtractor.extract(tree, queryIds)
    }

    private fun SyntaxNode.shifted(
        relativeStart: Int,
        relativeEnd: Int,
        baseLine: Int,
        baseColumn: Int,
        offset: Int,
    ): SyntaxNode {
        val startLine = baseLine + startLine
        val endLine = baseLine + endLine
        return copy(
            startByte = offset + relativeStart,
            endByte = offset + relativeEnd,
            startLine = startLine,
            endLine = endLine,
            startCol = if (startLine == baseLine) baseColumn + startCol else startCol,
            endCol = if (endLine == baseLine) baseColumn + endCol else endCol,
        )
    }

    private data class ScriptRegion(
        val start: Int,
        val text: String,
        val typescript: Boolean,
    )

    private fun scriptRegion(text: String): ScriptRegion? {
        val opening = SCRIPT_OPEN.find(text) ?: return null
        val afterTag = opening.range.last + 1
        val closing = text.indexOf("</script", afterTag).let { if (it < 0) text.length else it }
        return ScriptRegion(
            start = afterTag,
            text = text.substring(afterTag, closing),
            typescript = opening.groupValues[1].contains("ts", ignoreCase = true),
        )
    }

    private fun lineAt(text: String, offset: Int): Int =
        text.take(offset.coerceIn(0, text.length)).count { it == '\n' }

    private companion object {
        val SCRIPT_OPEN = Regex("<script([^>]*)>", RegexOption.IGNORE_CASE)
    }
}

private fun createVueScriptEngine(language: String): SyntaxEngine = createSyntaxEngine(language)
