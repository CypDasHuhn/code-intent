package dev.cint.server.live

import dev.cint.api.ConfigFieldKind
import dev.cint.api.ConfigFieldScope
import dev.cint.api.RulePackConfigDescriptor
import dev.cint.core.config.IntentConfig
import dev.cint.host.PackConfigOverlay
import dev.cint.host.PackConfigOverlayState
import dev.cint.server.LiveServer

internal fun LiveServer.configDescriptors(): List<RulePackConfigDescriptor> = packs.configDescriptors()

internal fun LiveServer.configReadState(): PackConfigOverlayState = PackConfigOverlay.read(root)

internal fun LiveServer.configRead(): Map<String, Map<String, Any?>> {
    val overlay = configReadState()
    if (overlay.error != null) throw IllegalStateException(overlay.error)
    return overlay.packs
}

internal fun LiveServer.configAppend(
    packId: String,
    language: String,
    field: String,
    value: String,
): Map<String, Map<String, Any?>> =
    synchronized(this) {
        val resolved = resolveConfigField(packId, language, field)
        applyConfigMutation(resolved.packId) {
            PackConfigOverlay.append(root, resolved.packId, resolved.language, resolved.field, value)
        }
    }

internal fun LiveServer.configRemove(
    packId: String,
    language: String,
    field: String,
    value: String,
): Map<String, Map<String, Any?>> =
    synchronized(this) {
        val resolved = resolveConfigField(packId, language, field)
        applyConfigMutation(resolved.packId) {
            PackConfigOverlay.remove(root, resolved.packId, resolved.language, resolved.field, value)
        }
    }

private fun LiveServer.applyConfigMutation(
    packId: String,
    mutate: () -> Map<String, Map<String, Any?>>,
): Map<String, Map<String, Any?>> {
    val previous = PackConfigOverlay.read(root)
    val previousError = previous.error
    if (previousError != null) {
        throw IllegalStateException("refusing to modify invalid config overlay: $previousError")
    }
    val overlay = mutate()
    packs.reload()
    // Any target failure means the candidate overlay was not applied, even
    // when the same failure was already present before this mutation.
    val failure = packs.failures.firstOrNull { packId in it.packIds }
    val targetMissing = packs.effectivePacks.none { it.id == packId }
    if (failure != null || targetMissing) {
        PackConfigOverlay.restore(root, previous)
        packs.reload()
        throw IllegalStateException(
            failure?.message ?: "rulepack '$packId' failed to reload after config mutation",
        )
    }
    configRevision += 1
    return overlay
}

private fun LiveServer.resolveConfigField(
    packId: String,
    language: String,
    field: String,
): ResolvedConfigField {
    val descriptor =
        packs.configDescriptors().firstOrNull { it.packId == packId }
            ?: throw IllegalArgumentException("unknown rulepack: $packId")
    val canonicalLanguage =
        descriptor.languages.firstOrNull { it.equals(language, ignoreCase = true) }
            ?: throw IllegalArgumentException("rulepack '$packId' does not support language '$language'")
    val configField =
        descriptor.fields.firstOrNull { it.key == field }
            ?: throw IllegalArgumentException("rulepack '$packId' has no config field '$field'")
    if (configField.kind != ConfigFieldKind.STRINGS && configField.kind != ConfigFieldKind.STRING_LIST) {
        throw IllegalArgumentException("rulepack '$packId' field '$field' is not a string list field")
    }
    if (
        configField.scope == ConfigFieldScope.LANGUAGE &&
        configField.languages.none { it.equals(canonicalLanguage, ignoreCase = true) }
    ) {
        throw IllegalArgumentException("rulepack '$packId' field '$field' does not apply to '$canonicalLanguage'")
    }
    return ResolvedConfigField(packId, canonicalLanguage, configField)
}

internal fun LiveServer.configPayload(
    overlay: Map<String, Map<String, Any?>> = configRead(),
    overlayError: String? = null,
): Map<String, Any?> {
    val result =
        linkedMapOf<String, Any?>(
            "packs" to configDescriptors(),
            "overlay" to overlay,
        )
    if (overlayError != null) result["overlayError"] = overlayError
    return result
}

internal fun LiveServer.republishOpenDocs(carry: Boolean = false) {
    for ((writer, clientDocs) in docs.entries.toList()) {
        for (doc in clientDocs.values.toList()) {
            val key = DocumentKey(writer, doc.uri)
            val proposals =
                if (carry) {
                    publishedProposals[key].orEmpty().filter { it.version == doc.version }.map { it.proposal }
                } else {
                    null
                }
            val candidates =
                if (carry) {
                    publishedCandidates[key].orEmpty().filter { it.version == doc.version }.map { it.candidate }
                } else {
                    null
                }
            runCatching { publish(writer, doc, proposals, candidates) }
                .onFailure { error ->
                    System.err.println("cint config republish: ${error.message ?: error.javaClass.name}")
                }
        }
    }
}

internal fun LiveServer.republishUri(
    uri: String,
    decidingKey: DocumentKey? = null,
    proposals: List<dev.cint.suggest.model.CandidateProposal>? = null,
    candidates: List<dev.cint.api.CandidateEmit>? = null,
) {
    for ((writer, clientDocs) in docs.entries.toList()) {
        val doc = clientDocs[uri] ?: continue
        val key = DocumentKey(writer, doc.uri)
        val carriedProposals =
            if (key == decidingKey) {
                proposals
            } else {
                publishedProposals[key].orEmpty().filter { it.version == doc.version }.map { it.proposal }
            }
        val carriedCandidates =
            if (key == decidingKey) {
                candidates
            } else {
                publishedCandidates[key].orEmpty().filter { it.version == doc.version }.map { it.candidate }
            }
        runCatching { publish(writer, doc, carriedProposals, carriedCandidates) }
            .onFailure { error ->
                System.err.println("cint decision republish: ${error.message ?: error.javaClass.name}")
            }
    }
}

internal fun LiveServer.configFor(language: String): IntentConfig =
    configs.computeIfAbsent(language) {
        settings.intentConfig(it)
    }
