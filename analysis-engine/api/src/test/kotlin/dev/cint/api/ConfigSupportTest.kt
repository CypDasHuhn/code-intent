package dev.cint.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class TestConfig(
    val name: String = "default",
    val enabled: Boolean = true,
    val flags: Set<String> = setOf("base"),
    val wrappers: Map<String, Set<String>> = mapOf("root" to setOf("wrapper")),
)

private val testSchema =
    ConfigSchema.of(
        ConfigField.string<TestConfig>("name", { it.name }, { config, value -> config.copy(name = value) }),
        ConfigField.boolean<TestConfig>("enabled", { it.enabled }, { config, value -> config.copy(enabled = value) }),
        ConfigField.strings<TestConfig>("flags", { it.flags }, { config, value -> config.copy(flags = value) }),
        ConfigField.stringSets<TestConfig>(
            "wrappers",
            { it.wrappers },
            { config, value -> config.copy(wrappers = value) },
        ),
    )

private class TestView(
    private val importNames: List<String> = emptyList(),
) : View {
    override fun language(): String = "kotlin"

    override fun typeAt(node: SyntaxNode): TypeRef? = null

    override fun receiverType(call: CallFact): TypeRef? = null

    override fun calls(): List<CallFact> = emptyList()

    override fun properties(): List<PropertyFact> = emptyList()

    override fun functions(): List<FunctionFact> = emptyList()

    override fun declarations(): List<DeclarationFact> = emptyList()

    override fun writes(): List<WriteFact> = emptyList()

    override fun comments(): List<CommentFact> = emptyList()

    override fun commentBefore(node: SyntaxNode): CommentFact? = null

    override fun scopes(): List<ScopeFact> = emptyList()

    override fun enclosingScope(node: SyntaxNode): ScopeFact? = null

    override fun enclosingBlock(node: SyntaxNode): SyntaxNode? = null

    override fun guards(): List<GuardFact> = emptyList()

    override fun returnSites(): List<ReturnFact> = emptyList()

    override fun ifs(): List<IfFact> = emptyList()

    override fun guardConditions(): List<SyntaxNode> = emptyList()

    override fun tries(): List<TryFact> = emptyList()

    override fun catches(): List<SyntaxNode> = emptyList()

    override fun imports(): List<String> = importNames

    override fun references(): Map<String, List<SyntaxNode>> = emptyMap()

    override fun throws(): List<SyntaxNode> = emptyList()

    override fun coverage(node: SyntaxNode): Coverage = Coverage(emptySet())

    override fun enclosingFunction(node: SyntaxNode): SyntaxNode? = null

    override fun enclosingDeclaration(node: SyntaxNode): SyntaxNode? = null

    override fun writes(reference: SyntaxNode): SyntaxNode? = null

    override fun isAnnotatedPure(node: SyntaxNode): Boolean = false

    override fun isConstant(node: SyntaxNode): Boolean = false

    override fun isSideEffectFree(node: SyntaxNode): Boolean = false

    override fun statements(container: SyntaxNode): List<SyntaxNode>? = null

    override fun isExit(node: SyntaxNode): Boolean = false

    override fun annotations(intent: Intent): List<Annotation> = emptyList()

    override fun intentsAt(node: SyntaxNode): Set<Intent> = emptySet()
}

private class ConfigTestPack(
    config: TestConfig = TestConfig(),
) : SingleIntentPack<TestConfig>(
        id = "test",
        intent = "TEST",
        languages = setOf("kotlin"),
        config = config,
        schema = testSchema,
        ruleId = "test:test",
    ) {
    override val reads: Set<Intent> = setOf("DEPENDENCY")

    override val matchers: List<Matcher> =
        listOf(
            Matcher { view -> view.imports().map { name -> emit(name) } },
            Matcher { view -> if (view.language() == "kotlin") listOf(emit(config.name)) else emptyList() },
        )

    override fun withConfig(config: TestConfig): SingleIntentPack<TestConfig> = ConfigTestPack(config)
}

private fun emit(evidence: String): Emit =
    Emit(
        intents = setOf("TEST"),
        evidence = evidence,
        span = Span(0, 0, 0, 0),
        source = "test",
        confidence = 1.0,
        startByte = 0,
        endByte = 0,
    )

class ConfigSupportTest {
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
    fun singleIntentPackUsesTypedConfigAndUnionsMatchers() {
        val pack = ConfigTestPack()
        val rule = pack.rules().single()

        assertEquals("test", pack.id)
        assertEquals("TEST", pack.intent)
        assertEquals(setOf("kotlin"), pack.languages)
        assertEquals("test:test", rule.id)
        assertEquals("TEST", rule.owns)
        assertEquals(setOf("DEPENDENCY"), rule.reads)
        assertEquals(listOf("import", "default"), rule.run(TestView(listOf("import"))).map { it.evidence })

        val configured = pack.configure(mapOf("name" to "configured", "flags" to listOf("audit"))) as ConfigTestPack
        assertEquals(
            listOf("configured"),
            configured
                .rules()
                .single()
                .run(TestView())
                .map { it.evidence },
        )
        assertEquals("test", configured.id)
    }

    @Test
    fun matcherUnionPreservesOrder() {
        val first = Matcher { listOf(emit("first")) }
        val second = Matcher { listOf(emit("second")) }

        assertEquals(listOf("first", "second"), (first union second).match(TestView()).map { it.evidence })
        assertEquals(
            listOf("first", "second"),
            matcherUnion(first, second).match(TestView()).map { it.evidence },
        )
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
    fun supportSurfaceRemainsAdditiveAtVersionThree() {
        assertEquals(3, CINT_API_VERSION)

        val matcher: Matcher = Matcher { emptyList() }
        val schema: ConfigSchema<TestConfig> = testSchema
        val configValues: ConfigValues = ConfigValues
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
