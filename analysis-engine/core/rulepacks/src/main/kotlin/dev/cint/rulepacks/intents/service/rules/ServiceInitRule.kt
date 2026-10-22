package dev.cint.rulepacks.intents.service.rules
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.DeclarationFact
import dev.cint.api.DeclarationKind
import dev.cint.api.Emit
import dev.cint.api.FunctionFact
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.intents.service.SERVICE_INIT_INTENT
import dev.cint.rulepacks.intents.service.SERVICE_INIT_RULE_ID
import dev.cint.rulepacks.intents.service.ServiceConfig
import dev.cint.rulepacks.intents.service.ServiceLanguageConfig

internal class ServiceInitRule(
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
