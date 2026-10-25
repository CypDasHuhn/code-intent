package dev.cint.rulepacks.packs.service

import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.CreationFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.ParameterFact
import dev.cint.contracts.facts.matchesCallee
import dev.cint.contracts.facts.matchesCalleeSuffix
import dev.cint.contracts.facts.matchesType
import dev.cint.contracts.facts.pathMatches
import dev.cint.contracts.facts.receiverQualifierMatches
import dev.cint.contracts.facts.receiverSuffixMatches
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleSpec
import dev.cint.rulepacks.ANNOTATION_SOURCE
import dev.cint.rulepacks.HEURISTIC_SOURCE
import dev.cint.rulepacks.PackEvaluator
import dev.cint.rulepacks.PackRules
import dev.cint.rulepacks.SYNTAX_SOURCE
import dev.cint.rulepacks.decodeRule
import dev.cint.rulepacks.within
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
private data class ServiceData(
    val delegationCalls: Set<String> = emptySet(),
    val delegationQualifiers: Set<String> = emptySet(),
    val serviceNames: Set<String> = emptySet(),
    val serviceTypes: Set<String> = emptySet(),
    val serviceSuffixes: Set<String> = emptySet(),
    val repositorySuffixes: Set<String> = emptySet(),
    val initCalls: Set<String> = emptySet(),
    val initQualifiers: Set<String> = emptySet(),
    val initTypes: Set<String> = emptySet(),
    val initMethods: Set<String> = emptySet(),
    val initSuffix: String? = null,
    val clientSuffix: String? = null,
    val connectionCalls: Set<String> = emptySet(),
    val connectionQualifiers: Set<String> = emptySet(),
    val diCalls: Set<String> = emptySet(),
    val diQualifiers: Set<String> = emptySet(),
    val diAnnotations: Set<String> = emptySet(),
    val searchCalls: Set<String> = emptySet(),
    val lookupCalls: Set<String> = emptySet(),
    val persistenceCalls: Set<String> = emptySet(),
)

internal object ServicePack : PackRules {
    override val packId = "service"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator {
        val config = data.decodeRule<ServiceData>()
        return when (spec.owns) {
            Intents.SERVICE_DELEGATION -> ServiceDelegationRule(spec, config)
            else -> ServiceInitRule(spec, config)
        }
    }
}

private class ServiceDelegationRule(
    spec: RuleSpec,
    private val data: ServiceData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val receiverName = call.receiverLeaf.orEmpty()
            if (initEvidence(view, call) != null) continue
            val repositoryLike = data.repositorySuffixes.any { suffix -> call.receiverSuffixMatches(setOf(suffix)) }
            if (repositoryLike &&
                (
                    call.matchesCallee(data.searchCalls) ||
                        call.matchesCallee(data.lookupCalls) ||
                        call.matchesCallee(data.persistenceCalls, ignoreCase = false)
                )
            ) {
                continue
            }
            if (call.matchesCallee(data.persistenceCalls, ignoreCase = false) &&
                !data.serviceNames.any { it.equals(receiverName, ignoreCase = true) } &&
                !data.serviceSuffixes.any { it.equals(receiverName, ignoreCase = true) } &&
                !call.receiverSuffixMatches(data.serviceSuffixes)
            ) {
                continue
            }
            val evidence =
                when {
                    call.matchesCallee(data.delegationCalls, ignoreCase = false) ||
                        call.pathMatches(emptySet(), suffixes = data.delegationCalls.toList()) -> SYNTAX_SOURCE to 0.9

                    call.pathMatches(emptySet(), prefixes = data.delegationQualifiers.toList()) -> SYNTAX_SOURCE to 0.85

                    data.serviceNames.any { it.equals(receiverName, ignoreCase = true) } -> HEURISTIC_SOURCE to 0.75

                    call.receiverSuffixMatches(data.serviceSuffixes) -> HEURISTIC_SOURCE to 0.75

                    else -> null
                }
            if (evidence != null) {
                view.emitNode(view.node(call.nodeId), evidence.second, evidence.first)?.let(result::add)
            } else if (callee in parameterNames(view, call)) {
                view.candidateNode(view.node(call.nodeId), reason = CandidateReason.LOW_CONFIDENCE)?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun initEvidence(
        view: RuleEvaluationView,
        call: CallFact,
    ): Pair<String, Double>? = initEvidence(view, call, data)
}

private class ServiceInitRule(
    spec: RuleSpec,
    private val data: ServiceData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        val creations = view.creations
        for (call in view.calls) {
            val evidence = initEvidence(view, call, data) ?: continue
            view.emitNode(view.node(call.nodeId), evidence.second, evidence.first)?.let(result::add)
        }
        for (creation in creations) {
            if (!isCreationType(creation, data)) continue
            view.emitNode(view.node(creation.nodeId), 0.85, SYNTAX_SOURCE)?.let(result::add)
        }
        for (parameter in constructorInjectionParameters(view, data)) {
            view.emitNode(view.node(parameter.nodeId), 0.8, HEURISTIC_SOURCE)?.let(result::add)
        }
        for (declaration in view.declarations) {
            if (declaration.kind != DeclarationKind.FUNCTION) continue
            val name = declaration.name ?: continue
            if (name !in data.initMethods) continue
            val node = view.node(declaration.nameNodeId ?: declaration.nodeId) ?: continue
            val function = view.functions.firstOrNull { it.nodeId == declaration.nodeId } ?: continue
            val functionNode = view.node(function.nodeId) ?: continue
            val hasEvidence =
                view.calls.any { call ->
                    val callNode = view.node(call.nodeId) ?: return@any false
                    within(callNode, functionNode) && initEvidence(view, call, data) != null
                } || creations.any { creation -> view.node(creation.nodeId)?.let { within(it, functionNode) } == true }
            if (hasEvidence) view.emitNode(node, 0.8, HEURISTIC_SOURCE)?.let(result::add)
        }
        for (declaration in view.declarations) {
            val annotationNames = declaration.annotationNames.ifEmpty { declaration.annotations }
            if (annotationNames.any { it in data.diAnnotations }) {
                val node = view.node(declaration.annotationNodeIds.firstOrNull() ?: declaration.nodeId) ?: continue
                view.emitNode(node, 0.95, ANNOTATION_SOURCE)?.let(result::add)
            }
        }
        for (function in view.functions) {
            val name = function.name ?: continue
            if (name in data.initMethods) continue
            val node = view.node(function.nameNodeId ?: function.nodeId) ?: continue
            if (mostlyInit(view, function, creations, data)) {
                view
                    .candidateNode(node, reason = CandidateReason.LOW_CONFIDENCE)
                    ?.copy(rationale = "hidden initialization helper")
                    ?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private fun initEvidence(
    view: RuleEvaluationView,
    call: CallFact,
    data: ServiceData,
): Pair<String, Double>? {
    val receiver = call.receiverPath.orEmpty()
    val receiverName = call.receiverName.orEmpty()
    if (call.matchesCallee(data.initCalls, ignoreCase = false) ||
        call.pathMatches(emptySet(), suffixes = data.initCalls.toList())
    ) {
        return SYNTAX_SOURCE to 0.9
    }
    if (call.pathMatches(emptySet(), prefixes = data.initQualifiers.toList())) return SYNTAX_SOURCE to 0.85
    if (receiver.isEmpty() && call.matchesCalleeSuffix(data.initTypes)) return SYNTAX_SOURCE to 0.85
    if (call.matchesCallee(data.connectionCalls, ignoreCase = false)) {
        return if (call.receiverQualifierMatches(data.connectionQualifiers) ||
            data.connectionQualifiers.any { it.equals(receiverName, ignoreCase = true) }
        ) {
            SYNTAX_SOURCE to 0.85
        } else {
            null
        }
    }
    if (call.matchesCallee(data.diCalls, ignoreCase = false)) {
        return if (call.receiverQualifierMatches(data.diQualifiers) ||
            data.diQualifiers.any { it.equals(receiverName, ignoreCase = true) }
        ) {
            SYNTAX_SOURCE to 0.85
        } else {
            null
        }
    }
    return null
}

private fun isCreationType(
    creation: CreationFact,
    data: ServiceData,
): Boolean =
    creation.matchesType(
        data.initTypes,
        setOfNotNull(data.initSuffix, data.clientSuffix),
    )

private fun constructorInjectionParameters(
    view: RuleEvaluationView,
    data: ServiceData,
): List<ParameterFact> {
    val types = data.serviceTypes + data.initTypes
    val suffixes = data.serviceSuffixes + setOfNotNull(data.initSuffix)
    val result = mutableListOf<ParameterFact>()
    for (classFact in view.classes) {
        result += classFact.constructorParameters.filter { it.matchesType(types, suffixes) }
    }
    for (function in view.functions) {
        if (!function.constructor) continue
        result += function.parameters.filter { it.matchesType(types, suffixes) }
    }
    return result.distinctBy { (it.nodeId ?: "") to (it.type ?: "") }
}

private fun mostlyInit(
    view: RuleEvaluationView,
    function: FunctionFact,
    creations: List<CreationFact>,
    data: ServiceData,
): Boolean {
    val functionNode = view.node(function.nodeId) ?: return false
    return creations.any { creation ->
        view.node(creation.nodeId)?.let { within(it, functionNode) } == true &&
            creation.matchesType(data.initTypes, setOfNotNull(data.initSuffix))
    } ||
        view.calls.any { call ->
            val node = view.node(call.nodeId) ?: return@any false
            within(node, functionNode) && initEvidence(view, call, data) != null
        }
}

private fun parameterNames(
    view: RuleEvaluationView,
    call: CallFact,
): Set<String> {
    val callNode = view.node(call.nodeId) ?: return emptySet()
    val function = view.enclosingFunction(callNode) ?: return emptySet()
    return function.parameters.mapNotNull { it.name }.toSet()
}
