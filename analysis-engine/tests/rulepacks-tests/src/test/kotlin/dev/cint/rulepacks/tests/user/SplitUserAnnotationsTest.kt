package dev.cint.rulepacks.tests.user

import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.AiRestrictionResolver
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.restriction.RestrictionOperation
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.syntax.expressionUnits
import dev.cint.core.user.USER_CONTRIBUTOR_ID
import dev.cint.core.user.USER_SOURCE
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.core.user.UserAnnotationsJson
import dev.cint.frontend.SplitAnalyzer
import dev.cint.host.HostPipeline
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitUserAnnotationsTest {
    @Test
    fun splitAnalysisClipsRangesThatStartBeforeTheRegion() {
        val root = Files.createTempDirectory("cint-split-user-annotations-clip")
        try {
            val regionText = "fun make(): String = \"x\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(1, 0, 3, 0),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            val user = userAnnotation(annotations)
            assertEquals(startLine, user.span.startLine)
            assertEquals(startLine, user.span.endLine)
            assertEquals(0, user.span.startCol)
            assertEquals(regionText.trimEnd('\n').length, user.span.endCol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisClipsHashedRangesThatStartBeforeTheRegion() {
        val root = Files.createTempDirectory("cint-split-user-annotations-hashed-clip")
        try {
            val prefix = "<template>π</template>\n\n"
            val regionText = "fun make(): String = \"π\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", prefix + regionText)
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val endCol = regionText.trimEnd('\n').toByteArray(Charsets.UTF_8).size
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(0, 0, startLine, endCol),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash(prefix + regionText.trimEnd('\n')),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            val user = userAnnotation(annotations)
            assertEquals(startLine, user.span.startLine)
            assertEquals(startLine, user.span.endLine)
            assertEquals(0, user.span.startCol)
            assertEquals(endCol, user.span.endCol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisClipsHashedRangesThatEndOnTheEndTagLine() {
        val root = Files.createTempDirectory("cint-split-user-annotations-hashed-end-tag")
        try {
            val prefix = "<template></template>\n\n"
            val regionText = "fun make(): String = \"x\"\n"
            val endTag = "</script>"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", prefix + regionText + endTag + "\n")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val endCol = regionText.trimEnd('\n').toByteArray(Charsets.UTF_8).size
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(startLine, 0, startLine + 1, endTag.length),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash(regionText + endTag),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            val user = userAnnotation(annotations)
            assertEquals(startLine, user.span.startLine)
            assertEquals(startLine, user.span.endLine)
            assertEquals(0, user.span.startCol)
            assertEquals(endCol, user.span.endCol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisDropsAHashedRangeThatIsStaleOnTheHostSpan() {
        val root = Files.createTempDirectory("cint-split-user-annotations-stale-clip")
        try {
            val regionText = "fun make(): String = \"x\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(0, 0, startLine, regionText.trimEnd('\n').length),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash(regionText.trimEnd('\n')),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            assertTrue(annotations.none { it.provenance.any { contribution -> contribution.rule == USER_CONTRIBUTOR_ID } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisDropsRangesOnTheTrailingEndTagLine() {
        val root = Files.createTempDirectory("cint-split-user-annotations-end-tag")
        try {
            val regionText = "fun make(): String = \"x\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(startLine + 1, 0, startLine + 1, 5),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            assertTrue(annotations.none { it.provenance.any { contribution -> contribution.rule == USER_CONTRIBUTOR_ID } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisThreadsUserAnnotationsIntoTheRegionEngine() {
        val root = Files.createTempDirectory("cint-split-user-annotations")
        try {
            val regionText = "fun make(): String = \"x\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val file = root.resolve(".cint/user-annotations.json")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                UserAnnotationsJson.encode(
                    listOf(
                        UserAnnotation(
                            path = "Sample.vue",
                            span = Span(startLine, 4, startLine, 8),
                            intents = setOf("TEAM_CUSTOM_INTENT"),
                            symbol = "make",
                            contentHash = contentHash("make"),
                        ),
                    ),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)
            val config = IntentConfig("kotlin", emptyList())

            val annotations =
                SplitAnalyzer
                    .analyze(
                        source,
                        handler,
                        root,
                        OracleOptions(enabled = false, timeoutMs = 1_000L),
                        pipeline::plan,
                        pipeline::userContributionsFor,
                    ) { config }
                    .annotations

            val user =
                annotations.first { annotation ->
                    annotation.provenance.any { it.rule == USER_CONTRIBUTOR_ID }
                }
            assertEquals(Role.SOURCE_INTENT, user.role)
            assertEquals(startLine, user.span.startLine)
            assertEquals(setOf("TEAM_CUSTOM_INTENT"), user.intents)
            assertTrue(user.provenance.all { it.source == USER_SOURCE })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisClipsVersionedAnchorsToTheRegionSpan() {
        val root = Files.createTempDirectory("cint-split-user-annotations-versioned")
        try {
            val regionText = "fun make(): String = f(a, b)\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val frontend =
                dev.cint.frontend.Frontends
                    .require("kotlin")
            val regionSource = SourceFile("Sample.vue", regionText)
            val tree = frontend.parse(regionSource)
            val syntax = frontend.syntax(tree)
            val unit = syntax.expressionUnits(tree).first { it.text == "f(a, b)" }
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(startLine + unit.span.startLine, unit.span.startCol, startLine + unit.span.endLine, unit.span.endCol),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash(unit.text),
                    unitFingerprint =
                        dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                            regionSource,
                            tree,
                            syntax,
                            unit.startByte,
                            unit.endByte,
                        ),
                    unitFingerprintVersion = dev.cint.core.syntax.UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                    startNormalized = 0,
                    endNormalized =
                        dev.cint.core.support.hashing.SourceNormalizer
                            .normalize(unit.text)
                            .length,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            val user = userAnnotation(annotations)
            assertEquals(startLine + unit.span.startLine, user.span.startLine)
            assertEquals(unit.span.startCol, user.span.startCol)
            assertEquals(startLine + unit.span.endLine, user.span.endLine)
            assertEquals(unit.span.endCol, user.span.endCol)
            assertEquals(setOf("TEAM_CUSTOM_INTENT"), user.intents)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisClipsVersionedRestrictionAnchorsToTheRegionSpan() {
        val root = Files.createTempDirectory("cint-split-restriction-versioned")
        try {
            val regionText = "fun make() {\n    println(\"x\")\n}\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val frontend =
                dev.cint.frontend.Frontends
                    .require("kotlin")
            val regionSource = SourceFile("Sample.vue", regionText)
            val tree = frontend.parse(regionSource)
            val syntax = frontend.syntax(tree)
            val unit = syntax.expressionUnits(tree).first { it.text == "println(\"x\")" }
            val tokens =
                dev.cint.core.support.hashing.SourceNormalizer
                    .tokens(unit.text)
            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "split-restriction",
                    path = "Sample.vue",
                    span = Span(startLine + unit.span.startLine, unit.span.startCol, startLine + unit.span.endLine, unit.span.endCol),
                    contentHash = contentHash(unit.text),
                    remove =
                        listOf(
                            RestrictionOperation(
                                intent = "LOGGING",
                                startOffset = 0,
                                endOffset = unit.text.length,
                                startToken = 0,
                                endToken = tokens.size,
                            ),
                        ),
                    add = emptyList(),
                    unitFingerprint =
                        dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                            regionSource,
                            tree,
                            syntax,
                            unit.startByte,
                            unit.endByte,
                        ),
                    fingerprintVersion = dev.cint.core.syntax.UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyzeWithRestrictions(source, handler, root, pipeline)

            assertTrue(annotations.none { "LOGGING" in it.intents })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisDropsRestrictionsThatStraddleTheRegionBoundary() {
        val root = Files.createTempDirectory("cint-split-restriction-straddle")
        try {
            val regionText = "fun make() {\n    println(\"x\")\n}\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val hostPrefix = "<template></template>\n\n"
            val source = SourceFile("Sample.vue", hostPrefix + regionText)
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val straddleSpan = Span(1, 0, startLine + 1, 4 + "println(\"x\")".length)
            val original = UserAnnotationResolver.selectionText(source, straddleSpan) ?: error("selection")
            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "straddle",
                    path = "Sample.vue",
                    span = straddleSpan,
                    contentHash = contentHash(original),
                    remove = listOf(RestrictionOperation("LOGGING", 0, original.length)),
                    add = emptyList(),
                    unitFingerprint = "host-only-fingerprint",
                    fingerprintVersion = 2,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyzeWithRestrictions(source, handler, root, pipeline)

            assertTrue(annotations.any { "LOGGING" in it.intents })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisKeepsAFormattedAnchoredUserAnnotation() {
        val root = Files.createTempDirectory("cint-split-user-formatted")
        try {
            val oldText = "fun make(): String = f(a, b)\n"
            val formattedText = "fun make(): String = f( a , b )\n"
            val startLine = 2
            val region = SplitRegion("kotlin", formattedText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$formattedText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val frontend =
                dev.cint.frontend.Frontends
                    .require("kotlin")
            val regionSource = SourceFile("Sample.vue", formattedText)
            val tree = frontend.parse(regionSource)
            val syntax = frontend.syntax(tree)
            val unit = syntax.expressionUnits(tree).first { it.text == "f( a , b )" }
            val oldSpan = Span(startLine, oldText.indexOf("f("), startLine, oldText.indexOf(")") + 1)
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = oldSpan,
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash("f(a, b)"),
                    unitFingerprint =
                        dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                            regionSource,
                            tree,
                            syntax,
                            unit.startByte,
                            unit.endByte,
                        ),
                    unitFingerprintVersion = dev.cint.core.syntax.UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                    startNormalized = 0,
                    endNormalized =
                        dev.cint.core.support.hashing.SourceNormalizer
                            .normalize(unit.text)
                            .length,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            val user = userAnnotation(annotations)
            assertEquals(startLine + unit.span.startLine, user.span.startLine)
            assertEquals(unit.span.startCol, user.span.startCol)
            assertEquals(startLine + unit.span.endLine, user.span.endLine)
            assertEquals(unit.span.endCol, user.span.endCol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisKeepsAFormattedAnchoredRestriction() {
        val root = Files.createTempDirectory("cint-split-restriction-formatted")
        try {
            val oldText = "fun make() {\n    println(\"x\")\n}\n"
            val formattedText = "fun make() {\n    println( \"x\" )\n}\n"
            val startLine = 2
            val region = SplitRegion("kotlin", formattedText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$formattedText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val frontend =
                dev.cint.frontend.Frontends
                    .require("kotlin")
            val regionSource = SourceFile("Sample.vue", formattedText)
            val tree = frontend.parse(regionSource)
            val syntax = frontend.syntax(tree)
            val unit = syntax.expressionUnits(tree).first { it.text == "println( \"x\" )" }
            val oldLine = startLine + oldText.substringBefore("println").count { it == '\n' }
            val oldStart = oldText.indexOf("println(\"x\")")
            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "formatted-split",
                    path = "Sample.vue",
                    span =
                        Span(
                            oldLine,
                            oldStart - oldText.lastIndexOf('\n', oldStart) - 1,
                            oldLine,
                            oldStart - oldText.lastIndexOf('\n', oldStart) - 1 + "println(\"x\")".length,
                        ),
                    contentHash = contentHash("println(\"x\")"),
                    remove =
                        listOf(
                            RestrictionOperation(
                                intent = "LOGGING",
                                startOffset = 0,
                                endOffset = unit.text.length,
                                startToken = 0,
                                endToken =
                                    dev.cint.core.support.hashing.SourceNormalizer
                                        .tokens(unit.text)
                                        .size,
                            ),
                        ),
                    add = emptyList(),
                    unitFingerprint =
                        dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                            regionSource,
                            tree,
                            syntax,
                            unit.startByte,
                            unit.endByte,
                        ),
                    fingerprintVersion = dev.cint.core.syntax.UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyzeWithRestrictions(source, handler, root, pipeline)

            assertTrue(annotations.none { "LOGGING" in it.intents })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun productionHostAnchoredUserAnnotationResolvesAfterHostFormatting() {
        val root = Files.createTempDirectory("cint-split-host-user-formatted")
        try {
            val oldScript = "function make() {\n  return f(a, b)\n}\n"
            val formattedScript = "function make() {\n  return f( a , b )\n}\n"
            val oldHost = vueHost(oldScript)
            val formattedHost = vueHost(formattedScript)
            val oldSource = SourceFile("Sample.vue", oldHost)
            val region =
                dev.cint.frontend.VueFrontend
                    .regions(oldSource)
                    .single()
            val localStart = oldScript.indexOf("f(a, b)")
            val startByte = region.startByte + localStart
            val endByte = startByte + "f(a, b)".length
            val context =
                dev.cint.frontend.SplitAnchorResolver.anchor(
                    oldSource,
                    dev.cint.frontend.VueFrontend,
                    startByte,
                    endByte,
                )
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = UserAnnotationResolver.spanFor(oldHost, startByte, endByte) ?: error("span"),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash("f(a, b)"),
                    unitFingerprint = context.unitFingerprint,
                    unitFingerprintVersion = context.fingerprintVersion,
                    regionOrdinal = context.regionOrdinal,
                    regionLanguage = context.language,
                    regionKey = context.regionKey,
                    startToken = context.startToken,
                    endToken = context.endToken,
                    startTokenOffset = context.startTokenOffset,
                    endTokenOffset = context.endTokenOffset,
                    startNormalized = context.startNormalized,
                    endNormalized = context.endNormalized,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)
            val formattedSource = SourceFile("Sample.vue", formattedHost)

            val annotations = analyzeVue(formattedSource, root, pipeline)

            val formattedRegion =
                dev.cint.frontend.VueFrontend
                    .regions(formattedSource)
                    .single()
            val frontend =
                dev.cint.frontend.Frontends
                    .require(formattedRegion.language)
            val tree = frontend.parse(SourceFile("Sample.vue", formattedRegion.text))
            val syntax = frontend.syntax(tree)
            val unit = syntax.expressionUnits(tree).first { it.text == "f( a , b )" }
            val user = userAnnotation(annotations)
            assertEquals(formattedRegion.startLine + unit.span.startLine, user.span.startLine)
            assertEquals(unit.span.startCol, user.span.startCol)
            assertEquals(formattedRegion.startLine + unit.span.endLine, user.span.endLine)
            assertEquals(unit.span.endCol, user.span.endCol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun versionedStraddlingUserAnnotationIsDropped() {
        val root = Files.createTempDirectory("cint-split-host-straddle")
        try {
            val script = "function make() {\n  return f(a, b)\n}\n"
            val host = vueHost(script)
            val source = SourceFile("Sample.vue", host)
            val region =
                dev.cint.frontend.VueFrontend
                    .regions(source)
                    .single()
            val startByte = region.startByte + script.indexOf("f(a, b)")
            val endByte = startByte + "f(a, b)".length
            val context =
                dev.cint.frontend.SplitAnchorResolver.anchor(
                    source,
                    dev.cint.frontend.VueFrontend,
                    startByte,
                    endByte,
                )
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(0, 0, region.startLine + 1, 10),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash("f(a, b)"),
                    unitFingerprint = context.unitFingerprint,
                    unitFingerprintVersion = context.fingerprintVersion,
                    startToken = context.startToken,
                    endToken = context.endToken,
                    startTokenOffset = context.startTokenOffset,
                    endTokenOffset = context.endTokenOffset,
                    startNormalized = context.startNormalized,
                    endNormalized = context.endNormalized,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyzeVue(source, root, pipeline)

            assertTrue(annotations.none { it.provenance.any { contribution -> contribution.rule == USER_CONTRIBUTOR_ID } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun productionHostAnchoredRestrictionResolvesAfterHostFormatting() {
        val root = Files.createTempDirectory("cint-split-host-restriction-formatted")
        try {
            val oldScript = "function make() {\n  console.log(\"x\")\n}\n"
            val formattedScript = "function make() {\n  console.log( \"x\" )\n}\n"
            val oldHost = vueHost(oldScript)
            val formattedHost = vueHost(formattedScript)
            val oldSource = SourceFile("Sample.vue", oldHost)
            val oldRegion =
                dev.cint.frontend.VueFrontend
                    .regions(oldSource)
                    .single()
            val localStart = oldScript.indexOf("console.log(\"x\")")
            val startByte = oldRegion.startByte + localStart
            val endByte = startByte + "console.log(\"x\")".length
            val context =
                dev.cint.frontend.SplitAnchorResolver.anchor(
                    oldSource,
                    dev.cint.frontend.VueFrontend,
                    startByte,
                    endByte,
                )
            val tokenOffsets =
                AiRestrictionResolver.tokenOffsetsFor(
                    context.text,
                    context.anchorStart,
                    startByte - context.regionStartByte,
                    endByte - context.regionStartByte,
                )
            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "host-formatted-restriction",
                    path = "Sample.vue",
                    span = UserAnnotationResolver.spanFor(oldHost, startByte, endByte) ?: error("span"),
                    contentHash = contentHash("console.log(\"x\")"),
                    remove =
                        listOf(
                            RestrictionOperation(
                                intent = "LOGGING",
                                startOffset = 0,
                                endOffset = endByte - startByte,
                                startToken = tokenOffsets?.first,
                                endToken = tokenOffsets?.second,
                            ),
                        ),
                    add = emptyList(),
                    unitFingerprint = context.unitFingerprint,
                    fingerprintVersion = context.fingerprintVersion,
                    regionOrdinal = context.regionOrdinal,
                    regionLanguage = context.language,
                    regionKey = context.regionKey,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyzeWithRestrictionsVue(SourceFile("Sample.vue", formattedHost), root, pipeline)

            assertTrue(annotations.none { "LOGGING" in it.intents })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnchoredUserAnnotationUsesUnitRelativeNormalizedFallback() {
        val root = Files.createTempDirectory("cint-split-normalized-fallback")
        try {
            val oldScript = "function make() {\n  return f(a, b)\n}\n"
            val formattedScript = "function make() {\n  return f( a , b )\n}\n"
            val oldHost = vueHost(oldScript)
            val formattedHost = vueHost(formattedScript)
            val oldSource = SourceFile("Sample.vue", oldHost)
            val oldRegion =
                dev.cint.frontend.VueFrontend
                    .regions(oldSource)
                    .single()
            val startByte = oldRegion.startByte + oldScript.indexOf("f(a, b)")
            val endByte = startByte + "f(a, b)".length
            val context =
                dev.cint.frontend.SplitAnchorResolver.anchor(
                    oldSource,
                    dev.cint.frontend.VueFrontend,
                    startByte,
                    endByte,
                )
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = UserAnnotationResolver.spanFor(oldHost, startByte, endByte) ?: error("span"),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash("f(a, b)"),
                    unitFingerprint = context.unitFingerprint,
                    unitFingerprintVersion = context.fingerprintVersion,
                    regionOrdinal = context.regionOrdinal,
                    regionLanguage = context.language,
                    regionKey = context.regionKey,
                    startNormalized = context.startNormalized,
                    endNormalized = context.endNormalized,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyzeVue(SourceFile("Sample.vue", formattedHost), root, pipeline)

            val formattedRegion =
                dev.cint.frontend.VueFrontend
                    .regions(SourceFile("Sample.vue", formattedHost))
                    .single()
            val frontend =
                dev.cint.frontend.Frontends
                    .require(formattedRegion.language)
            val tree = frontend.parse(SourceFile("Sample.vue", formattedRegion.text))
            val syntax = frontend.syntax(tree)
            val unit = syntax.expressionUnits(tree).first { it.text == "f( a , b )" }
            val user = userAnnotation(annotations)
            assertEquals(formattedRegion.startLine + unit.span.startLine, user.span.startLine)
            assertEquals(unit.span.startCol, user.span.startCol)
            assertEquals(formattedRegion.startLine + unit.span.endLine, user.span.endLine)
            assertEquals(unit.span.endCol, user.span.endCol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun regionKeySurvivesRegionInsertionRemovalAndReorder() {
        val root = Files.createTempDirectory("cint-split-region-key")
        try {
            val scriptA = "function a() {\n  return f(a, b)\n}\n"
            val scriptB = "function b() {\n  return g(x, y)\n}\n"
            val scriptC = "function c() {\n  return h(m, n)\n}\n"
            val oldHost = vueHosts(scriptA, scriptB)
            val oldSource = SourceFile("Sample.vue", oldHost)
            val regionB =
                dev.cint.frontend.VueFrontend
                    .regions(oldSource)[1]
            val startByte = regionB.startByte + scriptB.indexOf("g(x, y)")
            val endByte = startByte + "g(x, y)".length
            val context =
                dev.cint.frontend.SplitAnchorResolver.anchor(
                    oldSource,
                    dev.cint.frontend.VueFrontend,
                    startByte,
                    endByte,
                )
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = UserAnnotationResolver.spanFor(oldHost, startByte, endByte) ?: error("span"),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash("g(x, y)"),
                    unitFingerprint = context.unitFingerprint,
                    unitFingerprintVersion = context.fingerprintVersion,
                    regionOrdinal = context.regionOrdinal,
                    regionLanguage = context.language,
                    regionKey = context.regionKey,
                    startNormalized = context.startNormalized,
                    endNormalized = context.endNormalized,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val variants =
                listOf(
                    vueHosts(scriptA, scriptC, scriptB),
                    vueHosts(scriptB, scriptA),
                    vueHosts(scriptB),
                )
            for (host in variants) {
                val source = SourceFile("Sample.vue", host)
                val region =
                    dev.cint.frontend.VueFrontend
                        .regions(source)
                        .first { "g(x, y)" in it.text }
                val frontend =
                    dev.cint.frontend.Frontends
                        .require(region.language)
                val tree = frontend.parse(SourceFile("Sample.vue", region.text))
                val syntax = frontend.syntax(tree)
                val unit = syntax.expressionUnits(tree).first { it.text == "g(x, y)" }
                val user = userAnnotation(analyzeVue(source, root, pipeline))
                assertEquals(region.startLine + unit.span.startLine, user.span.startLine)
                assertEquals(unit.span.startCol, user.span.startCol)
                assertEquals(region.startLine + unit.span.endLine, user.span.endLine)
                assertEquals(unit.span.endCol, user.span.endCol)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ambiguousRegionIdentityLeavesAUserAnnotationDormant() {
        val root = Files.createTempDirectory("cint-split-ambiguous-region")
        try {
            val script = "function make() {\n  return f(a, b)\n}\n"
            val host = vueHosts(script, script)
            val source = SourceFile("Sample.vue", host)
            val regions =
                dev.cint.frontend.VueFrontend
                    .regions(source)
            val first = regions.first()
            val startByte = first.startByte + script.indexOf("f(a, b)")
            val endByte = startByte + "f(a, b)".length
            val context =
                dev.cint.frontend.SplitAnchorResolver.anchor(
                    source,
                    dev.cint.frontend.VueFrontend,
                    startByte,
                    endByte,
                )
            assertTrue(context.regionAmbiguous)
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = UserAnnotationResolver.spanFor(host, startByte, endByte) ?: error("span"),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash("f(a, b)"),
                    unitFingerprint = context.unitFingerprint,
                    unitFingerprintVersion = context.fingerprintVersion,
                    regionOrdinal = context.regionOrdinal,
                    regionLanguage = context.language,
                    regionKey = context.regionKey,
                    startNormalized = context.startNormalized,
                    endNormalized = context.endNormalized,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyzeVue(source, root, pipeline)

            assertTrue(annotations.none { it.provenance.any { contribution -> contribution.rule == USER_CONTRIBUTOR_ID } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun identicalTemplateAndScriptContentKeepRecordOwnership() {
        val root = Files.createTempDirectory("cint-split-identical-host-region")
        try {
            val script = "function make() {\n  return f(a, b)\n}\n"
            val host = "<template>{{ f(a, b) }}</template>\n\n<script>\n$script</script>\n"
            val source = SourceFile("Sample.vue", host)
            val region =
                dev.cint.frontend.VueFrontend
                    .regions(source)
                    .single()
            val scriptStart = region.startByte + script.indexOf("f(a, b)")
            val scriptEnd = scriptStart + "f(a, b)".length
            val regionContext =
                dev.cint.frontend.SplitAnchorResolver.anchor(
                    source,
                    dev.cint.frontend.VueFrontend,
                    scriptStart,
                    scriptEnd,
                )
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = UserAnnotationResolver.spanFor(host, scriptStart, scriptEnd) ?: error("span"),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash("f(a, b)"),
                    unitFingerprint = regionContext.unitFingerprint,
                    unitFingerprintVersion = regionContext.fingerprintVersion,
                    regionOrdinal = regionContext.regionOrdinal,
                    regionLanguage = regionContext.language,
                    regionKey = regionContext.regionKey,
                    startNormalized = regionContext.startNormalized,
                    endNormalized = regionContext.endNormalized,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val regionAnnotations = analyzeVue(source, root, pipeline)
            val regionUsers =
                regionAnnotations.filter { it.provenance.any { contribution -> contribution.rule == USER_CONTRIBUTOR_ID } }
            assertEquals(1, regionUsers.size)
            assertTrue(regionUsers.single().span.startLine >= region.startLine)
        } finally {
            root.toFile().deleteRecursively()
        }

        val secondRoot = Files.createTempDirectory("cint-split-identical-host-only")
        try {
            val script = "function make() {\n  return f(a, b)\n}\n"
            val host = "<template>{{ f(a, b) }}</template>\n\n<script>\n$script</script>\n"
            val source = SourceFile("Sample.vue", host)
            val region =
                dev.cint.frontend.VueFrontend
                    .regions(source)
                    .single()
            val tree =
                dev.cint.frontend.VueFrontend
                    .parse(source)
            val syntax =
                dev.cint.frontend.VueFrontend
                    .syntax(tree)
            val hostUnit = syntax.expressionUnits(tree).first { "f(a, b)" in it.text && it.startByte < region.startByte }
            writeRecord(
                secondRoot,
                UserAnnotation(
                    path = "Sample.vue",
                    span = hostUnit.span,
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash(hostUnit.text),
                    unitFingerprint =
                        dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                            source,
                            tree,
                            syntax,
                            hostUnit.startByte,
                            hostUnit.endByte,
                        ),
                    unitFingerprintVersion = dev.cint.core.syntax.UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = secondRoot)

            val hostAnnotations = analyzeVue(source, secondRoot, pipeline)
            val hostUsers =
                hostAnnotations.filter { it.provenance.any { contribution -> contribution.rule == USER_CONTRIBUTOR_ID } }
            assertEquals(1, hostUsers.size)
            assertTrue(hostUsers.single().span.startLine < region.startLine)
        } finally {
            secondRoot.toFile().deleteRecursively()
        }
    }

    private fun vueHost(script: String): String = "<template></template>\n\n<script>\n$script</script>\n"

    private fun vueHosts(vararg scripts: String): String =
        "<template></template>\n\n" +
            scripts.joinToString("\n") { script -> "<script>\n$script</script>\n" }

    private fun analyze(
        source: SourceFile,
        handler: SplitHandler,
        root: java.nio.file.Path,
        pipeline: HostPipeline,
    ): List<dev.cint.api.Annotation> =
        SplitAnalyzer
            .analyze(
                source,
                handler,
                root,
                OracleOptions(enabled = false, timeoutMs = 1_000L),
                pipeline::plan,
                pipeline::userContributionsFor,
            ) { IntentConfig("kotlin", emptyList()) }
            .annotations

    private fun analyzeVue(
        source: SourceFile,
        root: java.nio.file.Path,
        pipeline: HostPipeline,
    ): List<dev.cint.api.Annotation> {
        val frontend = dev.cint.frontend.VueFrontend
        return SplitAnalyzer
            .analyze(
                source,
                frontend,
                root,
                OracleOptions(enabled = false, timeoutMs = 1_000L),
                pipeline::plan,
                pipeline::userContributionsFor,
                pipeline::restrictionsFor,
                hostFrontend = frontend,
                hostUserContributionsFor = { host, tree, syntax ->
                    pipeline.hostUserContributionsFor(host, tree, syntax) { annotation ->
                        annotation.regionKey == null &&
                            annotation.regionOrdinal == null &&
                            dev.cint.frontend.SplitAnchorResolver
                                .isHostLevelSpan(source, frontend, annotation.span)
                    }
                },
                hostRestrictionsFor = { host, tree, syntax ->
                    pipeline.hostRestrictionsFor(host, tree, syntax) { record ->
                        record.regionKey == null &&
                            record.regionOrdinal == null &&
                            dev.cint.frontend.SplitAnchorResolver
                                .isHostLevelSpan(source, frontend, record.span)
                    }
                },
            ) { language -> IntentConfig(language, emptyList()) }
            .annotations
    }

    private fun analyzeWithRestrictionsVue(
        source: SourceFile,
        root: java.nio.file.Path,
        pipeline: HostPipeline,
    ): List<dev.cint.api.Annotation> = analyzeVue(source, root, pipeline)

    private fun analyzeWithRestrictions(
        source: SourceFile,
        handler: SplitHandler,
        root: java.nio.file.Path,
        pipeline: HostPipeline,
    ): List<dev.cint.api.Annotation> =
        SplitAnalyzer
            .analyze(
                source,
                handler,
                root,
                OracleOptions(enabled = false, timeoutMs = 1_000L),
                pipeline::plan,
                pipeline::userContributionsFor,
                pipeline::restrictionsFor,
            ) { IntentConfig("kotlin", emptyList()) }
            .annotations

    private fun userAnnotation(annotations: List<dev.cint.api.Annotation>): dev.cint.api.Annotation =
        annotations.first { annotation ->
            annotation.provenance.any { it.rule == USER_CONTRIBUTOR_ID }
        }

    private fun writeRecord(
        root: java.nio.file.Path,
        record: UserAnnotation,
    ) {
        val file = root.resolve(".cint/user-annotations.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, UserAnnotationsJson.encode(listOf(record)))
    }
}
