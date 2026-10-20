package dev.cint.test.fixtures

import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.core.test.testConfig
import dev.cint.frontend.Frontends

data class ParsedSource(
    val source: SourceFile,
    val tree: SyntaxTree,
    val syntax: SyntaxModel,
    val config: IntentConfig,
)

fun parseFile(
    language: String,
    source: SourceFile,
): SyntaxTree = Frontends.require(language).parse(source)

fun syntaxModel(
    language: String,
    tree: SyntaxTree,
): SyntaxModel = Frontends.require(language).syntax(tree)

fun parseSource(
    language: String,
    text: String,
    name: String = defaultFileName(language),
    config: IntentConfig = testConfig(language),
): ParsedSource {
    val source = SourceFile(name, text)
    val tree = parseFile(language, source)
    return ParsedSource(source, tree, syntaxModel(language, tree), config)
}

fun defaultFileName(language: String): String = "Sample${Frontends.require(language).extensions.first()}"
