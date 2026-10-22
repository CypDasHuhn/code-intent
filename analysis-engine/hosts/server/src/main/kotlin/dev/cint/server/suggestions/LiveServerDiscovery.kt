package dev.cint.server.suggestions

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.syntax.expressionUnits
import dev.cint.frontend.Frontends
import dev.cint.server.LiveServer
import dev.cint.server.live.CachedAnalysis
import dev.cint.server.live.DocState
import dev.cint.server.live.oracleFor
import dev.cint.server.live.relativePath
import dev.cint.suggest.session.SessionAnnotation
import dev.cint.suggest.session.SessionDocument
import dev.cint.suggest.session.SessionUnit

internal fun LiveServer.sessionDocument(
    doc: DocState,
    cached: CachedAnalysis,
    knownIntents: Set<String>,
    includeLeaves: Boolean,
): SessionDocument? {
    val frontend = Frontends.forLanguage(doc.language) ?: return null
    val source = SourceFile(doc.uri, doc.text)
    val tree = frontend.parse(source)
    val syntax = frontend.syntax(tree)
    val annotations =
        cached.output.annotations.map { annotation ->
            SessionAnnotation(
                intents = annotation.intents,
                startByte = annotation.startByte,
                endByte = annotation.endByte,
                span = annotation.span,
                source = annotation.source,
                confidence = annotation.confidence,
                role = annotation.role.name,
                provenance = annotation.provenance.map { "${it.rule}:${it.source}" },
            )
        }
    val units = syntax.expressionUnits(tree)
    val parents = units.mapNotNull { it.parent?.unitId }.toSet()
    val selected = if (includeLeaves) units else units.filter { it.unitId in parents }
    val sessionUnits =
        selected.map { unit ->
            val overlapping = annotations.filter { it.endByte > unit.startByte && it.startByte < unit.endByte }
            SessionUnit(
                unitId = unit.unitId,
                path = relativePath(doc.uri),
                language = doc.language,
                startByte = unit.startByte,
                endByte = unit.endByte,
                span = unit.span,
                text = unit.text,
                parentUnitId = unit.parent?.unitId,
                parentSpan = unit.parent?.span,
                enclosingStatement = unit.enclosingStatement.span(),
                enclosingFunction = unit.enclosingFunction?.span(),
                existingAnnotations = overlapping,
            )
        }
    return SessionDocument(
        path = relativePath(doc.uri),
        version = doc.version,
        text = doc.text,
        language = doc.language,
        units = sessionUnits,
        annotations = annotations,
        knownIntents = knownIntents,
    )
}

internal fun LiveServer.sessionTypeLookup(
    doc: DocState,
    document: SessionDocument,
): (Int, Int) -> Map<String, Any?>? {
    val frontend = Frontends.forLanguage(doc.language) ?: return { _, _ -> null }
    val source = SourceFile(doc.uri, document.text)
    val tree = frontend.parse(source)
    val oracle = oracleFor(frontend)
    return { startByte, endByte ->
        val node =
            tree.root
                .descendants()
                .filter { it.startByte <= startByte && it.endByte >= endByte }
                .minByOrNull { it.endByte - it.startByte }
        runCatching {
            oracle.typeAt(source, node?.startByte ?: startByte)?.let { fact ->
                linkedMapOf(
                    "fqName" to fact.fqName,
                    "source" to fact.source,
                    "confidence" to fact.confidence,
                )
            }
        }.getOrNull()
    }
}
