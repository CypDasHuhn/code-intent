package dev.cint.rulepacks.intents.service

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.DeclarationFact
import dev.cint.api.DeclarationKind
import dev.cint.api.Emit
import dev.cint.api.FunctionFact
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.api.emit

const val SERVICE_DELEGATION_INTENT: Intent = "SERVICE_DELEGATION"
const val SERVICE_INIT_INTENT: Intent = "SERVICE_INIT"
const val SERVICE_DELEGATION_RULE_ID: String = "$SERVICE_PACK_ID:delegation"
const val SERVICE_INIT_RULE_ID: String = "$SERVICE_PACK_ID:init"

private const val HEURISTIC_SOURCE: String = "heuristic"

class ServiceRulePack(
    private val config: ServiceConfig = ServiceConfig(),
) : ConfigurableRulePack {
    override val id: String = SERVICE_PACK_ID
    override val languages: Set<String> = config.languages.keys
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(ServiceDelegationRule(config), ServiceInitRule(config))

    override fun configure(values: Map<String, Any?>): ConfigurableRulePack =
        if (values.isEmpty()) this else ServiceRulePack(ServiceConfig.from(values))

    override fun configureAdditive(values: Map<String, Any?>): ConfigurableRulePack =
        if (values.isEmpty()) this else ServiceRulePack(config.withAdditiveOverrides(values))

    override fun configSchema(): ConfigSchema<*> = ServiceConfig.SCHEMA
}

private data class Evidence(
    val source: String,
    val confidence: Double,
)

private class ServiceDelegationRule(
    private val config: ServiceConfig,
) : Rule {
    override val id: String = SERVICE_DELEGATION_RULE_ID
    override val owns: Intent = SERVICE_DELEGATION_INTENT
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> {
        val language = config.forLanguage(view.language())
        val result = mutableListOf<RuleEmit>()

        for (function in view.functions()) {
            val units = bodyUnits(view, function)
            if (units.isEmpty()) continue

            val calls = functionCalls(view, function)
            val matched =
                calls.mapNotNull { call ->
                    delegationEvidence(view, call, language)?.let { call to it }
                }
            val patterned = matchesControllerOrFacade(view, function, language)
            val evidence =
                matched.ifEmpty {
                    if (patterned) {
                        outermost(calls).map { it to Evidence(HEURISTIC_SOURCE, 0.6) }
                    } else {
                        emptyList()
                    }
                }
            if (evidence.isEmpty()) continue

            val forwarding =
                outermost(evidence.map { it.first }).firstNotNullOfOrNull { call ->
                    evidence.firstOrNull { it.first === call }
                } ?: continue

            if (isThin(view, units)) {
                result += forwarding.first.emit(SERVICE_DELEGATION_INTENT, forwarding.second.source, forwarding.second.confidence)
            } else if (mostlyDelegating(units.size, evidence.size)) {
                result +=
                    CandidateEmit.call(
                        SERVICE_DELEGATION_INTENT,
                        forwarding.first,
                        CandidateReason.LOW_CONFIDENCE,
                        source = "rule:$SERVICE_DELEGATION_RULE_ID",
                        confidence = 0.4,
                    )
            }
        }

        return result
    }
}

private class ServiceInitRule(
    private val config: ServiceConfig,
) : Rule {
    override val id: String = SERVICE_INIT_RULE_ID
    override val owns: Intent = SERVICE_INIT_INTENT
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> {
        val language = config.forLanguage(view.language())
        val result = mutableListOf<RuleEmit>()
        val emitted = mutableSetOf<Pair<Int, Int>>()

        for (call in view.calls()) {
            val evidence = initCallEvidence(view, call, language) ?: continue
            if (emitted.add(call.node.startByte to call.node.endByte)) {
                result += call.emit(SERVICE_INIT_INTENT, evidence.source, evidence.confidence)
            }
        }

        for (declaration in view.declarations()) {
            declarationAnnotationEvidence(declaration, language)?.let { evidence ->
                if (emitted.add(declaration.node.startByte to declaration.node.endByte)) {
                    result += declaration.emit(SERVICE_INIT_INTENT, evidence.source, evidence.confidence)
                }
            }
            declarationLifecycleEvidence(declaration, language)?.let { evidence ->
                if (emitted.add(declaration.node.startByte to declaration.node.endByte)) {
                    result += declaration.emit(SERVICE_INIT_INTENT, evidence.source, evidence.confidence)
                }
            }
            val initializer =
                view
                    .properties()
                    .firstOrNull {
                        it.node.startByte == declaration.node.startByte &&
                            it.node.endByte == declaration.node.endByte
                    }?.initializer
            creationEvidence(view, initializer, language)?.let { evidence ->
                if (emitted.add(evidence.first.startByte to evidence.first.endByte)) {
                    result += Emit.of(SERVICE_INIT_INTENT, evidence.first, evidence.second.source, evidence.second.confidence)
                }
            }
        }

        val lifecycleNames = language.initMethods
        for (function in view.functions()) {
            val name = function.name ?: continue
            if (name in lifecycleNames) continue
            if (name == enclosingClassName(view, function)) continue
            val evidence =
                functionCalls(view, function).firstNotNullOfOrNull { call ->
                    initCallEvidence(view, call, language)
                } ?: continue
            result +=
                CandidateEmit
                    .of(
                        SERVICE_INIT_INTENT,
                        function.nameNode ?: function.node,
                        CandidateReason.LOW_CONFIDENCE,
                        source = "rule:$SERVICE_INIT_RULE_ID",
                        confidence = 0.4,
                    ).copy(rationale = "hidden initialization helper")
        }

        return result
    }

    private fun declarationAnnotationEvidence(
        declaration: DeclarationFact,
        language: ServiceLanguageConfig,
    ): Evidence? {
        if (language.diAnnotations.none { declaration.hasAnnotation(it) }) return null
        return Evidence("annotation", 0.95)
    }

    private fun declarationLifecycleEvidence(
        declaration: DeclarationFact,
        language: ServiceLanguageConfig,
    ): Evidence? {
        if (declaration.kind != DeclarationKind.FUNCTION) return null
        val name = declaration.name ?: return null
        if (name !in language.initMethods) return null
        return Evidence(HEURISTIC_SOURCE, 0.8)
    }

    private fun creationEvidence(
        view: View,
        initializer: SyntaxNode?,
        language: ServiceLanguageConfig,
    ): Pair<SyntaxNode, Evidence>? {
        if (initializer == null) return null
        val hasInitCall =
            view.calls().any { call ->
                within(call.node, initializer) && initCallEvidence(view, call, language) != null
            }
        if (hasInitCall) return null
        val type = NEW_TYPE.find(initializer.text)?.groupValues?.get(1) ?: return null
        if (!typeMatches(type, language.initTypes, emptySet())) return null
        return initializer to Evidence(Emit.DEFAULT_SOURCE, 0.85)
    }

    private fun initCallEvidence(
        view: View,
        call: CallFact,
        language: ServiceLanguageConfig,
    ): Evidence? {
        val callee = call.callee ?: return null
        val path = call.path ?: callee
        if (callee in language.initCalls || pathMatches(path, language.initCalls)) {
            return Evidence(Emit.DEFAULT_SOURCE, 0.9)
        }
        if (language.initQualifiers.any { path == it || path.startsWith("$it.") }) {
            return Evidence(Emit.DEFAULT_SOURCE, 0.85)
        }
        if (callee in language.diCalls) return Evidence(Emit.DEFAULT_SOURCE, 0.8)
        if (callee in language.connectionCalls) return Evidence(Emit.DEFAULT_SOURCE, 0.8)
        if (language.connectionQualifiers.any { path == it || path.startsWith("$it.") }) {
            return Evidence(Emit.DEFAULT_SOURCE, 0.8)
        }
        if (call.receiver == null && typeMatches(callee, language.initTypes, emptySet())) {
            return Evidence(Emit.DEFAULT_SOURCE, 0.9)
        }
        val receiver = call.receiver ?: return null
        val type = view.receiverType(call)?.fqName ?: view.typeAt(receiver)?.fqName ?: return null
        if (!typeMatches(type, language.initTypes, emptySet())) return null
        return Evidence(Emit.DEFAULT_SOURCE, 0.85)
    }

    companion object {
        private val NEW_TYPE = Regex("""\bnew\s+([A-Za-z_][A-Za-z0-9_.]*)""")
    }
}

private fun bodyUnits(
    view: View,
    function: FunctionFact,
): List<SyntaxNode> {
    val body = function.body
    if (body != null) {
        val statements = view.statements(body)
        if (statements != null) return statements.filter { it.text.isNotBlank() }
        return if (containsCall(view, body)) listOf(body) else emptyList()
    }
    val nameEnd = function.nameNode?.endByte ?: function.node.startByte
    return function.node.children
        .filter { it.startByte >= nameEnd && containsCall(view, it) }
        .lastOrNull()
        ?.let { listOf(it) }
        .orEmpty()
}

private fun containsCall(
    view: View,
    node: SyntaxNode,
): Boolean = view.calls().any { within(it.node, node) }

private fun isThin(
    view: View,
    units: List<SyntaxNode>,
): Boolean {
    if (units.size != 1) return false
    val unit = units.single()
    if (view.writes().any { within(it.node, unit) }) return false
    if (view.guards().any { within(it.node, unit) }) return false
    if (view.tries().any { within(it.node, unit) }) return false
    return true
}

private fun mostlyDelegating(
    units: Int,
    evidence: Int,
): Boolean = units > 0 && evidence > 0 && evidence * 2 >= units

private fun functionCalls(
    view: View,
    function: FunctionFact,
): List<CallFact> =
    view.calls().filter { call ->
        within(call.node, function.node) && sameFunction(view.enclosingFunction(call.node), function.node)
    }

private fun sameFunction(
    node: SyntaxNode?,
    function: SyntaxNode,
): Boolean = node != null && node.startByte == function.startByte && node.endByte == function.endByte

private fun within(
    node: SyntaxNode,
    container: SyntaxNode,
): Boolean = node.startByte >= container.startByte && node.endByte <= container.endByte

private fun outermost(calls: List<CallFact>): List<CallFact> =
    calls.filterNot { call ->
        calls.any { other ->
            other !== call && within(call.node, other.node) && other.node.startByte != call.node.startByte
        }
    }

private fun delegationEvidence(
    view: View,
    call: CallFact,
    language: ServiceLanguageConfig,
): Evidence? {
    val callee = call.callee ?: return null
    val path = call.path ?: callee
    if (callee in language.delegationCalls || pathMatches(path, language.delegationCalls)) {
        return Evidence(Emit.DEFAULT_SOURCE, 0.9)
    }
    if (language.delegationQualifiers.any { path == it || path.startsWith("$it.") }) {
        return Evidence(Emit.DEFAULT_SOURCE, 0.85)
    }
    val receiver = call.receiver ?: return null
    val receiverName = receiver.text.substringAfterLast('.').trim('_')
    if (receiverName in language.serviceNames) return Evidence(HEURISTIC_SOURCE, 0.75)
    if (language.serviceSuffixes.any { receiverName.endsWith(it, ignoreCase = true) }) {
        return Evidence(HEURISTIC_SOURCE, 0.75)
    }
    val type = view.receiverType(call)?.fqName ?: view.typeAt(receiver)?.fqName ?: return null
    if (!typeMatches(type, language.serviceTypes, language.serviceSuffixes)) return null
    return Evidence(Emit.DEFAULT_SOURCE, 0.8)
}

private fun matchesControllerOrFacade(
    view: View,
    function: FunctionFact,
    language: ServiceLanguageConfig,
): Boolean {
    val name = enclosingClassName(view, function) ?: return false
    return language.controllerSuffixes.any { name.endsWith(it) } ||
        language.facadeSuffixes.any { name.endsWith(it) }
}

private fun enclosingClassName(
    view: View,
    function: FunctionFact,
): String? {
    val owner = view.enclosingScope(function.node)?.owner ?: return null
    return owner.childByField("name")?.text ?: TYPE_NAME.find(owner.text)?.groupValues?.get(1)
}

private val TYPE_NAME = Regex("""\b(?:class|object|interface|record|struct)\s+([A-Za-z_][A-Za-z0-9_]*)""")

private fun typeMatches(
    actual: String,
    configured: Set<String>,
    suffixes: Set<String>,
): Boolean {
    val simple = actual.substringAfterLast('.')
    return configured.any { expected ->
        actual == expected ||
            actual.endsWith(".$expected") ||
            (!actual.contains('.') && expected.substringAfterLast('.') == actual)
    } || suffixes.any { simple.endsWith(it) }
}

private fun pathMatches(
    path: String,
    names: Set<String>,
): Boolean =
    names.any { name ->
        path == name || path.endsWith(".$name")
    }
