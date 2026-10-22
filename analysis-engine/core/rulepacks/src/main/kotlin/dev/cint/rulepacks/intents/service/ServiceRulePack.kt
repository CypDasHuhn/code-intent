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
import dev.cint.api.ScopeKind
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.intents.callbackParameterAt
import dev.cint.rulepacks.intents.isTypeQueryableReceiver
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT

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
        if (values.isEmpty()) this else ServiceRulePack(config.withOverrides(values))

    override fun configureAdditive(values: Map<String, Any?>): ConfigurableRulePack =
        if (values.isEmpty()) this else ServiceRulePack(config.withAdditiveOverrides(values))

    override fun configSchema(): ConfigSchema<*> = ServiceConfig.SCHEMA
}

private data class Evidence(
    val source: String,
    val confidence: Double,
    val candidateOnly: Boolean = false,
)

private data class Creation(
    val node: SyntaxNode,
    val type: String?,
)

private data class ForwardingUnit(
    val serviceCalls: List<Pair<CallFact, Evidence>>,
    val pureForwarding: Pair<CallFact, Evidence>?,
    val singleEffectCall: CallFact?,
)

private class ServiceDelegationRule(
    private val config: ServiceConfig,
) : Rule {
    override val id: String = SERVICE_DELEGATION_RULE_ID
    override val owns: Intent = SERVICE_DELEGATION_INTENT
    override val reads: Set<Intent> = emptySet()
    override val optionalReads: Set<Intent> = setOf(DATA_MODIFY_INTENT)

    override fun run(view: View): List<RuleEmit> {
        val language = config.forLanguage(view.language())
        val result = mutableListOf<RuleEmit>()

        for (function in view.functions()) {
            val units = bodyUnits(view, function)
            if (units.isEmpty()) continue

            val calls = functionCalls(view, function)
            val forwarding = units.map { analyzeUnit(view, it, calls, language) }
            val established = forwarding.singleOrNull()?.pureForwarding
            if (units.size == 1 && established != null) {
                result +=
                    Emit.of(SERVICE_DELEGATION_INTENT, established.first.node, established.second.source, established.second.confidence)
                continue
            }

            val forwardingUnits = forwarding.count { it.serviceCalls.isNotEmpty() }
            if (forwardingUnits > 0 && forwardingUnits * 2 >= units.size) {
                result += candidates(forwarding)
                continue
            }

            if (forwarding.none { it.serviceCalls.isNotEmpty() } && matchesControllerOrFacade(view, function, language)) {
                val call = forwarding.singleOrNull()?.singleEffectCall
                if (call != null && initCallEvidence(view, call, language) == null) {
                    result += delegationCandidate(call)
                }
            }
        }

        val emitted = result.mapTo(mutableSetOf()) { it.startByte to it.endByte }
        for (call in view.calls()) {
            if (callbackParameterAt(view, call) == null) continue
            if (DATA_MODIFY_INTENT in view.intentsAt(call.node)) continue
            if (emitted.add(call.node.startByte to call.node.endByte)) {
                result += delegationCandidate(call)
            }
        }

        return result
    }

    private fun candidates(forwarding: List<ForwardingUnit>): List<RuleEmit> {
        val seen = mutableSetOf<Pair<Int, Int>>()
        return forwarding.flatMap { unit ->
            unit.serviceCalls.mapNotNull { (call, _) ->
                if (seen.add(call.node.startByte to call.node.endByte)) delegationCandidate(call) else null
            }
        }
    }

    private fun delegationCandidate(call: CallFact): CandidateEmit =
        CandidateEmit.of(
            SERVICE_DELEGATION_INTENT,
            call.node,
            CandidateReason.LOW_CONFIDENCE,
            source = "rule:$SERVICE_DELEGATION_RULE_ID",
            confidence = 0.4,
        )
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
        val creations = creationNodes(view)
        val annotatedFunctions = mutableSetOf<Pair<Int, Int>>()
        val lifecycleFunctions = mutableSetOf<Pair<Int, Int>>()

        for (call in view.calls()) {
            val evidence = initCallEvidence(view, call, language) ?: continue
            if (emitted.add(call.node.startByte to call.node.endByte)) {
                result += initEmit(call.node, evidence)
            }
        }

        for ((node, type) in creations) {
            if (!typeMatches(type ?: continue, language.initTypes, language.initSuffixes)) continue
            if (emitted.add(node.startByte to node.endByte)) {
                result += Emit.of(SERVICE_INIT_INTENT, node, Emit.DEFAULT_SOURCE, 0.85)
            }
        }

        for (declaration in view.declarations()) {
            val annotationNodes = configuredAnnotationNodes(declaration, language)
            for (node in annotationNodes) {
                if (emitted.add(node.startByte to node.endByte)) {
                    result += Emit.of(SERVICE_INIT_INTENT, node, "annotation", 0.95)
                }
            }
            if (annotationNodes.isNotEmpty() && declaration.kind == DeclarationKind.FUNCTION) {
                annotatedFunctions += declaration.node.startByte to declaration.node.endByte
            }
        }

        for ((node, evidence) in constructorInjectionEvidence(view, language)) {
            if (emitted.add(node.startByte to node.endByte)) {
                result += Emit.of(SERVICE_INIT_INTENT, node, evidence.source, evidence.confidence)
            }
        }

        for ((node1, kind, name1, nameNode) in view.declarations()) {
            if (kind != DeclarationKind.FUNCTION) continue
            val name = name1 ?: continue
            if (name !in language.initMethods) continue
            val span = node1.startByte to node1.endByte
            lifecycleFunctions += span
            if (span in annotatedFunctions) continue
            val function = view.functions().firstOrNull { it.node.startByte == span.first && it.node.endByte == span.second } ?: continue
            val hasEvidence = functionHasInitEvidence(view, function, language, creations)
            val evidence =
                if (hasEvidence) {
                    Evidence(HEURISTIC_SOURCE, 0.8)
                } else {
                    Evidence(HEURISTIC_SOURCE, 0.4, candidateOnly = true)
                }
            val node = nameNode ?: node1
            if (emitted.add(node.startByte to node.endByte)) {
                result += initEmit(node, evidence)
            }
        }

        for (function in view.functions()) {
            val name = function.name ?: continue
            val span = function.node.startByte to function.node.endByte
            if (name in language.initMethods || span in annotatedFunctions || span in lifecycleFunctions) continue
            if (name == enclosingClassName(view, function)) continue
            if (!mostlyInit(view, function, language, creations)) continue
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

    private fun initEmit(
        node: SyntaxNode,
        evidence: Evidence,
    ): RuleEmit =
        if (evidence.candidateOnly) {
            CandidateEmit.of(
                SERVICE_INIT_INTENT,
                node,
                CandidateReason.LOW_CONFIDENCE,
                source = "rule:$SERVICE_INIT_RULE_ID",
                confidence = evidence.confidence,
            )
        } else {
            Emit.of(SERVICE_INIT_INTENT, node, evidence.source, evidence.confidence)
        }
}

private fun configuredAnnotationNodes(
    declaration: DeclarationFact,
    language: ServiceLanguageConfig,
): List<SyntaxNode> =
    declaration.annotationNodes.filter { node ->
        val name = annotationName(node.text)
        language.diAnnotations.any { configured -> name == configured.substringAfterLast('.') }
    }

private fun annotationName(text: String): String =
    text
        .trim()
        .trim('[', ']')
        .removePrefix("@")
        .substringBefore('(')
        .trim()
        .substringAfterLast('.')
        .substringAfterLast(':')

private fun constructorInjectionEvidence(
    view: View,
    language: ServiceLanguageConfig,
): List<Pair<SyntaxNode, Evidence>> {
    val result = mutableListOf<Pair<SyntaxNode, Evidence>>()
    val seenScopes = mutableSetOf<Pair<Int, Int>>()
    val languageName = view.language()

    for ((node, kind, owner) in view.scopes()) {
        if (kind != ScopeKind.CLASS) continue
        val classNode = owner ?: node
        if (!seenScopes.add(classNode.startByte to classNode.endByte)) continue
        val params = PRIMARY_CONSTRUCTOR.find(classNode.text)?.groupValues?.get(1) ?: continue
        for (parameter in splitParameters(params)) {
            val type = injectionParameterType(parameter, languageName) ?: continue
            if (!isWiringType(type, language)) continue
            result += (findTextNode(classNode, parameter) ?: classNode) to Evidence(HEURISTIC_SOURCE, 0.8)
        }
    }

    for ((node, name) in view.functions()) {
        val classNode = view.enclosingScope(node)?.owner ?: continue
        val className = classNameOf(classNode) ?: continue
        val constructor =
            name == className ||
                (languageName.equals("typescript", ignoreCase = true) && name == "constructor")
        if (!constructor) continue
        val params = FUNCTION_PARAMETERS.find(node.text)?.groupValues?.get(1) ?: continue
        for (parameter in splitParameters(params)) {
            val type = injectionParameterType(parameter, languageName) ?: continue
            if (!isWiringType(type, language)) continue
            result += (findTextNode(node, parameter) ?: node) to Evidence(HEURISTIC_SOURCE, 0.8)
        }
    }

    return result.distinctBy { it.first.startByte to it.first.endByte }
}

private fun injectionParameterType(
    parameter: String,
    language: String,
): String? {
    val declaration = parameter.substringBefore('=').trim()
    val property = PROPERTY_PARAMETER.find(declaration)
    if (property != null) return property.groupValues[2].trim()
    if (language.equals("csharp", ignoreCase = true)) return parameterType(declaration)
    if (language.equals("typescript", ignoreCase = true)) {
        return TYPESCRIPT_PARAMETER_PROPERTY
            .find(declaration)
            ?.groupValues
            ?.get(2)
            ?.trim()
    }
    return null
}

private fun functionHasInitEvidence(
    view: View,
    function: FunctionFact,
    language: ServiceLanguageConfig,
    creations: List<Creation>,
): Boolean =
    functionCalls(view, function).any { call ->
        val evidence = initCallEvidence(view, call, language)
        evidence != null && !evidence.candidateOnly
    } ||
        creations.any {
            creationWithin(view, it, function) &&
                typeMatches(it.type ?: return@any false, language.initTypes, language.initSuffixes)
        }

private fun mostlyInit(
    view: View,
    function: FunctionFact,
    language: ServiceLanguageConfig,
    creations: List<Creation>,
): Boolean {
    val units = bodyUnits(view, function)
    if (units.isEmpty()) return false
    val calls = functionCalls(view, function)
    val initUnits =
        units.count { unit ->
            calls.any { within(it.node, unit) && initCallEvidence(view, it, language) != null } ||
                creations.any {
                    within(it.node, unit) &&
                        creationWithin(view, it, function) &&
                        typeMatches(it.type ?: return@any false, language.initTypes, language.initSuffixes)
                }
        }
    return initUnits > 0 && initUnits * 2 >= units.size
}

private fun creationNodes(view: View): List<Creation> {
    val seen = mutableSetOf<Pair<Int, Int>>()
    return view
        .declarations()
        .flatMap { declaration -> declaration.node.descendants().map { declaration.node to it } }
        .mapNotNull { (container, node) ->
            if (isLiteralOrComment(node, container)) return@mapNotNull null
            val text = node.text.trim()
            val type = explicitCreationType(text) ?: if (TARGET_CREATION.containsMatchIn(text)) targetType(node) else null
            if (type == null) null else Creation(node, type)
        }.filter { seen.add(it.node.startByte to it.node.endByte) }
}

private fun creationWithin(
    view: View,
    creation: Creation,
    function: FunctionFact,
): Boolean =
    within(creation.node, function.node) &&
        sameFunction(view.enclosingFunction(creation.node), function.node)

private fun isLiteralOrComment(
    node: SyntaxNode,
    boundary: SyntaxNode,
): Boolean {
    var current: SyntaxNode? = node
    while (current != null) {
        if (current.startByte == boundary.startByte && current.endByte == boundary.endByte) return false
        val text = current.text.trim()
        if (text.startsWith("\"") || text.startsWith("'") || text.startsWith("`") || text.startsWith("//") || text.startsWith("/*")) {
            return true
        }
        current = current.parent
    }
    return false
}

private fun explicitCreationType(text: String): String? {
    val match = EXPLICIT_CREATION.find(text) ?: return null
    var index = match.range.last + 1
    while (index < text.length && text[index].isWhitespace()) index++
    if (index < text.length && text[index] == '<') {
        var depth = 0
        while (index < text.length) {
            when (text[index]) {
                '<' -> {
                    depth++
                }

                '>' -> {
                    depth--
                    if (depth == 0) {
                        index++
                        break
                    }
                }
            }
            index++
        }
        while (index < text.length && text[index].isWhitespace()) index++
    }
    if (index < text.length && text[index] == '(') {
        var depth = 0
        while (index < text.length) {
            when (text[index]) {
                '(' -> {
                    depth++
                }

                ')' -> {
                    depth--
                    if (depth == 0) {
                        index++
                        break
                    }
                }
            }
            index++
        }
    }
    val rest = text.substring(index).trim()
    return if (rest.isEmpty() || rest == ";" || rest.startsWith("{")) match.groupValues[1] else null
}

private fun targetType(node: SyntaxNode): String? {
    var current: SyntaxNode = node
    while (true) {
        val parent = current.parent ?: return null
        val offset = (node.startByte - parent.startByte).coerceIn(0, parent.text.length)
        val before = parent.text.substring(0, offset)
        ASSIGNED_TYPE.findAll(before).lastOrNull()?.let { return it.groupValues[1] }
        current = parent
    }
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
    if (call.receiver == null && typeMatches(callee, language.initTypes, language.initSuffixes)) {
        return Evidence(Emit.DEFAULT_SOURCE, 0.9)
    }
    val connectionCall = callee in language.connectionCalls || pathMatches(path, language.connectionCalls)
    val diCall = callee in language.diCalls || pathMatches(path, language.diCalls)
    if (!connectionCall && !diCall) return null
    val receiverName =
        call.receiver
            ?.text
            ?.substringAfterLast('.')
            ?.trim('_')
    val receiverType =
        call.receiver
            ?.takeIf(::isTypeQueryableReceiver)
            ?.let { view.receiverType(call)?.fqName }
    if (connectionCall) {
        return if (isConnectionReceiver(path, receiverName, receiverType, language)) {
            Evidence(Emit.DEFAULT_SOURCE, 0.85)
        } else {
            Evidence(HEURISTIC_SOURCE, 0.4, candidateOnly = true)
        }
    }
    if (diCall) {
        return if (isDiReceiver(path, receiverName, receiverType, language)) {
            Evidence(Emit.DEFAULT_SOURCE, 0.85)
        } else {
            Evidence(HEURISTIC_SOURCE, 0.4, candidateOnly = true)
        }
    }
    return null
}

private fun isConnectionReceiver(
    path: String,
    receiverName: String?,
    receiverType: String?,
    language: ServiceLanguageConfig,
): Boolean =
    language.connectionQualifiers.any { path.startsWith("$it.") || receiverName == it } ||
        (
            receiverType != null &&
                typeMatches(receiverType, language.initTypes + language.serviceTypes, language.serviceSuffixes + language.initSuffixes)
        )

private fun isDiReceiver(
    path: String,
    receiverName: String?,
    receiverType: String?,
    language: ServiceLanguageConfig,
): Boolean =
    language.diQualifiers.any { path.startsWith("$it.") || receiverName == it } ||
        (
            receiverType != null &&
                typeMatches(receiverType, language.serviceTypes, language.serviceSuffixes + language.initSuffixes)
        )

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
        .lastOrNull { it.startByte >= nameEnd && containsCall(view, it) }
        ?.let { listOf(it) }
        .orEmpty()
}

private fun containsCall(
    view: View,
    node: SyntaxNode,
): Boolean = view.calls().any { within(it.node, node) }

private fun analyzeUnit(
    view: View,
    unit: SyntaxNode,
    calls: List<CallFact>,
    language: ServiceLanguageConfig,
): ForwardingUnit {
    val unitCalls = calls.filter { within(it.node, unit) }
    val serviceEvidence =
        unitCalls.mapNotNull { call ->
            delegationEvidence(view, call, language)?.let { call to it }
        }
    val serviceCalls =
        outermost(serviceEvidence.map { it.first }).mapNotNull { call ->
            serviceEvidence.firstOrNull { it.first === call }
        }
    val clean = isClean(view, unit)
    val outermost = if (clean) outermost(unitCalls) else emptyList()
    val single = outermost.singleOrNull()
    val evidence = single?.let { call -> serviceEvidence.firstOrNull { it.first === call } }
    val pure =
        if (single != null &&
            evidence != null &&
            unitCalls.none { it !== single && within(it.node, single.node) } &&
            single.trailingLambda == null &&
            isWholeExpression(unit, single)
        ) {
            evidence
        } else {
            null
        }
    val singleEffect =
        single?.takeIf {
            unitCalls.none { call -> call !== single && within(call.node, single.node) } &&
                single.trailingLambda == null &&
                isWholeExpression(unit, single)
        }
    return ForwardingUnit(serviceCalls, pure, singleEffect)
}

private fun isClean(
    view: View,
    unit: SyntaxNode,
): Boolean =
    view.writes().none { within(it.node, unit) } &&
        view.guards().none { within(it.node, unit) } &&
        view.tries().none { within(it.node, unit) }

private fun isWholeExpression(
    unit: SyntaxNode,
    call: CallFact,
): Boolean {
    val start = (call.node.startByte - unit.startByte).coerceIn(0, unit.text.length)
    val end = (call.node.endByte - unit.startByte).coerceIn(start, unit.text.length)
    val prefix =
        unit.text
            .substring(0, start)
            .trim()
            .removePrefix("=>")
            .trim()
    val suffix = unit.text.substring(end).trim()
    val wrapper =
        prefix.isEmpty() ||
            prefix.split(Regex("""\s+""")).all { it in setOf("return", "throw", "yield", "await") } ||
            (prefix.endsWith("=") && !prefix.endsWith("==") && !prefix.endsWith("!=") && !prefix.endsWith("<=") && !prefix.endsWith(">="))
    return wrapper && suffix.all { it == ';' || it == ')' }
}

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
    if (initCallEvidence(view, call, language) != null) return null
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
    if (!isTypeQueryableReceiver(receiver)) return null
    val type = view.receiverType(call)?.fqName ?: return null
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
    return classNameOf(owner)
}

private fun classNameOf(node: SyntaxNode): String? = node.childByField("name")?.text ?: TYPE_NAME.find(node.text)?.groupValues?.get(1)

private fun isWiringType(
    type: String,
    language: ServiceLanguageConfig,
): Boolean =
    TYPE_TOKEN
        .findAll(type)
        .any { match ->
            typeMatches(
                match.value,
                language.serviceTypes + language.initTypes,
                language.serviceSuffixes + language.initSuffixes,
            )
        }

private fun splitParameters(params: String): List<String> {
    val result = mutableListOf<String>()
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    var start = 0
    for (index in params.indices) {
        when (params[index]) {
            '<' -> {
                angle++
            }

            '>' -> {
                if (angle > 0) angle--
            }

            '(' -> {
                parens++
            }

            ')' -> {
                if (parens > 0) parens--
            }

            '[' -> {
                brackets++
            }

            ']' -> {
                if (brackets > 0) brackets--
            }

            '{' -> {
                braces++
            }

            '}' -> {
                if (braces > 0) braces--
            }

            ',' -> {
                if (angle == 0 && parens == 0 && brackets == 0 && braces == 0) {
                    result += params.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
    }
    result += params.substring(start).trim()
    return result.filter { it.isNotEmpty() }
}

private fun parameterType(parameter: String): String? {
    val withoutAttribute = parameter.replace(Regex("""^\s*\[[^\]]*]\s*"""), "")
    val withoutDefault = withoutAttribute.substringBefore('=').trim()
    return PARAMETER_TYPE
        .find(withoutDefault)
        ?.groupValues
        ?.get(1)
        ?.trim()
}

private fun findTextNode(
    container: SyntaxNode,
    text: String,
): SyntaxNode? {
    val target = text.trim()
    return container
        .descendants()
        .filter { it.text.trim() == target }
        .minByOrNull { it.endByte - it.startByte }
}

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

private val TYPE_NAME = Regex("""\b(?:class|object|interface|record|struct)\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val PRIMARY_CONSTRUCTOR =
    Regex(
        """\b(?:class|record)\s+\w+(?:<[^>]*>)?(?:\s*@[A-Za-z_][\w.]*(?:\s*\([^)]*\))?)*\s*(?:(?:public|private|protected|internal)\s+)?(?:constructor\s*)?\(([^)]*)\)""",
        RegexOption.DOT_MATCHES_ALL,
    )
private val PROPERTY_PARAMETER =
    Regex(
        """(?:^|\s)(?:private|public|protected|internal|open|override|lateinit)?\s*(?:val|var)\s+(\w+)\s*:\s*(.+?)\s*$""",
    )
private val TYPESCRIPT_PARAMETER_PROPERTY =
    Regex(
        """^(?:(?:public|private|protected|readonly|override)\s+)+([A-Za-z_$][A-Za-z0-9_$]*\??)\s*:\s*(.+?)\s*$""",
    )
private val FUNCTION_PARAMETERS = Regex("""^[^(]*\(([^)]*)\)""")
private val PARAMETER_TYPE = Regex("""^(.+?)\s+[A-Za-z_][A-Za-z0-9_]*$""")
private val EXPLICIT_CREATION = Regex("""^new\s+([A-Za-z_][A-Za-z0-9_.]*)""")
private val TARGET_CREATION = Regex("""^new\s*\(""")
private val TYPE_TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
private val ASSIGNED_TYPE = Regex("""([A-Za-z_][A-Za-z0-9_.]*(?:<[^=]*>)?)\s+[A-Za-z_][A-Za-z0-9_]*\s*=\s*$""")
