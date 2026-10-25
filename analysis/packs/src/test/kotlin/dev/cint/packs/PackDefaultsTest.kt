package dev.cint.packs

import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.util.ContractError
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackDefaultsTest {
    private val source = BundledRulePackSource()

    @Test
    fun defaultsArePackDefaultCompatible() {
        val manifestIds =
            source
                .manifests()
                .getOrThrow()
                .map { it.id }
                .toSet()
        for (language in TestLanguages.ALL) {
            val defaults = source.defaults(language).getOrThrow()
            assertTrue(defaults.isNotEmpty(), "no defaults for $language")
            for (default in defaults) {
                assertTrue(default.packId in manifestIds)
                assertTrue(default.packVersion.isNotBlank())
                assertTrue(default.intents.isNotEmpty())
                assertTrue(default.intents.all { Intents.isKnown(it) })
                assertEquals(Provenance.PACK_DEFAULT, default.provenance)
                assertEquals(Status.SUGGESTED, default.status)
                assertEquals(Instant.fromEpochMilliseconds(0), default.createdAt)

                val annotation = default.toFunctionAnnotation()
                assertEquals(default.selector, annotation.selector)
                assertEquals(default.intents, annotation.intents)
                assertEquals(Provenance.PACK_DEFAULT, annotation.provenance)
                assertEquals(Status.SUGGESTED, annotation.status)
            }
        }
    }

    @Test
    fun loggingDefaultsAreLanguageSpecific() {
        val kotlinNames = names(source, TestLanguages.KOTLIN, Intents.LOGGING)
        assertTrue("kotlin.io.println" in kotlinNames)
        assertTrue("org.slf4j.Logger.info" in kotlinNames)

        val webNames = names(source, TestLanguages.JAVASCRIPT, Intents.LOGGING)
        assertTrue("console.log" in webNames)

        val csharpNames = names(source, TestLanguages.CSHARP, Intents.LOGGING)
        assertTrue("System.Console.WriteLine" in csharpNames)

        assertTrue("console.log" !in kotlinNames)
    }

    @Test
    fun dataAccessDefaultsCoverSearchAndLookup() {
        for (language in TestLanguages.ALL) {
            val search = names(source, language, Intents.SEARCH)
            val lookup = names(source, language, Intents.LOOKUP)
            assertTrue("findAll" in search, "SEARCH default missing for $language")
            assertTrue("findUnique" in search, "SEARCH default missing for $language")
            assertTrue("getOrDefault" in lookup, "LOOKUP default missing for $language")
        }
    }

    @Test
    fun exactLookupPrecedenceAndTokenBoundaryPrefixesAreEncoded() {
        val defaults = source.defaults(TestLanguages.KOTLIN).getOrThrow()
        val searchPatterns =
            defaults
                .filter { Intents.SEARCH in it.intents }
                .mapNotNull { it.selector.structuralPattern }
        val searchNames =
            defaults
                .filter { Intents.SEARCH in it.intents }
                .mapNotNull { it.selector.qualifiedName.takeIf { name -> name.isNotBlank() } }
                .toSet()
        val lookupNames =
            defaults
                .filter { Intents.LOOKUP in it.intents }
                .mapNotNull { it.selector.qualifiedName.takeIf { name -> name.isNotBlank() } }
                .toSet()

        assertTrue("getByKey" in lookupNames)
        assertTrue("getByKey" !in searchNames)
        assertTrue(searchPatterns.none { it == "getBy*" })
        assertTrue(searchPatterns.none { matchesStructuralPattern(it, "getByKey") })
        assertTrue(searchPatterns.none { matchesStructuralPattern(it, "getBytes") })
        assertTrue(searchPatterns.any { matchesStructuralPattern(it, "getById") })
    }

    @Test
    fun defaultsCoverV1IntentDomains() {
        val covered =
            source
                .defaults(TestLanguages.KOTLIN)
                .getOrThrow()
                .flatMap { it.intents }
                .toSet()
        val expected =
            setOf(
                Intents.LOGGING,
                Intents.ERROR_HANDLING,
                Intents.BOOKKEEPING,
                Intents.VALIDATION,
                Intents.SEARCH,
                Intents.LOOKUP,
                Intents.NORMALIZATION,
                Intents.DATA_TRANSFORMATION,
                Intents.DATA_MODIFY,
                Intents.LOCAL_VALUE_MODIFY,
                Intents.SERVICE_DELEGATION,
                Intents.SERVICE_INIT,
                Intents.RETURN_VALUE,
                Intents.CHAINING,
                Intents.LOCAL_COPY,
                Intents.BOOL_COMBINATORICS,
            )
        assertTrue(covered.containsAll(expected), "missing intents: ${expected - covered}")
    }

    @Test
    fun defaultsAreDeterministicAndSorted() {
        val first = source.defaults(TestLanguages.KOTLIN).getOrThrow()
        val second = source.defaults(TestLanguages.KOTLIN).getOrThrow()
        val third = BundledRulePackSource().defaults(TestLanguages.KOTLIN).getOrThrow()
        assertEquals(first, second)
        assertEquals(first, third)

        val keys =
            first.map {
                listOf(it.packId, it.selector.identityKey, it.intents.sorted().joinToString(","))
            }
        assertEquals(keys.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] })), keys)
        assertEquals(first.size, first.distinctBy { Triple(it.packId, it.selector.identityKey, it.intents) }.size)
    }

    @Test
    fun unknownLanguageIsExplicit() {
        val error = source.defaults("brainfuck").errorOrNull
        assertIs<ContractError.InvalidInput>(error)
        assertTrue(source.defaults("KOTLIN").isFailure)
    }

    @Test
    fun blankLanguageIsExplicit() {
        val error = source.defaults("  ").errorOrNull
        assertIs<ContractError.InvalidInput>(error)
    }

    @Test
    fun customCatalogSeedsAreUsedInsteadOfBundledSeeds() {
        val custom =
            PackDefinition(
                manifest =
                    PackManifest(
                        id = "custom",
                        name = "Custom",
                        version = "9.9.9",
                        languages = listOf(TestLanguages.KOTLIN),
                        providesIntents = listOf(Intents.LOGGING),
                    ),
                playbook =
                    Playbook(
                        packId = "custom",
                        rules = listOf(RuleSpec(id = "custom:rule", owns = Intents.LOGGING)),
                    ),
                seeds =
                    listOf(
                        PackSeedDefault(
                            selector =
                                dev.cint.contracts.knowledge
                                    .FunctionSelector(qualifiedName = "custom.log"),
                            intents = setOf(Intents.LOGGING),
                            languages = setOf(TestLanguages.KOTLIN),
                        ),
                    ),
            )
        val customSource = BundledRulePackSource(PackCatalog.of(listOf(custom)))

        val defaults = customSource.defaults(TestLanguages.KOTLIN).getOrThrow()
        assertEquals(1, defaults.size)
        assertEquals("custom.log", defaults.single().selector.qualifiedName)
        assertEquals("9.9.9", defaults.single().packVersion)
    }

    @Test
    fun degradedPackContentIsWithheldAndDiagnosed() {
        val broken =
            PackDefinition(
                manifest =
                    PackManifest(
                        id = "broken",
                        name = "Broken",
                        version = "1.0.0",
                        languages = listOf(TestLanguages.KOTLIN),
                        dependencies = listOf("missing"),
                        providesIntents = listOf(Intents.USER_FEEDBACK),
                    ),
                playbook =
                    Playbook(
                        packId = "broken",
                        rules = listOf(RuleSpec(id = "broken:rule", owns = Intents.USER_FEEDBACK)),
                    ),
                seeds =
                    listOf(
                        PackSeedDefault(
                            selector =
                                dev.cint.contracts.knowledge
                                    .FunctionSelector(qualifiedName = "broken.log"),
                            intents = setOf(Intents.USER_FEEDBACK),
                            languages = setOf(TestLanguages.KOTLIN),
                        ),
                    ),
            )
        val catalog = PackCatalog.of(BundledPacks.all + broken)
        val degradedSource = BundledRulePackSource(catalog)

        assertTrue(degradedSource.manifests().getOrThrow().none { it.id == "broken" })
        assertTrue(degradedSource.defaults(TestLanguages.KOTLIN).getOrThrow().none { it.packId == "broken" })
        assertTrue(degradedSource.diagnostics().getOrThrow().any { it.packId == "broken" })
        assertIs<ContractError.DependencyUnavailable>(degradedSource.playbook("broken").errorOrNull)
    }

    private fun names(
        source: BundledRulePackSource,
        language: String,
        intent: String,
    ): Set<String> =
        source
            .defaults(language)
            .getOrThrow()
            .filter { intent in it.intents }
            .mapNotNull { it.selector.qualifiedName.takeIf { name -> name.isNotBlank() } }
            .toSet()

    private fun matchesStructuralPattern(
        pattern: String,
        value: String,
    ): Boolean {
        val parts = pattern.split(';')
        val prefix = parts.first().removePrefix("token-prefix:")
        val exceptions =
            parts
                .drop(1)
                .filter { it.startsWith("except:") }
                .flatMap { it.removePrefix("except:").split(',') }
                .toSet()
        if (value in exceptions) return false
        if (value.equals(prefix, ignoreCase = true)) return true
        if (value.startsWith(prefix, ignoreCase = true)) {
            val suffix = value.substring(prefix.length)
            if (suffix.isNotEmpty()) {
                val first = suffix.first()
                if (first.isUpperCase() || first.isDigit() || !first.isLetterOrDigit()) return true
            }
        }
        val valueTokens = tokenize(value)
        val prefixTokens = tokenize(prefix)
        return prefixTokens.isNotEmpty() &&
            valueTokens.size >= prefixTokens.size &&
            valueTokens.subList(0, prefixTokens.size) == prefixTokens
    }

    private fun tokenize(value: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString().lowercase()
                current.clear()
            }
        }

        for (index in value.indices) {
            val character = value[index]
            if (!character.isLetterOrDigit()) {
                flush()
                continue
            }
            if (current.isNotEmpty()) {
                val previous = value[index - 1]
                val next = value.getOrNull(index + 1)
                val boundary =
                    ((previous.isLowerCase() || previous.isDigit()) && character.isUpperCase()) ||
                        (previous.isUpperCase() && character.isUpperCase() && next != null && next.isLowerCase())
                if (boundary) flush()
            }
            current.append(character)
        }
        flush()
        return tokens
    }
}
