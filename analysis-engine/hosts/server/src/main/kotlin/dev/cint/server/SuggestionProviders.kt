package dev.cint.server

import dev.cint.suggest.DEEPSEEK_MODEL_ID
import dev.cint.suggest.DeepSeekIntentSuggestionProvider
import dev.cint.suggest.DeepSeekProviderConfig
import dev.cint.suggest.FakeIntentSuggestionProvider
import dev.cint.suggest.IntentSuggestionProvider
import dev.cint.suggest.SuggestionProviderRegistry
import dev.cint.suggest.SuggestionService

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
