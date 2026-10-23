package dev.cint.host

import dev.cint.api.Contribution
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigDescriptor
import dev.cint.api.RulePackConfigResolver
import dev.cint.api.Span
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.api.configDescriptor
import dev.cint.core.config.IntentConfig
import dev.cint.core.config.ProjectConfig
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.AiRestrictionResolver
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.restriction.ResolvedRestriction
import dev.cint.core.runtime.rulepack.RulePackLoadFailure
import dev.cint.core.runtime.rulepack.RulePackLoadResult
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.core.runtime.scheduler.ExecutionPlan
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.support.hashing.CodeFingerprint
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.core.user.UserAnnotationsJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class HostPipeline(
    private val loader: RulePackLoader = defaultLoader(),
    private val project: ProjectConfig = ProjectConfig(),
    private val configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
    private val root: Path? = null,
) {
    private val reloadLock = Any()
    private val engine = RuleEngine()
    private val snapshots = ConcurrentHashMap<String, RulePackLoadResult>()
    private val plans = ConcurrentHashMap<String, ExecutionPlan>()
    private val fingerprints = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = loadPacks()

    @Volatile
    private var discovered = RulePackLoadResult(loaded.packs, loaded.failures)

    val failures: List<RulePackLoadFailure>
        get() = loaded.failures + snapshots.values.flatMap { it.failures }

    val effectivePacks: List<RulePack>
        get() = loaded.packs

    fun configDescriptors(): List<RulePackConfigDescriptor> =
        synchronized(reloadLock) {
            loaded.packs.map { it.configDescriptor() }
        }

    fun reload() {
        synchronized(reloadLock) {
            loaded = loadPacks()
            discovered = RulePackLoadResult(loaded.packs, loaded.failures)
            snapshots.clear()
            plans.clear()
            fingerprints.clear()
        }
    }

    private fun loadPacks(): LoadedPackConfig {
        val overlay = PackConfigOverlay.read(root)
        if (overlay.error != null) {
            System.err.println("cint config overlay: ${overlay.error}")
        }
        return PackConfigLoader(loader).load(project, configResolver, overlay.packs)
    }

    fun packs(language: String): List<RulePack> =
        synchronized(reloadLock) {
            snapshot(language).packs
        }

    fun plan(language: String): ExecutionPlan =
        synchronized(reloadLock) {
            plans.computeIfAbsent(normalize(language)) {
                engine.plan(snapshot(it).packs)
            }
        }

    fun analyze(
        source: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        oracle: TypeOracle,
        config: IntentConfig,
    ) = engine.analyze(
        plan(config.language),
        source,
        tree,
        syntax,
        oracle,
        config,
        userContributionsFor(source, source, tree, syntax),
        restrictionsFor(source, source, tree, syntax),
    )

    fun restrictionsFor(
        host: SourceFile,
        source: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        region: SplitRegion? = null,
    ): List<ResolvedRestriction> {
        val restrictionsRoot = root ?: return emptyList()
        val records =
            AiRestrictionsFile.loadTolerant(restrictionsRoot) { error ->
                System.err.println("cint ai restrictions: ${error.message ?: error.javaClass.name}")
            }
        val scoped = region?.let { scopeRestrictionsToRegion(host, records, it) } ?: records
        return AiRestrictionResolver.resolve(scoped, source, tree, syntax, restrictionsRoot)
    }

    fun userContributionsFor(
        host: SourceFile,
        source: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        region: SplitRegion? = null,
    ): List<Contribution> {
        val annotationsRoot = root ?: return emptyList()
        val records = loadUserAnnotations(annotationsRoot) ?: return emptyList()
        val scoped = region?.let { scopeToRegion(host, records, it) } ?: records
        return UserAnnotationResolver.resolve(scoped, source, tree, syntax, annotationsRoot)
    }

    fun hostUserContributionsFor(
        host: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        include: (UserAnnotation) -> Boolean,
    ): List<Contribution> {
        val annotationsRoot = root ?: return emptyList()
        val records = loadUserAnnotations(annotationsRoot) ?: return emptyList()
        return UserAnnotationResolver.resolve(records.filter(include), host, tree, syntax, annotationsRoot)
    }

    fun hostRestrictionsFor(
        host: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        include: (AiRestrictionRecord) -> Boolean,
    ): List<ResolvedRestriction> {
        val restrictionsRoot = root ?: return emptyList()
        val records =
            AiRestrictionsFile.loadTolerant(restrictionsRoot) { error ->
                System.err.println("cint ai restrictions: ${error.message ?: error.javaClass.name}")
            }
        return AiRestrictionResolver.resolve(
            records.filter(include),
            host,
            tree,
            syntax,
            restrictionsRoot,
        )
    }

    private fun loadUserAnnotations(annotationsRoot: Path): List<UserAnnotation>? {
        val path = UserAnnotationsFile.path(annotationsRoot)
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            UserAnnotationsJson.parse(Files.readString(path))
        }.getOrElse { error ->
            System.err.println("cint user annotations: $path: ${error.message}")
            emptyList()
        }
    }

    private fun scopeRestrictionsToRegion(
        host: SourceFile,
        records: List<AiRestrictionRecord>,
        region: SplitRegion,
    ): List<AiRestrictionRecord> {
        val lines = region.text.split("\n")
        val lastLine = (lines.lastIndex - if (region.text.endsWith("\n")) 1 else 0).coerceAtLeast(0)
        val lastHostLine = region.startLine + lastLine
        return records.mapNotNull { record ->
            if (record.unitFingerprint != null && record.regionKey != null) {
                return@mapNotNull record.takeIf {
                    !region.identityAmbiguous &&
                        record.regionKey == region.identityKey &&
                        record.regionLanguage == region.language
                }
            }
            if (record.unitFingerprint != null && record.regionOrdinal != null) {
                return@mapNotNull record.takeIf {
                    record.regionOrdinal == region.ordinal &&
                        record.regionLanguage == region.language
                }
            }
            if (record.span.endLine < region.startLine || record.span.startLine > lastHostLine) {
                return@mapNotNull null
            }
            if (record.span.startLine < region.startLine || record.span.endLine > lastHostLine) {
                return@mapNotNull null
            }
            if (record.unitFingerprint != null) {
                return@mapNotNull record
            }
            val original = UserAnnotationResolver.selectionText(host, record.span) ?: return@mapNotNull null
            if (contentHash(original) != record.contentHash.removePrefix("sha256:")) return@mapNotNull null
            val localStartLine = (record.span.startLine - region.startLine).coerceIn(0, lastLine)
            val localEndLine = (record.span.endLine - region.startLine).coerceIn(0, lastLine)
            val lineStart = lineLength(lines, localStartLine)
            val lineEnd = lineLength(lines, localEndLine)
            val localStartCol =
                when {
                    record.span.startLine < region.startLine -> {
                        0
                    }

                    record.span.startLine == region.startLine -> {
                        (record.span.startCol - region.startCol).coerceIn(0, lineStart)
                    }

                    else -> {
                        record.span.startCol.coerceIn(0, lineStart)
                    }
                }
            val localEndCol =
                when {
                    record.span.endLine > lastHostLine -> {
                        lineEnd
                    }

                    record.span.endLine == region.startLine -> {
                        (record.span.endCol - region.startCol).coerceIn(0, lineEnd)
                    }

                    else -> {
                        record.span.endCol.coerceIn(0, lineEnd)
                    }
                }
            if (localStartLine == localEndLine && localStartCol >= localEndCol) return@mapNotNull null
            val localSpan = Span(localStartLine, localStartCol, localEndLine, localEndCol)
            val localText = UserAnnotationResolver.selectionText(region.text, localSpan) ?: return@mapNotNull null
            val clippedHash = contentHash(localText)
            if (clippedHash != record.contentHash.removePrefix("sha256:")) return@mapNotNull null
            record.copy(
                span = localSpan,
                contentHash = clippedHash,
                unitFingerprint = null,
                remove = record.remove.map { it.copy(startToken = null, endToken = null) },
                add = record.add.map { it.copy(startToken = null, endToken = null) },
            )
        }
    }

    private fun scopeToRegion(
        host: SourceFile,
        records: List<UserAnnotation>,
        region: SplitRegion,
    ): List<UserAnnotation> {
        val lines = region.text.split("\n")
        val lastLine = (lines.lastIndex - if (region.text.endsWith("\n")) 1 else 0).coerceAtLeast(0)
        val lastHostLine = region.startLine + lastLine
        return records.mapNotNull { record ->
            if (record.unitFingerprint != null && record.regionKey != null) {
                return@mapNotNull record.takeIf {
                    !region.identityAmbiguous &&
                        record.regionKey == region.identityKey &&
                        record.regionLanguage == region.language
                }
            }
            if (record.unitFingerprint != null && record.regionOrdinal != null) {
                return@mapNotNull record.takeIf {
                    record.regionOrdinal == region.ordinal &&
                        record.regionLanguage == region.language
                }
            }
            if (record.span.endLine < region.startLine || record.span.startLine > lastHostLine) {
                return@mapNotNull null
            }
            if (record.span.startLine < region.startLine || record.span.endLine > lastHostLine) {
                if (record.unitFingerprint != null) return@mapNotNull null
            }
            if (record.unitFingerprint != null) {
                return@mapNotNull record
            }
            if (record.contentHash != null) {
                val original = UserAnnotationResolver.selectionText(host, record.span) ?: return@mapNotNull null
                if (contentHash(original) != record.contentHash) return@mapNotNull null
            }
            val localStartLine = (record.span.startLine - region.startLine).coerceIn(0, lastLine)
            val localEndLine = (record.span.endLine - region.startLine).coerceIn(0, lastLine)
            val lineStart = lineLength(lines, localStartLine)
            val lineEnd = lineLength(lines, localEndLine)
            val localStartCol =
                when {
                    record.span.startLine < region.startLine -> {
                        0
                    }

                    record.span.startLine == region.startLine -> {
                        (record.span.startCol - region.startCol).coerceIn(0, lineStart)
                    }

                    else -> {
                        record.span.startCol.coerceIn(0, lineStart)
                    }
                }
            val localEndCol =
                when {
                    record.span.endLine > lastHostLine -> {
                        lineEnd
                    }

                    record.span.endLine == region.startLine -> {
                        (record.span.endCol - region.startCol).coerceIn(0, lineEnd)
                    }

                    else -> {
                        record.span.endCol.coerceIn(0, lineEnd)
                    }
                }
            if (localStartLine == localEndLine && localStartCol >= localEndCol) return@mapNotNull null
            val span = Span(localStartLine, localStartCol, localEndLine, localEndCol)
            val clippedHash =
                if (record.contentHash != null) {
                    UserAnnotationResolver.selectionText(region.text, span)?.let(::contentHash)
                        ?: return@mapNotNull null
                } else {
                    null
                }
            record.copy(
                span = span,
                contentHash = clippedHash,
                symbol = null,
                unitFingerprint = null,
                unitFingerprintVersion = null,
                anchorFingerprint = null,
                startToken = null,
                endToken = null,
                startNormalized = null,
                endNormalized = null,
            )
        }
    }

    private fun lineLength(
        lines: List<String>,
        line: Int,
    ): Int = lines.getOrNull(line)?.toByteArray(Charsets.UTF_8)?.size ?: 0

    fun intents(languages: Collection<String>): List<String> {
        val intents = sortedSetOf<String>()
        for (language in languages) {
            intents += plan(language).terminal.sourceIntents
        }
        intents += userIntents()
        intents += UNTAGGED_INTENT
        return intents.toList()
    }

    fun fingerprint(language: String): String =
        synchronized(reloadLock) {
            fingerprints.computeIfAbsent(normalize(language)) {
                fingerprintOf(language)
            }
        }

    private fun fingerprintOf(language: String): String {
        val plan = plan(language)
        val rules =
            plan.strata.flatten().map { rule ->
                listOf(
                    rule.id,
                    rule.owns,
                    rule.reads.sorted().joinToString(","),
                    rule.optionalReads.sorted().joinToString(","),
                    CodeFingerprint.classDigest(rule.javaClass),
                ).joinToString(":")
            }
        val packs =
            effectivePacks.map {
                "${it.id}:${it.javaClass.name}:${CodeFingerprint.classDigest(it.javaClass)}:" +
                    it
                        .rules()
                        .map { rule -> CodeFingerprint.classDigest(rule.javaClass) }
                        .sorted()
                        .joinToString(",") + ":" + it.languages.sorted()
            }
        val overlay = PackConfigOverlay.read(root).packs
        val sharedRuleCode =
            effectivePacks
                .firstOrNull()
                ?.let { pack ->
                    CodeFingerprint.ofPackages(
                        label = "rulepacks-shared",
                        prefixes = listOf("dev/cint/rulepacks/"),
                        anchors = listOf(pack.javaClass),
                        allowEmpty = true,
                    )
                }.orEmpty()
        return contentHash(
            listOf(
                plan.terminal.sourceIntents.joinToString(","),
                rules.joinToString("\n"),
                packs.joinToString("\n"),
                sharedRuleCode,
                project.toString(),
                overlay.toString(),
            ).joinToString("\n"),
        )
    }

    private fun userIntents(): Set<String> {
        val annotationsRoot = root ?: return emptySet()
        val path = UserAnnotationsFile.path(annotationsRoot)
        if (!Files.isRegularFile(path)) return emptySet()
        return runCatching {
            UserAnnotationsJson
                .parse(Files.readString(path))
                .flatMap { it.intents }
                .filter { it != UNTAGGED_INTENT }
                .toSet()
        }.getOrElse { emptySet() }
    }

    private fun snapshot(language: String): RulePackLoadResult =
        snapshots.computeIfAbsent(normalize(language)) { normalized ->
            loader.validate(discovered.packs.filter { supports(it, normalized) })
        }

    private fun supports(
        pack: RulePack,
        language: String,
    ): Boolean =
        try {
            pack.languages.isEmpty() || pack.languages.any { it.equals(language, ignoreCase = true) }
        } catch (error: VirtualMachineError) {
            throw error
        } catch (_: Throwable) {
            true
        }

    private fun normalize(language: String): String = language.lowercase()
}

private fun defaultLoader(): RulePackLoader =
    RulePackLoader(
        onFailure = { System.err.println("cint rulepack: ${it.message}") },
    )
