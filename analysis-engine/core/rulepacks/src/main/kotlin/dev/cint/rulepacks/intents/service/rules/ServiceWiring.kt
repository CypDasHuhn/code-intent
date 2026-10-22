package dev.cint.rulepacks.intents.service.rules
import dev.cint.api.ScopeKind
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.intents.service.ServiceLanguageConfig

internal fun constructorInjectionEvidence(
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
private val TYPE_TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
