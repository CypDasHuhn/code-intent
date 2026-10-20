package dev.cint.rulepacks

import dev.cint.api.RulePackConfigException
import dev.cint.rulepacks.bookkeeping.BookkeepingConfig
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.errorhandling.ErrorHandlingConfig
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack
import dev.cint.rulepacks.validation.ValidationConfig
import dev.cint.rulepacks.validation.ValidationRulePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackConfigMigrationTest {
    @Test
    fun errorHandlingDefaultsMatchTheLanguageProfiles() {
        assertEquals(
            setOf("require", "requireNotNull", "check", "checkNotNull", "error", "TODO", "assert"),
            ErrorHandlingConfig().forLanguage("kotlin").calls,
        )
        assertEquals(setOf("assert", "invariant"), ErrorHandlingConfig().forLanguage("typescript").calls)
        assertEquals(setOf("Assert"), ErrorHandlingConfig().forLanguage("csharp").calls)
        assertEquals(setOf("Debug", "Trace", "Contract"), ErrorHandlingConfig().forLanguage("csharp").qualifiers)
        assertTrue(ErrorHandlingConfig().forLanguage("kotlin").detectThrows)
    }

    @Test
    fun errorHandlingConfigReplacesOnlyNamedValues() {
        val config =
            ErrorHandlingConfig()
                .forLanguage("csharp")
                .withOverrides(
                    mapOf(
                        "calls" to listOf("fail"),
                        "qualifiers" to listOf("Checks"),
                        "throws" to false,
                    ),
                    "csharp",
                )

        assertEquals(setOf("fail"), config.calls)
        assertEquals(setOf("Checks"), config.qualifiers)
        assertEquals(false, config.detectThrows)
    }

    @Test
    fun errorHandlingRejectsUnknownKeysAndBadValues() {
        val unknown =
            assertFailsWith<RulePackConfigException> {
                ErrorHandlingRulePack().configure(mapOf("kotlin" to mapOf("bogus" to true)))
            }
        assertTrue(unknown.message.orEmpty().contains("error-handling.kotlin.bogus: unknown key"))

        val badValue =
            assertFailsWith<RulePackConfigException> {
                ErrorHandlingRulePack().configure(mapOf("kotlin" to mapOf("throws" to "yes")))
            }
        assertTrue(badValue.message.orEmpty().contains("error-handling.kotlin.throws: expected a boolean"))
    }

    @Test
    fun errorHandlingAcceptsDetectThrowsAlias() {
        val config = ErrorHandlingConfig().forLanguage("kotlin").withOverrides(mapOf("detectThrows" to false), "kotlin")

        assertEquals(false, config.detectThrows)
    }

    @Test
    fun bookkeepingConfigIsTypedAndStrict() {
        val config =
            BookkeepingConfig.from(
                mapOf(
                    "annotation" to "Tracked",
                    "magicComment" to "tracked",
                ),
            )

        assertEquals("Tracked", config.annotation)
        assertEquals("tracked", config.magicComment)

        val grammarError =
            assertFailsWith<RulePackConfigException> {
                BookkeepingRulePack().configure(mapOf("propertyKinds" to listOf("my_property")))
            }
        assertTrue(grammarError.message.orEmpty().contains("bookkeeping.propertyKinds: unknown key"))

        val error =
            assertFailsWith<RulePackConfigException> {
                BookkeepingRulePack().configure(mapOf("bogus" to true))
            }
        assertTrue(error.message.orEmpty().contains("bookkeeping.bogus: unknown key"))
    }

    @Test
    fun validationConfigIsTypedAndStrict() {
        val config =
            ValidationConfig.from(
                mapOf(
                    "predicates" to listOf("isValid"),
                    "predicatePrefixes" to listOf("is", "can"),
                    "magicComment" to "guard",
                    "commentKinds" to listOf("line_comment"),
                ),
            )

        assertEquals(setOf("isValid"), config.predicates)
        assertEquals(listOf("is", "can"), config.predicatePrefixes)
        assertEquals("guard", config.magicComment)
        assertEquals(setOf("line_comment"), config.commentKinds)
        val error =
            assertFailsWith<RulePackConfigException> {
                ValidationRulePack().configure(mapOf("predicatePrefix" to listOf("is")))
            }
        assertTrue(error.message.orEmpty().contains("validation.predicatePrefix: unknown key"))
    }

    @Test
    fun configuredPacksKeepTheirDistributionIdentity() {
        assertIs<ErrorHandlingRulePack>(
            ErrorHandlingRulePack().configure(mapOf("javascript" to mapOf("calls" to listOf("audit")))),
        ).let { assertEquals("error-handling", it.id) }
        assertIs<BookkeepingRulePack>(
            BookkeepingRulePack().configure(mapOf("annotation" to "Tracked")),
        ).let { assertEquals("bookkeeping", it.id) }
        assertIs<ValidationRulePack>(
            ValidationRulePack().configure(mapOf("predicates" to listOf("isValid"))),
        ).let { assertEquals("validation", it.id) }
    }
}
