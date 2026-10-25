import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import v2.graphguard.GraphGuardValidator
import v2.graphguard.ProjectGraph

plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
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
    // contracts uses kotlin("multiplatform") instead
    if (project.name != "contracts") {
        apply(plugin = "org.jetbrains.kotlin.jvm")
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    if (project.name != "contracts") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            showStandardStreams = true
        }
    }
}

object ProjectGraphCollector {
    fun collect(root: Project): ProjectGraph {
        val modules = linkedSetOf<String>()
        val dependencies = linkedMapOf<String, Set<String>>()
        val rootDependencies = linkedSetOf<String>()
        val missingBuildFiles = linkedSetOf<String>()

        for (candidate in root.allprojects) {
            val declaredDependencies = candidate.configurations.flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    (dependency as? ProjectDependency)?.path
                }
            }.map { path -> path.removePrefix(":") }.toSet()

            if (candidate.path == ":") {
                rootDependencies.addAll(declaredDependencies)
                continue
            }

            val module = candidate.path.removePrefix(":")
            modules.add(module)
            dependencies[module] = declaredDependencies
            if (!candidate.buildFile.isFile) {
                missingBuildFiles.add(module)
            }
        }

        return ProjectGraph(modules, dependencies, rootDependencies, missingBuildFiles)
    }
}

open class GraphGuardTask : DefaultTask() {
    @TaskAction
    fun validateGraph() {
        val config = GraphGuardValidator.loadConfig(project.file("graph-guard.json").readText())
        val graph = ProjectGraphCollector.collect(project.rootProject)
        val result = GraphGuardValidator.validate(config, graph)

        if (!result.passed) {
            logger.error("GRAPH GUARD FAILED:")
            for (error in result.errors) {
                logger.error("  ERROR: $error")
            }
            throw GradleException("Graph guard validation failed with ${result.errors.size} error(s)")
        }

        logger.lifecycle("GRAPH GUARD PASSED")
    }
}

tasks.register("graphGuard", GraphGuardTask::class) {
    description = "Validates the module dependency graph against declared edges"
    group = "verification"
}

tasks.register<Exec>("graphGuardSelfTest") {
    description = "Runs build-logic tests proving the guard fails illegal, reverse, root, and missing edges"
    group = "verification"
    workingDir = file("buildSrc")
    commandLine(rootProject.file("gradlew").absolutePath, "--no-daemon", "test")
}

tasks.named("check") {
    dependsOn("graphGuard", "graphGuardSelfTest")
}
