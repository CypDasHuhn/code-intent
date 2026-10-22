package dev.cint.rulepacks.intents.service.rules
import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Emit
import dev.cint.api.FunctionFact
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.callback.callbackParameterAt
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_INTENT
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_RULE_ID
import dev.cint.rulepacks.intents.service.ServiceConfig
import dev.cint.rulepacks.intents.service.ServiceLanguageConfig

private data class ForwardingUnit(
    val serviceCalls: List<Pair<CallFact, Evidence>>,
    val pureForwarding: Pair<CallFact, Evidence>?,
    val singleEffectCall: CallFact?,
)

internal class ServiceDelegationRule(
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

private fun matchesControllerOrFacade(
    view: View,
    function: FunctionFact,
    language: ServiceLanguageConfig,
): Boolean {
    val name = enclosingClassName(view, function) ?: return false
    return language.controllerSuffixes.any { name.endsWith(it) } ||
        language.facadeSuffixes.any { name.endsWith(it) }
}
