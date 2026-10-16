package com.semanticpainter

object IntentQuery {
    private val PRINT_CALLS = setOf("println", "print")
    private val STD_STREAMS = setOf("System.out", "System.err")

    fun run(facts: SourceFacts, cfg: FeatureConfig): List<Span> {
        val index = LineIndex(facts.text)

        val loggerSymbols = facts.declarations
            .filter { it.initCallPath != null && cfg.has("factories", it.initCallPath) }
            .map { it.name }
            .toSet()

        val importsMatch = facts.imports.any { imp ->
            cfg.list("imports").any { imp == it || imp.startsWith("$it.") }
        }

        val spans = mutableListOf<Span>()
        for (call in facts.calls) {
            val recv = call.receiverText
            val recvSimple = recv?.substringAfterLast('.')

            val rule = when {
                recvSimple != null && recvSimple in loggerSymbols ->
                    "call-on-logger-symbol"

                recvSimple != null &&
                    cfg.list("receiverNames").any { it.equals(recvSimple, ignoreCase = true) } &&
                    cfg.has("methods", call.calleeName) ->
                    "receiver-name"

                recv != null && cfg.has("qualifiers", recv) && cfg.has("methods", call.calleeName) ->
                    "qualified-static"

                recv != null && cfg.has("methods", call.calleeName) && importsMatch ->
                    "name-import-gated"

                call.calleeName in PRINT_CALLS && (recv == null || recv in STD_STREAMS) ->
                    "print"

                else -> null
            } ?: continue

            spans.add(
                Span(
                    tag = cfg.tag,
                    rule = rule,
                    evidence = call.callPath + "(" + call.args.joinToString(", ") + ")",
                    startLine = index.lineOf(call.startOffset),
                    startCol = index.colOf(call.startOffset),
                    endLine = index.lineOf(call.endOffset),
                    endCol = index.colOf(call.endOffset),
                ),
            )
        }
        return spans
    }
}
