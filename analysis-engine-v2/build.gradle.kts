import com.google.gson.Gson
import org.gradle.api.tasks.TaskAction
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.nio.file.Files
import java.util.regex.Pattern

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.google.code.gson:gson:2.11.0")
    }
}

plugins {
    kotlin("jvm") version "2.4.10" apply false
    base
}

allprojects {
    group = "dev.cint.v2"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            showStandardStreams = true
        }
    }
}

data class GraphGuardConfig(
    val modules: List<String>,
    val allowedEdges: List<List<String>>
)

open class GraphGuardTask : DefaultTask() {
    @TaskAction
    fun validateGraph() {
        val configFile = project.file("graph-guard.json")
        val configContent = Files.readString(configFile.toPath())
        val gson = Gson()
        val config = gson.fromJson(configContent, GraphGuardConfig::class.java)

        val allowedEdges = config.allowedEdges.map { edge -> "${edge[0]}->${edge[1]}" }.toSet()
        val modules = config.modules.toSet()

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val rootDir = project.rootDir

        val moduleToDir = mapOf(
            "contracts" to "contracts",
            "core" to "core",
            "packs" to "packs",
            "index" to "index",
            "knowledge" to "knowledge",
            "languages" to "languages",
            "syntax-treesitter" to "syntax-treesitter",
            "lsp" to "lsp",
            "intent-pipeline" to "intent-pipeline",
            "runtime" to "runtime",
            "ai" to "ai",
            "ipc" to "ipc",
            "cli" to "apps/cli",
            "server" to "apps/server",
            "mcp" to "apps/mcp",
            "test-fixtures" to "tests/fixtures",
            "integration-tests" to "tests/integration"
        )

        for (module in config.modules) {
            val moduleDir = moduleToDir[module] ?: module
            val buildFile = File(rootDir, "$moduleDir/build.gradle.kts")

            if (!buildFile.exists()) {
                warnings.add("Module '$module' has no build.gradle.kts at $moduleDir")
            } else {
                validateBuildFile(buildFile, module, allowedEdges, modules, errors, warnings)
            }
        }

        checkReverseEdges(allowedEdges, errors)

        if (!errors.isEmpty()) {
            logger.error("GRAPH GUARD FAILED:")
            for (error in errors) {
                logger.error("  ERROR: $error")
            }
            throw GradleException("Graph guard validation failed with ${errors.size} error(s)")
        } else {
            logger.lifecycle("GRAPH GUARD PASSED")
            for (warning in warnings) {
                logger.warn("  WARNING: $warning")
            }
        }
    }

    private fun validateBuildFile(
        buildFile: File,
        module: String,
        allowedEdges: Set<String>,
        modules: Set<String>,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val content = Files.readString(buildFile.toPath())

        val projectDepPattern = Pattern.compile("implementation\\(project\\(\"\\:([^\"]+)\"\\)\\)")
        val matcher = projectDepPattern.matcher(content)

        while (matcher.find()) {
            val dep = matcher.group(1)
            val edge = "$module->$dep"

            if (!modules.contains(dep)) {
                errors.add("Module '$module' depends on unknown module '$dep'")
            } else if (!allowedEdges.contains(edge)) {
                errors.add("Module '$module' has undeclared dependency on '$dep' (edge '$edge' not in allowedEdges)")
            }
        }

        val apiDepPattern = Pattern.compile("api\\(project\\(\"\\:([^\"]+)\"\\)\\)")
        val apiMatcher = apiDepPattern.matcher(content)
        while (apiMatcher.find()) {
            val dep = apiMatcher.group(1)
            val edge = "$module->$dep"

            if (!modules.contains(dep)) {
                errors.add("Module '$module' depends on unknown module '$dep' (api)")
            } else if (!allowedEdges.contains(edge)) {
                errors.add("Module '$module' has undeclared api dependency on '$dep' (edge '$edge' not in allowedEdges)")
            }
        }
    }

    private fun checkReverseEdges(allowedEdges: Set<String>, errors: MutableList<String>) {
        for (edge in allowedEdges) {
            val parts = edge.split("->")
            if (parts.size == 2) {
                val reverse = "${parts[1]}->${parts[0]}"
                if (allowedEdges.contains(reverse)) {
                    errors.add("Reverse edge detected: both '$edge' and '$reverse' are allowed (creates cycle)")
                }
            }
        }
    }
}

tasks.register("graphGuard", GraphGuardTask::class) {
    description = "Validates the module dependency graph against declared edges"
    group = "verification"
}

tasks.named("check") {
    dependsOn("graphGuard")
}