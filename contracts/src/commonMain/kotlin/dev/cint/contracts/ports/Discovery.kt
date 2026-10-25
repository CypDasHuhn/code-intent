package dev.cint.contracts.ports

import dev.cint.contracts.data.Span
import dev.cint.contracts.knowledge.Selector
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryFilesRequest(
    val languages: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val limit: Int = DEFAULT_FILE_LIMIT,
) {
    companion object {
        const val DEFAULT_FILE_LIMIT = 5_000
        const val MAX_FILE_LIMIT = 50_000
    }
}

@Serializable
data class RepositoryFile(
    val path: String,
    val absolutePath: String,
    val language: String? = null,
    val extension: String = "",
)

@Serializable
data class RepositoryFilesResult(
    val root: String,
    val files: List<RepositoryFile> = emptyList(),
    val count: Int = files.size,
    val truncated: Boolean = false,
    val ignoredDirectories: List<String> = emptyList(),
)

@Serializable
data class RepositorySymbolsRequest(
    val targetPaths: List<String> = emptyList(),
    val language: String? = null,
    val options: Map<String, String> = emptyMap(),
)

@Serializable
data class RepositorySymbolsResult(
    val sources: List<RepositorySymbolInventory> = emptyList(),
    val count: Int = sources.size,
)

@Serializable
data class RepositorySymbolInventory(
    val path: String,
    val language: String? = null,
    val declarations: List<SymbolDeclaration> = emptyList(),
    val calls: List<SymbolCall> = emptyList(),
    val imports: List<SymbolImport> = emptyList(),
)

@Serializable
data class SymbolDeclaration(
    val nodeId: String,
    val name: String? = null,
    val kind: String,
    val span: Span,
    val startByte: Int,
    val endByte: Int,
    val declaringType: String? = null,
    val signature: String? = null,
)

@Serializable
data class SymbolCall(
    val nodeId: String,
    val callee: String? = null,
    val path: String? = null,
    val span: Span,
    val startByte: Int,
    val endByte: Int,
    val receiverType: String? = null,
    val declaringType: String? = null,
)

@Serializable
data class SymbolImport(
    val nodeId: String,
    val path: String,
    val alias: String? = null,
    val span: Span? = null,
)

@Serializable
data class SelectorResolutionRequest(
    val selector: Selector,
    val targetPaths: List<String> = emptyList(),
    val root: String? = null,
    val language: String? = null,
    val options: Map<String, String> = emptyMap(),
)

@Serializable
data class ResolvedSelectorTarget(
    val path: String? = null,
    val language: String? = null,
    val kind: String,
    val name: String? = null,
    val names: List<String> = emptyList(),
    val span: Span,
    val startByte: Int,
    val endByte: Int,
    val nodeId: String,
    val receiverTypes: List<String> = emptyList(),
    val declaringTypes: List<String> = emptyList(),
    val importPaths: List<String> = emptyList(),
    val signatures: List<String> = emptyList(),
)

@Serializable
data class SelectorUnsupportedReason(
    val reason: String,
    val path: String? = null,
    val kind: String? = null,
    val nodeId: String? = null,
)

@Serializable
data class SelectorResolution(
    val selector: String,
    val matched: List<ResolvedSelectorTarget> = emptyList(),
    val unsupported: List<SelectorUnsupportedReason> = emptyList(),
    val scannedPaths: List<String> = emptyList(),
)
