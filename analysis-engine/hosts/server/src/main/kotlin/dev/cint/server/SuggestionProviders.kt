package dev.cint.server

import dev.cint.suggest.SuggestionProviderRegistry
import dev.cint.suggest.SuggestionService
import dev.cint.suggest.provider.DEEPSEEK_MODEL_ID
import dev.cint.suggest.provider.DeepSeekIntentSuggestionProvider
import dev.cint.suggest.provider.DeepSeekProviderConfig
import dev.cint.suggest.provider.FakeIntentSuggestionProvider
import dev.cint.suggest.provider.IntentSuggestionProvider

internal fun suggestionServiceFrom(
    settings: SuggestionSettings,
    apiKeyLookup: (String) -> String? = { name -> System.getenv(name) },
): SuggestionService {
    val providers: List<IntentSuggestionProvider> =
        listOf(
            FakeIntentSuggestionProvider(),
            DeepSeekIntentSuggestionProvider(
                config =
                    DeepSeekProviderConfig(
                        apiKeyEnv = settings.apiKeyEnv,
                        baseUrl = settings.baseUrl,
                        model = settings.model ?: DEEPSEEK_MODEL_ID,
                        maxTokens = settings.maxTokens,
                        maxConfidence = settings.maxConfidence,
                        timeoutMs = settings.timeoutMs,
                    ),
                apiKey = apiKeyLookup,
            ),
        )
    return SuggestionService(SuggestionProviderRegistry(providers))
}
