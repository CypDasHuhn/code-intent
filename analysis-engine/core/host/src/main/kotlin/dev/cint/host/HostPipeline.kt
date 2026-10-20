package dev.cint.host

import dev.cint.api.Contribution
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigResolver
import dev.cint.api.Span
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.config.IntentConfig
import dev.cint.core.config.ProjectConfig
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.runtime.rulepack.RulePackLoadFailure
import dev.cint.core.runtime.rulepack.RulePackLoadResult
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.core.runtime.scheduler.ExecutionPlan
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.core.user.UserAnnotationsJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class HostPipeline(
    private val loader: RulePackLoader = defaultLoader(),
    project: ProjectConfig = ProjectConfig(),
    configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
    private val root: Path? = null,
) {
    private val loaded = PackConfigLoader(loader).load(project, configResolver)
    private val discovered = RulePackLoadResult(loaded.packs, loaded.failures)
    private val engine = RuleEngine()
    private val snapshots = ConcurrentHashMap<String, RulePackLoadResult>()
    private val plans = ConcurrentHashMap<String, ExecutionPlan>()

    val failures: List<RulePackLoadFailure>
        get() = loaded.failures + snapshots.values.flatMap { it.failures }

    fun packs(language: String): List<RulePack> = snapshot(language).packs

    fun plan(language: String): ExecutionPlan =
        plans.computeIfAbsent(normalize(language)) {
            engine.plan(snapshot(it).packs)
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
        userContributionsFor(source, tree, syntax),
    )

    fun userContributionsFor(
        source: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        region: SplitRegion? = null,
    ): List<Contribution> {
        val annotationsRoot = root ?: return emptyList()
        val path = annotationsRoot.resolve(".cint").resolve("user-annotations.json")
        if (!Files.isRegularFile(path)) return emptyList()
        val records =
            runCatching {
                UserAnnotationsJson.parse(Files.readString(path))
            }.getOrElse { error ->
                System.err.println("cint user annotations: $path: ${error.message}")
                emptyList()
            }
        val scoped = region?.let { scopeToRegion(records, it) } ?: records
        return UserAnnotationResolver.resolve(scoped, source, tree, syntax, annotationsRoot)
    }

    private fun scopeToRegion(
        records: List<UserAnnotation>,
        region: SplitRegion,
    ): List<UserAnnotation> {
        val lines = region.text.split("\n")
        val lastLine = (lines.lastIndex - if (region.text.endsWith("\n")) 1 else 0).coerceAtLeast(0)
        val lastHostLine = region.startLine + lastLine
        return records.mapNotNull { record ->
            if (record.span.endLine < region.startLine || record.span.startLine > lastHostLine) {
                return@mapNotNull null
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
            record.copy(
                span = Span(localStartLine, localStartCol, localEndLine, localEndCol),
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

    private fun userIntents(): Set<String> {
        val annotationsRoot = root ?: return emptySet()
        val path = annotationsRoot.resolve(".cint").resolve("user-annotations.json")
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
