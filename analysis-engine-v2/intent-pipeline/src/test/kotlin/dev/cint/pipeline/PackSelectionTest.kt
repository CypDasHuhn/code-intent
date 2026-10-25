package dev.cint.pipeline

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.ports.PackDiagnostic
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.util.ContractError
import dev.cint.v2.testing.fakes.FakeRulePackSource
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackSelectionTest {
    private val createdAt = Instant.fromEpochMilliseconds(0)

    @Test
    fun selectionIsDeterministicAndRespectsManifestsDefaultsDiagnosticsAndLanguage() {
        val manifests = listOf(
            PackManifest("zeta", "Zeta", "1", listOf("fake"), dependencies = listOf("alpha")),
            PackManifest("alpha", "Alpha", "1", listOf("fake"), defaultEnabled = false),
            PackManifest("delta", "Delta", "1", listOf("fake")),
            PackManifest("beta", "Beta", "1", listOf("other")),
            PackManifest("gamma", "Gamma", "1", listOf("fake")),
        )
        val playbooks = manifests.associate { it.id to Playbook(it.id, emptyList()) }
        val defaults = listOf(
            default("zeta", "zetaFn", Intents.LOGGING),
            default("alpha", "alphaFn", Intents.LOOKUP),
            default("delta", "deltaFn", Intents.SEARCH),
            default("beta", "betaFn", Intents.VALIDATION),
            default("gamma", "gammaFn", Intents.ERROR_HANDLING),
            default("disabled", "disabledFn", Intents.NORMALIZATION),
        )
        val diagnostic = PackDiagnostic(
            packId = "gamma",
            code = "BROKEN_PACK",
            message = "gamma is broken",
            error = ContractError.ValidationError("gamma is broken", "id"),
        )
        val source = FakeRulePackSource(
            packManifests = manifests,
            playbooks = playbooks,
            defaultsByLanguage = mapOf("fake" to defaults),
            packDiagnostics = listOf(diagnostic),
        )

        val first = PackSelector(source).select("fake").getOrThrow()
        val second = PackSelector(source).select("fake").getOrThrow()

        assertEquals(first, second)
        assertEquals(listOf("alpha", "delta", "zeta"), first.packs.map { it.manifest.id })
        assertEquals(listOf("alphaFn", "deltaFn", "zetaFn"), first.defaults.map { it.selector.qualifiedName })
        assertEquals(listOf(diagnostic), first.diagnostics)
    }

    @Test
    fun missingDependencyFailsDeterministically() {
        val source = FakeRulePackSource(
            packManifests = listOf(
                PackManifest("zeta", "Zeta", "1", listOf("fake"), dependencies = listOf("missing")),
            ),
            playbooks = mapOf("zeta" to Playbook("zeta", emptyList())),
        )

        val result = PackSelector(source).select("fake")

        assertTrue(result.isFailure)
        assertEquals(
            ContractError.DependencyUnavailable("pack:missing", "Missing dependency 'missing' of pack 'zeta'"),
            result.errorOrNull,
        )
    }

    @Test
    fun duplicateManifestIdsFailInsteadOfDroppingAPack() {
        val source = FakeRulePackSource(
            packManifests = listOf(
                PackManifest("demo", "First", "1", listOf("fake")),
                PackManifest("demo", "Second", "1", listOf("fake")),
            ),
            playbooks = mapOf("demo" to Playbook("demo", emptyList())),
        )

        val result = PackSelector(source).select("fake")

        assertTrue(result.isFailure)
        assertEquals(
            ContractError.ValidationError("Duplicate pack ids: demo", "id"),
            result.errorOrNull,
        )
    }

    private fun default(packId: String, name: String, intent: String): PackDefault =
        PackDefault(
            selector = FunctionSelector(qualifiedName = name),
            intents = setOf(intent),
            packId = packId,
            packVersion = "1",
            confidence = Confidence.MEDIUM,
            createdAt = createdAt,
        )
}
