package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

data class TreeSitterGrammar(
    val id: String,
    val grammarName: String,
    val version: String,
    val nativeLibrary: String,
    val nativeSymbol: String,
    val jvmFactory: String,
)

object TreeSitterGrammars {
    val grammars: List<TreeSitterGrammar> =
        listOf(
            TreeSitterGrammar(
                id = "kotlin",
                grammarName = "tree-sitter-kotlin",
                version = "0.3.8.1",
                nativeLibrary = "libtree-sitter-kotlin.so",
                nativeSymbol = "tree_sitter_kotlin",
                jvmFactory = "org.treesitter.TreeSitterKotlin",
            ),
            TreeSitterGrammar(
                id = "c",
                grammarName = "tree-sitter-c",
                version = "0.24.1",
                nativeLibrary = "libtree-sitter-c.so",
                nativeSymbol = "tree_sitter_c",
                jvmFactory = "org.treesitter.TreeSitterC",
            ),
            TreeSitterGrammar(
                id = "javascript",
                grammarName = "tree-sitter-javascript",
                version = "0.23.1",
                nativeLibrary = "libtree-sitter-javascript.so",
                nativeSymbol = "tree_sitter_javascript",
                jvmFactory = "org.treesitter.TreeSitterJavascript",
            ),
            TreeSitterGrammar(
                id = "typescript",
                grammarName = "tree-sitter-typescript",
                version = "0.23.2",
                nativeLibrary = "libtree-sitter-typescript.so",
                nativeSymbol = "tree_sitter_typescript",
                jvmFactory = "org.treesitter.TreeSitterTypescript",
            ),
            TreeSitterGrammar(
                id = "vue",
                grammarName = "tree-sitter-vue",
                version = "0.2.1a",
                nativeLibrary = "libtree-sitter-vue.so",
                nativeSymbol = "tree_sitter_vue",
                jvmFactory = "org.treesitter.TreeSitterVue",
            ),
            TreeSitterGrammar(
                id = "csharp",
                grammarName = "tree-sitter-c-sharp",
                version = "0.23.1",
                nativeLibrary = "libtree-sitter-c-sharp.so",
                nativeSymbol = "tree_sitter_c_sharp",
                jvmFactory = "org.treesitter.TreeSitterCSharp",
            ),
        )

    private val byId: Map<String, TreeSitterGrammar> = grammars.associateBy { it.id }

    fun find(id: String): TreeSitterGrammar? = byId[id.trim().lowercase().removePrefix(".")]

    fun canonical(id: String): String = find(id)?.id ?: id.trim().lowercase()
}

expect fun createSyntaxEngine(
    language: String,
    profile: String = language,
): SyntaxEngine

internal expect val treeSitterRuntimeVersion: String

internal class UnavailableSyntaxEngine(
    override val language: String,
    private val reason: String,
) : SyntaxEngine {
    override fun parse(
        content: ByteArray,
        previous: SyntaxTree?,
        cancellation: CancellationToken,
    ): ContractResult<SyntaxTree> = failure(language)

    override fun diff(
        previous: SyntaxTree,
        next: SyntaxTree,
    ): ChangedRanges = ChangedRanges.WHOLE_FILE

    override fun nodeHash(
        tree: SyntaxTree,
        nodeId: String,
    ): ContractResult<String> = failure(nodeId)

    override fun facts(
        tree: SyntaxTree,
        queryIds: List<String>,
        cancellation: CancellationToken,
    ): ContractResult<FactBatch> = failure(tree.language)

    private fun failure(resource: String): ContractResult<Nothing> =
        ResultFactory.failure(
            ContractError.DependencyUnavailable(
                dependency = "tree-sitter-$language",
                reason = "$reason (resource=$resource)",
            ),
        )
}
