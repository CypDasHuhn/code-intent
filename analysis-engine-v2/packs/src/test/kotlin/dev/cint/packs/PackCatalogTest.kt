package dev.cint.packs

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.util.ContractError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackCatalogTest {
    @Test
    fun bundledManifestsAreDeterministicAndSorted() {
        val source = BundledRulePackSource()

        val first = source.manifests().getOrThrow()
        val second = BundledRulePackSource().manifests().getOrThrow()

        assertEquals(first, second)
        assertEquals(first.map { it.id }, first.map { it.id }.sorted())
        assertEquals(first.size, first.map { it.id }.distinct().size)
        assertFalse(first.isEmpty())

        val ids = first.map { it.id }.toSet()
        for (manifest in first) {
            assertTrue(manifest.dependencies.all { it in ids }, "${manifest.id} has an unresolved dependency")
            assertTrue(manifest.languages.isNotEmpty(), "${manifest.id} advertises no languages")
        }
        assertTrue(source.diagnostics().getOrThrow().isEmpty())
    }

    @Test
    fun v1DisabledPacksAreBundledButNotDefaultEnabled() {
        val manifests = BundledRulePackSource().manifests().getOrThrow().associateBy { it.id }
        assertFalse(manifests.getValue("chaining").defaultEnabled)
        assertFalse(manifests.getValue("bool-combinatorics").defaultEnabled)
        assertTrue(manifests.getValue("logging").defaultEnabled)
        assertTrue(manifests.getValue("return-value").defaultEnabled)
        assertTrue(manifests.getValue("local-copy").defaultEnabled)
    }

    @Test
    fun bundledPlaybooksExposeRulesForEveryManifest() {
        val source = BundledRulePackSource()
        for (manifest in source.manifests().getOrThrow()) {
            val playbook = source.playbook(manifest.id).getOrThrow()
            assertEquals(manifest.id, playbook.packId)
            assertTrue(playbook.rules.isNotEmpty(), "${manifest.id} has no rules")
            assertEquals(playbook.rules.size, playbook.rules.map { it.id }.distinct().size)
            assertTrue(playbook.rules.all { it.owns in manifest.providesIntents })
        }
    }

    @Test
    fun unknownPackIsExplicit() {
        val error = BundledRulePackSource().playbook("not-a-pack").errorOrNull
        assertIs<ContractError.NotFound>(error)
        assertEquals("not-a-pack", error.identifier)
    }

    @Test
    fun blankPackIdIsExplicit() {
        val error = BundledRulePackSource().playbook("  ").errorOrNull
        assertIs<ContractError.InvalidInput>(error)
    }

    @Test
    fun duplicatePackIdsDegradeExplicitly() {
        val first = definition("duplicate", "first")
        val second = definition("duplicate", "second")
        val catalog = PackCatalog.of(listOf(first, second))
        val source = BundledRulePackSource(catalog)

        assertEquals(setOf("duplicate"), catalog.degradedPacks.keys)
        assertIs<ContractError.ValidationError>(catalog.degradedPacks.getValue("duplicate"))
        assertNull(catalog.definition("duplicate"))
        assertTrue(catalog.manifests.none { it.id == "duplicate" })
        assertTrue(source.diagnostics().getOrThrow().any { it.packId == "duplicate" && it.code == "DUPLICATE_PACK_ID" })

        val error = source.playbook("duplicate").errorOrNull
        assertIs<ContractError.ValidationError>(error)
    }

    @Test
    fun missingDependencyIsVisibleWithoutHidingRawContent() {
        val catalog =
            PackCatalog.of(
                listOf(
                    definition("ok", "ok", owns = Intents.LOGGING),
                    definition("broken", "broken", dependencies = listOf("absent"), owns = Intents.LOOKUP),
                    definition("downstream", "downstream", dependencies = listOf("broken"), owns = Intents.SEARCH),
                ),
            )
        val source = BundledRulePackSource(catalog)

        assertEquals(setOf("broken", "downstream"), catalog.degradedPacks.keys)
        assertIs<ContractError.DependencyUnavailable>(catalog.issue("broken"))
        assertIs<ContractError.DependencyUnavailable>(catalog.issue("downstream"))

        assertEquals(setOf("ok"), source.manifests().getOrThrow().map { it.id }.toSet())
        val diagnostics = source.diagnostics().getOrThrow()
        assertTrue(diagnostics.any { it.packId == "broken" && it.code == "MISSING_DEPENDENCY" })
        assertTrue(diagnostics.any { it.packId == "downstream" && it.code == "DEPENDENCY_UNAVAILABLE" })

        assertTrue(source.playbook("ok").isSuccess)
        assertTrue(source.playbook("broken").isFailure)
        assertTrue(source.playbook("downstream").isFailure)
    }

    @Test
    fun dependencyCyclesDegradeExplicitly() {
        val catalog =
            PackCatalog.of(
                listOf(
                    definition("a", "a", dependencies = listOf("b"), owns = Intents.LOGGING),
                    definition("b", "b", dependencies = listOf("a"), owns = Intents.LOOKUP),
                    definition("c", "c", dependencies = listOf("a"), owns = Intents.SEARCH),
                ),
            )
        val source = BundledRulePackSource(catalog)

        assertTrue("a" in catalog.degradedPacks)
        assertTrue("b" in catalog.degradedPacks)
        assertTrue("c" in catalog.degradedPacks)
        val error = catalog.issue("a")
        assertIs<ContractError.ValidationError>(error)
        assertTrue(error.message.contains("cycle"), error.message)
        assertTrue(source.diagnostics().getOrThrow().any { it.code == "DEPENDENCY_CYCLE" })
        assertTrue(source.manifests().getOrThrow().isEmpty())
    }

    @Test
    fun selfDependencyDegradesExplicitly() {
        val catalog = PackCatalog.of(listOf(definition("self", "self", dependencies = listOf("self"))))
        assertIs<ContractError.ValidationError>(catalog.issue("self"))
    }

    @Test
    fun globalRuleAndIntentOwnershipMustBeUnique() {
        val catalog =
            PackCatalog.of(
                listOf(
                    definition("first", "first", ruleId = "shared:rule", owns = Intents.LOGGING),
                    definition("second", "second", ruleId = "shared:rule", owns = Intents.LOGGING),
                ),
            )

        val codes = catalog.diagnostics.map { it.code }.toSet()
        assertTrue("GLOBAL_DUPLICATE_RULE_ID" in codes)
        assertTrue("GLOBAL_DUPLICATE_OWNED_INTENT" in codes)
        assertTrue("first" in catalog.degradedPacks)
        assertTrue("second" in catalog.degradedPacks)
    }

    @Test
    fun seedsMustMatchPlaybookOwnershipAndManifestLanguages() {
        val catalog =
            PackCatalog.of(
                listOf(
                    definition(
                        id = "invalid",
                        name = "invalid",
                        seeds =
                            listOf(
                                PackSeedDefault(
                                    selector = FunctionSelector(qualifiedName = "a.b"),
                                    intents = setOf(Intents.LOOKUP),
                                    languages = setOf(TestLanguages.KOTLIN, "rust"),
                                    confidence = Confidence.MEDIUM,
                                ),
                            ),
                    ),
                ),
            )

        val codes = catalog.diagnostics.map { it.code }.toSet()
        assertTrue("SEED_INTENT_NOT_OWNED" in codes)
        assertTrue("SEED_LANGUAGE_NOT_DECLARED" in codes)
        assertTrue("invalid" in catalog.degradedPacks)
    }

    private fun definition(
        id: String,
        name: String,
        dependencies: List<String> = emptyList(),
        ruleId: String = "$id:test",
        owns: String = Intents.LOGGING,
        seeds: List<PackSeedDefault> = emptyList(),
    ): PackDefinition =
        PackDefinition(
            manifest =
                PackManifest(
                    id = id,
                    name = name,
                    version = "1.0.0",
                    languages = listOf(TestLanguages.KOTLIN),
                    dependencies = dependencies,
                    providesIntents = listOf(owns),
                ),
            playbook =
                Playbook(
                    packId = id,
                    rules = listOf(RuleSpec(id = ruleId, owns = owns)),
                ),
            seeds = seeds,
        )
}
