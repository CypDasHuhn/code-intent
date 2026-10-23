package dev.cint.server.live

import dev.cint.api.CINT_API_VERSION
import dev.cint.core.cache.AiEnvironment
import dev.cint.core.cache.DeterministicEnvironment
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.support.hashing.CodeFingerprint
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.frontend.CsharpFrontend
import dev.cint.frontend.JavascriptFrontend
import dev.cint.frontend.KotlinFrontend
import dev.cint.frontend.TypescriptFrontend
import dev.cint.frontend.VueFrontend
import dev.cint.server.LiveServer
import dev.cint.server.cache.AiVerdictCacheEnvironment
import dev.cint.server.cache.AiVerdictCacheStore
import dev.cint.server.suggestions.knownIntents
import dev.cint.suggest.session.AnnotationSession
import java.nio.file.Files
import java.nio.file.Path

internal val ENGINE_PACKAGE_PREFIXES: List<String> =
    listOf(
        "dev/cint/api/",
        "dev/cint/core/",
        "dev/cint/suggest/",
        "dev/cint/host/",
        "dev/cint/server/",
        "dev/cint/lsp/",
        "dev/cint/rulepacks/",
    )

internal val LANGUAGE_PACKAGE_PREFIXES: List<String> =
    listOf("dev/cint/frontend/", "dev/cint/treesitter/")

internal val ENGINE_VERSION: String =
    CodeFingerprint.ofPackages(
        label = "cint-engine-v1-api$CINT_API_VERSION",
        prefixes = ENGINE_PACKAGE_PREFIXES,
        anchors =
            listOf(
                dev.cint.api.Annotation::class.java,
                dev.cint.core.runtime.scheduler.RuleEngine::class.java,
                AnnotationSession::class.java,
                dev.cint.host.HostPipeline::class.java,
                LiveServer::class.java,
                Class.forName("dev.cint.lsp.KotlinLspOracle"),
                Class.forName("dev.cint.rulepacks.BundledRulePackConfigResolver"),
            ),
    )

internal val LANGUAGE_PROFILE_VERSION: String =
    CodeFingerprint.ofPackages(
        label = "cint-language-profile-v1",
        prefixes = LANGUAGE_PACKAGE_PREFIXES,
        anchors =
            listOf(
                KotlinFrontend::class.java,
                JavascriptFrontend::class.java,
                TypescriptFrontend::class.java,
                VueFrontend::class.java,
                CsharpFrontend::class.java,
                Class.forName("dev.cint.treesitter.KotlinParser"),
            ),
    ) +
        ":" +
        dev.cint.frontend.Frontends
            .languages()
            .sorted()
            .joinToString(",") { language ->
                CodeFingerprint.resourceDigest(KotlinFrontend::class.java, "/syntax/$language.yaml")
            }

internal fun LiveServer.deterministicEnvironment(language: String): DeterministicEnvironment {
    val oracle = oracleOptions()
    return DeterministicEnvironment(
        engineVersion = ENGINE_VERSION,
        packFingerprint = packs.fingerprint(language),
        configFingerprint =
            contentHash(
                listOf(
                    settings.project.toString(),
                    configReadState().packs.toString(),
                ).joinToString("\n"),
            ),
        userAnnotationsHash = fileHash(UserAnnotationsFile.path(root)),
        aiRestrictionsHash = fileHash(AiRestrictionsFile.path(root)),
        languageProfileVersion = LANGUAGE_PROFILE_VERSION,
        oracleMode = "${oracle.enabled}:${oracle.timeoutMs}",
    )
}

internal fun LiveServer.aiEnvironment(
    language: String,
    knownIntents: Set<String>,
): AiEnvironment =
    AiEnvironment(
        deterministic = deterministicEnvironment(language),
        provider = suggestionSettings.effectiveProvider,
        model = suggestionSettings.effectiveModel,
        promptVersion = effectivePromptVersion(),
        vocabularyVersion = contentHash(knownIntents.sorted().joinToString("\n")),
    )

internal fun LiveServer.cacheFileEnvironment(
    language: String,
    knownIntents: Set<String>,
): AiVerdictCacheEnvironment {
    val ai = aiEnvironment(language, knownIntents)
    return AiVerdictCacheEnvironment(
        engineVersion = ai.deterministic.engineVersion,
        deterministicHash = ai.deterministic.hash,
        aiEnv = ai.hash,
        provider = ai.provider,
        model = ai.model,
        promptVersion = ai.promptVersion,
        vocabularyVersion = ai.vocabularyVersion,
    )
}

internal fun LiveServer.effectivePromptVersion(): String =
    suggestionSettings.promptVersion
        ?: suggestionService
            .findSessionProvider(suggestionSettings.effectiveProvider)
            ?.defaultPromptVersion
        ?: "none"

internal fun LiveServer.ensureVerdictCache(environment: AiVerdictCacheEnvironment) {
    synchronized(verdictCache) {
        val previous = verdictCacheEnvironment
        if (previous == environment) return
        verdictCache.clear()
        verdictCacheEnvironment = environment
        if (suggestionSettings.cachePersist) {
            verdictCache.restore(
                AiVerdictCacheStore.load(
                    root = root,
                    environment = environment,
                    maxEntries = suggestionSettings.cacheMaxEntries,
                ),
            )
        }
    }
}

internal fun LiveServer.persistVerdictCache() {
    val environment = verdictCacheEnvironment ?: return
    if (!suggestionSettings.cachePersist) return
    synchronized(verdictCache) {
        runCatching {
            AiVerdictCacheStore.save(
                root = root,
                environment = environment,
                entries = verdictCache.snapshot(),
            )
        }.onFailure { error ->
            System.err.println("cint ai verdict cache write: ${error.message ?: error.javaClass.name}")
        }
    }
}

private fun fileHash(path: Path): String {
    if (!Files.isRegularFile(path)) return contentHash("")
    return runCatching { contentHash(Files.readString(path)) }.getOrDefault(contentHash(""))
}
