package dev.cint.core.config

data class IntentDefinition(
    val params: Map<String, List<String>>,
) {
    fun list(key: String): List<String> = params[key].orEmpty()
}

data class IntentConfig(
    val language: String,
    val intents: Map<String, IntentDefinition>,
    val pureAnnotations: List<String>,
)
