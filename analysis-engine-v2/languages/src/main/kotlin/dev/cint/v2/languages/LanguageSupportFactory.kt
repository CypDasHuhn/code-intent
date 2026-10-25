package dev.cint.v2.languages

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.v2.lsp.LspProviderFactory
import dev.cint.v2.lsp.LspTransport
import dev.cint.v2.syntax.treesitter.TreeSitterGrammars
import dev.cint.v2.syntax.treesitter.createSyntaxEngine
import java.nio.file.Files
import java.nio.file.Path

data class LanguageOptions(
    val providerConfig: SemanticProviderConfig? = null,
    val rootPath: String? = null,
    val hostChannel: HostSemanticChannel? = null,
    val hostChannelProvider: HostSemanticChannelProvider? = null,
    val requireRoot: Boolean? = null,
) {
    val requestedRoot: String? get() = rootPath ?: providerConfig?.rootPath
}

fun interface LanguageSyntaxFactory {
    fun create(descriptor: LanguageDescriptor): ContractResult<SyntaxEngine>
}

fun interface LanguageProviderFactory {
    fun create(
        descriptor: LanguageDescriptor,
        config: SemanticProviderConfig,
        hostChannel: HostSemanticChannel?,
        hostChannelProvider: HostSemanticChannelProvider?,
    ): ContractResult<SemanticProvider>
}

class LanguageSupportFactory(
    private val catalog: LanguageCatalog = TreeSitterLanguageRegistry,
    private val syntaxFactory: LanguageSyntaxFactory = DefaultSyntaxFactory,
    private val providerFactory: LanguageProviderFactory = DefaultProviderFactory(),
    private val rootLocator: LanguageRootLocator? = MarkerRootLocator,
    private val providerDefaults: LanguageProviderDefaults = DefaultLanguageProviderDefaults,
    private val transportProvided: Boolean = false,
) {
    internal constructor(
        transport: LspTransport,
        catalog: LanguageCatalog = TreeSitterLanguageRegistry,
        syntaxFactory: LanguageSyntaxFactory = DefaultSyntaxFactory,
        rootLocator: LanguageRootLocator? = MarkerRootLocator,
        providerDefaults: LanguageProviderDefaults = DefaultLanguageProviderDefaults,
    ) : this(catalog, syntaxFactory, DefaultProviderFactory(transport), rootLocator, providerDefaults, true)

    fun resolve(
        language: String,
        options: LanguageOptions = LanguageOptions(),
    ): ContractResult<LanguageSupport> {
        val descriptor =
            catalog.forLanguage(language)
                ?: return failure(ContractError.NotFound("language", language))
        return resolveDescriptor(descriptor, path = null, options = options)
    }

    fun resolveForPath(
        path: String,
        options: LanguageOptions = LanguageOptions(),
    ): ContractResult<LanguageSupport> {
        val descriptor =
            catalog.forPath(path)
                ?: return failure(ContractError.NotFound("language for path", path))
        return resolveDescriptor(descriptor, path, options)
    }

    fun resolveForInput(
        input: SourceInput,
        options: LanguageOptions = LanguageOptions(),
    ): ContractResult<LanguageSupport> =
        when (input) {
            is SourceInput.FilePath -> {
                val hint = input.languageHint
                val descriptor =
                    if (hint != null) {
                        catalog.forLanguage(hint)
                            ?: return failure(ContractError.NotFound("language", hint))
                    } else {
                        catalog.forPath(input.path)
                            ?: return failure(ContractError.NotFound("language for path", input.path))
                    }
                resolveDescriptor(descriptor, input.path, options)
            }

            is SourceInput.InMemory -> {
                val descriptor =
                    catalog.forLanguage(input.language)
                        ?: return failure(ContractError.NotFound("language", input.language))
                resolveDescriptor(descriptor, input.path, options)
            }
        }

    private fun resolveDescriptor(
        descriptor: LanguageDescriptor,
        path: String?,
        options: LanguageOptions,
    ): ContractResult<LanguageSupport> {
        val baseConfig = options.providerConfig ?: providerDefaults.configFor(descriptor)
        val rootResult = resolveRoot(descriptor, path, options, baseConfig.mode)
        if (rootResult.isFailure) return failure(rootResult.errorOrNull ?: missingValue("language root"))
        @Suppress("UNCHECKED_CAST")
        val root = rootResult.getValue() as Path?

        val syntaxResult = syntaxFactory.create(descriptor)
        if (syntaxResult.isFailure) return failure(syntaxResult.errorOrNull ?: missingValue("syntax engine"))
        @Suppress("UNCHECKED_CAST")
        val syntax = syntaxResult.getValue() as SyntaxEngine
        val syntaxCheck = syntax.parse(ByteArray(0), null)
        if (syntaxCheck.isFailure) {
            return failure(
                syntaxCheck.errorOrNull
                    ?: ContractError.DependencyUnavailable(
                        "tree-sitter-${descriptor.id}",
                        "Syntax engine could not be loaded",
                    ),
            )
        }

        val config = baseConfig.copy(rootPath = root?.toString())
        val configError = validateProviderConfiguration(config, options)
        if (configError != null) return failure(configError)
        val providerResult =
            try {
                providerFactory.create(
                    descriptor,
                    config,
                    options.hostChannel,
                    options.hostChannelProvider,
                )
            } catch (error: Throwable) {
                failure(
                    ContractError.DependencyUnavailable(
                        "semantic-provider-${descriptor.id}",
                        "Provider resolution failed: ${error.message ?: error::class.simpleName}",
                    ),
                )
            }
        if (providerResult.isFailure) return failure(providerResult.errorOrNull ?: missingValue("semantic provider"))
        @Suppress("UNCHECKED_CAST")
        val provider = providerResult.getValue() as SemanticProvider
        return ResultFactory.success(ResolvedLanguageSupport(descriptor, syntax, provider))
    }

    private fun resolveRoot(
        descriptor: LanguageDescriptor,
        path: String?,
        options: LanguageOptions,
        mode: ProviderMode,
    ): ContractResult<Path?> {
        val configured = options.requestedRoot?.takeIf { it.isNotBlank() }
        if (configured != null) return validateRoot(configured)
        val discovered = rootLocator?.locate(path, descriptor.rootMarkers)
        if (discovered != null) return validateRoot(discovered)
        val required = options.requireRoot ?: (mode != ProviderMode.EDITOR)
        if (required) {
            return failure(
                ContractError.DependencyUnavailable(
                    "language-root-${descriptor.id}",
                    "No project root configured and no root marker ${descriptor.rootMarkers} found for ${path ?: descriptor.id}",
                ),
            )
        }
        return ResultFactory.success(null)
    }

    private fun validateRoot(root: String): ContractResult<Path> {
        val resolved =
            try {
                Path.of(root).toAbsolutePath().normalize()
            } catch (error: Exception) {
                return failure(
                    ContractError.InvalidInput(
                        "Invalid root path '$root': ${error.message ?: error::class.simpleName}",
                        root,
                    ),
                )
            }
        if (!Files.isDirectory(resolved)) {
            return failure(ContractError.InvalidInput("Project root is not a directory: $resolved", resolved.toString()))
        }
        return ResultFactory.success(resolved)
    }

    private fun validateProviderConfiguration(
        config: SemanticProviderConfig,
        options: LanguageOptions,
    ): ContractError? =
        when {
            transportProvided -> {
                null
            }

            config.mode == ProviderMode.MANAGED && config.command.isNullOrBlank() -> {
                ContractError.PreconditionFailed("Managed LSP mode requires a command")
            }

            config.mode == ProviderMode.ATTACHED && config.endpoint.isNullOrBlank() -> {
                ContractError.PreconditionFailed("Attached LSP mode requires an endpoint")
            }

            config.mode == ProviderMode.EDITOR &&
                options.hostChannel == null &&
                options.hostChannelProvider == null &&
                config.hostChannelConfig == null -> {
                ContractError.PreconditionFailed(
                    "Editor semantic mode requires hostChannelConfig or a HostSemanticChannel",
                )
            }

            else -> {
                null
            }
        }

    private fun failure(error: ContractError): ContractResult<Nothing> = ResultFactory.failure(error)

    private fun missingValue(value: String): ContractError = ContractError.InternalError("Language resolution returned no $value")

    private object DefaultSyntaxFactory : LanguageSyntaxFactory {
        override fun create(descriptor: LanguageDescriptor): ContractResult<SyntaxEngine> {
            val grammar =
                TreeSitterGrammars.grammars.firstOrNull {
                    it.id == descriptor.id && it.grammarName == descriptor.grammarName
                }
                    ?: return ResultFactory.failure(
                        ContractError.DependencyUnavailable(
                            "tree-sitter-${descriptor.id}",
                            "No grammar registered for parser '${descriptor.grammarName}'",
                        ),
                    )
            return ResultFactory.success(createSyntaxEngine(grammar.id))
        }
    }

    private class DefaultProviderFactory(
        private val transport: LspTransport? = null,
    ) : LanguageProviderFactory {
        override fun create(
            descriptor: LanguageDescriptor,
            config: SemanticProviderConfig,
            hostChannel: HostSemanticChannel?,
            hostChannelProvider: HostSemanticChannelProvider?,
        ): ContractResult<SemanticProvider> =
            LspProviderFactory.create(
                config,
                descriptor.id,
                hostChannel = hostChannel,
                hostChannelProvider = hostChannelProvider,
                transport = transport,
            )
    }
}

object Languages {
    private val defaultFactory = LanguageSupportFactory()

    fun resolve(
        language: String,
        options: LanguageOptions = LanguageOptions(),
    ): ContractResult<LanguageSupport> = defaultFactory.resolve(language, options)

    fun resolveForPath(
        path: String,
        options: LanguageOptions = LanguageOptions(),
    ): ContractResult<LanguageSupport> = defaultFactory.resolveForPath(path, options)

    fun resolveForInput(
        input: SourceInput,
        options: LanguageOptions = LanguageOptions(),
    ): ContractResult<LanguageSupport> = defaultFactory.resolveForInput(input, options)

    val descriptors: List<LanguageDescriptor> = TreeSitterLanguageRegistry.descriptors
}

private class ResolvedLanguageSupport(
    private val languageDescriptor: LanguageDescriptor,
    private val syntaxEngine: SyntaxEngine,
    private val provider: SemanticProvider,
) : LanguageSupport {
    override fun descriptor(): ContractResult<LanguageDescriptor> = ResultFactory.success(languageDescriptor)

    override fun syntax(): ContractResult<SyntaxEngine> = ResultFactory.success(syntaxEngine)

    override fun semanticProvider(): ContractResult<SemanticProvider> = ResultFactory.success(provider)

    override fun shutdown(): ContractResult<Unit> = provider.shutdown()
}
