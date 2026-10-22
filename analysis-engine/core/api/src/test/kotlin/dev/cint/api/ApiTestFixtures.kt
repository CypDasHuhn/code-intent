package dev.cint.api

import dev.cint.api.RuleEmit

internal data class TestConfig(
    val name: String = "default",
    val enabled: Boolean = true,
    val flags: Set<String> = setOf("base"),
    val wrappers: Map<String, Set<String>> = mapOf("root" to setOf("wrapper")),
)

internal data class ListConfig(
    val names: List<String> = listOf("default"),
)

internal val listSchema =
    ConfigSchema.of(
        ConfigField.stringList<ListConfig>(
            "names",
            { it.names },
            { config, value -> config.copy(names = value) },
        ),
    )

internal val testSchema =
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

internal class TestView(
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

internal class ConfigTestPack(
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

internal class LegacyConfigurablePack : ConfigurableRulePack {
    override val id: String = "legacy"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack = this

    override fun rules(): List<Rule> =
        listOf(
            object : Rule {
                override val id: String = "legacy:rule"
                override val owns: Intent = "LEGACY"
                override val reads: Set<Intent> = emptySet()

                override fun run(view: View): List<RuleEmit> = emptyList()
            },
        )
}

internal class LanguageConfigTestPack : ConfigurableRulePack {
    override val id: String = "language-test"
    override val languages: Set<String> = setOf("kotlin", "javascript")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack = this

    override fun configSchema(): ConfigSchema<*> =
        LanguageConfig.schema<TestConfig, LanguageConfig<TestConfig>>(
            languageSchema = testSchema,
            get = { it.values },
            set = { _, languages -> LanguageConfig(languages) },
        )

    override fun rules(): List<Rule> = emptyList()
}

internal fun emit(label: String): Emit =
    Emit(
        intents = setOf(label),
        span = Span(0, 0, 0, 0),
        source = "test",
        confidence = 1.0,
        startByte = 0,
        endByte = 0,
    )

internal class TestNode(
    override val kind: String,
    override val startByte: Int,
    override val endByte: Int,
    override val text: String,
    override val parent: SyntaxNode? = null,
    override val children: List<SyntaxNode> = emptyList(),
) : SyntaxNode {
    override val startLine: Int get() = 0
    override val startCol: Int get() = startByte
    override val endLine: Int get() = 0
    override val endCol: Int get() = endByte
}

internal class TestRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
) : Rule {
    override fun run(view: View): List<RuleEmit> = emptyList()
}

internal class TestPack(
    override val id: String,
    override val languages: Set<String> = setOf("kotlin"),
    override val apiVersion: Int = CINT_API_VERSION,
    private val entries: List<Rule>,
) : RulePack {
    override fun rules(): List<Rule> = entries
}

internal class TestConfigurablePack(
    private val extra: Boolean = false,
) : ConfigurableRulePack {
    override val id: String = "demo-config"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> =
        listOf(TestRule("demo-config:base", "CONFIG_BASE")) +
            if (extra) listOf(TestRule("demo-config:extra", "CONFIG_EXTRA")) else emptyList()

    override fun configure(values: Map<String, Any?>): RulePack = TestConfigurablePack(values["extra"] == true)
}

@RulePackApi(CINT_API_VERSION + 1)
internal class TestAnnotatedPack : RulePack {
    override val id: String = "annotated"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION + 1

    override fun rules(): List<Rule> = emptyList()
}

internal fun resolve(packs: List<RulePack>): List<Relation> {
    val owners = mutableMapOf<Intent, Rule>()
    for (rule in packs.flatMap { it.rules() }) {
        require(owners.putIfAbsent(rule.owns, rule) == null) { "duplicate owner for intent ${rule.owns}" }
    }
    return packs.flatMap { it.rules() }.flatMap { reader ->
        reader.reads.map { intent ->
            val owner = owners[intent] ?: throw IllegalArgumentException("unknown intent '$intent' read by ${reader.id}")
            Relation(reader, intent, owner)
        }
    }
}
