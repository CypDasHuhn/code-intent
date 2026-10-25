package dev.cint.ai

data class FallbackAiBudget(
    val maxCalls: Int = 1,
    val maxProposals: Int = 4,
    val maxSourceChars: Int = 8_000,
    val maxFunctionChars: Int = 2_000,
    val maxFunctions: Int = 64,
    val maxPromptChars: Int = 32_000,
    val maxConfidence: Double = 0.84,
) {
    init {
        require(maxCalls >= 0) { "FallbackAiBudget.maxCalls must not be negative" }
        require(maxProposals >= 0) { "FallbackAiBudget.maxProposals must not be negative" }
        require(maxSourceChars >= 0) { "FallbackAiBudget.maxSourceChars must not be negative" }
        require(maxFunctionChars >= 0) { "FallbackAiBudget.maxFunctionChars must not be negative" }
        require(maxFunctions >= 0) { "FallbackAiBudget.maxFunctions must not be negative" }
        require(maxPromptChars >= 0) { "FallbackAiBudget.maxPromptChars must not be negative" }
        require(maxConfidence in 0.0..1.0) { "FallbackAiBudget.maxConfidence must be between 0.0 and 1.0" }
    }
}
