package dev.cint.server.suggestions

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.support.hashing.SourceNormalizer
import dev.cint.core.syntax.expressionUnits
import dev.cint.core.user.UserAnnotationResolver
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
    parseRuns.incrementAndGet()
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
    val restrictions = AiRestrictionsFile.loadTolerant(root)
    val hostUnits =
        sessionUnits(
            doc = doc,
            source = source,
            tree = tree,
            syntax = syntax,
            annotations = annotations,
            restrictions = restrictions,
            unitPrefix = if (frontend is dev.cint.core.facts.frontend.SplitHandler) "host-" else "",
            language = doc.language,
            offset = null,
        )
    val regions = mutableListOf<Pair<dev.cint.core.facts.frontend.SplitRegion, String>>()
    collectLeafRegions(frontend, source, 0, 0, 0, "r", regions)
    val regionUnits =
        regions.flatMap { (region, prefix) ->
            regionSessionUnits(
                doc = doc,
                region = region,
                prefix = prefix,
                annotations = annotations,
                restrictions = restrictions,
            )
        }
    val hostLevelUnits =
        if (regions.isEmpty()) {
            hostUnits
        } else {
            hostUnits.filter { unit ->
                regions.none { (region, _) ->
                    val end = region.startByte + region.text.toByteArray(Charsets.UTF_8).size
                    unit.startByte < end && unit.endByte > region.startByte
                }
            }
        }
    val hostIds = hostLevelUnits.mapTo(linkedSetOf()) { it.unitId }
    val intactHostUnits =
        hostLevelUnits.map { unit ->
            if (unit.parentUnitId != null && unit.parentUnitId !in hostIds) {
                unit.copy(parentUnitId = null, parentSpan = null)
            } else {
                unit
            }
        }
    val allUnits = intactHostUnits + regionUnits
    val parents = allUnits.mapNotNull { it.parentUnitId }.toSet()
    val selected = if (includeLeaves) allUnits else allUnits.filter { it.unitId in parents }
    return SessionDocument(
        path = relativePath(doc.uri),
        version = doc.version,
        text = doc.text,
        language = doc.language,
        units = selected,
        annotations = annotations,
        knownIntents = knownIntents,
    )
}

internal fun collectLeafRegions(
    frontend: dev.cint.core.facts.frontend.LanguageFrontend,
    source: SourceFile,
    baseStartByte: Int,
    baseStartLine: Int,
    baseStartCol: Int,
    prefix: String,
    result: MutableList<Pair<dev.cint.core.facts.frontend.SplitRegion, String>>,
) {
    if (frontend !is dev.cint.core.facts.frontend.SplitHandler) return
    for ((index, region) in frontend.regions(source).withIndex()) {
        val regionFrontend = Frontends.forLanguage(region.language) ?: continue
        val absolute =
            region.copy(
                startByte = baseStartByte + region.startByte,
                startLine = baseStartLine + region.startLine,
                startCol = if (region.startLine == 0) baseStartCol + region.startCol else region.startCol,
            )
        val regionPrefix = "$prefix$index-"
        if (regionFrontend is dev.cint.core.facts.frontend.SplitHandler) {
            collectLeafRegions(
                regionFrontend,
                SourceFile(source.path, region.text),
                absolute.startByte,
                absolute.startLine,
                absolute.startCol,
                regionPrefix,
                result,
            )
        } else {
            result += absolute to regionPrefix
        }
    }
}

private fun LiveServer.regionSessionUnits(
    doc: DocState,
    region: dev.cint.core.facts.frontend.SplitRegion,
    prefix: String,
    annotations: List<SessionAnnotation>,
    restrictions: List<AiRestrictionRecord>,
): List<SessionUnit> {
    val regionFrontend = Frontends.forLanguage(region.language) ?: return emptyList()
    if (regionFrontend is dev.cint.core.facts.frontend.SplitHandler) return emptyList()
    val source = SourceFile(doc.uri, region.text)
    parseRuns.incrementAndGet()
    val tree = regionFrontend.parse(source)
    val syntax = regionFrontend.syntax(tree)
    return sessionUnits(
        doc = doc,
        source = source,
        tree = tree,
        syntax = syntax,
        annotations = annotations,
        restrictions = restrictions,
        unitPrefix = prefix,
        language = region.language,
        offset = region,
    )
}

private fun LiveServer.sessionUnits(
    doc: DocState,
    source: SourceFile,
    tree: dev.cint.core.syntax.SyntaxTree,
    syntax: dev.cint.core.syntax.SyntaxModel,
    annotations: List<SessionAnnotation>,
    restrictions: List<AiRestrictionRecord>,
    unitPrefix: String,
    language: String,
    offset: dev.cint.core.facts.frontend.SplitRegion?,
): List<SessionUnit> {
    val units = syntax.expressionUnits(tree)
    val imports = syntax.imports(tree).sorted()
    return units.map { unit ->
        val startByte = (offset?.startByte ?: 0) + unit.startByte
        val endByte = (offset?.startByte ?: 0) + unit.endByte
        val span = unit.span.shift(offset)
        val overlapping = annotations.filter { it.endByte > startByte && it.startByte < endByte }
        val symbol = UserAnnotationResolver.symbolAt(source, tree, syntax, unit.startByte)
        SessionUnit(
            unitId = "$unitPrefix${unit.unitId}",
            path = relativePath(doc.uri),
            language = language,
            startByte = startByte,
            endByte = endByte,
            span = span,
            text = unit.text,
            parentUnitId = unit.parent?.let { "$unitPrefix${it.unitId}" },
            parentSpan = unit.parent?.span?.shift(offset),
            enclosingStatement = unit.enclosingStatement.span().shift(offset),
            enclosingFunction = unit.enclosingFunction?.span()?.shift(offset),
            existingAnnotations = overlapping,
            nodeKind = unit.node.kind,
            imports = imports,
            enclosingSymbol = symbol,
            enclosingSignature = signatureOf(unit, syntax),
            referencedNames = referencedNames(unit.text),
            priorHints = restrictionHints(restrictions, symbol, doc.uri),
        )
    }
}

private fun dev.cint.api.Span.shift(offset: dev.cint.core.facts.frontend.SplitRegion?): dev.cint.api.Span {
    if (offset == null) return this
    return dev.cint.api.Span(
        startLine = offset.startLine + startLine,
        startCol = if (startLine == 0) offset.startCol + startCol else startCol,
        endLine = offset.startLine + endLine,
        endCol = if (endLine == 0) offset.startCol + endCol else endCol,
    )
}

internal fun LiveServer.sessionTypeLookup(
    doc: DocState,
    document: SessionDocument,
): (Int, Int) -> Map<String, Any?>? {
    val frontend = Frontends.forLanguage(doc.language) ?: return { _, _ -> null }
    val source = SourceFile(doc.uri, document.text)
    parseRuns.incrementAndGet()
    val tree = frontend.parse(source)
    val host =
        TypeLookupContext(
            startByte = 0,
            endByte = source.text.toByteArray(Charsets.UTF_8).size,
            source = source,
            tree = tree,
            oracle = oracleFor(frontend),
        )
    val regions = mutableListOf<Pair<dev.cint.core.facts.frontend.SplitRegion, String>>()
    collectLeafRegions(frontend, source, 0, 0, 0, "t", regions)
    val contexts =
        regions.mapNotNull { (region, _) ->
            val regionFrontend = Frontends.forLanguage(region.language) ?: return@mapNotNull null
            val regionSource = SourceFile(doc.uri, region.text)
            parseRuns.incrementAndGet()
            val regionTree = regionFrontend.parse(regionSource)
            TypeLookupContext(
                startByte = region.startByte,
                endByte = region.startByte + region.text.toByteArray(Charsets.UTF_8).size,
                source = regionSource,
                tree = regionTree,
                oracle = oracleFor(regionFrontend),
            )
        }
    return { startByte, endByte ->
        val context =
            contexts
                .filter { startByte >= it.startByte && endByte <= it.endByte }
                .minByOrNull { it.endByte - it.startByte }
                ?: host
        val localStart = startByte - context.startByte
        val localEnd = endByte - context.startByte
        val node =
            context.tree.root
                .descendants()
                .filter { it.startByte <= localStart && it.endByte >= localEnd }
                .minByOrNull { it.endByte - it.startByte }
        runCatching {
            context.oracle.typeAt(context.source, node?.startByte ?: localStart)?.let { fact ->
                linkedMapOf(
                    "fqName" to fact.fqName,
                    "source" to fact.source,
                    "confidence" to fact.confidence,
                )
            }
        }.getOrNull()
    }
}

private data class TypeLookupContext(
    val startByte: Int,
    val endByte: Int,
    val source: SourceFile,
    val tree: dev.cint.core.syntax.SyntaxTree,
    val oracle: dev.cint.core.facts.oracle.TypeOracle,
)

private fun signatureOf(
    unit: dev.cint.core.syntax.ExpressionUnit,
    syntax: dev.cint.core.syntax.SyntaxModel,
): String? {
    val declaration = unit.enclosingFunction ?: syntax.enclosingDeclaration(unit.node) ?: return null
    val name = syntax.functionNameNode(declaration)?.text ?: syntax.nameNode(declaration)?.text
    val body = syntax.bodyOf(declaration)
    val header =
        if (body != null && body.startByte >= declaration.startByte) {
            declaration.text.take(body.startByte - declaration.startByte)
        } else {
            declaration.text
        }
    return listOf(name.orEmpty(), SourceNormalizer.normalize(header)).joinToString(":")
}

private fun referencedNames(text: String): List<String> =
    SourceNormalizer
        .tokens(text)
        .filter { token -> token.firstOrNull()?.let { it.isLetter() || it == '_' } == true }
        .filter { it !in KEYWORDS }
        .distinct()
        .sorted()

private fun LiveServer.restrictionHints(
    records: List<AiRestrictionRecord>,
    symbol: String?,
    uri: String,
): List<String> {
    if (records.isEmpty()) return emptyList()
    val path = relativePath(uri)
    return records
        .filter { record ->
            record.symbol != null &&
                record.symbol == symbol &&
                UserAnnotationResolver.pathMatches(record.path, path, root)
        }.map { record ->
            val operations =
                record.remove.map { "REMOVE ${it.intent} ${it.startOffset}..${it.endOffset}" } +
                    record.add.map { "ADD ${it.intent} ${it.startOffset}..${it.endOffset}" }
            "prior restriction: ${operations.joinToString(", ")} - confirm, adjust, or discard"
        }
}

private val KEYWORDS =
    setOf(
        "val",
        "var",
        "fun",
        "class",
        "object",
        "interface",
        "if",
        "else",
        "when",
        "for",
        "while",
        "do",
        "return",
        "throw",
        "try",
        "catch",
        "finally",
        "import",
        "package",
        "private",
        "public",
        "protected",
        "internal",
        "override",
        "const",
        "function",
        "new",
        "this",
        "super",
        "null",
        "true",
        "false",
    )
