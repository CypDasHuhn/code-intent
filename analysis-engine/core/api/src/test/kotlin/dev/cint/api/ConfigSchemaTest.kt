package dev.cint.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigSchemaTest {
    @Test
    fun schemaDecodesDefaultsAndOverrides() {
        val config =
            testSchema.decode(
                TestConfig(),
                mapOf(
                    "name" to "override",
                    "enabled" to false,
                    "flags" to listOf("audit", "trace"),
                    "wrappers" to mapOf("root" to listOf("export_statement")),
                ),
            )

        assertEquals("override", config.name)
        assertEquals(false, config.enabled)
        assertEquals(setOf("audit", "trace"), config.flags)
        assertEquals(setOf("export_statement"), config.wrappers.getValue("root"))
    }

    @Test
    fun schemaReportsUnknownKeysAndBadValuesTogether() {
        val result =
            testSchema.decodeResult(
                TestConfig(),
                mapOf("bogus" to true, "flags" to listOf(1)),
            )

        assertEquals(TestConfig(), result.value)
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.any { it == "bogus: unknown key" })
        assertTrue(result.errors.any { it == "flags: expected a string or list of strings" })

        val error =
            assertFailsWith<RulePackConfigException> {
                testSchema.decode(TestConfig(), mapOf("bogus" to true), "test")
            }
        assertTrue(error.message.orEmpty().contains("test.bogus: unknown key"))
    }

    @Test
    fun languageConfigResolvesCaseAndRejectsUnknownLanguages() {
        val defaults = LanguageConfig.of("kotlin" to TestConfig(), "javascript" to TestConfig(name = "js"))

        assertEquals("js", defaults.forLanguage("JavaScript")?.name)
        assertEquals("default", defaults.forLanguage("missing", "kotlin")?.name)

        val overridden =
            defaults.withOverrides(
                mapOf("KOTLIN" to mapOf("flags" to listOf("audit"))),
                "pack",
                testSchema,
            )
        assertEquals(setOf("audit"), overridden.forLanguage("kotlin")?.flags)
        assertEquals("js", overridden.forLanguage("javascript")?.name)

        val error =
            assertFailsWith<RulePackConfigException> {
                defaults.withOverrides(mapOf("vue" to emptyMap<String, Any?>()), "pack", testSchema)
            }
        assertTrue(error.message.orEmpty().contains("pack.vue: unknown language"))
    }

    @Test
    fun languageKeyedSchemaDecodesThroughLanguageConfig() {
        val schema =
            LanguageConfig.schema<TestConfig, LanguageConfig<TestConfig>>(
                languageSchema = testSchema,
                get = { it.values },
                set = { _, languages -> LanguageConfig(languages) },
            )
        val defaults = LanguageConfig.of("kotlin" to TestConfig())
        val decoded =
            schema.decode(
                defaults,
                mapOf("KOTLIN" to mapOf("name" to "audit")),
                "logging",
            )

        assertEquals("audit", decoded.forLanguage("kotlin")?.name)

        val unknownLanguage =
            schema.decodeResult(
                defaults,
                mapOf("vue" to emptyMap<String, Any?>()),
                "logging",
            )
        assertEquals(defaults, unknownLanguage.value)
        assertTrue(unknownLanguage.errors.contains("logging.vue: unknown language"))

        val unknownKey =
            schema.decodeResult(
                defaults,
                mapOf("kotlin" to mapOf("bogus" to true)),
                "logging",
            )
        assertEquals(defaults, unknownKey.value)
        assertTrue(unknownKey.errors.contains("logging.kotlin.bogus: unknown key"))

        val invalidValue =
            schema.decodeResult(
                defaults,
                mapOf("kotlin" to mapOf("flags" to mapOf("nested" to true))),
                "logging",
            )
        assertEquals(defaults, invalidValue.value)
        assertTrue(invalidValue.errors.contains("logging.kotlin.flags: expected a string or list of strings"))

        val error =
            assertFailsWith<RulePackConfigException> {
                schema.decode(
                    defaults,
                    mapOf("vue" to emptyMap<String, Any?>()),
                    "logging",
                )
            }
        assertTrue(error.message.orEmpty().contains("logging.vue: unknown language"))
    }

    @Test
    fun schemaDescriptorReportsFieldKindsAndLanguageScope() {
        val descriptor = LanguageConfigTestPack().configSchema().describe()
        assertTrue(descriptor.fields.isEmpty())

        val language = assertNotNull(descriptor.language)
        assertEquals(
            listOf("name", "enabled", "flags", "wrappers"),
            language.fields.map { it.key },
        )
        assertEquals(ConfigFieldKind.STRING, language.fields.first { it.key == "name" }.kind)
        assertEquals(ConfigFieldKind.BOOLEAN, language.fields.first { it.key == "enabled" }.kind)
        assertEquals(ConfigFieldKind.STRINGS, language.fields.first { it.key == "flags" }.kind)
        assertEquals(ConfigFieldKind.STRING_SETS, language.fields.first { it.key == "wrappers" }.kind)
        assertTrue(language.fields.all { it.scope == ConfigFieldScope.LANGUAGE })
    }

    @Test
    fun packDescriptorIncludesRulesAndLanguageScopedFields() {
        val descriptor = LanguageConfigTestPack().configDescriptor()

        assertEquals("language-test", descriptor.packId)
        assertEquals(setOf("kotlin", "javascript"), descriptor.languages)
        assertTrue(descriptor.rules.isEmpty())
        assertEquals(
            listOf("name", "enabled", "flags", "wrappers"),
            descriptor.fields.map { it.key },
        )
        assertTrue(descriptor.fields.all { it.scope == ConfigFieldScope.LANGUAGE })
        assertTrue(descriptor.fields.all { it.languages == listOf("javascript", "kotlin") })

        val rules = ConfigTestPack().configDescriptor().rules
        assertEquals(listOf("test:test"), rules.map { it.id })
        assertEquals("TEST", rules.single().owns)
        assertEquals(setOf("DEPENDENCY"), rules.single().reads)
    }

    @Test
    fun schemaAdditiveDecodeUnionsCollectionsAndReplacesScalars() {
        val current =
            TestConfig(
                name = "base",
                enabled = true,
                flags = setOf("base", "shared"),
                wrappers = mapOf("root" to setOf("a")),
            )
        val decoded =
            testSchema.decodeAdditive(
                current,
                mapOf(
                    "name" to "overlay",
                    "enabled" to false,
                    "flags" to listOf("audit", "shared"),
                    "wrappers" to mapOf("root" to listOf("b"), "extra" to listOf("c")),
                ),
            )

        assertEquals("overlay", decoded.name)
        assertEquals(false, decoded.enabled)
        assertEquals(setOf("base", "shared", "audit"), decoded.flags)
        assertEquals(setOf("a", "b"), decoded.wrappers.getValue("root"))
        assertEquals(setOf("c"), decoded.wrappers.getValue("extra"))
    }

    @Test
    fun additiveStringListUnionsAndDeduplicates() {
        val current = ListConfig(names = listOf("base", "shared"))

        val fromList = listSchema.decodeAdditive(current, mapOf("names" to listOf("shared", "overlay")))
        assertEquals(listOf("base", "shared", "overlay"), fromList.names)

        val fromScalar = listSchema.decodeAdditive(current, mapOf("names" to "scalar"))
        assertEquals(listOf("base", "shared", "scalar"), fromScalar.names)
    }

    @Test
    fun oldStyleConfigurablePackStillYieldsMetadataWithEmptyFields() {
        val descriptor = LegacyConfigurablePack().configDescriptor()

        assertEquals("legacy", descriptor.packId)
        assertEquals(listOf("legacy:rule"), descriptor.rules.map { it.id })
        assertEquals(setOf("kotlin"), descriptor.languages)
        assertTrue(descriptor.fields.isEmpty())
    }

    @Test
    fun supportSurfaceRemainsAdditiveAtVersionFive() {
        assertEquals(5, CINT_API_VERSION)

        val matcher = Matcher { emptyList() }
        val schema: ConfigSchema<TestConfig> = testSchema
        val configValues = ConfigValues
        val languages: LanguageConfig<TestConfig> = LanguageConfig.of("kotlin" to TestConfig())
        val decoded: ConfigDecodeResult<TestConfig> = schema.decodeResult(TestConfig(), emptyMap())
        val pack: SingleIntentPack<TestConfig> = ConfigTestPack()

        assertTrue(matcher.match(TestView()).isEmpty())
        assertEquals(setOf("x"), configValues.strings(listOf("x"), "path", mutableListOf()))
        assertEquals("default", languages.forLanguage("kotlin")?.name)
        assertEquals(TestConfig(), decoded.value)
        assertEquals(CINT_API_VERSION, pack.apiVersion)
        assertEquals(1, pack.rules().size)
        assertTrue(
            pack
                .rules()
                .single()
                .id
                .startsWith("${pack.id}:"),
        )
        assertEquals(pack.intent, pack.rules().single().owns)
    }
}

class ConfigPackTest {
    @Test
    fun singleIntentPackUsesTypedConfigAndUnionsMatchers() {
        val pack = ConfigTestPack()
        val rule = pack.rules().single()

        assertEquals("test", pack.id)
        assertEquals("TEST", pack.intent)
        assertEquals(setOf("kotlin"), pack.languages)
        assertEquals("test:test", rule.id)
        assertEquals("TEST", rule.owns)
        assertEquals(setOf("DEPENDENCY"), rule.reads)
        assertEquals(listOf("import", "default"), rule.run(TestView(listOf("import"))).map { it.intents.single() })

        val configured = pack.configure(mapOf("name" to "configured", "flags" to listOf("audit"))) as ConfigTestPack
        assertEquals(
            listOf("configured"),
            configured
                .rules()
                .single()
                .run(TestView())
                .map { it.intents.single() },
        )
        assertEquals("test", configured.id)
    }

    @Test
    fun matcherUnionPreservesOrder() {
        val first = Matcher { listOf(emit("first")) }
        val second = Matcher { listOf(emit("second")) }

        assertEquals(listOf("first", "second"), (first union second).match(TestView()).map { it.intents.single() })
        assertEquals(
            listOf("first", "second"),
            matcherUnion(first, second).match(TestView()).map { it.intents.single() },
        )
    }
}
