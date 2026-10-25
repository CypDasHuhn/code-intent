package dev.cint.v2.spike

@kotlinx.serialization.Serializable
data class ProcessResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean,
    val error: String?
)

interface ProcessSpawner {
    fun spawn(command: List<String>, workingDir: String?): ProcessResult
    fun spawn(command: List<String>): ProcessResult
}

expect fun createProcessSpawner(): ProcessSpawner