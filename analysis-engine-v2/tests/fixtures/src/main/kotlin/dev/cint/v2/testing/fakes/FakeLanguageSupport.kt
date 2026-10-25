package dev.cint.v2.testing.fakes

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

class FakeLanguageSupport(
    private val languageDescriptor: LanguageDescriptor = fakeDescriptor(),
    private val syntaxEngine: SyntaxEngine = FakeSyntaxEngine(languageDescriptor.id),
    private val provider: SemanticProvider = FakeSemanticProvider(languageDescriptor.id),
    val recorder: FakeRecorder = FakeRecorder(),
    private val forcedFailure: ContractError? = null,
) : LanguageSupport {
    override fun descriptor(): ContractResult<LanguageDescriptor> {
        recorder.record("language.descriptor", languageDescriptor.id)
        forcedFailure?.let { return ResultFactory.failure(it) }
        return ResultFactory.success(languageDescriptor)
    }

    override fun syntax(): ContractResult<SyntaxEngine> {
        recorder.record("language.syntax", languageDescriptor.id)
        forcedFailure?.let { return ResultFactory.failure(it) }
        return ResultFactory.success(syntaxEngine)
    }

    override fun semanticProvider(): ContractResult<SemanticProvider> {
        recorder.record("language.semanticProvider", languageDescriptor.id)
        forcedFailure?.let { return ResultFactory.failure(it) }
        return ResultFactory.success(provider)
    }

    fun engine(): SyntaxEngine = syntaxEngine

    fun provider(): SemanticProvider = provider
}

fun fakeDescriptor(
    id: String = "fake",
    name: String = "Fake Language",
    extensions: List<String> = listOf("fake"),
): LanguageDescriptor = LanguageDescriptor(
    id = id,
    name = name,
    extensions = extensions,
    grammarName = "fake",
    mimeTypes = listOf("text/x-$id"),
)
